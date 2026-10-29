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

import bisq.common.processing.SubmissionResult.CoordinationPhase;
import bisq.common.timer.Clock;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;

/**
 * Serial ownership of one entity, from waiting for initialization through processing and checkpointing.
 * The application supplies an asynchronous executor, which may be shared by many lanes.
 * Only the submission queues and lifecycle flags use the lock; processing and callbacks run outside it.
 * Callbacks may submit more work but must not wait for work queued behind themselves.
 * <p>
 * The processor factory assembles processing logic from the prepared snapshot without performing business actions.
 * The lane owns the current snapshot and supplies it to the processor on every route and step.
 * <p>
 * Effect callbacks receive the saved current snapshot alongside the effect. They may use fixed entity data
 * such as its identity; the effect itself retains the payload needed for retries.
 * Snapshots and work supplied by the processor must remain stable after checkpoint construction.
 * Save failures retain the calculated result in memory, so a live retry does not repeat a handler.
 * Message handoff failures retain outgoing FIFO order but allow input processing to continue. Timers,
 * observations and other required effects must succeed before further processing. Each queued submission
 * may retry pending messages; the lane does not schedule background send retries or await delivery.
 * Restored lanes are constructed without starting work; the application queues resumption before new inputs.
 * Active deadlines are saved with the snapshot. Timer delivery transfers them to saved pending work before processing.
 * <p>
 * The snapshot publisher receives current local state and outstanding persistence and setup failures on the lane worker,
 * before completing a processed submission, including recovery and timer callbacks. Publication does not
 * acknowledge persistence or outgoing delivery. A waiting lane has no snapshot to publish.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 * @param <W> one addressed piece of work
 */
@Slf4j
public final class ProcessingLane<S, I extends Input, W> {
    private final Object lock = new Object();
    @Getter
    private final String entityId;
    private final Executor executor;
    private final ProcessingLaneDefinition<S, I, W> definition;
    private final CheckpointWriter<Checkpoint<S, I, W>> checkpointWriter;
    private final BiConsumer<? super S, ? super Effect> effectExecutor;
    private final Consumer<LogEntry> logWriter;
    private final SnapshotPublisher<? super S> snapshotPublisher;
    private final InputScheduler<ScheduledInput<I>> inputScheduler;
    private final ArrayDeque<Submission<?>> inbound = new ArrayDeque<>();
    private final ArrayDeque<WaitingInput<I, S>> waitingInputs = new ArrayDeque<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private final List<WorkAttempt<W>> pendingWork = new ArrayList<>();
    private final List<Effect> pendingEffects = new ArrayList<>();
    private final List<LogEntry> pendingLogs = new ArrayList<>();
    private final Map<String, ScheduledInput<I>> scheduledInputs = new LinkedHashMap<>();
    private Optional<Processor<S, I, W>> processor = Optional.empty();
    private Optional<S> snapshot = Optional.empty();
    private Optional<Checkpoint<S, I, W>> savedCheckpoint = Optional.empty();
    private Optional<RuntimeException> persistenceFailure = Optional.empty();
    private Optional<RuntimeException> setupFailure = Optional.empty();
    // Worker-only, reset for each queued submission so raised events do not repeatedly retry a failed send.
    private Optional<RuntimeException> messageHandoffFailure = Optional.empty();
    private Consumer<Throwable> onInitializationFailed = failure -> {};
    private long revision;
    // Tracks successful setup only for this live lane; never checkpointed.
    private boolean setupCompleted;
    private boolean initializationNotificationPending;
    private boolean recoveryEffectsPending;
    private boolean timersRestored;
    private boolean draining;
    private boolean closed;

