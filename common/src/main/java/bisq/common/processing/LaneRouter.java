/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public
 * License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.processing;

import bisq.common.timer.Clock;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;

import static com.google.common.base.Preconditions.checkState;

/**
 * Creates lanes and routes inputs by entity ID using one configured definition and application adapters.
 * Lane construction is passive; initialization, persistence and processing run after submission or resumption.
 * The router holds submissions until restored lanes are registered and {@link #start()} queues their recovery.
 * Lookup and registration are serialized. Lane submission and future completion run outside the registry lock.
 * Shutdown closes admission and drains accepted lane work without shutting down the shared executor.
 * Only the supplied rejection registry outlives an activation; a closed router is never reopened.
 */
public final class LaneRouter<S, I extends Input, W> {
    private final Object lock = new Object();
    private final ProcessingLaneDefinition<S, I, W> definition;
    private final Executor executor;
    private final ScheduledExecutorService timerExecutor;
    private final Clock clock;
    private final CheckpointWriter<Checkpoint<S, I, W>> checkpointWriter;
    private final BiConsumer<? super S, ? super Effect> effectExecutor;
    private final BiConsumer<String, LogEntry> logWriter;
    private final SnapshotPublisher<? super S> snapshotPublisher;
    private final RejectedEntityRegistry rejectedEntities;
    private final Map<String, ProcessingLane<S, I, W>> laneById = new HashMap<>();
    private final ArrayDeque<BufferedSubmission<S>> startupSubmissions = new ArrayDeque<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private State state = State.REGISTERING;

    public LaneRouter(ProcessingLaneDefinition<S, I, W> definition,
                      Executor executor,
                      ScheduledExecutorService timerExecutor,
                      Clock clock,
                      CheckpointWriter<Checkpoint<S, I, W>> checkpointWriter,
                      BiConsumer<? super S, ? super Effect> effectExecutor,
                      BiConsumer<String, LogEntry> logWriter,
                      SnapshotPublisher<? super S> snapshotPublisher,
                      RejectedEntityRegistry rejectedEntities) {
        this.definition = definition;
        this.executor = executor;
        this.timerExecutor = timerExecutor;
        this.clock = clock;
        this.checkpointWriter = checkpointWriter;
        this.effectExecutor = effectExecutor;
        this.logWriter = logWriter;
        this.snapshotPublisher = snapshotPublisher;
        this.rejectedEntities = rejectedEntities;
    }

    /**
     * Constructs and validates every lane in the supplied batch before registering any of them.
     * Construction runs outside the registry lock and starts no work. Registration is allowed only before start,
     * rejects existing IDs and installs the whole batch together. Call {@link #start()} after loading all checkpoints.
     */
    public void restore(Map<String, Checkpoint<S, I, W>> checkpoints) {
        synchronized (lock) {
            checkState(state == State.REGISTERING, "Restored lanes must be registered before starting the router");
        }
        var restoredLanes = checkpoints.entrySet().stream()
                .map(entry -> createLane(entry.getKey(), Optional.of(entry.getValue())))
                .toList();
        synchronized (lock) {
            checkState(state == State.REGISTERING, "Restored lanes must be registered before starting the router");
            restoredLanes.forEach(lane -> checkState(!laneById.containsKey(lane.getEntityId()),
                    "A processing lane is already registered for %s", lane.getEntityId()));
            restoredLanes.forEach(lane -> laneById.put(lane.getEntityId(), lane));
        }
    }

    /**
     * Queues recovery in every restored lane, then routes held submissions in arrival order.
     * Returns the recovery completion future; routing does not wait for recovery to finish.
     * The application calls this once, after registering all restored lanes and initializing required services.
     * Shutdown during startup stops further dispatch and makes the returned future exceptional.
     */
    public CompletableFuture<Void> start() {
        List<ProcessingLane<S, I, W>> restoredLanes;
        synchronized (lock) {
            checkState(state == State.REGISTERING, "Cannot start processing lane router in state %s", state);
            state = State.STARTING;
            restoredLanes = List.copyOf(laneById.values());
        }
        var recoveries = new ArrayList<CompletableFuture<S>>();
        for (var lane : restoredLanes) {
            synchronized (lock) {
                if (state == State.CLOSED) {
                    break;
                }
            }
            recoveries.add(lane.resume());
        }
        if (!releaseStartupSubmissions()) {
            recoveries.add(CompletableFuture.failedFuture(closedException()));
        }
        return CompletableFuture.allOf(recoveries.toArray(CompletableFuture[]::new));
    }

