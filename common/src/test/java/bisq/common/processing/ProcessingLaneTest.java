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
import bisq.common.proto.NetworkProto;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.MockClock;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProcessingLaneTest {
    private static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publishesTheAdoptedInitialSnapshotBeforeSaveAndSetup(boolean observerFails) throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var order = new ArrayList<String>();
        fixture.onPrepare = input -> order.add("admit");
        fixture.onCreateProcessor = snapshot -> order.add("create-processor");
        fixture.onPublish = (snapshot, persistenceFailure, setupFailure) -> {
            if (fixture.published.size() == 1) {
                order.add("publish");
                if (observerFails) {
                    throw new IllegalStateException("Observer failed");
                }
            }
        };
        fixture.onSave = checkpoint -> {
            if (fixture.saved.isEmpty()) {
                order.add("save");
            }
        };
        fixture.onSetup = snapshot -> order.add("setup");
        fixture.handler = (snapshot, work) -> {
            order.add("first-input");
            return applied(snapshot, emptyOutput());
        };

        var initialized = lane.submit(new Initialize(Optional.of(new Message("first"))));
        fixture.executor.runAll();

        assertEquals(List.of("admit", "create-processor", "publish", "save", "setup", "first-input"), order);
        assertEquals(new PublishedSnapshot(new Counter(0, false), Optional.empty(), Optional.empty()), fixture.published.getFirst());
        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertFalse(lane.isClosed());
    }

    @Test
    void savesTheFirstInputBeforeHandlingItAndThenReplaysWaitingMessagesInOrder() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        var early = fixture.lane.submit(new Message("early"));
        var second = fixture.lane.submit(new Message("second"));
        fixture.executor.runAll();
        assertFalse(early.isDone());
        assertFalse(second.isDone());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());

        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Message("first"))) {
                assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
                assertEquals(List.of(new PendingWork<>(work, false)), fixture.lastSaved().pendingWork());
                assertFalse(early.isDone());
            }
            return applied(snapshot, emptyOutput());
        };
        var initialized = fixture.lane.submit(new Initialize(Optional.of(new Message("first"))));
        assertEquals(0, preparations.get());
        fixture.executor.runAll();

        assertEquals(1, preparations.get());
        assertEquals(List.of(new Message("first"), new Message("early"), new Message("second")), fixture.attemptedInputs());
        assertEquals(new Counter(2, false), awaitSnapshot(early));
        assertEquals(new Counter(3, false), awaitSnapshot(second));
        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertEquals(List.of(new PublishedSnapshot(new Counter(0, false), Optional.empty(), Optional.empty()),
                new PublishedSnapshot(new Counter(1, false), Optional.empty(), Optional.empty()),
                new PublishedSnapshot(new Counter(2, false), Optional.empty(), Optional.empty()),
                new PublishedSnapshot(new Counter(3, false), Optional.empty(), Optional.empty())), fixture.published);
    }

    @Test
    void newEntityNotificationFollowsSetupAndNeverRepeatsDuringRetryOrRecovery() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var notifications = new ArrayList<Counter>();
        fixture.onSetup = snapshot -> { throw new IllegalStateException("Setup unavailable"); };
        fixture.onInitialized = snapshot -> {
            assertEquals(snapshot, fixture.lastSaved().snapshot());
            assertTrue(fixture.attempted.isEmpty());
            notifications.add(snapshot);
            throw new IllegalStateException("Best-effort contact creation failed");
        };
        var initialized = lane.submit(new Initialize(Optional.of(new Message("first"))));
        fixture.executor.runAll();
        var initializedPending = assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        assertEquals(CoordinationPhase.SETUP, initializedPending.phase());
        assertTrue(notifications.isEmpty());

        fixture.onSetup = snapshot -> {};
        var retried = lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), await(retried));
        var repeated = lane.resume();
        fixture.executor.runAll();
        await(repeated);
        assertEquals(List.of(new Counter(0, false)), notifications);
        assertEquals(1, fixture.attempted.size());

        var restored = new Fixture();
        restored.onInitialized = snapshot -> fail("Recovery must not recreate contacts");
        var recovery = restored.restoreLane(fixture.lastSaved(), restored.executor).resume();
        restored.executor.runAll();
        assertEquals(new Counter(1, false), await(recovery));
        assertTrue(restored.attempted.isEmpty());
    }

    @Test
    void recoveryCompletesAfterSavingAnExplicitProcessingFailure() throws Exception {
        var fixture = new Fixture();
        var input = new Message("pending");
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(0, false),
                List.of(new PendingWork<>(new Work("counter", input), false)),
                List.of(),
                List.of());
        var failed = new Counter(0, true);
        fixture.handler = (snapshot, work) -> new StepResult.Failed<>(failed,
                emptyOutput(),
                new IllegalStateException("Domain operation failed"));
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        var recovery = lane.resume();
        fixture.executor.runAll();
        assertEquals(failed, await(recovery));
        assertEquals(failed, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertFalse(lane.isClosed());
    }

    @Test
    void publishesLocalFailureBeforeCompletionAndClearsSaveFailureWithoutRepeatingTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var domainFailure = new IllegalStateException("Processing failed");
        var saveFailure = new IllegalStateException("Disk write failed");
        var failedSnapshot = new Counter(0, true);
        fixture.handler = (snapshot, work) -> new StepResult.Failed<>(failedSnapshot, emptyOutput(), domainFailure);
        fixture.onSave = checkpoint -> { throw saveFailure; };

        var submitted = fixture.lane.submit(new Action("fail"));
        var observedAtCompletion = submitted.thenApply(ignored -> fixture.published.getLast());
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submitted));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertSame(saveFailure, pending.cause());
        assertSame(domainFailure, assertInstanceOf(ProcessingResult.Failed.class, pending.processingResult()).cause());
        assertEquals(new PublishedSnapshot(failedSnapshot, Optional.of(saveFailure), Optional.empty()), await(observedAtCompletion));
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());

        // An irrelevant input must not replace current local state with its older admission snapshot or clear the error.
        fixture.routes = (snapshot, input) -> List.of();
        var ignored = fixture.lane.submit(new Message("irrelevant"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Ignored.class,
                assertInstanceOf(SubmissionResult.Completed.class, await(ignored)).processingResult());
        assertEquals(new PublishedSnapshot(failedSnapshot, Optional.of(saveFailure), Optional.empty()), fixture.published.getLast());

        fixture.onSave = checkpoint -> {};
        var resumed = fixture.lane.resume();
        var observedAfterRetry = resumed.thenApply(ignoredResult -> fixture.published.getLast());
        fixture.executor.runAll();

        assertEquals(new PublishedSnapshot(failedSnapshot, Optional.empty(), Optional.empty()), await(observedAfterRetry));
        assertSame(failedSnapshot, fixture.lastSaved().snapshot());
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void publishesAfterRaisedEventsBeforeCompletingTheSubmission() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(), List.of(), List.of(new Message("raised")))
                : emptyOutput());

        var submitted = fixture.lane.submit(new Action("increment"));
        var observedAtCompletion = submitted.thenApply(ignored -> fixture.published.getLast());
        fixture.executor.runAll();

        assertEquals(new PublishedSnapshot(new Counter(2, false), Optional.empty(), Optional.empty()), await(observedAtCompletion));
        assertEquals(3, fixture.published.size());
    }

    @Test
    void initialSaveFailureIsPublishedAndRecoveryRebuildsPublicationFromSavedState() throws Exception {
        var fixture = new Fixture();
        var saveFailure = new IllegalStateException("Initial save failed");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var submitted = fixture.lane.submit(new Initialize(Optional.empty()));
        fixture.executor.runAll();

        var submittedPending = assertInstanceOf(SubmissionResult.Pending.class, await(submitted));
        assertEquals(CoordinationPhase.PERSISTENCE, submittedPending.phase());
        assertEquals(new PublishedSnapshot(new Counter(0, false), Optional.of(saveFailure), Optional.empty()), fixture.published.getLast());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();
        await(resumed);
        var checkpoint = fixture.lastSaved();

        // A later unsaved failure belongs only to the live lane, not to the checkpoint used at restart.
        fixture.onSave = ignored -> { throw saveFailure; };
        var failedSave = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();
        var failedSavePending = assertInstanceOf(SubmissionResult.Pending.class, await(failedSave));
        assertEquals(CoordinationPhase.PERSISTENCE, failedSavePending.phase());
        fixture.published.clear();
        fixture.onSave = ignored -> {};
        var restored = fixture.restoreLane(checkpoint, fixture.executor);
        assertTrue(fixture.published.isEmpty());

        var recovered = restored.resume();
        var observedAtCompletion = recovered.thenApply(ignored -> fixture.published.getLast());
        fixture.executor.runAll();

        assertEquals(new PublishedSnapshot(new Counter(0, false), Optional.empty(), Optional.empty()), await(observedAtCompletion));
    }

    @Test
    void timerProcessingPublishesSaveFailureAndRetryWithoutAnotherHandlerCall() throws Exception {
        var fixture = new Fixture();
        fixture.startTimer(new Timer("timeout", NOW.plusSeconds(30), new Message("timeout")));
        var saveFailure = new IllegalStateException("Timer result could not be saved");
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() == 2) {
                throw saveFailure;
            }
        };

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(new PublishedSnapshot(new Counter(2, false), Optional.of(saveFailure), Optional.empty()), fixture.published.getLast());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(2, fixture.attempted.size());

        fixture.onSave = checkpoint -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(resumed));
        assertEquals(new PublishedSnapshot(new Counter(2, false), Optional.empty(), Optional.empty()), fixture.published.getLast());
        assertEquals(2, fixture.attempted.size());
    }

    @Test
    void outgoingFailureDoesNotBecomeAPersistenceError() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var sendFailure = new IllegalStateException("Send failed");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send("message")), List.of(), List.of()));
        fixture.onEffect = effect -> { throw sendFailure; };

        var submitted = fixture.lane.submit(new Action("send"));
        fixture.executor.runAll();

        var submittedPending = assertInstanceOf(SubmissionResult.Pending.class, await(submitted));
        assertEquals(CoordinationPhase.OUTGOING_WORK, submittedPending.phase());
        assertSame(sendFailure, submittedPending.cause());
        assertEquals(new PublishedSnapshot(new Counter(1, false), Optional.empty(), Optional.empty()), fixture.published.getLast());
    }

    @Test
    void publisherFailureDoesNotChangeTheSubmissionResultOrStopTheLane() throws Exception {
        var fixture = new Fixture();
        fixture.onPublish = (snapshot, persistenceFailure, setupFailure) -> { throw new IllegalStateException("Observer failed"); };
        fixture.initialize();

        var submitted = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(submitted));
        assertFalse(fixture.lane.isClosed());

        var saveFailure = new IllegalStateException("Disk write failed");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var failedSave = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        var failedSavePending = assertInstanceOf(SubmissionResult.Pending.class, await(failedSave));
        assertEquals(CoordinationPhase.PERSISTENCE, failedSavePending.phase());
        assertSame(saveFailure, failedSavePending.cause());
        assertFalse(fixture.lane.isClosed());

        fixture.onSave = checkpoint -> {};
        var processingFailure = new IllegalStateException("Domain operation failed");
        fixture.handler = (snapshot, work) -> new StepResult.Failed<>(new Counter(2, true),
                emptyOutput(),
                processingFailure);
        var failedInput = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(failedInput));
        assertEquals(new PublishedSnapshot(new Counter(2, true), Optional.empty(), Optional.empty()), fixture.published.getLast());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void setupRunsAfterTheInitialSaveAndBeforeTheFirstInputWithoutSavingASetupFlag() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var setupAttempts = new AtomicInteger();
        var first = new Message("first");
        var waiting = lane.submit(new Message("waiting"));
        fixture.onSetup = snapshot -> {
            setupAttempts.incrementAndGet();
            assertEquals(new Counter(0, false), snapshot);
            assertEquals(snapshot, fixture.lastSaved().snapshot());
            assertEquals(List.of(new PendingWork<>(new Work("counter", first), false)), fixture.lastSaved().pendingWork());
            assertTrue(fixture.attempted.isEmpty());
            assertFalse(waiting.isDone());
        };
        fixture.handler = (snapshot, work) -> {
            assertEquals(1, setupAttempts.get());
            if (work.input().equals(first)) {
                assertEquals(1, fixture.saved.size());
            }
            return applied(snapshot, emptyOutput());
        };
        var initialized = lane.submit(new Initialize(Optional.of(first)));
        assertEquals(0, setupAttempts.get());
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(List.of(first, new Message("waiting")), fixture.attemptedInputs());
        assertEquals(List.of(new Counter(0, false), new Counter(1, false), new Counter(1, false), new Counter(2, false)),
                fixture.saved.stream().map(Checkpoint::snapshot).toList());
        assertEquals(List.of(new PendingWork<>(new Work("counter", new Message("waiting")), false)),
                fixture.saved.get(2).pendingWork());

        var later = lane.submit(new Action("later"));
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), awaitSnapshot(later));
        assertEquals(1, setupAttempts.get());
    }

    @Test
    void failedSetupRetainsPreparationAndTheFirstInputUntilExplicitRetry() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        var setupFailure = new IllegalStateException("Chat could not be saved");
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> {
            if (setupAttempts.incrementAndGet() == 1) {
                throw setupFailure;
            }
        };
        var first = new Message("first");
        var waiting = lane.submit(new Message("waiting"));
        var initialized = lane.submit(new Initialize(Optional.of(first)));
        fixture.executor.runAll();

        assertSame(setupFailure, failure(initialized));
        var initializedPending = assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        assertEquals(CoordinationPhase.SETUP, initializedPending.phase());
        assertFalse(waiting.isDone());
        assertFalse(lane.isClosed());
        assertEquals(1, fixture.saved.size());
        assertEquals(List.of(new PendingWork<>(new Work("counter", first), false)), fixture.lastSaved().pendingWork());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(1, preparations.get());
        assertEquals(1, setupAttempts.get());

        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(retry));
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(List.of(first, new Message("waiting")), fixture.attemptedInputs());
        assertEquals(4, fixture.saved.size());
        assertEquals(List.of(new PendingWork<>(new Work("counter", new Message("waiting")), false)),
                fixture.saved.get(2).pendingWork());
        assertEquals(1, preparations.get());
        assertEquals(2, setupAttempts.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void setupFailureSurvivesIgnoredInputsAndClearsWithoutADomainTransition(boolean restored) throws Exception {
        var fixture = new Fixture();
        var snapshot = new Counter(0, false);
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(snapshot, List.of(), List.of(), List.of());
        var lane = restored ? fixture.restoreLane(checkpoint, fixture.executor) : fixture.createLane(fixture.executor, true);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = current -> { throw setupFailure; };

        var started = restored ? lane.resume() : lane.submit(new Initialize(Optional.empty()));
        var observedAtCompletion = started.handle((ignored, failure) -> fixture.published.getLast());
        fixture.executor.runAll();

        assertSame(setupFailure, failure(started));
        var failedPublication = new PublishedSnapshot(snapshot, Optional.empty(), Optional.of(setupFailure));
        assertEquals(failedPublication, await(observedAtCompletion));
        assertFalse(lane.isClosed());

        fixture.routes = (current, input) -> List.of();
        var ignored = lane.submit(new Message("irrelevant"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Ignored.class, awaitProcessingResult(ignored));
        assertEquals(failedPublication, fixture.published.getLast());

        fixture.onSetup = current -> {};
        var resumed = lane.resume();
        var observedAfterRetry = resumed.thenApply(current -> fixture.published.getLast());
        fixture.executor.runAll();

        assertEquals(snapshot, await(resumed));
        assertEquals(new PublishedSnapshot(snapshot, Optional.empty(), Optional.empty()), await(observedAfterRetry));
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void persistenceAndSetupFailuresRemainIndependentUntilTheirOwnOperationSucceeds() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var snapshot = new Counter(0, false);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = current -> { throw setupFailure; };
        var started = lane.submit(new Initialize(Optional.empty()));
        fixture.executor.runAll();
        assertSame(setupFailure, failure(started));

        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var incoming = lane.submit(new Message("retained"));
        fixture.executor.runAll();

        assertSame(saveFailure, failure(incoming));
        assertEquals(new PublishedSnapshot(snapshot, Optional.of(saveFailure), Optional.of(setupFailure)),
                fixture.published.getLast());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        var saved = lane.resume();
        fixture.executor.runAll();

        assertSame(setupFailure, failure(saved));
        assertEquals(new PublishedSnapshot(snapshot, Optional.empty(), Optional.of(setupFailure)),
                fixture.published.getLast());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSetup = current -> {};
        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), await(resumed));
        assertEquals(new PublishedSnapshot(new Counter(1, false), Optional.empty(), Optional.empty()),
                fixture.published.getLast());
        assertEquals(List.of(new Message("retained")), fixture.attemptedInputs());
    }

    @Test
    void successfulSetupWithoutAFirstInputIsNotRepeatedAfterAProtocolCheckpointFailure() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        var saveFailure = new IllegalStateException("Protocol checkpoint could not be saved");
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> setupAttempts.incrementAndGet();
        var initialized = lane.submit(new Initialize(Optional.empty()));
        fixture.executor.runAll();

        assertEquals(new Counter(0, false), awaitSnapshot(initialized));
        assertEquals(1, fixture.saved.size());
        assertEquals(1, setupAttempts.get());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> { throw saveFailure; };
        var incremented = lane.submit(new Action("increment"));
        fixture.executor.runAll();

        assertSame(saveFailure, failure(incremented));
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(new Action("increment")), fixture.attemptedInputs());
        assertFalse(lane.isClosed());

        var blocked = lane.resume();
        fixture.executor.runAll();
        assertSame(saveFailure, failure(blocked));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, setupAttempts.get());

        fixture.onSave = checkpoint -> {};
        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(2, fixture.saved.size());
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, preparations.get());
        assertEquals(1, setupAttempts.get());
    }

    @Test
    void restoredRecoveryEffectsAreSavedAfterSetupBeforeOutgoingAndPendingWork() throws Exception {
        var fixture = new Fixture();
        var oldObservation = new Observation("obsolete");
        var observation = new Observation("needed");
        var send = send("saved-message");
        var timer = new Timer("deadline", NOW.plusSeconds(30), new Notification());
        var input = new Message("saved-input");
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, false),
                List.of(new PendingWork<>(new Work("counter", input), false)),
                List.of(send, oldObservation),
                List.of(timer));
        var order = new ArrayList<String>();
        fixture.onSetup = snapshot -> order.add("setup");
        fixture.recoveryEffects = snapshot -> {
            assertSame(checkpoint.snapshot(), snapshot);
            assertEquals(List.of("setup"), order);
            order.add("recover");
            return List.of(observation);
        };
        fixture.onSchedule = () -> {
            assertEquals(List.of(observation, send), fixture.lastSaved().pendingEffects());
            assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
            order.add("timer");
        };
        fixture.onEffect = effect -> {
            assertTrue(fixture.lastSaved().pendingEffects().contains(effect));
            assertFalse(fixture.lastSaved().pendingEffects().contains(oldObservation));
            order.add(effect.equals(observation) ? "observe" : "send");
        };
        fixture.handler = (snapshot, work) -> {
            assertEquals(List.of(observation, send), fixture.executed);
            order.add("input");
            return applied(snapshot, emptyOutput());
        };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        assertTrue(order.isEmpty());
        assertTrue(fixture.saved.isEmpty());

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(3, false), await(resumed));
        assertEquals(List.of("setup", "recover", "timer", "observe", "send", "input"), order);
        assertEquals(List.of(input), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());

        var retry = lane.resume();
        fixture.executor.runAll();
        await(retry);
        assertEquals(List.of("setup", "recover", "timer", "observe", "send", "input"), order);
    }

    @Test
    void newLanesAndLiveRetriesDoNotRequestStartupRecoveryEffects() throws Exception {
        var fixture = new Fixture();
        fixture.recoveryEffects = snapshot -> { throw new AssertionError("Only restored lanes recover effects"); };
        fixture.initialize();

        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(0, false), await(retry));
        assertTrue(fixture.executed.isEmpty());
    }

    @Test
    void recoveryRemovesAnObsoleteObservationWithoutReplayingIt() throws Exception {
        var fixture = new Fixture();
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, true),
                List.of(),
                List.of(new Observation("no-longer-needed")),
                List.of());
        fixture.recoveryEffects = snapshot -> {
            assertTrue(snapshot.failed());
            return List.of();
        };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(checkpoint.snapshot(), await(resumed));
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertTrue(fixture.executed.isEmpty());
    }

    @Test
    void failedRecoveryCheckpointRetriesPreparedEffectsWithoutCallingRecoveryAgain() throws Exception {
        var fixture = new Fixture();
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, false),
                List.of(new PendingWork<>(new Work("counter", new Message("saved-input")), false)),
                List.of(new Observation("old")),
                List.of());
        var recoveryCalls = new AtomicInteger();
        fixture.recoveryEffects = snapshot -> List.of(new Observation("recovered-" + recoveryCalls.incrementAndGet()));
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = saved -> { throw saveFailure; };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertSame(saveFailure, failure(resumed));
        assertEquals(1, recoveryCalls.get());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.executed.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
        assertFalse(lane.isClosed());

        fixture.onSave = saved -> {};
        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(3, false), await(retry));
        assertEquals(1, recoveryCalls.get());
        assertEquals(List.of(new Observation("recovered-1")), fixture.executed);
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void failedRecoveryHandoffRetainsItsNonReplayableEffectForLiveRetry() throws Exception {
        var fixture = new Fixture();
        var observation = new Observation("needed");
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, false),
                List.of(),
                List.of(),
                List.of());
        var recoveryCalls = new AtomicInteger();
        fixture.recoveryEffects = snapshot -> {
            recoveryCalls.incrementAndGet();
            return List.of(observation);
        };
        var handoffFailure = new IllegalStateException("Observer unavailable");
        fixture.onEffect = effect -> { throw handoffFailure; };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertSame(handoffFailure, failure(resumed));
        assertEquals(List.of(observation), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.executed.isEmpty());

        fixture.onEffect = effect -> {};
        var retry = lane.resume();
        fixture.executor.runAll();

        assertEquals(checkpoint.snapshot(), await(retry));
        assertEquals(1, recoveryCalls.get());
        assertEquals(List.of(observation), fixture.executed);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void recoveryCancellationIsSavedBeforeRestoringTimers() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("obsolete", NOW.plusSeconds(30), new Notification());
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, false),
                List.of(),
                List.of(timer),
                List.of(timer));
        fixture.recoveryEffects = snapshot -> List.of(new CancelTimer(timer.id()));
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(checkpoint.snapshot(), await(resumed));
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertTrue(fixture.timerTasks.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnexpectedRecoveryExceptionStopsTheLaneWithoutDispatchingOldEffects(boolean previouslyFailed) {
        var fixture = new Fixture();
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(2, previouslyFailed),
                List.of(),
                List.of(new Observation("old"), send("saved")),
                List.of());
        var recoveryFailure = new IllegalStateException("Cannot determine recovery work");
        fixture.recoveryEffects = snapshot -> { throw recoveryFailure; };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        var queued = lane.submit(new Message("later"));
        fixture.executor.runAll();

        assertSame(recoveryFailure, failure(resumed));
        assertSame(recoveryFailure, failure(queued));
        assertTrue(lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.executed.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void restoresPassivelyAndResumesSavedWorkBeforeNewInputsWithoutRepeatingTheHandler() throws Exception {
        var original = new Fixture();
        var originalLane = original.createLane(original.executor, true);
        var originalSetup = new AtomicInteger();
        var start = new Action("start");
        var raised = new Message("raised");
        var send = send("saved-message");
        var logEntry = new LogEntry.Shared(new LogEntry.Text("Started"));
        var saveFailure = new IllegalStateException("Send removal could not be saved");
        original.onSetup = snapshot -> originalSetup.incrementAndGet();
        original.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start)
                ? new ProcessingOutput<>(List.of(send), List.of(logEntry), List.of(raised))
                : emptyOutput());
        original.onSave = saved -> {
            if (saved.snapshot().count() > 0 && saved.pendingEffects().isEmpty()) {
                throw saveFailure;
            }
        };
        var started = originalLane.submit(new Initialize(Optional.of(start)));
        original.executor.runAll();
        assertSame(saveFailure, failure(started));
        var startedPending = assertInstanceOf(SubmissionResult.Pending.class, await(started));
        assertEquals(CoordinationPhase.PERSISTENCE, startedPending.phase());
        assertEquals(1, originalSetup.get());
        assertEquals(List.of(start), original.attemptedInputs());
        var checkpoint = original.lastSaved();
        await(originalLane.shutdown());

        var restored = new Fixture();
        var order = new ArrayList<String>();
        restored.onPrepare = input -> { throw new AssertionError("Restoration must not initialize the entity again"); };
        restored.onSetup = snapshot -> {
            assertEquals(new Counter(1, false), snapshot);
            order.add("setup");
        };
        restored.onEffect = effect -> {
            assertSame(send, effect);
            order.add("send");
        };
        restored.onSave = saved -> order.add("save:" + saved.snapshot().count());
        restored.onLog = entry -> { throw new AssertionError("Old best-effort logs must not be replayed"); };
        restored.handler = (snapshot, work) -> {
            order.add(((Message) work.input()).name());
            return applied(snapshot, emptyOutput());
        };
        var lane = restored.restoreLane(checkpoint, restored.executor);
        restored.executor.runAll();
        assertTrue(order.isEmpty());
        assertTrue(restored.saved.isEmpty());
        assertTrue(restored.attempted.isEmpty());

        var resumed = lane.resume();
        var incoming = lane.submit(new Message("new"));
        assertFalse(resumed.isDone());
        assertTrue(order.isEmpty());
        restored.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(resumed));
        assertEquals(new Counter(3, false), awaitSnapshot(incoming));
        assertEquals(List.of("setup", "send", "save:1", "raised", "save:2", "save:2", "new", "save:3"), order);
        assertEquals(List.of(raised, new Message("new")), restored.attemptedInputs());
        assertEquals(List.of(send), restored.executed);
        assertEquals(List.of(checkpoint.snapshot()), restored.effectSnapshots);
        assertTrue(restored.lastSaved().pendingWork().isEmpty());
        assertTrue(restored.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void restoredDeferredWorkDoesNotPreventResumeFromCompletingOrRewriteAnUnchangedCheckpoint() throws Exception {
        var original = new Fixture();
        original.initialize();
        original.handler = (snapshot, work) -> new StepResult.Deferred<>();
        var early = new Message("early");
        var deferred = original.lane.submit(early);
        original.executor.runAll();
        await(deferred);
        var checkpoint = original.lastSaved();
        await(original.lane.shutdown());

        var restored = new Fixture();
        var setups = new AtomicInteger();
        restored.onSetup = snapshot -> setups.incrementAndGet();
        restored.handler = (snapshot, work) -> new StepResult.Deferred<>();
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertEquals(checkpoint.snapshot(), awaitSnapshot(resumed));
        assertEquals(List.of(early), restored.attemptedInputs());
        assertTrue(restored.saved.isEmpty());

        var retried = lane.resume();
        restored.executor.runAll();
        assertEquals(checkpoint.snapshot(), awaitSnapshot(retried));
        assertEquals(List.of(early, early), restored.attemptedInputs());
        assertEquals(1, setups.get());
        assertTrue(restored.saved.isEmpty());
    }

    @Test
    void restoredFirstInputKeepsItsCommandClassificationAndCannotBeDeferred() throws Exception {
        var original = new Fixture();
        var originalLane = original.createLane(original.executor, true);
        var setupFailure = new IllegalStateException("Setup unavailable");
        original.onSetup = snapshot -> { throw setupFailure; };
        var command = new Action("unavailable");
        var initialized = originalLane.submit(new Initialize(Optional.of(command)));
        original.executor.runAll();
        assertSame(setupFailure, failure(initialized));
        var checkpoint = original.lastSaved();
        assertEquals(List.of(new PendingWork<>(new Work("counter", command), true)), checkpoint.pendingWork());
        await(originalLane.shutdown());

        var restored = new Fixture();
        restored.handler = (snapshot, work) -> new StepResult.Deferred<>();
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(resumed));
        assertEquals(List.of(command), restored.attemptedInputs());
        assertTrue(restored.lastSaved().pendingWork().isEmpty());
        assertEquals(new Counter(0, false), restored.lastSaved().snapshot());
        assertFalse(lane.isClosed());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRecoverySendFailureStillProcessesSavedInputsAndAllowsAnotherLaneToResume(boolean previouslyFailed) throws Exception {
        var first = new Fixture();
        var second = new Fixture();
        var workers = new ManualExecutor();
        var send = send("saved-message");
        var pending = new Message("pending");
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(4, previouslyFailed),
                List.of(new PendingWork<>(new Work("counter", pending), false)),
                List.of(send),
                List.of());
        var firstLane = first.restoreLane(checkpoint, workers);
        var secondLane = second.restoreLane(
                new Checkpoint<>(new Counter(7, false), List.of(), List.of(), List.of()), workers);
        var sendFailure = new IllegalStateException("Sender unavailable");
        var setups = new AtomicInteger();
        first.onSetup = snapshot -> setups.incrementAndGet();
        first.onEffect = effect -> { throw sendFailure; };

        var firstRecovery = firstLane.resume();
        var secondRecovery = secondLane.resume();
        var incoming = secondLane.submit(new Action("increment"));
        workers.runAll();

        assertSame(sendFailure, failure(firstRecovery));
        assertEquals(new Counter(7, false), awaitSnapshot(secondRecovery));
        assertEquals(new Counter(8, false), awaitSnapshot(incoming));
        assertEquals(new Counter(5, previouslyFailed), first.lastSaved().snapshot());
        assertEquals(List.of(send), first.lastSaved().pendingEffects());
        assertTrue(first.lastSaved().pendingWork().isEmpty());
        assertEquals(List.of(pending), first.attemptedInputs());
        assertFalse(firstLane.isClosed());

        first.onEffect = effect -> {};
        var retried = firstLane.resume();
        workers.runAll();
        assertEquals(new Counter(5, previouslyFailed), awaitSnapshot(retried));
        assertEquals(1, setups.get());
        assertEquals(List.of(send), first.executed);
        assertEquals(List.of(pending), first.attemptedInputs());
        assertTrue(first.lastSaved().pendingEffects().isEmpty());
        assertTrue(first.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void restoresTimersOnlyAfterSetupAndKeepsTheirAbsoluteDeadlines() throws Exception {
        var fixture = new Fixture();
        fixture.clock.setTime(NOW.plusSeconds(30).toEpochMilli());
        var timer = new Timer("timeout", NOW.plusSeconds(60), new Message("timeout"));
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(1, false),
                List.of(),
                List.of(),
                List.of(timer));

        var setupComplete = new AtomicInteger();
        fixture.onSetup = snapshot -> setupComplete.incrementAndGet();
        fixture.onSchedule = () -> assertEquals(1, setupComplete.get());
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        fixture.executor.runAll();
        assertTrue(fixture.timerTasks.isEmpty());
        assertEquals(0, setupComplete.get());

        var resumed = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertEquals(1, fixture.timerTasks.size());
        assertEquals(Duration.ofSeconds(30).toNanos(), fixture.timerTasks.getFirst().delayNanos());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.executed.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void anInitializationMessageCreatesOnceAndLaterCopiesReachTheProcessor() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        var message = new InitializationMessage();
        var waiting = fixture.lane.submit(new Message("waiting"));
        var initialized = fixture.lane.submit(message);
        var repeated = fixture.lane.submit(message);
        fixture.executor.runAll();

        assertEquals(1, preparations.get());
        assertEquals(List.of(message, new Message("waiting"), message), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(new Counter(3, false), awaitSnapshot(repeated));
    }

    @Test
    void anInitializationOnlyCommandCannotPrepareAnExistingEntityAgain() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.initialize();

        var repeated = fixture.lane.submit(new Initialize(Optional.empty()));
        var incremented = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        assertInstanceOf(IllegalArgumentException.class, failure(repeated));
        assertEquals(1, preparations.get());
        assertEquals(List.of(new Action("increment")), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), awaitSnapshot(incremented));
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void onlyDeclaredInputTypesCanWaitBeforeInitialization() {
        var fixture = new Fixture();
        var command = fixture.lane.submit(new Action("increment"));
        var notification = fixture.lane.submit(new Notification());
        var unregistered = fixture.lane.submit(new UnregisteredCommand());
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(command));
        assertInstanceOf(IllegalStateException.class, failure(notification));
        assertInstanceOf(IllegalArgumentException.class, failure(unregistered));
        assertFalse(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
    }

    @Test
    void waitingLaneEligibilityUsesTheDefinitionsRulesWithoutPreparingTheEntity() {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        var definition = fixture.createDefinition(false);

        assertTrue(definition.canCreateWaitingLane(new Initialize(Optional.empty())));
        assertTrue(definition.canCreateWaitingLane(new InitializationMessage()));
        assertTrue(definition.canCreateWaitingLane(new Message("early")));
        assertFalse(definition.canCreateWaitingLane(new Notification()));
        assertFalse(definition.canCreateWaitingLane(new Action("increment")));
        assertFalse(definition.canCreateWaitingLane(new UnregisteredCommand()));
        assertFalse(definition.canCreateWaitingLane(new Input() {}));
        assertEquals(0, preparations.get());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void withoutStashDeclarationsOnlyRegisteredInitializersCanCreateWaitingLanes() {
        var fixture = new Fixture();
        var definition = ProcessingLaneDefinition.<Counter, CounterInput, Work>builder(
                        CounterInput.class,
                        fixture::createProcessor)
                .initializeOn(Initialize.class, input -> fixture.prepare(input, input.firstInput()))
                .build();

        assertTrue(definition.canCreateWaitingLane(new Initialize(Optional.empty())));
        assertFalse(definition.canCreateWaitingLane(new Message("early")));
        assertFalse(definition.canCreateWaitingLane(new InitializationMessage()));
    }

    @Test
    void aBroadStashTypeCannotMakeCommandsWaitForInitialization() {
        var fixture = new Fixture();
        var definition = ProcessingLaneDefinition.<Counter, CounterInput, Work>builder(
                        CounterInput.class,
                        fixture::createProcessor)
                .stashUntilInitialized(CounterInput.class)
                .build();
        var lane = new ProcessingLane<>("counter",
                Optional.empty(),
                fixture.executor,
                fixture.timers,
                fixture.clock,
                definition,
                fixture::saveCheckpoint,
                fixture::executeEffect,
                entry -> fixture.onLog.accept(entry),
                fixture::publishSnapshot);
        var action = new Action("increment");

        assertTrue(definition.canCreateWaitingLane(new Message("early")));
        assertFalse(definition.canCreateWaitingLane(action));
        var rejected = lane.submit(action);
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(rejected));
        assertFalse(lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void stashDeclarationsDoNotRestrictInputsAfterInitialization() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();

        var notification = new Notification();
        var result = fixture.lane.submit(notification);
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(result));
        assertEquals(List.of(notification), fixture.attemptedInputs());
    }

    @Test
    void rejectedInitializationClosesTheLaneAndFailsWaitingQueuedAndReentrantSubmissions() {
        var fixture = new Fixture();
        var rejected = new IllegalArgumentException("Invalid initializer");
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> {
            preparations.incrementAndGet();
            throw rejected;
        };
        var callback = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        var waiting = fixture.lane.submit(new Message("waiting"));
        fixture.executor.runAll();
        waiting.whenComplete((snapshot, failure) -> callback.set(fixture.lane.submit(new Message("callback"))));

        var initialized = fixture.lane.submit(new Initialize(Optional.empty()));
        var queued = fixture.lane.submit(new Message("queued"));
        var anotherInitialization = fixture.lane.submit(new Initialize(Optional.empty()));
        fixture.executor.runAll();

        for (var future : List.of(waiting, initialized, queued, anotherInitialization)) {
            assertSame(rejected, failure(future));
        }
        assertInstanceOf(RejectedExecutionException.class, failure(callback.get()));
        assertEquals(1, preparations.get());
        assertTrue(fixture.lane.isClosed());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());
    }

    @Test
    void aFailedInitialSaveRetainsPreparationAndWaitingMessagesUntilALaterInputRetriesIt() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var saves = new IllegalStateException("Disk unavailable");
        var preparations = new AtomicInteger();
        var setupAttempts = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        fixture.onSetup = snapshot -> setupAttempts.incrementAndGet();
        fixture.onSave = checkpoint -> { throw saves; };
        var waiting = lane.submit(new Message("waiting"));
        var initialized = lane.submit(new Initialize(Optional.of(new Message("first"))));
        fixture.executor.runAll();

        assertSame(saves, failure(initialized));
        assertFalse(waiting.isDone());
        assertFalse(lane.isClosed());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(0, setupAttempts.get());

        fixture.onSave = checkpoint -> {};
        var later = lane.submit(new Action("later"));
        fixture.executor.runAll();

        assertEquals(1, preparations.get());
        assertEquals(1, setupAttempts.get());
        assertEquals(List.of(new Message("first"), new Message("waiting"), new Action("later")), fixture.attemptedInputs());
        assertEquals(new Counter(2, false), awaitSnapshot(waiting));
        assertEquals(new Counter(3, false), awaitSnapshot(later));
    }

    @Test
    void raisedWorkRunsBeforeReconsideringTheStashAndUnchangedInputsDoNotCauseRetryLoops() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var early = new Message("early");
        var change = new Message("change");
        var raised = new Message("raised");
        var ignored = new Message("ignored");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(early) && snapshot.count() < 2) {
                return new StepResult.Deferred<>();
            }
            if (work.input().equals(ignored)) {
                return new StepResult.Ignored<>();
            }
            if (work.input().equals(raised)) {
                assertEquals(1, fixture.lastSaved().snapshot().count());
                assertEquals(List.of(new PendingWork<>(work, false), new PendingWork<>(new Work("counter", early), false)),
                        fixture.lastSaved().pendingWork());
            }
            return applied(snapshot, new ProcessingOutput<>(List.of(), List.of(),
                    work.input().equals(change) ? List.of(raised) : List.of()));
        };

        var deferred = fixture.lane.submit(early);
        fixture.executor.runAll();
        assertEquals(new Counter(0, false), awaitSnapshot(deferred));
        assertInstanceOf(ProcessingResult.Deferred.class, awaitProcessingResult(deferred));
        assertEquals(List.of(new PendingWork<>(new Work("counter", early), false)), fixture.lastSaved().pendingWork());
        var ignoredSubmission = fixture.lane.submit(ignored);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Ignored.class, awaitProcessingResult(ignoredSubmission));
        assertEquals(List.of(early, ignored), fixture.attemptedInputs());

        var result = fixture.lane.submit(change);
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), awaitSnapshot(result));
        assertEquals(List.of(early, ignored, change, raised, early), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void explicitRetryReconsidersDeferredWorkOnceWithoutChangingItsOrder() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.handler = (snapshot, work) -> new StepResult.Deferred<>();
        fixture.lane.submit(new Message("first"));
        fixture.lane.submit(new Message("second"));
        fixture.executor.runAll();
        assertEquals(2, fixture.attempted.size());
        int saves = fixture.saved.size();

        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(0, false), awaitSnapshot(retry));
        assertEquals(List.of(new Message("first"), new Message("second"), new Message("first"), new Message("second")),
                fixture.attemptedInputs());
        assertEquals(saves, fixture.saved.size());
    }

    @Test
    void unavailableCommandsAreRejectedWithoutBeingStashedOrClosingTheLane() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var rejection = new IllegalStateException("Unavailable action");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Action("reject"))) {
                return new StepResult.Rejected<>(rejection);
            }
            if (work.input().equals(new Action("defer"))) {
                return new StepResult.Deferred<>();
            }
            return applied(snapshot, emptyOutput());
        };
        var rejected = fixture.lane.submit(new Action("reject"));
        var incorrectlyDeferred = fixture.lane.submit(new Action("defer"));
        fixture.executor.runAll();

        assertSame(rejection, failure(rejected));
        assertInstanceOf(ProcessingResult.Rejected.class, awaitProcessingResult(rejected));
        assertInstanceOf(IllegalStateException.class, failure(incorrectlyDeferred));
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(1, fixture.saved.size());
        assertFalse(fixture.lane.isClosed());

        fixture.lane.submit(new Action("increment"));
        var repeatedType = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();
        assertEquals(new Counter(2, false), awaitSnapshot(repeatedType));
    }

    @Test
    void commandsWithoutADestinationRejectWhileUninterestingMessagesAreIgnored() {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.routes = (snapshot, input) -> List.of();
        var command = fixture.lane.submit(new Action("unknown"));
        var message = fixture.lane.submit(new Message("unknown"));
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(command));
        assertFalse(message.isCompletedExceptionally());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(1, fixture.saved.size());
    }

    @Test
    void invalidInputsAreRejectedBeforeRoutingOrSavingWithoutClosingTheLane() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var invalid = new Message("wrong-peer");
        var rejection = new IllegalArgumentException("Wrong peer");
        fixture.onValidate = (snapshot, input) -> {
            if (input.equals(invalid)) {
                throw rejection;
            }
        };
        fixture.routes = (snapshot, input) -> {
            assertFalse(input.equals(invalid));
            return List.of(new Work("counter", input));
        };

        var rejected = fixture.lane.submit(invalid);
        fixture.executor.runAll();

        assertSame(rejection, failure(rejected));
        assertInstanceOf(ProcessingResult.Rejected.class, awaitProcessingResult(rejected));
        assertEquals(1, fixture.saved.size());
        assertTrue(fixture.attempted.isEmpty());
        assertFalse(fixture.lane.isClosed());

        var valid = fixture.lane.submit(new Message("expected-peer"));
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(valid));
    }

    @Test
    void aMessageIsSavedBeforeItsFirstStepAndSaveFailureRetainsItForRetry() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var message = new Message("incoming");
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };

        var submission = fixture.lane.submit(message);
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
        assertSame(saveFailure, pending.cause());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        fixture.handler = (snapshot, work) -> {
            assertEquals(List.of(new PendingWork<>(work, false)), fixture.lastSaved().pendingWork());
            return applied(snapshot, emptyOutput());
        };
        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(List.of(message), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void messagesArrivingDuringAnObservationFailureAreSavedAndRecoveredInOrder() throws Exception {
        var original = new Fixture();
        original.initialize();
        var observation = new Observation("deposit");
        var observationFailure = new IllegalStateException("Observation unavailable");
        original.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(observation), List.of(), List.of()));
        original.onEffect = effect -> { throw observationFailure; };
        var started = original.lane.submit(new Action("start"));
        original.executor.runAll();
        assertSame(observationFailure, failure(started));

        // Neither input has succeeded yet. Sharing a type must not discard the second pending message.
        var first = new Message("first");
        var second = new Message("second");
        var firstResult = original.lane.submit(first);
        var secondResult = original.lane.submit(second);
        original.executor.runAll();

        for (var result : List.of(firstResult, secondResult)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(result));
            assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
            assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
            assertSame(observationFailure, pending.cause());
        }
        assertEquals(List.of(new Action("start")), original.attemptedInputs());
        var checkpoint = original.lastSaved();
        assertEquals(List.of(new PendingWork<>(new Work("counter", first), false),
                new PendingWork<>(new Work("counter", second), false)), checkpoint.pendingWork());
        assertEquals(List.of(observation), checkpoint.pendingEffects());

        var restored = new Fixture();
        restored.recoveryEffects = snapshot -> List.of(observation);
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertEquals(new Counter(3, false), awaitSnapshot(resumed));
        assertEquals(List.of(first, second), restored.attemptedInputs());
        assertEquals(List.of(observation), restored.executed);
        assertTrue(restored.lastSaved().pendingWork().isEmpty());
        assertTrue(restored.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void admissionUsesSavedHistoryAndProcessingRechecksAfterAnUnsavedTransition() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var routedCounts = new ArrayList<Integer>();
        var backendCalls = new AtomicInteger();
        fixture.routes = (snapshot, input) -> {
            routedCounts.add(snapshot.count());
            return snapshot.count() == 0 ? List.of(new Work("counter", input)) : List.of();
        };
        fixture.handler = (snapshot, work) -> {
            // The count represents successful processing history for this simple processor.
            if (snapshot.count() > 0) {
                return new StepResult.Ignored<>();
            }
            backendCalls.incrementAndGet();
            return applied(snapshot, emptyOutput());
        };
        var saveFailure = new IllegalStateException("Cannot save the processing result");
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() > 0) {
                throw saveFailure;
            }
        };
        var first = fixture.lane.submit(new Message("first"));
        fixture.executor.runAll();
        assertSame(saveFailure, failure(first));
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());

        var duplicate = fixture.lane.submit(new Message("same-type"));
        fixture.executor.runAll();
        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(duplicate));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
        assertEquals(List.of(0, 0), routedCounts);
        assertEquals(1, backendCalls.get());

        fixture.onSave = checkpoint -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertEquals(1, backendCalls.get());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());

        int savedCount = fixture.saved.size();
        var alreadyProcessed = fixture.lane.submit(new Message("another-copy"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Ignored.class, awaitProcessingResult(alreadyProcessed));
        assertEquals(savedCount, fixture.saved.size());
        assertEquals(1, backendCalls.get());
    }

    @Test
    void routingRetainsOnlyTheFlowsThatStillNeedTheInput() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.routes = (snapshot, input) -> input instanceof Message
                ? List.of(new Work("interested-and-reachable", input))
                : List.of();
        fixture.onEffect = effect -> { throw new AssertionError("No effects expected"); };
        fixture.handler = (snapshot, work) -> {
            assertEquals("interested-and-reachable", work.destination());
            assertEquals(List.of(new PendingWork<>(work, false)), fixture.lastSaved().pendingWork());
            return new StepResult.Deferred<>();
        };

        var uninteresting = fixture.lane.submit(new Notification());
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Ignored.class, awaitProcessingResult(uninteresting));
        assertEquals(1, fixture.saved.size());

        var needed = fixture.lane.submit(new Message("broadcast"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Deferred.class, awaitProcessingResult(needed));
        assertEquals(List.of(new PendingWork<>(new Work("interested-and-reachable", new Message("broadcast")), false)),
                fixture.lastSaved().pendingWork());
    }

    @Test
    void newMessagesDoNotOvertakePreInitializationMessagesWhileSetupIsBlocked() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = snapshot -> { throw setupFailure; };
        var first = new Message("first");
        var early = new Message("early");
        var second = new Message("second");
        var late = new Message("late");
        var earlyResult = lane.submit(early);
        var secondResult = lane.submit(second);
        lane.submit(new Initialize(Optional.of(first)));
        fixture.executor.runAll();
        assertFalse(earlyResult.isDone());
        assertFalse(secondResult.isDone());

        var lateResult = lane.submit(late);
        fixture.executor.runAll();
        for (var result : List.of(earlyResult, secondResult, lateResult)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(result));
            assertEquals(CoordinationPhase.SETUP, pending.phase());
            assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
        }
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(List.of(first, early, second, late), fixture.lastSaved().pendingWork().stream()
                .map(work -> work.value().input()).toList());

        fixture.onSetup = snapshot -> {};
        var resumed = lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(4, false), awaitSnapshot(resumed));
        assertEquals(List.of(first, early, second, late), fixture.attemptedInputs());
    }

    @Test
    void aFailedTransitionSaveBlocksEffectsAndReactionsAndRetryDoesNotRepeatTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var send = send("original-message");
        var start = new Action("start");
        var raised = new Message("raised");
        var entry = new LogEntry.Shared(new LogEntry.Text("Started"));
        var writtenLogs = new ArrayList<LogEntry>();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start)
                ? new ProcessingOutput<>(List.of(send), List.of(entry), List.of(raised))
                : emptyOutput());
        fixture.onLog = log -> {
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            assertEquals(List.of(send), fixture.executed);
            writtenLogs.add(log);
        };
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };

        var submission = fixture.lane.submit(start);
        fixture.executor.runAll();
        assertSame(saveFailure, failure(submission));
        var submissionPending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, submissionPending.phase());
        assertEquals(List.of(start), fixture.attemptedInputs());
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.executed.isEmpty());
        assertTrue(writtenLogs.isEmpty());

        var blocked = fixture.lane.submit(new Action("later"));
        fixture.executor.runAll();
        assertSame(saveFailure, failure(blocked));
        assertInstanceOf(SubmissionResult.NotProcessed.class, await(blocked));
        assertEquals(List.of(start), fixture.attemptedInputs());
        assertTrue(writtenLogs.isEmpty());

        fixture.onSave = checkpoint -> {};
        fixture.onEffect = effect -> {
            assertSame(send, effect);
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            assertEquals(List.of(new PendingWork<>(new Work("counter", raised), false)), fixture.lastSaved().pendingWork());
            assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
            assertEquals(List.of(start), fixture.attemptedInputs());
            assertTrue(writtenLogs.isEmpty());
        };
        var retry = fixture.lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(2, false), awaitSnapshot(retry));
        assertEquals(List.of(start, raised), fixture.attemptedInputs());
        assertEquals(List.of(send), fixture.executed);
        assertEquals(List.of(new Counter(1, false)), fixture.effectSnapshots);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(List.of(entry), writtenLogs);
    }

    @Test
    void aFailedEffectHandoffRetainsTheSavedEffectAndRetriesWithoutRepeatingTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var send = send("message");
        var entry = new LogEntry.Shared(new LogEntry.Text("Incremented"));
        var writtenLogs = new ArrayList<LogEntry>();
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send), List.of(entry), List.of()));
        fixture.onLog = log -> {
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            writtenLogs.add(log);
        };
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.onEffect = effect -> { throw sendFailure; };
        var submission = fixture.lane.submit(new Action("send"));
        fixture.executor.runAll();

        assertSame(sendFailure, failure(submission));
        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
        assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertFalse(fixture.lane.isClosed());
        assertEquals(List.of(entry), writtenLogs);

        fixture.onEffect = effect -> {};
        var retry = fixture.lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(1, fixture.attempted.size());
        assertEquals(List.of(send), fixture.executed);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(List.of(entry), writtenLogs);
    }

    @Test
    void failedInitialMessageHandoffStillProcessesTheFirstReactionAndWaitingMessages() throws Exception {
        var fixture = new Fixture();
        var first = new Message("first");
        var raised = new Message("raised");
        var waiting = new Message("waiting");
        var send = send("initial-message");
        var sendFailure = new IllegalStateException("Send unavailable");
        var handoffs = new AtomicInteger();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(first)
                ? new ProcessingOutput<>(List.of(send), List.of(), List.of(raised))
                : emptyOutput());
        fixture.onEffect = effect -> {
            handoffs.incrementAndGet();
            throw sendFailure;
        };

        var early = fixture.lane.submit(waiting);
        var initialized = fixture.lane.submit(new Initialize(Optional.of(first)));
        fixture.executor.runAll();

        for (var submission : List.of(initialized, early)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
            assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
            assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
            assertSame(sendFailure, pending.cause());
        }
        assertEquals(List.of(first, raised, waiting), fixture.attemptedInputs());
        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(1, handoffs.get());
    }

    @Test
    void sendFailuresAllowCommandsMessagesAndRaisedEventsWhileKeepingOutgoingFifo() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = send("first");
        var second = new Effect.SendMessage<>(new Payload("second"), new Payload("mediator"));
        var third = send("third");
        var start = new Action("start");
        var archive = new Action("archive");
        var raised = new Message("raised");
        var incoming = new Message("incoming");
        var sendFailure = new IllegalStateException("Sender unavailable");
        var handoffs = new ArrayList<Effect>();
        fixture.handler = (snapshot, work) -> {
            assertEquals(snapshot, fixture.lastSaved().snapshot());
            if (work.input().equals(start)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(first), List.of(), List.of(raised)));
            }
            if (work.input().equals(raised)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(second), List.of(), List.of()));
            }
            if (work.input().equals(incoming)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(third), List.of(), List.of()));
            }
            return applied(snapshot, emptyOutput());
        };
        fixture.onEffect = effect -> {
            assertEquals(first, effect);
            handoffs.add(effect);
            throw sendFailure;
        };

        var started = fixture.lane.submit(start);
        var archived = fixture.lane.submit(archive);
        var received = fixture.lane.submit(incoming);
        fixture.executor.runAll();

        int expectedCount = 2;
        for (var submission : List.of(started, archived, received)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
            assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
            assertEquals(new Counter(expectedCount++, false), applied.snapshot());
            assertSame(sendFailure, pending.cause());
        }
        assertEquals(List.of(start, raised, archive, incoming), fixture.attemptedInputs());
        assertEquals(List.of(first, first, first), handoffs);
        assertTrue(fixture.executed.isEmpty());
        assertEquals(new Counter(4, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(first, second, third), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());

        fixture.onEffect = effect -> handoffs.add(effect);
        var next = fixture.lane.submit(new Message("retry-on-arrival"));
        fixture.executor.runAll();

        assertEquals(new Counter(5, false), awaitSnapshot(next));
        assertEquals(List.of(first, second, third), fixture.executed);
        assertEquals(List.of(first, first, first, first, second, third), handoffs);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(5, fixture.attempted.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requiredTimerOrObservationFailurePausesInputsEvenWhenAMessageAlsoFails(boolean timerFailure) throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var send = send("message");
        var timer = new Timer("deadline", NOW.plusSeconds(30), new Notification());
        Effect required = timerFailure ? timer : new Observation("deposit");
        var start = new Action("start");
        var raised = new Message("raised");
        var incoming = new Message("incoming");
        var sendFailure = new IllegalStateException("Send unavailable");
        var setupFailure = new IllegalStateException("Required effect unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start)
                ? new ProcessingOutput<>(List.of(send, required), List.of(), List.of(raised))
                : emptyOutput());
        fixture.onEffect = effect -> {
            if (effect.equals(send)) {
                throw sendFailure;
            }
            throw setupFailure;
        };
        fixture.onSchedule = () -> { throw setupFailure; };

        var started = fixture.lane.submit(start);
        var command = fixture.lane.submit(new Action("blocked"));
        var message = fixture.lane.submit(incoming);
        fixture.executor.runAll();

        assertSame(setupFailure, failure(started));
        assertSame(setupFailure, assertInstanceOf(SubmissionResult.NotProcessed.class, await(command)).cause());
        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(message));
        assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
        assertSame(setupFailure, pending.cause());
        assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
        assertEquals(List.of(start), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send, required), fixture.lastSaved().pendingEffects());
        assertEquals(List.of(raised, incoming), fixture.lastSaved().pendingWork().stream()
                .map(work -> work.value().input()).toList());

        fixture.onEffect = effect -> {
            if (effect.equals(send)) {
                throw sendFailure;
            }
        };
        fixture.onSchedule = () -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();

        assertSame(sendFailure, failure(resumed));
        assertEquals(List.of(start, raised, incoming), fixture.attemptedInputs());
        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(timerFailure ? List.of(timer) : List.of(), fixture.lastSaved().scheduledInputs());
    }

    @Test
    void aFailedRemovalSaveDoesNotResendTheHandedOffEffectInTheRunningProcess() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = send("first");
        var second = send("second");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(first, second), List.of(), List.of()));
        var removalFailure = new IllegalStateException("Removal save failed");
        fixture.onSave = checkpoint -> {
            if (!fixture.executed.isEmpty()) {
                throw removalFailure;
            }
        };
        var submission = fixture.lane.submit(new Action("send"));
        fixture.executor.runAll();

        assertSame(removalFailure, failure(submission));
        assertEquals(List.of(first), fixture.executed);
        assertEquals(List.of(first, second), fixture.lastSaved().pendingEffects());

        var blocked = fixture.lane.submit(new Action("later"));
        fixture.executor.runAll();
        assertSame(removalFailure, assertInstanceOf(SubmissionResult.NotProcessed.class, await(blocked)).cause());
        assertEquals(1, fixture.attempted.size());
        assertEquals(List.of(first), fixture.executed);

        fixture.onSave = checkpoint -> {};
        var retry = fixture.lane.resume();
        fixture.executor.runAll();
        await(retry);
        assertEquals(List.of(first, second), fixture.executed);
        assertEquals(1, fixture.attempted.size());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void aMultiDestinationInputRetainsOnlyTheRemainingDestination() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var broadcast = new Message("broadcast");
        fixture.routes = (snapshot, input) -> input.equals(broadcast)
                ? List.of(new Work("left", input), new Work("right", input))
                : List.of(new Work("counter", input));
        fixture.handler = (snapshot, work) -> work.destination().equals("right") && snapshot.count() < 2
                ? new StepResult.Deferred<>()
                : applied(snapshot, emptyOutput());
        var submission = fixture.lane.submit(broadcast);
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(submission));
        assertInstanceOf(ProcessingResult.Deferred.class, awaitProcessingResult(submission));
        assertEquals(List.of(new PendingWork<>(new Work("right", broadcast), false)), fixture.lastSaved().pendingWork());

        var progress = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), awaitSnapshot(progress));
        assertEquals(1, fixture.attempted.stream().filter(work -> work.destination().equals("left")).count());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void aReportedProcessingFailureSavesItsSnapshotAndFinishesReactionsBeforeReturningFailed() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var processingFailure = new IllegalStateException("Operation failed");
        var reaction = new Message("reaction");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Action("fail"))) {
                return new StepResult.Failed<>(new Counter(1, true),
                        new ProcessingOutput<>(List.of(), List.of(), List.of(reaction)),
                        processingFailure);
            }
            assertTrue(fixture.lastSaved().snapshot().failed());
            return applied(snapshot, emptyOutput());
        };
        var submission = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(submission));
        assertInstanceOf(ProcessingResult.Failed.class, awaitProcessingResult(submission));
        assertEquals(new Counter(2, true), fixture.lastSaved().snapshot());
        assertEquals(List.of(new Action("fail"), reaction), fixture.attemptedInputs());
        assertFalse(fixture.lane.isClosed());

        var next = fixture.lane.submit(new Action("allowed-in-another-flow"));
        fixture.executor.runAll();
        assertEquals(new Counter(3, true), awaitSnapshot(next));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedFailureCheckpointReportsBothProcessingAndPersistenceFailures(boolean sameCause) throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var processingFailure = new IllegalStateException("Operation failed");
        var saveFailure = sameCause ? processingFailure : new IllegalStateException("Disk unavailable");
        fixture.handler = (snapshot, work) -> new StepResult.Failed<>(new Counter(1, true),
                emptyOutput(),
                processingFailure);
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var submission = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        var failed = assertInstanceOf(ProcessingResult.Failed.class, pending.processingResult());
        assertSame(processingFailure, failed.cause());
        assertSame(saveFailure, pending.cause());
        assertEquals(new Counter(1, true), failed.snapshot());
        assertEquals(0, processingFailure.getSuppressed().length);
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());

        fixture.onSave = checkpoint -> {};
        var retry = fixture.lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, true), awaitSnapshot(retry));
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void aBufferedSaveFailureIsSeparateFromTheInitializersSavedProcessingFailure() throws Exception {
        var fixture = new Fixture();
        var processingFailure = new IllegalStateException("First input failed");
        var saveFailure = new IllegalStateException("Buffered input could not be saved");
        fixture.handler = (snapshot, work) -> work.input().equals(new Message("first"))
                ? new StepResult.Failed<>(new Counter(1, true), emptyOutput(), processingFailure)
                : applied(snapshot, emptyOutput());
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() == 2) {
                throw saveFailure;
            }
        };
        var waiting = fixture.lane.submit(new Message("waiting"));
        var initialized = fixture.lane.submit(new Initialize(Optional.of(new Message("first"))));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(initialized));
        assertSame(saveFailure, failure(waiting));
        var failed = assertInstanceOf(ProcessingResult.Failed.class, awaitProcessingResult(initialized));
        assertEquals(new Counter(1, true), failed.snapshot());
        var waitingPending = assertInstanceOf(SubmissionResult.Pending.class, await(waiting));
        assertEquals(CoordinationPhase.PERSISTENCE, waitingPending.phase());
        assertEquals(0, processingFailure.getSuppressed().length);
        assertEquals(new Counter(1, true), fixture.lastSaved().snapshot());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void aFailedTimerRemovalCheckpointTakesPrecedenceOverTheEarlierSendFailure() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("deadline", NOW.plusSeconds(30), new Notification());
        var send = send("message");
        var sendFailure = new IllegalStateException("Send unavailable");
        var saveFailure = new IllegalStateException("Cannot save timer handoff");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send, timer), List.of(), List.of()));
        fixture.onEffect = effect -> { throw sendFailure; };
        fixture.onSave = checkpoint -> {
            if (!fixture.timerTasks.isEmpty()) {
                throw saveFailure;
            }
        };

        var submission = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
        assertSame(saveFailure, pending.cause());
        assertEquals(List.of(sendFailure), List.of(saveFailure.getSuppressed()));
        assertEquals(List.of(send, timer), fixture.lastSaved().pendingEffects());
        assertEquals(1, fixture.timerTasks.size());

        fixture.onSave = checkpoint -> {};
        fixture.onEffect = effect -> {};
        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), await(retry));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, fixture.timerTasks.size());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void anOlderRaisedRejectionDoesNotRejectTheNextSubmission() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = new Message("first");
        var raised = new Message("raised");
        var next = new Action("next");
        var rejection = new IllegalStateException("Older reaction rejected");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(first)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(), List.of(), List.of(raised)));
            }
            if (work.input().equals(raised)) {
                return new StepResult.Rejected<>(rejection);
            }
            return applied(snapshot, emptyOutput());
        };
        fixture.onSave = checkpoint -> { throw new IllegalStateException("Disk unavailable"); };
        var firstResult = fixture.lane.submit(first);
        fixture.executor.runAll();
        var firstResultPending = assertInstanceOf(SubmissionResult.Pending.class, await(firstResult));
        assertEquals(CoordinationPhase.PERSISTENCE, firstResultPending.phase());

        fixture.onSave = checkpoint -> {};
        var nextResult = fixture.lane.submit(next);
        fixture.executor.runAll();

        var applied = assertInstanceOf(ProcessingResult.Applied.class, awaitProcessingResult(nextResult));
        assertEquals(new Counter(2, false), applied.snapshot());
        assertEquals(List.of(first, raised, next), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void aDeferredInputsLaterFailureDoesNotFailTheInputThatMadeItReady() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var early = new Message("early");
        var olderFailure = new IllegalStateException("Deferred flow failed");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(early)) {
                return snapshot.count() == 0
                        ? new StepResult.Deferred<>()
                        : new StepResult.Failed<>(new Counter(snapshot.count(), true), emptyOutput(), olderFailure);
            }
            return applied(snapshot, emptyOutput());
        };
        var earlyResult = fixture.lane.submit(early);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingResult.Deferred.class, awaitProcessingResult(earlyResult));

        var nextResult = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        var applied = assertInstanceOf(ProcessingResult.Applied.class, awaitProcessingResult(nextResult));
        assertEquals(new Counter(1, true), applied.snapshot());
        assertInstanceOf(ProcessingResult.Deferred.class, awaitProcessingResult(earlyResult));
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void anImmediateRaisedRejectionBelongsToItsOriginatingSubmission() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var raised = new Message("raised");
        var rejection = new IllegalStateException("Reaction rejected");
        fixture.handler = (snapshot, work) -> work.input().equals(raised)
                ? new StepResult.Rejected<>(rejection)
                : applied(snapshot, new ProcessingOutput<>(List.of(), List.of(), List.of(raised)));

        var submitted = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        var failed = assertInstanceOf(ProcessingResult.Failed.class, awaitProcessingResult(submitted));
        assertSame(rejection, failed.cause());
        assertEquals(new Counter(1, false), failed.snapshot());
        assertEquals(failed.snapshot(), fixture.lastSaved().snapshot());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void anAppliedDestinationPreventsReportingTheWholeInputAsUnapplied() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.routes = (snapshot, input) -> List.of(new Work("left", input), new Work("right", input));
        var rejection = new IllegalStateException("Right flow rejected");
        fixture.handler = (snapshot, work) -> work.destination().equals("right")
                ? new StepResult.Rejected<>(rejection)
                : applied(snapshot, emptyOutput());

        var submitted = fixture.lane.submit(new Message("broadcast"));
        fixture.executor.runAll();

        var failed = assertInstanceOf(ProcessingResult.Failed.class, awaitProcessingResult(submitted));
        assertSame(rejection, failed.cause());
        assertEquals(new Counter(1, false), failed.snapshot());
        assertEquals(2, fixture.attempted.size());
    }

    @Test
    void anUnexpectedProcessorExceptionStopsTheLaneAndFailsQueuedSubmissions() {
        var fixture = new Fixture();
        fixture.initialize();
        var unexpected = new IllegalStateException("No valid failure snapshot");
        fixture.handler = (snapshot, work) -> { throw unexpected; };
        var first = fixture.lane.submit(new Action("fail"));
        var later = fixture.lane.submit(new Message("later"));
        fixture.executor.runAll();

        assertSame(unexpected, failure(first));
        assertSame(unexpected, failure(later));
        assertTrue(fixture.lane.isClosed());
        assertEquals(1, fixture.attempted.size());
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
    }

    @Test
    void anUnrecordedFailureStopsOnlyItsLaneAndLeavesSnapshotsAvailable() throws Exception {
        try (var executor = ExecutorFactory.newSingleThreadExecutor("lane-failure-test")) {
            var broken = new Fixture();
            var other = new Fixture();
            var lane = broken.createLane(executor);
            var otherLane = other.createLane(executor);
            try {
                await(lane.submit(new Initialize(Optional.empty())));
                await(otherLane.submit(new Initialize(Optional.empty())));
                var savedSnapshot = awaitSnapshot(lane.submit(new Action("advance")));
                var unexpected = new IllegalStateException("No valid failure snapshot");
                broken.handler = (snapshot, work) -> { throw unexpected; };
                var input = new Message("fail");

                assertSame(unexpected, failure(lane.submit(input)));

                assertTrue(lane.isClosed());
                var checkpoint = broken.lastSaved();
                assertEquals(new Counter(1, false), savedSnapshot);
                assertEquals(savedSnapshot, checkpoint.snapshot());
                assertEquals(List.of(new PendingWork<>(new Work("counter", input), false)), checkpoint.pendingWork());
                assertEquals(savedSnapshot, broken.published.getLast().snapshot());
                assertInstanceOf(RejectedExecutionException.class, failure(lane.submit(new Action("late"))));
                assertInstanceOf(RejectedExecutionException.class, failure(lane.resume()));

                var otherSnapshot = awaitSnapshot(otherLane.submit(new Action("advance")));

                assertEquals(new Counter(1, false), otherSnapshot);
                assertEquals(otherSnapshot, other.lastSaved().snapshot());
                assertEquals(otherSnapshot, other.published.getLast().snapshot());
                assertFalse(otherLane.isClosed());
                assertSame(checkpoint, broken.lastSaved());
                assertEquals(List.of(new Action("advance"), input), broken.attemptedInputs());
                assertEquals(List.of(new Action("advance")), other.attemptedInputs());
            } finally {
                await(lane.shutdown());
                await(otherLane.shutdown());
            }
        }
    }

    @Test
    void logsAreBestEffortAndAreNotRetried() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var entry = new LogEntry.Shared(new LogEntry.Text("Incremented"));
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(), List.of(entry), List.of()));
        var attempts = new AtomicInteger();
        fixture.onLog = log -> {
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            attempts.incrementAndGet();
            throw new IllegalStateException("Chat unavailable");
        };
        var submission = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(submission));

        var retry = fixture.lane.resume();
        fixture.executor.runAll();
        await(retry);
        assertEquals(1, attempts.get());
    }

    @Test
    void persistsTheTimerBeforeSchedulingAndTransfersItToSavedWorkBeforeHandling() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.onSchedule = () -> {
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
            assertEquals(List.of(timer), fixture.lastSaved().pendingEffects());
        };
        fixture.handler = (snapshot, work) -> {
            if (work.input() instanceof Action) {
                return applied(snapshot, new ProcessingOutput<>(List.of(timer), List.of(), List.of()));
            }
            assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            assertEquals(List.of(new PendingWork<>(work, false)), fixture.lastSaved().pendingWork());
            return applied(snapshot, emptyOutput());
        };
        var submission = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(submission));
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertTrue(fixture.executed.isEmpty());

        fixture.timerTasks.getFirst().task().run();
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertEquals(1, fixture.attempted.size());
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aScheduledCommandWithoutADestinationConsumesItsDeadline(boolean failFirstSave) throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Action("unavailable"));
        fixture.startTimer(timer);
        var routeAttempts = new AtomicInteger();
        fixture.routes = (snapshot, input) -> {
            if (input.equals(timer.input())) {
                routeAttempts.incrementAndGet();
                return List.of();
            }
            return List.of(new Work("counter", input));
        };
        if (failFirstSave) {
            fixture.onSave = checkpoint -> { throw new IllegalStateException("Disk unavailable"); };
        }

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(1, routeAttempts.get());
        assertFalse(fixture.lane.isClosed());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        if (failFirstSave) {
            assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
            fixture.onSave = checkpoint -> {};
            var retried = fixture.lane.resume();
            fixture.executor.runAll();
            assertEquals(new Counter(1, false), awaitSnapshot(retried));
        }
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertEquals(1, routeAttempts.get());
        assertEquals(List.of(new Action("start-timer")), fixture.attemptedInputs());
        await(fixture.lane.shutdown());

        var restored = new Fixture();
        var lane = restored.restoreLane(fixture.lastSaved(), restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertTrue(restored.timerTasks.isEmpty());
        assertTrue(restored.attempted.isEmpty());

        var next = lane.submit(new Action("increment"));
        restored.executor.runAll();
        assertEquals(new Counter(2, false), awaitSnapshot(next));
    }

    @Test
    void anUnexpectedScheduledInputRoutingFailureKeepsItsSavedDeadline() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Action("unexpected"));
        fixture.startTimer(timer);
        var unexpected = new IllegalStateException("Unexpected routing failure");
        fixture.routes = (snapshot, input) -> { throw unexpected; };

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertTrue(fixture.lane.isClosed());
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(List.of(new Action("start-timer")), fixture.attemptedInputs());
    }

    @Test
    void aFailedTimerCheckpointDoesNotScheduleOrRepeatTheProducingHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(timer), List.of(), List.of()));
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var started = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        assertSame(saveFailure, failure(started));
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.timerTasks.isEmpty());

        fixture.onSave = checkpoint -> {};
        var retried = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retried));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, fixture.timerTasks.size());
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void aFailedDeliveryCheckpointLeavesTheSavedTimerUntilThePendingInputCanBeSaved() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.startTimer(timer);
        var saveFailure = new IllegalStateException("Could not save timer delivery");
        fixture.onSave = checkpoint -> { throw saveFailure; };

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(1, fixture.attempted.size());

        fixture.onSave = checkpoint -> {};
        fixture.handler = (snapshot, work) -> {
            assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            assertEquals(List.of(new PendingWork<>(work, false)), fixture.lastSaved().pendingWork());
            return applied(snapshot, emptyOutput());
        };
        var retried = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(retried));
        assertEquals(List.of(new Action("start-timer"), timer.input()), fixture.attemptedInputs());
        assertEquals(1, fixture.timerTasks.size());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void aDeferredTimerSurvivesRestartAsPendingWorkWithoutSchedulingAgain() throws Exception {
        var original = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        original.startTimer(timer);
        original.handler = (snapshot, work) -> work.input().equals(timer.input()) && snapshot.count() < 2
                ? new StepResult.Deferred<>()
                : applied(snapshot, emptyOutput());
        original.timerTasks.getFirst().task().run();
        original.executor.runAll();

        var checkpoint = original.lastSaved();
        assertTrue(checkpoint.scheduledInputs().isEmpty());
        assertEquals(List.of(new PendingWork<>(new Work("counter", timer.input()), false)), checkpoint.pendingWork());
        await(original.lane.shutdown());

        var restored = new Fixture();
        restored.handler = original.handler;
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertTrue(restored.timerTasks.isEmpty());

        var progressed = lane.submit(new Action("increment"));
        restored.executor.runAll();

        assertEquals(new Counter(3, false), awaitSnapshot(progressed));
        assertTrue(restored.lastSaved().scheduledInputs().isEmpty());
        assertTrue(restored.lastSaved().pendingWork().isEmpty());
        assertTrue(restored.timerTasks.isEmpty());
    }

    @Test
    void replacementInvalidatesAnAlreadyQueuedTimerDelivery() throws Exception {
        var fixture = new Fixture();
        var original = new Timer("timeout", NOW.plusSeconds(30), new Message("old"));
        var replacement = new Timer("timeout", NOW.plusSeconds(60), new Message("replacement"));
        fixture.startTimer(original);
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(replacement), List.of(), List.of())
                : emptyOutput());

        var replaced = fixture.lane.submit(new Action("replace"));
        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(replaced));
        assertEquals(List.of(replacement), fixture.lastSaved().scheduledInputs());
        assertFalse(fixture.attemptedInputs().contains(original.input()));
        verify(fixture.timerTasks.getFirst().future()).cancel(false);

        fixture.timerTasks.getLast().task().run();
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        assertEquals(replacement.input(), fixture.attemptedInputs().getLast());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
    }

    @Test
    void aHandledTimerCanScheduleItsReplacementWithTheSameId() throws Exception {
        var fixture = new Fixture();
        var original = new Timer("timeout", NOW.plusSeconds(30), new Message("first-timeout"));
        var replacement = new Timer("timeout", NOW.plusSeconds(60), new Message("second-timeout"));
        fixture.startTimer(original);
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(original.input())
                ? new ProcessingOutput<>(List.of(replacement), List.of(), List.of())
                : emptyOutput());

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(replacement), fixture.lastSaved().scheduledInputs());
        assertEquals(2, fixture.timerTasks.size());

        fixture.timerTasks.getLast().task().run();
        fixture.executor.runAll();

        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(new Action("start-timer"), original.input(), replacement.input()), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void aFailedSendStillAppliesCommittedTimerCancellation() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.startTimer(timer);
        var send = send("closing-message");
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send, new CancelTimer(timer.id())), List.of(), List.of()));
        fixture.onEffect = effect -> {
            assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            throw sendFailure;
        };

        var cancelled = fixture.lane.submit(new Action("cancel"));
        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertSame(sendFailure, failure(cancelled));
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertFalse(fixture.attemptedInputs().contains(timer.input()));
        verify(fixture.timerTasks.getFirst().future()).cancel(false);
    }

    @Test
    void aFailedSendStillSchedulesTheCommittedTimer() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var send = send("payment-message");
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send, timer), List.of(), List.of()));
        fixture.onEffect = effect -> { throw sendFailure; };

        var started = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        assertSame(sendFailure, failure(started));
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertEquals(1, fixture.timerTasks.size());
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void recoveryArmsAnOverdueTimerAndProcessesItsDeliveryEvenIfSendingStillFails() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.minusSeconds(30), new Message("timeout"));
        var send = send("saved-message");
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(1, false),
                List.of(),
                List.of(send),
                List.of(timer));
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.onEffect = effect -> {
            assertEquals(1, fixture.timerTasks.size());
            throw sendFailure;
        };

        var resumed = lane.resume();
        fixture.executor.runAll();
        assertSame(sendFailure, failure(resumed));
        assertEquals(0, fixture.timerTasks.getFirst().delayNanos());
        assertTrue(fixture.attempted.isEmpty());

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(List.of(timer.input()), fixture.attemptedInputs());
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());

        fixture.onEffect = effect -> {};
        var retried = lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(retried));
        assertEquals(List.of(send), fixture.executed);
        assertEquals(List.of(timer.input()), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void recoveryDoesNotReviveATimerFromAnObsoletePendingScheduleEffect() throws Exception {
        var fixture = new Fixture();
        var original = new Timer("timeout", NOW.minusSeconds(30), new Message("old"));
        var replacement = new Timer("timeout", NOW.plusSeconds(60), new Message("replacement"));
        var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(1, false),
                List.of(),
                List.of(original, new CancelTimer(original.id()), replacement),
                List.of(replacement));
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var resumed = lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertTrue(fixture.timerTasks.stream().allMatch(task -> task.delayNanos() == Duration.ofSeconds(60).toNanos()));

        fixture.timerTasks.forEach(task -> task.task().run());
        fixture.executor.runAll();
        assertEquals(List.of(replacement.input()), fixture.attemptedInputs());
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
    }

    @Test
    void shutdownCancelsLiveTimersButKeepsTheirSavedDeadlines() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.initialize();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(new Action("start-timer"))
                ? new ProcessingOutput<>(List.of(timer), List.of(), List.of())
                : emptyOutput());
        var started = fixture.lane.submit(new Action("start-timer"));
        var cancelled = fixture.lane.submit(new Action("accepted"));
        cancelled.cancel(false);

        var stopped = fixture.lane.shutdown();
        assertFalse(stopped.isDone());
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.lane.submit(new Action("late"))));
        fixture.executor.runAll();
        await(stopped);
        assertEquals(new Counter(1, false), awaitSnapshot(started));
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertTrue(cancelled.isCancelled());

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertEquals(List.of(new Action("start-timer"), new Action("accepted")), fixture.attemptedInputs());
        verify(fixture.timerTasks.getFirst().future()).cancel(false);
        verify(fixture.timers, never()).shutdown();
        verify(fixture.timers, never()).shutdownNow();
        var unrelated = new AtomicInteger();
        fixture.executor.execute(unrelated::incrementAndGet);
        fixture.executor.runAll();
        assertEquals(1, unrelated.get());
    }

    @Test
    void shutdownAndExecutorRejectionDoNotLeaveWaitingFuturesUnresolved() throws Exception {
        var fixture = new Fixture();
        var waiting = fixture.lane.submit(new Message("waiting"));
        fixture.executor.runAll();
        await(fixture.lane.shutdown());
        assertInstanceOf(RejectedExecutionException.class, failure(waiting));
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.lane.submit(new Message("late"))));

        var executorFailure = new RejectedExecutionException("Executor stopped");
        var rejectedLane = fixture.createLane(task -> { throw executorFailure; });
        assertSame(executorFailure, failure(rejectedLane.submit(new Message("early"))));
        assertTrue(rejectedLane.isClosed());
        await(rejectedLane.shutdown());
    }

    @Test
    void executorRejectionFailsRecoveryAndEveryQueuedSubmissionWithoutRunningSetup() throws Exception {
        try (var caller = ExecutorFactory.newSingleThreadExecutor("rejected-lane-caller")) {
            var fixture = new Fixture();
            var checkpoint = new Checkpoint<Counter, CounterInput, Work>(new Counter(3, false),
                    List.of(),
                    List.of(send("saved")),
                    List.of());
            var scheduling = new CountDownLatch(1);
            var reject = new CountDownLatch(1);
            var executorFailure = new RejectedExecutionException("Executor stopped");
            var lane = fixture.restoreLane(checkpoint, task -> {
                scheduling.countDown();
                awaitLatch(reject);
                throw executorFailure;
            });
            fixture.onSetup = snapshot -> fail("Rejected recovery must not run setup");
            var recoverySubmission = CompletableFuture.supplyAsync(lane::resume, caller);
            try {
                awaitLatch(scheduling);
                var queued = lane.submit(new Action("increment"));
                var stopped = lane.shutdown();
                assertFalse(queued.isDone());
                assertFalse(stopped.isDone());
                reject.countDown();

                assertSame(executorFailure, failure(await(recoverySubmission)));
                assertSame(executorFailure, failure(queued));
                await(stopped);
                assertTrue(lane.isClosed());
                assertTrue(fixture.attempted.isEmpty());
                assertTrue(fixture.executed.isEmpty());
                assertTrue(fixture.saved.isEmpty());
                assertTrue(fixture.published.isEmpty());
            } finally {
                reject.countDown();
                await(lane.shutdown());
            }
        }
    }

    @Test
    void submissionsRetryAndTimersShareTheQueueAndReentrantInputsWaitForTheCurrentStep() throws Exception {
        try (var executor = ExecutorFactory.newSingleThreadExecutor("lane-order-test")) {
            var fixture = new Fixture();
            var lane = fixture.createLane(executor);
            var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
            await(lane.submit(new Initialize(Optional.empty())));
            fixture.handler = (snapshot, work) -> applied(snapshot,
                    new ProcessingOutput<>(List.of(timer), List.of(), List.of()));
            await(lane.submit(new Action("start-timer")));

            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var callback = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
            var worker = new AtomicReference<Thread>();
            var order = new ArrayList<String>();
            fixture.handler = (snapshot, work) -> {
                if (work.input().equals(new Action("hold"))) {
                    worker.set(Thread.currentThread());
                    order.add("start");
                    entered.countDown();
                    awaitLatch(release);
                    callback.set(lane.submit(new Action("callback")));
                    order.add("end");
                } else {
                    assertSame(worker.get(), Thread.currentThread());
                    order.add(switch (work.input()) {
                        case Action action -> action.name();
                        case Message message -> message.name();
                        default -> throw new AssertionError("Unexpected input");
                    });
                }
                return applied(snapshot, emptyOutput());
            };
            var held = lane.submit(new Action("hold"));
            try {
                awaitLatch(entered);
                var retry = lane.resume();
                var next = lane.submit(new Action("next"));
                fixture.timerTasks.getFirst().task().run();
                assertFalse(retry.isDone());
                assertFalse(next.isDone());
                release.countDown();

                assertEquals(new Counter(2, false), awaitSnapshot(held));
                assertEquals(new Counter(2, false), await(retry));
                assertEquals(new Counter(3, false), awaitSnapshot(next));
                assertEquals(new Counter(5, false), awaitSnapshot(callback.get()));
                assertEquals(List.of("start", "end", "next", "timeout", "callback"), order);
                assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            } finally {
                release.countDown();
                await(lane.shutdown());
            }
        }
    }

    @Test
    void aSharedPoolSerializesEachLaneWhileAnotherLaneCanMakeProgress() throws Exception {
        try (var workers = ExecutorFactory.newFixedThreadPool("lane-workers", 2);
             var callers = ExecutorFactory.newFixedThreadPool("lane-callers", 4)) {
            var first = new Fixture();
            var second = new Fixture();
            var lane = first.createLane(workers);
            var otherLane = second.createLane(workers);
            await(lane.submit(new Initialize(Optional.empty())));
            await(otherLane.submit(new Initialize(Optional.empty())));
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            first.handler = (snapshot, work) -> {
                assertEquals(1, active.incrementAndGet());
                try {
                    if (work.input().equals(new Action("hold"))) {
                        entered.countDown();
                        awaitLatch(release);
                    }
                    return applied(snapshot, emptyOutput());
                } finally {
                    active.decrementAndGet();
                }
            };
            var blocked = lane.submit(new Action("hold"));
            try {
                awaitLatch(entered);
                assertEquals(new Counter(1, false), awaitSnapshot(otherLane.submit(new Action("increment"))));
                var submissions = new ArrayList<CompletableFuture<CompletableFuture<SubmissionResult<Counter>>>>();
                for (int i = 0; i < 30; i++) {
                    submissions.add(CompletableFuture.supplyAsync(() -> lane.submit(new Action("increment")), callers));
                }
                var pending = new ArrayList<CompletableFuture<SubmissionResult<Counter>>>();
                for (var submission : submissions) {
                    pending.add(await(submission));
                }
                assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));
                release.countDown();
                await(blocked);
                for (var submission : pending) {
                    await(submission);
                }
                assertEquals(new Counter(31, false), awaitSnapshot(lane.resume()));
            } finally {
                release.countDown();
                await(lane.shutdown());
                await(otherLane.shutdown());
            }
        }
    }

    private static ProcessingOutput<CounterInput> emptyOutput() {
        return new ProcessingOutput<>(List.of(), List.of(), List.of());
    }

    private static StepResult<Counter, CounterInput> applied(Counter snapshot, ProcessingOutput<CounterInput> output) {
        return new StepResult.Applied<>(new Counter(snapshot.count() + 1, snapshot.failed()), output);
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static ProcessingResult<?> awaitProcessingResult(CompletableFuture<?> future) throws Exception {
        return assertInstanceOf(SubmissionResult.Completed.class, await(future)).processingResult();
    }

    private static Counter awaitSnapshot(CompletableFuture<?> future) throws Exception {
        return switch (await(future)) {
            case Counter counter -> counter;
            case SubmissionResult.Completed<?> completed -> switch (completed.processingResult()) {
                case ProcessingResult.Applied<?> applied -> assertInstanceOf(Counter.class, applied.snapshot());
                case ProcessingResult.Deferred<?> deferred -> assertInstanceOf(Counter.class, deferred.snapshot());
                case ProcessingResult.Ignored<?> ignored -> assertInstanceOf(Counter.class, ignored.snapshot());
                default -> throw new AssertionError("Expected a successful snapshot");
            };
            default -> throw new AssertionError("Expected a successful snapshot");
        };
    }

    private static Throwable failure(CompletableFuture<?> future) {
        try {
            return switch (await(future)) {
                case SubmissionResult.Completed<?> completed -> switch (completed.processingResult()) {
                    case ProcessingResult.Rejected<?> rejected -> rejected.cause();
                    case ProcessingResult.Failed<?> failed -> failed.cause();
                    default -> throw new AssertionError("Expected a failure");
                };
                case SubmissionResult.Pending<?> pending -> pending.cause();
                case SubmissionResult.NotProcessed<?> notProcessed -> notProcessed.cause();
                default -> throw new AssertionError("Expected a failure");
            };
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    // These inputs deliberately have no Event or Proto dependency.
    private interface CounterInput extends Input {}

    private interface CounterMessage extends CounterInput {}

    private record Initialize(Optional<CounterInput> firstInput) implements Command {}

    private record InitializationMessage() implements CounterMessage {}

    private record UnregisteredCommand() implements Command {}

    private record Message(String name) implements CounterMessage {}

    private record Notification() implements CounterInput {}

    private record Action(String name) implements CounterInput, Command {}

    private record Counter(int count, boolean failed) {}

    private record PublishedSnapshot(Counter snapshot,
                                     Optional<RuntimeException> persistenceFailure,
                                     Optional<RuntimeException> setupFailure) {}

    private record Work(String destination, CounterInput input) {}

    private interface TestEffect extends Effect {
        @Override
        default StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(toString());
        }
    }

    private static Effect.SendMessage<Payload, Payload> send(String id) {
        return new Effect.SendMessage<>(new Payload(id), new Payload("peer"));
    }

    private record Payload(String value) implements NetworkProto {
        @Override
        public StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(value);
        }

        @Override
        public void verify() {}
    }

    private record Observation(String id) implements TestEffect {
        @Override
        public boolean replayOnRestart() {
            return false;
        }
    }

    private record Timer(String id, Instant triggerAt, CounterInput input) implements TestEffect, ScheduledInput<CounterInput> {}

    private record CancelTimer(String id) implements TestEffect, CancelScheduledInput {}

    private record TimerTask(Runnable task, long delayNanos, ScheduledFuture<?> future) {}

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }

    private static final class Fixture {
        private final ManualExecutor executor = new ManualExecutor();
        private final ScheduledExecutorService timers = mock(ScheduledExecutorService.class);
        private final MockClock clock = new MockClock();
        private final List<TimerTask> timerTasks = new ArrayList<>();
        private final List<Checkpoint<Counter, CounterInput, Work>> saved = new ArrayList<>();
        private final List<Effect> executed = new ArrayList<>();
        private final List<Counter> effectSnapshots = new ArrayList<>();
        private final List<Work> attempted = new ArrayList<>();
        private final List<PublishedSnapshot> published = new ArrayList<>();
        private final ProcessingLane<Counter, CounterInput, Work> lane = createLane(executor);
        private BiFunction<Counter, CounterInput, List<Work>> routes = (snapshot, input) -> List.of(new Work("counter", input));
        private BiFunction<Counter, Work, StepResult<Counter, CounterInput>> handler = (snapshot, work) -> applied(snapshot, emptyOutput());
        private Function<Counter, List<Effect>> recoveryEffects = snapshot -> List.of();
        private BiConsumer<Counter, CounterInput> onValidate = (snapshot, input) -> {};
        private Consumer<Input> onPrepare = input -> {};
        private Consumer<Counter> onCreateProcessor = snapshot -> {};
        private Consumer<Counter> onSetup = snapshot -> {};
        private Consumer<Counter> onInitialized = snapshot -> {};
        private Consumer<Checkpoint<Counter, CounterInput, Work>> onSave = checkpoint -> {};
        private Consumer<Effect> onEffect = effect -> {};
        private Consumer<LogEntry> onLog = entry -> {};
        private SnapshotPublisher<Counter> onPublish = (snapshot, persistenceFailure, setupFailure) -> {};
        private Runnable onSchedule = () -> {};

        private Fixture() {
            clock.setTime(NOW.toEpochMilli());
            when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        onSchedule.run();
                        var future = mock(ScheduledFuture.class);
                        timerTasks.add(new TimerTask(invocation.getArgument(0), invocation.getArgument(1), future));
                        return future;
                    });
        }

        private ProcessingLane<Counter, CounterInput, Work> createLane(Executor worker) {
            return createLane(worker, false);
        }

        private ProcessingLane<Counter, CounterInput, Work> createLane(Executor worker, boolean withSetup) {
            return new ProcessingLane<>("counter",
                    Optional.empty(),
                    worker,
                    timers,
                    clock,
                    createDefinition(withSetup),
                    this::saveCheckpoint,
                    this::executeEffect,
                    entry -> onLog.accept(entry),
                    this::publishSnapshot);
        }

        private ProcessingLane<Counter, CounterInput, Work> restoreLane(Checkpoint<Counter, CounterInput, Work> checkpoint,
                                                                       Executor worker) {
            return new ProcessingLane<>("counter",
                    Optional.of(checkpoint),
                    worker,
                    timers,
                    clock,
                    createDefinition(true),
                    this::saveCheckpoint,
                    this::executeEffect,
                    entry -> onLog.accept(entry),
                    this::publishSnapshot);
        }

        private ProcessingLaneDefinition<Counter, CounterInput, Work> createDefinition(boolean withSetup) {
            var builder = ProcessingLaneDefinition.<Counter, CounterInput, Work>builder(
                            CounterInput.class,
                            this::createProcessor)
                    .initializeOn(Initialize.class, input -> prepare(input, input.firstInput()))
                    .initializeOn(InitializationMessage.class, input -> prepare(input, Optional.of(input)))
                    .stashUntilInitialized(CounterMessage.class)
                    .onInitialized(snapshot -> onInitialized.accept(snapshot));
            if (withSetup) {
                builder.setupWith(snapshot -> onSetup.accept(snapshot));
            }
            return builder.build();
        }

        private void saveCheckpoint(Checkpoint<Counter, CounterInput, Work> checkpoint) {
            onSave.accept(checkpoint);
            saved.add(checkpoint);
        }

        private void executeEffect(Counter snapshot, Effect effect) {
            effectSnapshots.add(snapshot);
            onEffect.accept(effect);
            executed.add(effect);
        }

        private void publishSnapshot(Counter snapshot,
                                     Optional<RuntimeException> persistenceFailure,
                                     Optional<RuntimeException> setupFailure) {
            published.add(new PublishedSnapshot(snapshot, persistenceFailure, setupFailure));
            onPublish.publish(snapshot, persistenceFailure, setupFailure);
        }

        private Initialization<Counter, CounterInput> prepare(Input input, Optional<CounterInput> firstInput) {
            onPrepare.accept(input);
            return new Initialization<>(new Counter(0, false), firstInput);
        }

        private Processor<Counter, CounterInput, Work> createProcessor(Counter initialSnapshot) {
            onCreateProcessor.accept(initialSnapshot);
            return new Processor<>() {
                @Override
                public List<Effect> recoveryEffects(Counter snapshot) {
                    return recoveryEffects.apply(snapshot);
                }

                @Override
                public void validateInput(Counter snapshot, CounterInput input) {
                    onValidate.accept(snapshot, input);
                }

                @Override
                public List<Work> route(Counter snapshot, CounterInput input) {
                    return routes.apply(snapshot, input);
                }

                @Override
                public StepResult<Counter, CounterInput> processStep(Counter snapshot, Work work) {
                    attempted.add(work);
                    return handler.apply(snapshot, work);
                }
            };
        }

        private void initialize() {
            var initialized = lane.submit(new Initialize(Optional.empty()));
            executor.runAll();
            assertFalse(initialized.isCompletedExceptionally());
            assertTrue(initialized.isDone());
        }

        private void startTimer(Timer timer) throws Exception {
            initialize();
            handler = (snapshot, work) -> applied(snapshot,
                    new ProcessingOutput<>(List.of(timer), List.of(), List.of()));
            var started = lane.submit(new Action("start-timer"));
            executor.runAll();
            assertEquals(new Counter(1, false), awaitSnapshot(started));
            handler = (snapshot, work) -> applied(snapshot, emptyOutput());
        }

        private Checkpoint<Counter, CounterInput, Work> lastSaved() {
            return saved.getLast();
        }

        private List<CounterInput> attemptedInputs() {
            return attempted.stream().map(Work::input).toList();
        }
    }
}