    /**
     * Creates a passive waiting lane, or restores one from an already-loaded checkpoint.
     * Restoration assembles and validates the processor without preparing the entity, running setup or dispatching work.
     * Queue {@link #resume()} on restored lanes before routing new inputs.
     */
    public ProcessingLane(String entityId,
                          Optional<Checkpoint<S, I, W>> checkpoint,
                          Executor executor,
                          ScheduledExecutorService timerExecutor,
                          Clock clock,
                          ProcessingLaneDefinition<S, I, W> definition,
                          CheckpointWriter<Checkpoint<S, I, W>> checkpointWriter,
                          BiConsumer<? super S, ? super Effect> effectExecutor,
                          Consumer<LogEntry> logWriter,
                          SnapshotPublisher<? super S> snapshotPublisher) {
        this.entityId = entityId;
        this.executor = executor;
        this.definition = definition;
        this.checkpointWriter = checkpointWriter;
        this.effectExecutor = effectExecutor;
        this.logWriter = logWriter;
        this.snapshotPublisher = snapshotPublisher;
        inputScheduler = new InputScheduler<>(timerExecutor,
                this::enqueueScheduledInput,
                this::handleScheduledInput,
                clock);
        checkpoint.ifPresent(saved -> {
            saved.scheduledInputs().forEach(scheduled -> {
                checkState(!scheduledInputs.containsKey(scheduled.id()), "Duplicate scheduled input id: %s", scheduled.id());
                scheduledInputs.put(scheduled.id(), checkedScheduledInput(scheduled));
            });
            var restoredProcessor = definition.createProcessor(saved.snapshot());
            restoredProcessor.validateRestoration(saved.snapshot(),
                    saved.pendingWork().stream().map(PendingWork::value).toList());
            processor = Optional.of(restoredProcessor);
            snapshot = Optional.of(saved.snapshot());
            pendingWork.addAll(saved.pendingWork().stream().map(WorkAttempt::new).toList());
            pendingEffects.addAll(saved.pendingEffects());
            savedCheckpoint = Optional.of(saved);
            setupCompleted = !definition.hasSetup();
            recoveryEffectsPending = true;
        });
    }


    /* --------------------------------------------------------------------- */
    // Public API
    /* --------------------------------------------------------------------- */

