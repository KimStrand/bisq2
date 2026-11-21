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

import bisq.common.processing.CancelScheduledInput;
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.EffectFailureRule;
import bisq.common.processing.HandleResult;
import bisq.common.processing.Input;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.Processor;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.ScheduledInput;
import bisq.common.processing.TimerChange;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import bisq.common.timer.Clock;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;

/**
 * Serial ownership of one entity, from initialization through processing and checkpointing.
 * The application supplies an asynchronous executor, which may be shared by many lanes.
 * SerialTaskQueue owns task scheduling and locking; lane state is accessed by tasks or exclusive lifecycle callbacks.
 * Callbacks may submit more work but must not wait for work queued behind themselves.
 * <p>
 * The processor factory assembles processing logic from the prepared snapshot without performing business actions.
 * The lane owns the current snapshot and supplies it to the processor for each input attempt.
 * <p>
 * Effect callbacks receive the saved current snapshot alongside the effect. They may use fixed entity data
 * such as its identity; the effect itself retains the payload needed for retries.
 * Snapshots and work supplied by the processor must remain stable after checkpoint construction.
 * Save failures retain the calculated result in memory, so a live retry does not repeat a handler.
 * Message handoff failures retain outgoing FIFO order but allow input processing to continue. Observations
 * and other required effects must succeed before further processing, and so must the start of a timer. Each queued
 * submission may retry pending effects; an automatic retry uses this same serial lane. No retry awaits network
 * delivery.
 * Restored lanes are constructed without starting work; the application queues resumption before new inputs.
 * Active deadlines are saved with the snapshot. A timer change is not an effect and is never pending work: the
 * timers follow the saved deadlines, in the running process and after a restart. Timer delivery transfers a
 * deadline to saved pending work before processing.
 * The input policy can pause a pending input. The lane does not start the handler of a paused input and handles
 * its other inputs; the paused input stays pending and saved until the policy allows it.
 * <p>
 * The router publishes restored snapshots on its startup thread before queueing any lane work.
 * Subsequent publication runs on the lane worker before completing a submission, including recovery and timers.
 * The publisher receives current local state and outstanding persistence and setup failures. Publication does not
 * acknowledge persistence or outgoing delivery. A lane whose initializer has not run has no snapshot to publish.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
@Slf4j
public final class ProcessingLane<S, I extends Input> {
    private static final long RETRY_DELAY_SECONDS = 1;

    // Lane identity and processing dependencies
    @Getter
    private final String entityId;
    private final ProcessingLaneDefinition<S, I> definition;
    private final InputPolicy inputPolicy;
    private final CheckpointWriter<Checkpoint<S, I>> checkpointWriter;
    private final EffectExecutor<? super S> effectExecutor;
    private final SnapshotListener<? super S> snapshotListener;

    // Execution and scheduling
    private final SerialTaskQueue taskQueue;
    private final ScheduledExecutorService timerExecutor;
    private final InputScheduler<ScheduledInput<I>> inputScheduler;

    // Initialization and setup
    private Consumer<Throwable> onInitializationFailed = failure -> {
    };
    private Optional<Throwable> initializationFailureToReport = Optional.empty();
    private Optional<RuntimeException> setupFailure = Optional.empty();
    // Tracks successful setup only for this live lane; never checkpointed.
    private boolean setupCompleted;

    // Processing and checkpoint state
    private final List<InputAttempt<I>> pendingInputs = new ArrayList<>();
    private Optional<Processor<S, I>> processor = Optional.empty();
    private Optional<S> snapshot = Optional.empty();
    private Optional<Checkpoint<S, I>> savedCheckpoint = Optional.empty();
    private Optional<RuntimeException> persistenceFailure = Optional.empty();
    private long revision;

    // Pending effects and recovery
    private final List<PersistableEffect> pendingEffects = new ArrayList<>();
    private final List<ProtocolLogMessage> pendingLogs = new ArrayList<>();
    // Worker-only, reset for each queued submission so raised inputs do not repeatedly retry a failed send.
    private Optional<RuntimeException> messageHandoffFailure = Optional.empty();
    private boolean recoveryEffectsPending;

    // Scheduled inputs
    private final Map<String, ScheduledInput<I>> scheduledInputs = new LinkedHashMap<>();
    // The ids whose deadline was set, replaced or removed since the timers last followed the deadlines.
    private final Set<String> timerIdsToApply = new LinkedHashSet<>();

    // Automatic retries
    private boolean retryRequired;
    private long retryGeneration;
    private Optional<ScheduledFuture<?>> retryTask = Optional.empty();


    /* --------------------------------------------------------------------- */
    // Construction
    /* --------------------------------------------------------------------- */

    /**
     * Creates a passive lane for an initializer, or restores one from an already-loaded checkpoint.
     * Restoration assembles and validates the processor without preparing the entity, running setup or dispatching work.
     * Queue {@link #resume()} on restored lanes before routing new inputs.
     */
    public ProcessingLane(String entityId,
                          Optional<Checkpoint<S, I>> checkpoint,
                          Executor executor,
                          ScheduledExecutorService timerExecutor,
                          Clock clock,
                          ProcessingLaneDefinition<S, I> definition,
                          InputPolicy inputPolicy,
                          CheckpointWriter<Checkpoint<S, I>> checkpointWriter,
                          EffectExecutor<? super S> effectExecutor,
                          SnapshotListener<? super S> snapshotListener) {
        this.entityId = entityId;
        this.timerExecutor = timerExecutor;
        this.definition = definition;
        this.inputPolicy = inputPolicy;
        this.checkpointWriter = checkpointWriter;
        this.effectExecutor = effectExecutor;
        this.snapshotListener = snapshotListener;
        taskQueue = new SerialTaskQueue(
                executor,
                new SerialTaskQueue.Listener() {
                    @Override
                    public void onAborted(Throwable failure) {
                        handleQueueAbort(failure);
                    }

                    @Override
                    public void onStopped() {
                        finishShutdown();
                    }
                });
        inputScheduler = new InputScheduler<>(
                timerExecutor,
                this::enqueueScheduledTask,
                this::handleScheduledInput,
                clock);
        checkpoint.ifPresent(saved -> {
            saved.scheduledInputs().forEach(scheduled -> {
                checkState(!scheduledInputs.containsKey(scheduled.id()), "Duplicate scheduled input id: %s", scheduled.id());
                scheduledInputs.put(scheduled.id(), checkedScheduledInput(scheduled));
                timerIdsToApply.add(scheduled.id());
            });
            Processor<S, I> restoredProcessor = definition.createProcessor(saved.snapshot());
            processor = Optional.of(restoredProcessor);
            snapshot = Optional.of(saved.snapshot());
            pendingInputs.addAll(saved.pendingInputs().stream().map(input -> new InputAttempt<>(definition.processorInput(input))).toList());
            pendingEffects.addAll(saved.pendingEffects());
            savedCheckpoint = Optional.of(saved);
            recoveryEffectsPending = true;
        });
    }


    /* --------------------------------------------------------------------- */
    // Public API
    /* --------------------------------------------------------------------- */

    /**
     * Reports this input's processing summary separately from pending persistence, setup or outgoing work.
     * Includes immediately processable raised inputs, but not failures belonging to older pending inputs.
     * A registered initialization input prepares the entity if no processor exists yet. Before that, other inputs
     * are rejected; the router keeps eligible inputs until the initializer has run.
     * Initialized non-command inputs are prevalidated against saved state, then retained and checkpointed before
     * processing or retrying outgoing work. A message which, according to the saved state, can no longer be handled
     * is ignored without being saved. Save, setup and required-effect failures leave admitted work retained.
     * Cancelling the returned future does not undo an accepted submission.
     */
    public CompletableFuture<SubmissionResult<S>> submit(Input input) {
        return enqueue(() -> {
            if (processor.isEmpty()) {
                return handleBeforeInitialization(input);
            }
            I processorInput;
            try {
                processorInput = definition.processorInput(input);
            } catch (RuntimeException failure) {
                return SubmissionResult.rejected(failure);
            }
            if (processorInput instanceof Command) {
                return handleCommand(processorInput);
            }
            return handleMessage(processorInput);
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
        return enqueue(() -> {
            checkState(processor.isPresent(), "Entity %s is not initialized", entityId);
            revision++;
            resumeProcessingAndThrowFailure();
            return snapshot.orElseThrow();
        });
    }

    /**
     * Continues if a pending input can be handled now, in particular one which the input policy paused before
     * and allows now. Otherwise it changes nothing. It also changes nothing while a retry is pending: the retry
     * continues with such an input, and this call must not repeat the work which the retry waits for. A lane
     * whose initializer has not run has no pending input. Completes with true if the lane continued.
     * Failures are reported as for {@link #resume()}.
     */
    public CompletableFuture<Boolean> resumePausedInputs() {
        return enqueue(() -> {
            if (retryRequired || nextInputToHandle().isEmpty()) {
                return false;
            }
            resumeProcessingAndThrowFailure();
            return true;
        });
    }

    /**
     * Stops accepting submissions and drains accepted tasks.
     */
    public CompletableFuture<Void> shutdown() {
        return taskQueue.shutdown();
    }

    @VisibleForTesting
    public boolean isClosed() {
        return taskQueue.isClosed();
    }


    /* --------------------------------------------------------------------- */
    // Router integration
    /* --------------------------------------------------------------------- */

    /**
     * Installed by the router on a newly constructed lane before its first submission.
     */
    void setOnInitializationFailed(Consumer<Throwable> onInitializationFailed) {
        this.onInitializationFailed = onInitializationFailed;
    }

    /**
     * Called once by the router on its startup thread, before any lane work is queued.
     */
    void publishRestoredSnapshot() {
        publishSnapshot();
    }


    /* --------------------------------------------------------------------- */
    // Initialization and recovery (worker only)
    /* --------------------------------------------------------------------- */

    private SubmissionResult<S> handleBeforeInitialization(Input input) {
        Optional<Supplier<Initialization<S, I>>> initializer = definition.initializerFor(input);
        if (initializer.isEmpty()) {
            // The router submits other inputs of a new entity only after its initializer has run.
            return SubmissionResult.rejected(new IllegalStateException("Entity " + entityId + " is not initialized"));
        }
        SubmissionProgress progress = new SubmissionProgress();
        return processSubmission(progress, () -> initialize(initializer.orElseThrow(), progress));
    }

    private void initialize(Supplier<Initialization<S, I>> initializer, SubmissionProgress progress) {
        Initialization<S, I> initialization;
        Optional<Processor<S, I>> preparedProcessor;
        Optional<S> preparedSnapshot;
        try {
            // Prepare everything, including the Optional values, before the lane adopts any of it. Every failure
            // here, including an Error, is a failed initialization which the router must learn about.
            initialization = initializer.get();
            preparedProcessor = Optional.of(definition.createProcessor(initialization.snapshot()));
            preparedSnapshot = Optional.of(initialization.snapshot());
        } catch (RuntimeException failure) {
            RuntimeException cause =
                    failure instanceof InitializationRejectedException rejected ? rejected.getRejection() : failure;
            progress.failure = Optional.of(cause);
            initializationFailureToReport = Optional.of(failure);
            taskQueue.abort(cause);
            return;
        } catch (Error failure) {
            initializationFailureToReport = Optional.of(failure);
            taskQueue.abort(failure);
            throw failure;
        }
        // Plain assignments cannot fail, so the lane adopts the processor and the snapshot together.
        // An Error from retaining the input closes the lane through the task wrapper.
        processor = preparedProcessor;
        snapshot = preparedSnapshot;
        progress.applied = true;
        retainInput(initialization.firstInput(), Optional.of(progress));
        // Publish the initial state before saving or setup so observers can report failures from either step.
        publishSnapshot();
        // Save the prepared entity and first input before setup or handlers run.
        resumeProcessing();
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

    private void prepareRecoveryEffects() {
        if (!recoveryEffectsPending) {
            return;
        }
        callProcessor(() -> {
            List<Effect> effects = List.copyOf(processor.orElseThrow().createRecoveryEffects(snapshot.orElseThrow()));
            List<PersistableEffect> replayed =
                    pendingEffects.stream().filter(PersistableEffect::replayOnRestart).toList();
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
    // Handling new inputs (worker only)
    /* --------------------------------------------------------------------- */

    private SubmissionResult<S> handleCommand(I input) {
        try {
            resumeProcessing();
        } catch (CoordinationFailure failure) {
            retryRequired = true;
            return new SubmissionResult.NotProcessed<>(failure.failure);
        }
        Optional<RuntimeException> validationFailure = validateInput(snapshot.orElseThrow(), input);
        if (validationFailure.isPresent()) {
            return SubmissionResult.rejected(validationFailure.get());
        }
        SubmissionProgress progress = new SubmissionProgress();
        return processSubmission(progress, () -> {
            retainInput(input, Optional.of(progress));
            processPendingInputs();
        });
    }

    private SubmissionResult<S> handleMessage(I input) {
        // Before the first checkpoint, no handlers have run, so the prepared snapshot has no unsaved progress.
        S admissionSnapshot = savedCheckpoint.map(Checkpoint::snapshot).orElseGet(() -> snapshot.orElseThrow());
        Optional<RuntimeException> validationFailure = validateInput(admissionSnapshot, input);
        if (validationFailure.isPresent()) {
            return SubmissionResult.rejected(validationFailure.get());
        }
        // According to the saved state, handling would ignore this message now and later: neither keep nor save it.
        // Earlier unfinished work, such as a failed send, still gets this submission's turn. The empty progress
        // makes the result Ignored, wrapped in Pending if that earlier work fails.
        if (!processor.orElseThrow().canStillBeHandled(admissionSnapshot, input)) {
            return processSubmission(new SubmissionProgress(), this::resumeProcessing);
        }
        SubmissionProgress progress = new SubmissionProgress();
        retainInput(input, Optional.of(progress));
        return processSubmission(progress, () -> {
            // Save reception even while an earlier required effect prevents business processing.
            saveCheckpoint();
            resumeProcessing();
        });
    }

    private Optional<RuntimeException> validateInput(S admissionSnapshot, I input) {
        try {
            definition.validateInput(admissionSnapshot, input);
            return Optional.empty();
        } catch (RuntimeException failure) {
            return Optional.of(failure);
        }
    }



    /* --------------------------------------------------------------------- */
    // Processing retained inputs (worker only)
    /* --------------------------------------------------------------------- */

    private Optional<RuntimeException> resumeProcessing() {
        // A send that failed earlier in this submission keeps its retry; the guard stays set until the task ends.
        retryRequired = messageHandoffFailure.isPresent();
        // Resume here after a save failure, using the result already retained in memory.
        saveCheckpoint();
        finishSetup();
        prepareRecoveryEffects();
        // Recovery may replace pending effects or deadlines; unchanged checkpoints do not write again.
        saveCheckpoint();
        processOutgoingWork();
        return processPendingInputs();
    }

    // Used by tasks without a processing summary of their own: the first processing failure, or else a failed
    // message handoff, fails the task's submission.
    private void resumeProcessingAndThrowFailure() {
        Optional<RuntimeException> failure = resumeProcessing();
        if (failure.isPresent()) {
            throw failure.get();
        }
        if (messageHandoffFailure.isPresent()) {
            throw messageHandoffFailure.get();
        }
    }

    private void retainInput(I input, Optional<SubmissionProgress> progress) {
        pendingInputs.add(new InputAttempt<>(input, progress));
    }

    private Optional<RuntimeException> processPendingInputs() {
        Optional<RuntimeException> failure = Optional.empty();
        Optional<InputAttempt<I>> next;
        while ((next = nextInputToHandle()).isPresent()) {
            InputAttempt<I> attempt = next.get();
            attempt.lastAttemptedRevision = revision;
            HandleResult<S, I> result =
                    callProcessor(() -> processor.orElseThrow().handle(snapshot.orElseThrow(), attempt.input));
            Optional<RuntimeException> processingFailure = callProcessor(() -> retainResult(attempt, result));
            if (processingFailure.isPresent()) {
                reportProcessingFailure(attempt, processingFailure.get());
                if (!(result instanceof HandleResult.Failed<?, ?>) && failure.isEmpty()) {
                    failure = processingFailure;
                }
            }
            // A failed save leaves the calculated result intact; no further work is evaluated.
            saveCheckpoint();
            processOutgoingWork();
        }
        return failure;
    }

    private void reportProcessingFailure(InputAttempt<I> attempt, RuntimeException failure) {
        attempt.progress.ifPresentOrElse(
                progress -> progress.failure = appendFailure(progress.failure, failure),
                () -> log.warn("Pending input processing failed for {}", entityId, failure));
    }

    // A paused input is not attempted: it stays pending, and this method asks the input policy again each time.
    private Optional<InputAttempt<I>> nextInputToHandle() {
        return pendingInputs.stream()
                .filter(attempt -> attempt.lastAttemptedRevision != revision)
                .filter(attempt -> !inputPolicy.isPaused(attempt.input))
                .findFirst();
    }

    private Optional<RuntimeException> retainResult(InputAttempt<I> attempt, HandleResult<S, I> result) {
        switch (result) {
            case HandleResult.Applied<S, I> applied ->
                    retainSnapshotAndOutput(attempt, applied.nextSnapshot(), applied.output());
            case HandleResult.Deferred<S, I> deferred -> {
                if (attempt.input instanceof Command) {
                    pendingInputs.remove(attempt);
                    return Optional.of(new IllegalStateException("Commands cannot be deferred"));
                }
            }
            case HandleResult.Ignored<S, I> ignored -> pendingInputs.remove(attempt);
            case HandleResult.Rejected<S, I> rejected -> {
                pendingInputs.remove(attempt);
                return Optional.of(rejected.cause());
            }
            case HandleResult.Failed<S, I> failed -> {
                retainSnapshotAndOutput(attempt, failed.nextSnapshot(), failed.output());
                return Optional.of(failed.cause());
            }
        }
        return Optional.empty();
    }

    private void retainSnapshotAndOutput(InputAttempt<I> attempt,
                                         S nextSnapshot,
                                         ProcessingOutput<I> output) {
        List<InputAttempt<I>> raised = output.raisedInputs().stream()
                .map(input -> new InputAttempt<>(input, attempt.progress))
                .toList();
        snapshot = Optional.of(nextSnapshot);
        pendingInputs.remove(attempt);
        pendingInputs.addAll(0, raised);
        retainEffects(output.effects());
        retainTimerChanges(output.timerChanges());
        attempt.progress.ifPresent(progress -> progress.applied = true);
        // An applied result may change data or processing history without changing the named business state.
        revision++;
    }

    private void retainEffects(List<Effect> effects) {
        for (Effect effect : effects) {
            switch (effect) {
                case PersistableEffect persistable -> pendingEffects.add(persistable);
                case ProtocolLogMessage entry -> pendingLogs.add(entry);
            }
        }
    }

    // A timer is not pending work: its deadline is saved, and the timers follow the saved deadlines.
    private void retainTimerChanges(List<TimerChange> timerChanges) {
        for (TimerChange timerChange : timerChanges) {
            switch (timerChange) {
                case ScheduledInput<?> scheduled ->
                        scheduledInputs.put(scheduled.id(), checkedScheduledInput(scheduled));
                case CancelScheduledInput cancellation -> scheduledInputs.remove(cancellation.id());
            }
            timerIdsToApply.add(timerChange.id());
        }
    }

    private <T> T callProcessor(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (RuntimeException | Error failure) {
            // Without a valid failure snapshot there is no safe next step to checkpoint or retry.
            taskQueue.abort(failure);
            throw failure;
        }
    }


    /* --------------------------------------------------------------------- */
    // Submission results and snapshot publication
    /* --------------------------------------------------------------------- */

    private SubmissionResult<S> processSubmission(SubmissionProgress progress, Runnable action) {
        try {
            action.run();
            ProcessingSummary<S> processingSummary = processingSummary(progress);
            if (messageHandoffFailure.isPresent()) {
                return new SubmissionResult.Pending<>(processingSummary,
                        CoordinationPhase.OUTGOING_WORK,
                        messageHandoffFailure.get());
            }
            return new SubmissionResult.Completed<>(processingSummary);
        } catch (CoordinationFailure failure) {
            retryRequired = true;
            return new SubmissionResult.Pending<>(processingSummary(progress), failure.phase, failure.failure);
        } finally {
            // The result is a point-in-time value. Retained work must not keep its caller's progress alive.
            pendingInputs.stream()
                    .filter(attempt -> attempt.progress.filter(owner -> owner == progress).isPresent())
                    .forEach(attempt -> attempt.progress = Optional.empty());
        }
    }

    private ProcessingSummary<S> processingSummary(SubmissionProgress progress) {
        boolean retained = pendingInputs.stream()
                .anyMatch(attempt -> attempt.progress.filter(owner -> owner == progress).isPresent());
        if (progress.failure.isPresent()) {
            RuntimeException failure = progress.failure.get();
            if (!progress.applied && !retained) {
                return new ProcessingSummary.Rejected<>(failure);
            }
            return new ProcessingSummary.Failed<>(snapshot.orElseThrow(), failure);
        }
        if (retained) {
            return new ProcessingSummary.Deferred<>(snapshot.orElseThrow());
        }
        if (progress.applied) {
            return new ProcessingSummary.Applied<>(snapshot.orElseThrow());
        }
        return new ProcessingSummary.Ignored<>(snapshot.orElseThrow());
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

    private void publishSnapshot() {
        if (snapshot.isEmpty()) {
            return;
        }
        try {
            snapshotListener.onSnapshot(snapshot.orElseThrow(), persistenceFailure, setupFailure);
        } catch (Throwable failure) {
            // Observers must not change processing results or hide the original failure.
            log.warn("Snapshot publication failed for entity {}", entityId, failure);
        }
    }


    /* --------------------------------------------------------------------- */
    // Scheduled inputs (worker only)
    /* --------------------------------------------------------------------- */

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
        retainInput(scheduled.input(), Optional.empty());
        scheduledInputs.remove(scheduled.id());
        // Atomically replace the active deadline with its input, even if processing must wait.
        saveCheckpoint();
        resumeProcessingAndThrowFailure();
    }

    /**
     * Lets the timers follow the deadlines: it starts or replaces the timer of a set deadline and stops the timer of
     * a removed one. Called only after the checkpoint with these deadlines is saved. After a restart it starts the
     * timer of every saved deadline.
     */
    private void applyTimerChanges() {
        for (String id : List.copyOf(timerIdsToApply)) {
            Optional.ofNullable(scheduledInputs.get(id)).ifPresentOrElse(inputScheduler::schedule,
                    () -> inputScheduler.cancel(id));
            timerIdsToApply.remove(id);
        }
    }


    /* --------------------------------------------------------------------- */
    // Checkpoint persistence (worker only)
    /* --------------------------------------------------------------------- */

    private Checkpoint<S, I> createCheckpoint() {
        return new Checkpoint<>(
                snapshot.orElseThrow(),
                pendingInputs.stream().map(attempt -> attempt.input).toList(),
                pendingEffects,
                List.copyOf(scheduledInputs.values()));
    }

    private void saveCheckpoint() {
        Checkpoint<S, I> checkpoint = createCheckpoint();
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


    /* --------------------------------------------------------------------- */
    // Outgoing work (worker only)
    /* --------------------------------------------------------------------- */

    /**
     * Lets the timers follow the saved deadlines, executes the pending effects and writes the logs.
     * Called only after the snapshot, the pending effects and the deadlines have been saved successfully.
     */
    private void processOutgoingWork() {
        try {
            applyTimerChanges();
            executePendingEffects();
        } catch (CoordinationFailure failure) {
            messageHandoffFailure.filter(cause -> cause != failure.failure).ifPresent(failure.failure::addSuppressed);
            throw failure;
        } catch (RuntimeException failure) {
            messageHandoffFailure.filter(cause -> cause != failure).ifPresent(failure::addSuppressed);
            throw new CoordinationFailure(CoordinationPhase.OUTGOING_WORK, failure);
        } finally {
            processLogs();
        }
    }

    private void executePendingEffects() {
        for (PersistableEffect effect : List.copyOf(pendingEffects)) {
            EffectFailureRule failureRule = effect.failureRule();
            if (failureRule == EffectFailureRule.HANDOFF && messageHandoffFailure.isPresent()) {
                // Keep all later messages behind the failed handoff, including messages from other flows.
                continue;
            }
            try {
                processEffect(effect);
            } catch (CoordinationFailure failure) {
                // Even after a successful handoff, its removal must be saved before anything else runs.
                throw failure;
            } catch (RuntimeException failure) {
                switch (failureRule) {
                    case BARRIER -> throw failure;
                    case HANDOFF -> {
                        messageHandoffFailure = Optional.of(failure);
                        retryRequired = true;
                    }
                    case BEST_EFFORT -> {
                        log.warn("Best-effort effect {} failed for {}",
                                effect.getClass().getSimpleName(), entityId, failure);
                        pendingEffects.remove(effect);
                        saveCheckpoint();
                    }
                }
            }
        }
    }

    private void processEffect(PersistableEffect effect) {
        effectExecutor.execute(snapshot.orElseThrow(), effect);
        pendingEffects.remove(effect);
        // If removal cannot be saved, retry this save before another effect or handler.
        saveCheckpoint();
    }

    private void processLogs() {
        List<ProtocolLogMessage> entries = List.copyOf(pendingLogs);
        pendingLogs.clear();
        for (ProtocolLogMessage entry : entries) {
            try {
                effectExecutor.execute(snapshot.orElseThrow(), entry);
            } catch (RuntimeException failure) {
                log.warn("Could not write processing log for {}", entityId, failure);
            }
        }
    }


    /* --------------------------------------------------------------------- */
    // Serial submissions
    /* --------------------------------------------------------------------- */

    private void enqueueScheduledTask(Runnable task) {
        enqueue(() -> {
            task.run();
            return snapshot.orElseThrow();
        }).whenComplete((ignored, failure) -> {
            if (failure != null && !(isClosed() && failure instanceof RejectedExecutionException)) {
                log.warn("Scheduled processing failed for {}", entityId, failure);
            }
        });
    }

    private <T> CompletableFuture<T> enqueue(Supplier<T> action) {
        CompletableFuture<T> completion = new CompletableFuture<T>();
        taskQueue.submit(new SerialTaskQueue.Task() {
            @Override
            public void run() {
                runSubmission(action, completion);
            }

            @Override
            public void onRejected(Throwable failure) {
                // May run on the submitting thread, so it fails the task without reading or publishing lane state.
                completion.completeExceptionally(failure);
            }
        });
        return completion;
    }

    // The only place where a task that ran completes its future: publish the snapshot, complete the future, and only
    // then update the retry schedule. A scheduling failure closes the lane but cannot replace the final result.
    private <T> void runSubmission(Supplier<T> action, CompletableFuture<T> completion) {
        try {
            Optional<T> result = Optional.empty();
            Optional<Throwable> failure = Optional.empty();
            try {
                result = Optional.of(action.get());
            } catch (CoordinationFailure coordinationFailure) {
                retryRequired = true;
                failure = Optional.of(coordinationFailure.failure);
            } catch (RuntimeException runtimeFailure) {
                failure = Optional.of(runtimeFailure);
            } catch (Error error) {
                taskQueue.abort(error);
                failure = Optional.of(error);
            }
            publishSnapshot();
            if (failure.isPresent()) {
                completion.completeExceptionally(failure.get());
            } else {
                completion.complete(result.orElseThrow());
            }
        } finally {
            try {
                updateRetrySchedule();
            } catch (RuntimeException | Error schedulingFailure) {
                taskQueue.abort(schedulingFailure);
                throw schedulingFailure;
            } finally {
                messageHandoffFailure = Optional.empty();
            }
        }
    }


    /* --------------------------------------------------------------------- */
    // Automatic retries
    /* --------------------------------------------------------------------- */

    // The timer only submits work. All retry decisions and effects run under the same serial owner.
    private void updateRetrySchedule() {
        if (isClosed() || !retryRequired) {
            cancelRetry();
            return;
        }
        if (retryTask.isPresent()) {
            return;
        }
        long generation = ++retryGeneration;
        try {
            retryTask = Optional.of(timerExecutor.schedule(() -> enqueueScheduledTask(() -> {
                if (generation == retryGeneration && retryRequired) {
                    retryTask = Optional.empty();
                    resumeProcessing();
                }
            }), RETRY_DELAY_SECONDS, TimeUnit.SECONDS));
        } catch (RejectedExecutionException failure) {
            log.warn("Could not schedule processing retry for entity {}", entityId, failure);
        }
    }

    private void cancelRetry() {
        retryGeneration++;
        retryTask.ifPresent(task -> task.cancel(false));
        retryTask = Optional.empty();
    }


    /* --------------------------------------------------------------------- */
    // Abort and shutdown callbacks
    /* --------------------------------------------------------------------- */

    private void handleQueueAbort(Throwable failure) {
        Optional<Throwable> initializationFailure = initializationFailureToReport;
        initializationFailureToReport = Optional.empty();
        try {
            initializationFailure.ifPresent(onInitializationFailed);
        } catch (RuntimeException | Error notificationFailure) {
            if (notificationFailure != failure) {
                failure.addSuppressed(notificationFailure);
            }
        }
    }

    private void finishShutdown() {
        try {
            cancelRetry();
        } finally {
            inputScheduler.close();
        }
    }


    /* --------------------------------------------------------------------- */
    // Internal types
    /* --------------------------------------------------------------------- */

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

    private static final class InputAttempt<I> {
        private final I input;
        private Optional<SubmissionProgress> progress;
        private long lastAttemptedRevision = -1;

        private InputAttempt(I input) {
            this(input, Optional.empty());
        }

        private InputAttempt(I input, Optional<SubmissionProgress> progress) {
            this.input = input;
            this.progress = progress;
        }
    }
}