    /**
     * Checks submission admission before buffering or allocating lanes. A policy rejection retains no work and
     * does not block the entity ID. Admitted submissions wait during startup, then return the lane's result.
     */
    public CompletableFuture<SubmissionResult<S>> submit(String entityId, Input input) {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (rejectedEntities.isRejected(entityId)) {
                return previouslyRejectedSubmission();
            }
        }
        try {
            definition.checkSubmission(input);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(failure)));
        }
        synchronized (lock) {
            // Admission runs outside the lock; shutdown or initialization rejection may have happened meanwhile.
            if (state == State.CLOSED) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (rejectedEntities.isRejected(entityId)) {
                return previouslyRejectedSubmission();
            }
            if (state != State.RUNNING) {
                var completion = new CompletableFuture<SubmissionResult<S>>();
                startupSubmissions.addLast(new BufferedSubmission<>(entityId, input, completion));
                return completion;
            }
        }
        return route(entityId, input);
    }

    /** Retries an existing lane's pending work without admitting another domain input. */
    public CompletableFuture<S> resume(String entityId) {
        ProcessingLane<S, I, W> lane;
        synchronized (lock) {
            if (state != State.RUNNING) {
                return CompletableFuture.failedFuture(new IllegalStateException("Processing lane router is not running"));
            }
            lane = laneById.get(entityId);
            if (lane == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown entity: " + entityId));
            }
        }
        return lane.resume();
    }

    /**
     * Stops admission, fails startup-buffered submissions and drains work already accepted by registered lanes.
     * Repeated calls observe the same shutdown. Cancelling the returned future does not cancel shutdown.
     * The application shuts down its shared executor only after this future completes.
     */
    public CompletableFuture<Void> shutdown() {
        List<ProcessingLane<S, I, W>> lanes;
        List<BufferedSubmission<S>> buffered;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return stopped.copy();
            }
            state = State.CLOSED;
            lanes = List.copyOf(laneById.values());
            buffered = List.copyOf(startupSubmissions);
            startupSubmissions.clear();
        }
        var failure = closedException();
        buffered.forEach(submission -> submission.completion().completeExceptionally(failure));
        var completions = lanes.stream()
                .map(ProcessingLane::shutdown)
                .toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(completions).whenComplete((ignored, shutdownFailure) -> {
            if (shutdownFailure != null) {
                stopped.completeExceptionally(shutdownFailure);
            } else {
                stopped.complete(null);
            }
        });
        return stopped.copy();
    }

    private boolean releaseStartupSubmissions() {
        while (true) {
            BufferedSubmission<S> submission;
            synchronized (lock) {
                if (state == State.CLOSED) {
                    return false;
                }
                submission = startupSubmissions.peekFirst();
                if (submission == null) {
                    state = State.RUNNING;
                    return true;
                }
            }
            releaseStartupSubmission(submission);
        }
    }

    private void releaseStartupSubmission(BufferedSubmission<S> submission) {
        CompletableFuture<SubmissionResult<S>> result;
        try {
            Optional<ProcessingLane<S, I, W>> lane;
            synchronized (lock) {
                // Initialization rejection may have removed this submission since it was selected.
                if (!startupSubmissions.remove(submission)) {
                    return;
                }
                lane = findOrCreateLane(submission.entityId(), submission.input());
            }
            result = submitToLane(lane, submission.entityId(), submission.input());
        } catch (RuntimeException failure) {
            result = CompletableFuture.failedFuture(failure);
        }
        result.whenComplete((submissionResult, failure) -> {
            if (failure != null) {
                submission.completion().completeExceptionally(failure);
            } else {
                submission.completion().complete(submissionResult);
            }
        });
    }

    private CompletableFuture<SubmissionResult<S>> route(String entityId, Input input) {
        try {
            var lane = findOrCreateLane(entityId, input);
            return submitToLane(lane, entityId, input);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    private CompletableFuture<SubmissionResult<S>> submitToLane(Optional<ProcessingLane<S, I, W>> lane,
                                                                String entityId,
                                                                Input input) {
        if (lane.isPresent()) {
            return lane.get().submit(input);
        }
        if (rejectedEntities.isRejected(entityId)) {
            return previouslyRejectedSubmission();
        }
        var failure = new IllegalStateException("Entity " + entityId + " is not initialized and input " +
                input.getClass().getSimpleName() + " cannot create a waiting lane");
        return CompletableFuture.completedFuture(new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(failure)));
    }

    private Optional<ProcessingLane<S, I, W>> findOrCreateLane(String entityId, Input input) {
        synchronized (lock) {
            if (state == State.CLOSED) {
                throw closedException();
            }
            if (rejectedEntities.isRejected(entityId)) {
                return Optional.empty();
            }
            var existing = laneById.get(entityId);
            if (existing != null) {
                return Optional.of(existing);
            }
            if (!definition.canCreateWaitingLane(input)) {
                return Optional.empty();
            }
            var lane = createLane(entityId, Optional.empty());
            lane.setOnInitializationFailed(failure -> removeUninitializedLane(entityId, lane, failure));
            laneById.put(entityId, lane);
            return Optional.of(lane);
        }
    }

    private ProcessingLane<S, I, W> createLane(String entityId, Optional<Checkpoint<S, I, W>> checkpoint) {
        return new ProcessingLane<>(entityId,
                checkpoint,
                executor,
                timerExecutor,
                clock,
                definition,
                checkpointWriter,
                effectExecutor,
                entry -> logWriter.accept(entityId, entry),
                snapshotPublisher);
    }

    private void removeUninitializedLane(String entityId, ProcessingLane<S, I, W> failedLane, Throwable failure) {
        List<BufferedSubmission<S>> rejectedSubmissions;
        synchronized (lock) {
            if (laneById.get(entityId) != failedLane) {
                return;
            }
            if (failure instanceof InitializationRejectedException) {
                rejectedEntities.record(entityId);
            }
            laneById.remove(entityId);
            rejectedSubmissions = startupSubmissions.stream()
                    .filter(submission -> entityId.equals(submission.entityId()))
                    .toList();
            startupSubmissions.removeIf(submission -> entityId.equals(submission.entityId()));
        }
        var cause = failure instanceof InitializationRejectedException rejected ? rejected.getRejection() : failure;
        rejectedSubmissions.forEach(submission -> submission.completion().completeExceptionally(cause));
    }

    private CompletableFuture<SubmissionResult<S>> previouslyRejectedSubmission() {
        var cause = new IllegalStateException("Initialization was already rejected for this entity ID");
        return CompletableFuture.completedFuture(new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(cause)));
    }

    private static RejectedExecutionException closedException() {
        return new RejectedExecutionException("Processing lane router is closed");
    }

    private record BufferedSubmission<S>(String entityId, Input input, CompletableFuture<SubmissionResult<S>> completion) {}

    private enum State {
        REGISTERING,
        STARTING,
        RUNNING,
        CLOSED
    }
}