    /**
     * Reports this input's processing result separately from pending persistence, setup or outgoing work.
     * Includes immediately processable raised events, but not failures belonging to older pending inputs.
     * A registered initialization input prepares the entity if no processor exists yet.
     * Eligible inputs arriving before initialization remain pending in memory until initialization succeeds.
     * Initialized non-command inputs are validated and routed against saved state, then checkpointed before
     * processing or retrying outgoing work. Save, setup and required-effect failures leave admitted work retained.
     * Cancelling the returned future does not undo an accepted submission.
     */
    public CompletableFuture<SubmissionResult<S>> submit(Input input) {
        return enqueue(completion -> {
            if (processor.isEmpty()) {
                handleBeforeInitialization(input, completion);
                return;
            }
            I processorInput;
            try {
                processorInput = definition.processorInput(input);
            } catch (RuntimeException failure) {
                completeSubmission(completion,
                        new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(failure)));
                return;
            }
            if (processorInput instanceof Command) {
                handleCommand(processorInput, completion);
            } else {
                handleNotification(processorInput, completion);
            }
        });
    }

    /**
     * Queues startup recovery or a live retry: finish saving, setup, recovery effects and timers, then pending work.
     * Recovery effects are prepared once per restored lane; a live retry reuses any remaining effects.
     * Completes after currently processable work, including safely checkpointed failure results; deferred inputs may remain.
     * Failed message handoffs are reported after processing retained inputs; other coordination failures stop processing.
     * Rejection, unexpected processor failure and coordination failure remain exceptional. Outgoing handoff does not confirm delivery.
     */
    public CompletableFuture<S> resume() {
        return enqueue(completion -> {
            checkState(processor.isPresent(), "Entity %s is not initialized", entityId);
            revision++;
            var failure = finishOutstandingWork();
            if (failure.isPresent()) {
                throw failure.get();
            }
            if (messageHandoffFailure.isPresent()) {
                throw messageHandoffFailure.get();
            }
            completeSubmission(completion, snapshot.orElseThrow());
        });
    }

    /** Stops accepting submissions, drains accepted tasks and fails inputs still waiting for initialization. */
    public CompletableFuture<Void> shutdown() {
        boolean idle;
        synchronized (lock) {
            closed = true;
            idle = !draining;
        }
        if (idle) {
            finishShutdown();
        }
        return stopped.copy();
    }

    public boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    /** Installed by the router on a newly constructed lane before its first submission. */
    void setOnInitializationFailed(Consumer<Throwable> onInitializationFailed) {
        this.onInitializationFailed = onInitializationFailed;
    }


    /* --------------------------------------------------------------------- */
    // Initialization and recovery (worker only)
    /* --------------------------------------------------------------------- */

    private void handleBeforeInitialization(Input input, CompletableFuture<SubmissionResult<S>> completion) {
        var initializer = definition.initializerFor(input);
        if (initializer.isPresent()) {
            var progress = new SubmissionProgress();
            var failure = processSubmission(progress,
                    completion,
                    () -> initialize(initializer.orElseThrow(), progress));
            if (failure.isEmpty()) {
                replayInputsWaitingForInitialization();
            }
        } else {
            try {
                stashUntilInitialized(definition.processorInput(input), completion);
            } catch (RuntimeException failure) {
                completeSubmission(completion,
                        new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(failure)));
            }
        }
    }

    private void initialize(Supplier<Initialization<S, I>> initializer, SubmissionProgress progress) {
        try {
            var initialization = initializer.get();
            var initialSnapshot = initialization.snapshot();
            processor = Optional.of(definition.createProcessor(initialSnapshot));
            snapshot = Optional.of(initialSnapshot);
            progress.applied = true;
            initializationNotificationPending = true;
            setupCompleted = !definition.hasSetup();
            initialization.firstInput().ifPresent(input -> routeInput(input, Optional.of(progress)));
        } catch (RuntimeException failure) {
            if (snapshot.isEmpty()) {
                var cause = failure instanceof InitializationRejectedException rejected ? rejected.getRejection() : failure;
                progress.failure = Optional.of(cause);
                abort(cause, () -> onInitializationFailed.accept(failure));
                return;
            }
            abort(failure);
            throw failure;
        } catch (Error failure) {
            abort(failure, () -> {
                if (snapshot.isEmpty()) {
                    onInitializationFailed.accept(failure);
                }
            });
            throw failure;
        }
        // Publish the initial state before saving or setup so observers can report failures from either step.
        publishSnapshot();
        // Save the prepared entity and first input before setup, handlers or buffered inputs run.
        finishPendingWork();
    }

    private void finishSetup() {
        if (setupCompleted) {
            return;
        }
        try {
            definition.runSetup(snapshot.orElseThrow());
        } catch (RuntimeException failure) {
            setupFailure = Optional.of(failure);
            throw new CoordinationFailure(CoordinationPhase.SETUP, failure);
        }
        setupFailure = Optional.empty();
        setupCompleted = true;
    }

    private void notifyInitialized() {
        if (!initializationNotificationPending) {
            return;
        }
        initializationNotificationPending = false;
        try {
            definition.onInitialized(snapshot.orElseThrow());
        } catch (RuntimeException failure) {
            log.warn("Initialization notification failed for entity {}", entityId, failure);
        }
    }

    private void prepareRecoveryEffects() {
        if (!recoveryEffectsPending) {
            return;
        }
        callProcessor(() -> {
            var effects = List.copyOf(processor.orElseThrow().recoveryEffects(snapshot.orElseThrow()));
            var replayed = pendingEffects.stream().filter(Effect::replayOnRestart).toList();
            pendingEffects.clear();
            // Re-establish ongoing work before retrying older outgoing effects.
            retainEffects(effects);
            pendingEffects.addAll(replayed);
            return effects;
        });
        // A failed checkpoint retries these prepared effects without recreating them.
        recoveryEffectsPending = false;
    }


    /* --------------------------------------------------------------------- */
    // Initialized input processing (worker only)
    /* --------------------------------------------------------------------- */

    private void handleCommand(I input, CompletableFuture<SubmissionResult<S>> completion) {
        try {
            finishOutstandingWork();
        } catch (CoordinationFailure failure) {
            completeSubmission(completion, new SubmissionResult.NotProcessed<>(failure.failure));
            return;
        }
        if (!validateInput(snapshot.orElseThrow(), input, completion)) {
            return;
        }
        var progress = new SubmissionProgress();
        processSubmission(progress, completion, () -> {
            routeInput(input, Optional.of(progress));
            processAvailableWork();
        });
    }

    private void handleNotification(I input, CompletableFuture<SubmissionResult<S>> completion) {
        try {
            // Admit older waiting messages first, including when setup or outgoing work is still blocked.
            replayInputsWaitingForInitialization();
        } catch (CoordinationFailure failure) {
            // Each waiting submission reports its own failure. The new input still needs to be retained.
        }
        retainAndProcessInput(input, completion);
    }

    private boolean validateInput(S admissionSnapshot,
                                  I input,
                                  CompletableFuture<SubmissionResult<S>> completion) {
        try {
            processor.orElseThrow().validateInput(admissionSnapshot, input);
            return true;
        } catch (RuntimeException failure) {
            completeSubmission(completion, new SubmissionResult.Completed<>(new ProcessingResult.Rejected<>(failure)));
            return false;
        }
    }

    private Optional<CoordinationFailure> retainAndProcessInput(I input,
                                                               CompletableFuture<SubmissionResult<S>> completion) {
        // Before the first checkpoint, no handlers have run, so the prepared snapshot has no unsaved progress.
        S admissionSnapshot = savedCheckpoint.map(Checkpoint::snapshot).orElseGet(() -> snapshot.orElseThrow());
        if (!validateInput(admissionSnapshot, input, completion)) {
            return Optional.empty();
        }
        var progress = new SubmissionProgress();
        var work = routeToDestinations(admissionSnapshot, input, Optional.of(progress));
        if (work.isEmpty()) {
            completeSubmission(completion,
                    new SubmissionResult.Completed<>(new ProcessingResult.Ignored<>(admissionSnapshot)));
            return Optional.empty();
        }
        pendingWork.addAll(work);
        // Save admitted work even if an older send still fails. A save failure leaves it in memory for retry.
        return processSubmission(progress, completion, this::finishPendingWork);
    }

    private Optional<RuntimeException> finishOutstandingWork() {
        var failure = finishPendingWork();
        replayInputsWaitingForInitialization();
        return failure;
    }

    private Optional<RuntimeException> finishPendingWork() {
        // Resume here after a save failure, using the result already retained in memory.
        saveCheckpoint();
        finishSetup();
        notifyInitialized();
        prepareRecoveryEffects();
        // Recovery may replace pending effects or deadlines; unchanged checkpoints do not write again.
        saveCheckpoint();
        restoreScheduledInputs();
        processOutgoingWork();
        return processAvailableWork();
    }

    private Optional<RuntimeException> routeInput(I input, Optional<SubmissionProgress> progress) {
        var work = routeToDestinations(snapshot.orElseThrow(), input, progress);
        if (input instanceof Command && work.isEmpty()) {
            var failure = new IllegalStateException("No destination for command " + input);
            progress.ifPresent(value -> value.failure = Optional.of(failure));
            return Optional.of(failure);
        }
        pendingWork.addAll(work);
        return Optional.empty();
    }

    private List<WorkAttempt<W>> routeToDestinations(S currentSnapshot, I input, Optional<SubmissionProgress> progress) {
        return callProcessor(() -> processor.orElseThrow().route(currentSnapshot, input).stream()
                .map(work -> new WorkAttempt<>(new PendingWork<>(work, input instanceof Command), progress))
                .toList());
    }

    private Optional<RuntimeException> processAvailableWork() {
        Optional<RuntimeException> failure = Optional.empty();
        Optional<WorkAttempt<W>> next;
        while ((next = nextWorkToConsider()).isPresent()) {
            var attempt = next.get();
            attempt.lastAttemptedRevision = revision;
            var result = callProcessor(() -> processor.orElseThrow().processStep(snapshot.orElseThrow(), attempt.work.value()));
            var stepFailure = callProcessor(() -> recordStepResult(attempt, result));
            if (stepFailure.isPresent()) {
                var cause = stepFailure.get();
                // A Failed result supplies the processor's explicit failure snapshot. Once the following
                // checkpoint and outgoing work succeed, recovery can continue from that recorded outcome.
                // The originating submission still reports its own processing failure below.
                if (!(result instanceof StepResult.Failed<?, ?>) && failure.isEmpty()) {
                    failure = Optional.of(cause);
                }
                attempt.progress.ifPresentOrElse(
                        progress -> progress.failure = appendFailure(progress.failure, cause),
                        () -> log.warn("Pending input processing failed for {}", entityId, cause));
            }
            // A failed save leaves the calculated result intact; no further work is evaluated.
            saveCheckpoint();
            processOutgoingWork();
        }
        return failure;
    }

    private Optional<WorkAttempt<W>> nextWorkToConsider() {
        return pendingWork.stream()
                .filter(attempt -> attempt.lastAttemptedRevision != revision)
                .findFirst();
    }

    private Optional<RuntimeException> recordStepResult(WorkAttempt<W> attempt, StepResult<S, I> result) {
        switch (result) {
            case StepResult.Applied<S, I> applied -> retainStepResult(attempt, applied.nextSnapshot(), applied.output());
            case StepResult.Deferred<S, I> deferred -> {
                if (attempt.work.command()) {
                    pendingWork.remove(attempt);
                    return Optional.of(new IllegalStateException("Commands cannot be deferred"));
                }
            }
            case StepResult.Ignored<S, I> ignored -> pendingWork.remove(attempt);
            case StepResult.Rejected<S, I> rejected -> {
                pendingWork.remove(attempt);
                return Optional.of(rejected.cause());
            }
            case StepResult.Failed<S, I> failed -> {
                retainStepResult(attempt, failed.nextSnapshot(), failed.output());
                return Optional.of(failed.cause());
            }
        }
        return Optional.empty();
    }

    private void retainStepResult(WorkAttempt<W> attempt, S nextSnapshot, ProcessingOutput<I> output) {
        var raised = output.raisedEvents().stream()
                .flatMap(input -> routeToDestinations(nextSnapshot, input, attempt.progress).stream())
                .toList();
        snapshot = Optional.of(nextSnapshot);
        pendingWork.remove(attempt);
        pendingWork.addAll(0, raised);
        retainEffects(output.effects());
        pendingLogs.addAll(output.logs());
        attempt.progress.ifPresent(progress -> progress.applied = true);
        // An applied step may change data or processing history without changing the named business state.
        revision++;
    }

    private void retainEffects(List<Effect> effects) {
        pendingEffects.addAll(effects);
        for (var effect : effects) {
            if (effect instanceof ScheduledInput<?> scheduled) {
                scheduledInputs.put(scheduled.id(), checkedScheduledInput(scheduled));
            } else if (effect instanceof CancelScheduledInput cancellation) {
                scheduledInputs.remove(cancellation.id());
            }
        }
    }

    private Optional<CoordinationFailure> processSubmission(SubmissionProgress progress,
                                                           CompletableFuture<SubmissionResult<S>> completion,
                                                           Runnable action) {
        try {
            action.run();
            var processingResult = processingResult(progress);
            if (messageHandoffFailure.isPresent()) {
                completeSubmission(completion,
                        new SubmissionResult.Pending<>(processingResult,
                                CoordinationPhase.OUTGOING_WORK,
                                messageHandoffFailure.get()));
            } else {
                completeSubmission(completion, new SubmissionResult.Completed<>(processingResult));
            }
            return Optional.empty();
        } catch (CoordinationFailure failure) {
            var processingResult = processingResult(progress);
            completeSubmission(completion,
                    new SubmissionResult.Pending<>(processingResult, failure.phase, failure.failure));
            return Optional.of(failure);
        } finally {
            // Completion is a point-in-time result. Retained work must not keep its caller's progress alive.
            pendingWork.stream()
                    .filter(attempt -> attempt.progress.filter(owner -> owner == progress).isPresent())
                    .forEach(attempt -> attempt.progress = Optional.empty());
        }
    }

    private ProcessingResult<S> processingResult(SubmissionProgress progress) {
        boolean retained = pendingWork.stream()
                .anyMatch(attempt -> attempt.progress.filter(owner -> owner == progress).isPresent());
        if (progress.failure.isPresent()) {
            var failure = progress.failure.get();
            if (!progress.applied && !retained) {
                return new ProcessingResult.Rejected<>(failure);
            }
            return new ProcessingResult.Failed<>(snapshot.orElseThrow(), failure);
        }
        if (retained) {
            return new ProcessingResult.Deferred<>(snapshot.orElseThrow());
        }
        if (progress.applied) {
            return new ProcessingResult.Applied<>(snapshot.orElseThrow());
        }
        return new ProcessingResult.Ignored<>(snapshot.orElseThrow());
    }

    private <T> T callProcessor(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            // Without a valid failure snapshot there is no safe next step to checkpoint or retry.
            abort(failure);
            throw failure;
        }
    }

    private Optional<RuntimeException> appendFailure(Optional<RuntimeException> previous, RuntimeException failure) {
        if (previous.isEmpty()) {
            return Optional.of(failure);
        }
        if (previous.get() != failure) {
            previous.get().addSuppressed(failure);
        }
        return previous;
    }


    /* --------------------------------------------------------------------- */
    // Inputs waiting for initialization (worker only)
    /* --------------------------------------------------------------------- */

    private void stashUntilInitialized(I input, CompletableFuture<SubmissionResult<S>> completion) {
        checkState(definition.canStashUntilInitialized(input),
                "Entity %s is not initialized", entityId);
        synchronized (lock) {
            waitingInputs.addLast(new WaitingInput<>(input, completion));
        }
    }

    private void replayInputsWaitingForInitialization() {
        Optional<CoordinationFailure> firstFailure = Optional.empty();
        while (true) {
            WaitingInput<I, S> waiting;
            synchronized (lock) {
                waiting = waitingInputs.pollFirst();
            }
            if (waiting == null) {
                break;
            }
            try {
                var failure = retainAndProcessInput(waiting.input(), waiting.completion());
                if (firstFailure.isEmpty()) {
                    firstFailure = failure;
                }
            } catch (RuntimeException | Error failure) {
                failSubmission(waiting.completion(), failure);
                throw failure;
            }
        }
        if (firstFailure.isPresent()) {
            throw firstFailure.get();
        }
    }


    /* --------------------------------------------------------------------- */
    // Scheduled inputs (worker only)
    /* --------------------------------------------------------------------- */

    private void restoreScheduledInputs() {
        if (timersRestored) {
            return;
        }
        try {
            scheduledInputs.values().forEach(inputScheduler::schedule);
        } catch (RuntimeException failure) {
            throw new CoordinationFailure(CoordinationPhase.OUTGOING_WORK, failure);
        }
        timersRestored = true;
    }

    @SuppressWarnings("unchecked")
    private ScheduledInput<I> checkedScheduledInput(ScheduledInput<?> scheduled) {
        // Preserve the application's serializable descriptor after checking its input family.
        definition.processorInput(Input.class.cast(scheduled.input()));
        return (ScheduledInput<I>) scheduled;
    }

    private void handleScheduledInput(ScheduledInput<I> scheduled) {
        if (!scheduled.equals(scheduledInputs.get(scheduled.id()))) {
            return;
        }
        var rejection = routeInput(scheduled.input(), Optional.empty());
        scheduledInputs.remove(scheduled.id());
        // Atomically replace the active deadline with its addressed work, even if processing must wait.
        // A command without a destination consumes its deadline before reporting the rejection.
        saveCheckpoint();
        if (rejection.isPresent()) {
            throw rejection.get();
        }
        var failure = finishOutstandingWork();
        if (failure.isPresent()) {
            throw failure.get();
        }
        if (messageHandoffFailure.isPresent()) {
            throw messageHandoffFailure.get();
        }
    }

    private void applyTimerChange(String id) {
        // An older pending effect must not revive a deadline that was cancelled or replaced in the saved snapshot.
        Optional.ofNullable(scheduledInputs.get(id)).ifPresentOrElse(inputScheduler::schedule,
                () -> inputScheduler.cancel(id));
    }


    /* --------------------------------------------------------------------- */
    // Checkpoints and outgoing work (worker only)
    /* --------------------------------------------------------------------- */

    /** Called only after the snapshot and pending effects have been saved successfully. */
    private void processOutgoingWork() {
        try {
            processPendingEffects();
        } catch (CoordinationFailure failure) {
            messageHandoffFailure.filter(cause -> cause != failure.failure).ifPresent(failure.failure::addSuppressed);
            throw failure;
        } catch (RuntimeException failure) {
            messageHandoffFailure.filter(cause -> cause != failure).ifPresent(failure::addSuppressed);
            throw new CoordinationFailure(CoordinationPhase.OUTGOING_WORK, failure);
        } finally {
            writeBestEffortLogs();
        }
    }

    private void processPendingEffects() {
        for (var effect : List.copyOf(pendingEffects)) {
            if (effect instanceof Effect.SendMessage<?, ?> && messageHandoffFailure.isPresent()) {
                // Keep all later messages behind the failed handoff, including messages from other flows.
                continue;
            }
            try {
                processEffect(effect);
            } catch (CoordinationFailure failure) {
                // Even after a successful handoff, its removal must be saved before anything else runs.
                throw failure;
            } catch (RuntimeException failure) {
                if (effect instanceof Effect.SendMessage<?, ?>) {
                    messageHandoffFailure = Optional.of(failure);
                } else {
                    // Timers, observations and other required effects remain processing barriers.
                    throw failure;
                }
            }
        }
    }

    private void processEffect(Effect effect) {
        if (effect instanceof ScheduledInput<?> scheduled) {
            applyTimerChange(scheduled.id());
        } else if (effect instanceof CancelScheduledInput cancellation) {
            applyTimerChange(cancellation.id());
        } else {
            effectExecutor.accept(snapshot.orElseThrow(), effect);
        }
        pendingEffects.remove(effect);
        // If removal cannot be saved, retry this save before another effect or handler.
        saveCheckpoint();
    }

    private Checkpoint<S, I, W> createCheckpoint() {
        return new Checkpoint<>(snapshot.orElseThrow(),
                pendingWork.stream().map(attempt -> attempt.work).toList(),
                pendingEffects,
                List.copyOf(scheduledInputs.values()));
    }

    private void saveCheckpoint() {
        var checkpoint = createCheckpoint();
        if (savedCheckpoint.filter(checkpoint::equals).isPresent()) {
            persistenceFailure = Optional.empty();
            return;
        }
        try {
            checkpointWriter.saveAndWait(checkpoint);
        } catch (RuntimeException failure) {
            persistenceFailure = Optional.of(failure);
            throw new CoordinationFailure(CoordinationPhase.PERSISTENCE, failure);
        }
        savedCheckpoint = Optional.of(checkpoint);
        persistenceFailure = Optional.empty();
    }

    private void writeBestEffortLogs() {
        var entries = List.copyOf(pendingLogs);
        pendingLogs.clear();
        for (var entry : entries) {
            try {
                logWriter.accept(entry);
            } catch (RuntimeException failure) {
                log.warn("Could not write processing log for {}", entityId, failure);
            }
        }
    }


    /* --------------------------------------------------------------------- */
    // Serial submissions and lifecycle
    /* --------------------------------------------------------------------- */

    private <T> void completeSubmission(CompletableFuture<T> completion, T result) {
        publishSnapshot();
        completion.complete(result);
    }

    private void failSubmission(CompletableFuture<?> completion, Throwable failure) {
        publishSnapshot();
        completion.completeExceptionally(failure);
    }

    private void publishSnapshot() {
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            snapshotPublisher.publish(snapshot.orElseThrow(), persistenceFailure, setupFailure);
        } catch (Throwable failure) {
            // Observers must not change processing results or hide the original failure.
            log.warn("Snapshot publication failed for entity {}", entityId, failure);
        }
    }

    private void enqueueScheduledInput(Runnable task) {
        enqueue(completion -> {
            task.run();
            completeSubmission(completion, snapshot.orElseThrow());
        }).whenComplete((ignored, failure) -> {
            if (failure != null && !(isClosed() && failure instanceof RejectedExecutionException)) {
                log.warn("Scheduled input failed for {}", entityId, failure);
            }
        });
    }

    private <T> CompletableFuture<T> enqueue(Consumer<CompletableFuture<T>> action) {
        var submission = new Submission<>(action, new CompletableFuture<T>());
        boolean start;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Processing lane is closed: " + entityId));
            }
            inbound.addLast(submission);
            start = !draining;
            draining = true;
        }
        if (start) {
            try {
                executor.execute(this::drain);
            } catch (RejectedExecutionException failure) {
                abort(failure);
                synchronized (lock) {
                    draining = false;
                }
                finishShutdown();
            }
        }
        return submission.completion();
    }

    private void drain() {
        while (true) {
            Submission<?> submission;
            boolean finish;
            synchronized (lock) {
                submission = inbound.pollFirst();
                if (submission == null) {
                    draining = false;
                }
                finish = closed && !draining;
            }
            if (submission == null) {
                if (finish) {
                    finishShutdown();
                }
                return;
            }
            try {
                submission.run();
            } catch (CoordinationFailure failure) {
                failSubmission(submission.completion(), failure.failure);
            } catch (RuntimeException failure) {
                failSubmission(submission.completion(), failure);
            } catch (Error failure) {
                abort(failure);
                failSubmission(submission.completion(), failure);
            } finally {
                messageHandoffFailure = Optional.empty();
            }
        }
    }

    private void abort(Throwable failure) {
        abort(failure, () -> {});
    }

    private void abort(Throwable failure, Runnable beforeFailingSubmissions) {
        List<Submission<?>> rejected;
        List<WaitingInput<I, S>> waiting;
        synchronized (lock) {
            closed = true;
            rejected = List.copyOf(inbound);
            inbound.clear();
            waiting = List.copyOf(waitingInputs);
            waitingInputs.clear();
        }
        try {
            beforeFailingSubmissions.run();
        } catch (RuntimeException | Error notificationFailure) {
            if (notificationFailure != failure) {
                failure.addSuppressed(notificationFailure);
            }
        }
        waiting.forEach(input -> input.completion().completeExceptionally(failure));
        rejected.forEach(submission -> submission.completion().completeExceptionally(failure));
    }

    private void finishShutdown() {
        inputScheduler.close();
        List<WaitingInput<I, S>> waiting;
        synchronized (lock) {
            waiting = List.copyOf(waitingInputs);
            waitingInputs.clear();
        }
        var failure = new RejectedExecutionException("Processing lane stopped before input was saved: " + entityId);
        waiting.forEach(input -> input.completion().completeExceptionally(failure));
        stopped.complete(null);
    }

    private record Submission<T>(Consumer<CompletableFuture<T>> action, CompletableFuture<T> completion) {
        private void run() {
            action.accept(completion);
        }
    }

    private record WaitingInput<I, S>(I input, CompletableFuture<SubmissionResult<S>> completion) {}

    private static final class SubmissionProgress {
        private boolean applied;
        private Optional<RuntimeException> failure = Optional.empty();
    }

    private static final class CoordinationFailure extends RuntimeException {
        private final CoordinationPhase phase;
        private final RuntimeException failure;

        private CoordinationFailure(CoordinationPhase phase, RuntimeException failure) {
            super(failure);
            this.phase = phase;
            this.failure = failure;
        }
    }

    private static final class WorkAttempt<W> {
        private final PendingWork<W> work;
        private Optional<SubmissionProgress> progress;
        private long lastAttemptedRevision = -1;

        private WorkAttempt(PendingWork<W> work) {
            this(work, Optional.empty());
        }

        private WorkAttempt(PendingWork<W> work, Optional<SubmissionProgress> progress) {
            this.work = work;
            this.progress = progress;
        }
    }
}
