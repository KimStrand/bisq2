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

package bisq.common.processing.lane;

import bisq.common.processing.Input;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;

import static com.google.common.base.Preconditions.checkState;

/**
 * Creates lanes and routes inputs by entity ID using one configured definition and application adapters.
 * Only a registered initializer or a restored checkpoint creates a lane. Until an entity's initializer arrives,
 * the router keeps the entity's other eligible inputs as pending inputs: at most {@link #MAX_PENDING_INPUTS} for
 * all entities together, in arrival order. When the limit is reached, the oldest pending input is dropped and its
 * submissions fail. Equal inputs stay separate pending inputs; each counts toward the limit.
 * A new lane receives its initializer first, then its pending inputs and the inputs which arrive meanwhile, in
 * arrival order.
 * Lane construction is passive; initialization, persistence and processing run after submission or resumption.
 * The router holds submissions until restored lanes are registered and {@link #start()} queues their recovery.
 * Lookup and registration are serialized. Lane submission and future completion run outside the registry lock.
 * Shutdown closes admission, fails the inputs which the router still holds and drains accepted lane work without
 * shutting down the shared executor.
 * Only the supplied rejection registry outlives an activation; a closed router is never reopened.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
@Slf4j
public final class LaneRouter<S, I extends Input> {
    // Anybody can send inputs for made-up entity IDs. Such inputs never get a lane, so their number is bounded.
    static final int MAX_PENDING_INPUTS = 100;

    private final Object lock = new Object();
    private final InputPolicy inputPolicy;
    private final ProcessingLaneDefinition<S, I> definition;
    private final Executor executor;
    private final ScheduledExecutorService timerExecutor;
    private final Clock clock;
    private final CheckpointWriter<Checkpoint<S, I>> checkpointWriter;
    private final EffectExecutor<? super S> effectExecutor;
    private final SnapshotListener<? super S> snapshotListener;
    private final RejectedEntityRegistry rejectedEntities;
    private final Map<String, ProcessingLane<S, I>> laneById = new HashMap<>();
    private final ArrayDeque<HeldInput<S>> startupSubmissions = new ArrayDeque<>();
    // Inputs of entities without a lane, in arrival order.
    private final ArrayDeque<HeldInput<S>> pendingInputs = new ArrayDeque<>();
    // Inputs which must follow a new lane's initializer, in arrival order, until the router has submitted them.
    private final Map<String, ArrayDeque<HeldInput<S>>> inputsBehindInitializerById = new HashMap<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private State state = State.REGISTERING;

    public LaneRouter(InputPolicy inputPolicy,
                      ProcessingLaneDefinition<S, I> definition,
                      Executor executor,
                      ScheduledExecutorService timerExecutor,
                      Clock clock,
                      CheckpointWriter<Checkpoint<S, I>> checkpointWriter,
                      EffectExecutor<? super S> effectExecutor,
                      SnapshotListener<? super S> snapshotListener,
                      RejectedEntityRegistry rejectedEntities) {
        this.inputPolicy = inputPolicy;
        this.definition = definition;
        this.executor = executor;
        this.timerExecutor = timerExecutor;
        this.clock = clock;
        this.checkpointWriter = checkpointWriter;
        this.effectExecutor = effectExecutor;
        this.snapshotListener = snapshotListener;
        this.rejectedEntities = rejectedEntities;
    }

    /**
     * Constructs and validates every lane in the supplied batch before registering any of them.
     * Construction runs outside the registry lock and starts no work. Registration is allowed only before start,
     * rejects existing IDs and installs the whole batch together. Call {@link #start()} after loading all checkpoints.
     */
    public void restore(Map<String, Checkpoint<S, I>> checkpoints) {
        synchronized (lock) {
            checkState(state == State.REGISTERING, "Restored lanes must be registered before starting the router");
        }
        List<ProcessingLane<S, I>> restoredLanes = checkpoints.entrySet().stream()
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
     * Publishes every restored snapshot synchronously on the caller's thread before queueing any recovery.
     * Publication runs outside the registry lock; submissions from observers remain buffered.
     * Then queues recovery in every restored lane and routes held submissions in arrival order.
     * Returns the recovery completion future; routing does not wait for recovery to finish.
     * The application calls this once, after registering all restored lanes and initializing required services.
     * Shutdown during startup stops further dispatch and makes the returned future exceptional.
     */
    public CompletableFuture<Void> start() {
        List<ProcessingLane<S, I>> restoredLanes;
        synchronized (lock) {
            checkState(state == State.REGISTERING, "Cannot start processing lane router in state %s", state);
            state = State.STARTING;
            restoredLanes = List.copyOf(laneById.values());
        }
        // Keep publication and recovery in separate loops: resume() may start recovery immediately.
        // Every restored snapshot must be published before any lane begins recovery.
        for (ProcessingLane<S, I> lane : restoredLanes) {
            synchronized (lock) {
                if (state == State.CLOSED) {
                    return CompletableFuture.failedFuture(closedException());
                }
            }
            lane.publishRestoredSnapshot();
        }
        ArrayList<CompletableFuture<S>> recoveryFutures = new ArrayList<CompletableFuture<S>>();
        for (ProcessingLane<S, I> lane : restoredLanes) {
            synchronized (lock) {
                if (state == State.CLOSED) {
                    break;
                }
            }
            recoveryFutures.add(lane.resume());
        }
        if (!releaseStartupSubmissions()) {
            recoveryFutures.add(CompletableFuture.failedFuture(closedException()));
        }
        return CompletableFuture.allOf(recoveryFutures.toArray(CompletableFuture[]::new));
    }

    /**
     * Lets every lane continue with the pending inputs which the input policy paused and allows now; see
     * {@link InputPolicy#isPaused}. The application calls this when the reason of a pause may have ended. A lane
     * without such an input changes nothing. Does nothing before {@link #start()} has returned, because the
     * start resumes every restored lane, and nothing after shutdown.
     * Completes when every lane has finished; the failure of a lane makes the returned future exceptional.
     */
    public CompletableFuture<Void> resumePausedInputs() {
        List<ProcessingLane<S, I>> lanes;
        synchronized (lock) {
            if (state != State.RUNNING) {
                return CompletableFuture.completedFuture(null);
            }
            lanes = List.copyOf(laneById.values());
        }
        return CompletableFuture.allOf(lanes.stream()
                .map(ProcessingLane::resumePausedInputs)
                .toArray(CompletableFuture[]::new));
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
                return CompletableFuture.completedFuture(previouslyRejected());
            }
        }
        try {
            inputPolicy.check(input);
        } catch (RuntimeException failure) {
            return CompletableFuture.completedFuture(SubmissionResult.rejected(failure));
        }
        HeldInput<S> submission = HeldInput.<S>of(entityId, input);
        Runnable afterUnlock;
        synchronized (lock) {
            // Admission runs outside the lock; shutdown or initialization rejection may have happened meanwhile.
            if (state == State.CLOSED) {
                return CompletableFuture.failedFuture(closedException());
            }
            if (rejectedEntities.isRejected(entityId)) {
                return CompletableFuture.completedFuture(previouslyRejected());
            }
            if (state != State.RUNNING) {
                startupSubmissions.addLast(submission);
                return submission.completion();
            }
            afterUnlock = route(submission);
        }
        runAfterUnlock(submission, afterUnlock);
        return submission.completion();
    }

    /**
     * Stops admission, fails the inputs which the router still holds and drains work already accepted by
     * registered lanes. Repeated calls observe the same shutdown. Cancelling the returned future does not cancel
     * shutdown. The application shuts down its shared executor only after this future completes.
     */
    public CompletableFuture<Void> shutdown() {
        List<ProcessingLane<S, I>> lanes;
        ArrayList<HeldInput<S>> heldInputs = new ArrayList<HeldInput<S>>();
        synchronized (lock) {
            if (state == State.CLOSED) {
                return stopped.copy();
            }
            state = State.CLOSED;
            lanes = List.copyOf(laneById.values());
            heldInputs.addAll(startupSubmissions);
            startupSubmissions.clear();
            heldInputs.addAll(pendingInputs);
            pendingInputs.clear();
            inputsBehindInitializerById.values().forEach(heldInputs::addAll);
            inputsBehindInitializerById.clear();
        }
        RejectedExecutionException failure = closedException();
        heldInputs.forEach(held -> held.fail(failure));
        CompletableFuture[] completions = lanes.stream()
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
            HeldInput<S> submission;
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

    private void releaseStartupSubmission(HeldInput<S> submission) {
        Runnable afterUnlock;
        synchronized (lock) {
            // Initialization rejection may have removed this submission since it was selected.
            if (!startupSubmissions.remove(submission)) {
                return;
            }
            afterUnlock = route(submission);
        }
        runAfterUnlock(submission, afterUnlock);
    }

    /**
     * Decides under the registry lock where an admitted input goes. The returned action submits to a lane and
     * completes futures; the caller runs it after releasing the lock. Callers have already excluded a closed router
     * under the same lock: shutdown sets the state and empties the startup buffer in one step.
     */
    private Runnable route(HeldInput<S> held) {
        String entityId = held.entityId();
        Input input = held.input();
        if (rejectedEntities.isRejected(entityId)) {
            return () -> held.complete(previouslyRejected());
        }
        ProcessingLane<S, I> lane = laneById.get(entityId);
        if (lane != null) {
            ArrayDeque<HeldInput<S>> behindInitializer = inputsBehindInitializerById.get(entityId);
            if (behindInitializer != null) {
                behindInitializer.addLast(held);
                return () -> {
                };
            }
            return () -> held.forward(lane.submit(input));
        }
        if (definition.hasInitializer(input)) {
            ProcessingLane<S, I> newLane = createLane(entityId, Optional.empty());
            newLane.setOnInitializationFailed(failure -> removeUninitializedLane(entityId, newLane, failure));
            laneById.put(entityId, newLane);
            // Until these inputs are submitted, later inputs of this entity queue behind them.
            inputsBehindInitializerById.put(entityId, new ArrayDeque<>(removePendingInputs(entityId)));
            return () -> {
                held.forward(newLane.submit(input));
                submitInputsBehindInitializer(entityId, newLane);
            };
        }
        if (definition.canWaitForInitialization(input)) {
            Optional<HeldInput<S>> dropped = addPendingInput(held);
            return () -> dropped.ifPresent(this::dropPendingInput);
        }
        IllegalStateException failure =
                new IllegalStateException("Entity " + entityId + " is not initialized and input " +
                        input.getClass().getSimpleName() + " cannot wait for initialization");
        return () -> held.complete(SubmissionResult.rejected(failure));
    }

    private void runAfterUnlock(HeldInput<S> held, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            held.fail(failure);
        }
    }

    /**
     * Submits the inputs behind a new lane's initializer, one at a time and in arrival order, so that inputs
     * arriving meanwhile stay behind them. A failed initialization or shutdown fails the inputs not yet submitted.
     */
    private void submitInputsBehindInitializer(String entityId, ProcessingLane<S, I> lane) {
        while (true) {
            HeldInput<S> next;
            synchronized (lock) {
                ArrayDeque<HeldInput<S>> behindInitializer = inputsBehindInitializerById.get(entityId);
                // After a failed initialization, a new lane of the same entity can have its own inputs here.
                if (behindInitializer == null || laneById.get(entityId) != lane) {
                    return;
                }
                next = behindInitializer.pollFirst();
                if (next == null) {
                    inputsBehindInitializerById.remove(entityId);
                    return;
                }
            }
            HeldInput<S> held = next;
            runAfterUnlock(held, () -> held.forward(lane.submit(held.input())));
        }
    }

    private Optional<HeldInput<S>> addPendingInput(HeldInput<S> held) {
        Optional<HeldInput<S>> dropped = pendingInputs.size() < MAX_PENDING_INPUTS
                ? Optional.empty()
                : Optional.of(pendingInputs.removeFirst());
        pendingInputs.addLast(held);
        return dropped;
    }

    private List<HeldInput<S>> removePendingInputs(String entityId) {
        ArrayList<HeldInput<S>> removed = new ArrayList<HeldInput<S>>();
        for (Iterator<HeldInput<S>> iterator = pendingInputs.iterator(); iterator.hasNext(); ) {
            HeldInput<S> pending = iterator.next();
            if (pending.entityId().equals(entityId)) {
                removed.add(pending);
                iterator.remove();
            }
        }
        return removed;
    }

    private void dropPendingInput(HeldInput<S> dropped) {
        // The entity ID comes from the sender, and input content can be private, so the log names only its type.
        log.warn("Dropped the oldest input waiting for initialization because the limit of {} pending inputs is " +
                        "reached. type={}, entityId={}",
                MAX_PENDING_INPUTS,
                dropped.input().getClass().getSimpleName(),
                StringUtils.sanitizeForLog(dropped.entityId()));
        dropped.fail(new RejectedExecutionException("Input dropped while waiting for initialization because the limit of " +
                MAX_PENDING_INPUTS + " pending inputs was reached"));
    }

    private ProcessingLane<S, I> createLane(String entityId, Optional<Checkpoint<S, I>> checkpoint) {
        return new ProcessingLane<>(entityId,
                checkpoint,
                executor,
                timerExecutor,
                clock,
                definition,
                inputPolicy,
                checkpointWriter,
                effectExecutor,
                snapshotListener);
    }

    private void removeUninitializedLane(String entityId, ProcessingLane<S, I> failedLane, Throwable failure) {
        ArrayList<HeldInput<S>> failedInputs = new ArrayList<HeldInput<S>>();
        synchronized (lock) {
            if (laneById.get(entityId) != failedLane) {
                return;
            }
            if (failure instanceof InitializationRejectedException) {
                rejectedEntities.record(entityId);
            }
            laneById.remove(entityId);
            Optional.ofNullable(inputsBehindInitializerById.remove(entityId)).ifPresent(failedInputs::addAll);
            for (Iterator<HeldInput<S>> iterator = startupSubmissions.iterator(); iterator.hasNext(); ) {
                HeldInput<S> submission = iterator.next();
                if (entityId.equals(submission.entityId())) {
                    failedInputs.add(submission);
                    iterator.remove();
                }
            }
        }
        Throwable cause =
                failure instanceof InitializationRejectedException rejected ? rejected.getRejection() : failure;
        failedInputs.forEach(held -> held.fail(cause));
    }

    private SubmissionResult<S> previouslyRejected() {
        return SubmissionResult.rejected(
                new IllegalStateException("Initialization was already rejected for this entity ID"));
    }

    private static RejectedExecutionException closedException() {
        return new RejectedExecutionException("Processing lane router is closed");
    }

    /** An input which the router holds before a lane accepts it, with the future of its submission. */
    private record HeldInput<S>(String entityId,
                                Input input,
                                CompletableFuture<SubmissionResult<S>> completion) {
        private static <S> HeldInput<S> of(String entityId, Input input) {
            return new HeldInput<>(entityId, input, new CompletableFuture<>());
        }

        private void complete(SubmissionResult<S> result) {
            completion.complete(result);
        }

        private void fail(Throwable failure) {
            completion.completeExceptionally(failure);
        }

        private void forward(CompletableFuture<SubmissionResult<S>> result) {
            result.whenComplete((submissionResult, failure) -> {
                if (failure != null) {
                    fail(failure);
                } else {
                    complete(submissionResult);
                }
            });
        }
    }

    private enum State {
        REGISTERING,
        STARTING,
        RUNNING,
        CLOSED
    }
}
