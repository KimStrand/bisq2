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

import bisq.common.processing.HandleResult;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * When a lane publishes its snapshot, and which failures it publishes with it.
 */
class ProcessingLanePublicationTest extends ProcessingLaneCounterFixture {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void publishesTheAdoptedInitialSnapshotBeforeSaveAndSetup(boolean observerFails) throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var order = new ArrayList<String>();
        fixture.onPrepare = input -> order.add("admit");
        fixture.onCreateProcessor = snapshot -> order.add("create-processor");
        fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {
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

        var initialized = lane.submit(new Initialize(new Message("first")));
        fixture.executor.runAll();

        assertEquals(List.of("admit", "create-processor", "publish", "save", "setup", "first-input"), order);
        assertEquals(new PublishedSnapshot(new Counter(0, false), Optional.empty(), Optional.empty()), fixture.published.getFirst());
        assertEquals(new Counter(1, false), awaitSnapshot(initialized));
        assertFalse(lane.isClosed());
    }

    @Test
    void savesTheFirstInputBeforeHandlingItAndThenHandlesQueuedMessagesInOrder() throws Exception {
        var fixture = new Fixture();
        var preparations = new AtomicInteger();
        fixture.onPrepare = input -> preparations.incrementAndGet();
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var early = fixture.lane.submit(new Message("early"));
        var second = fixture.lane.submit(new Message("second"));
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Message("first"))) {
                assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
                assertEquals(List.of(work.input()), fixture.lastSaved().pendingInputs());
                assertFalse(early.isDone());
            }
            return applied(snapshot, emptyOutput());
        };
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
    void recoveryCompletesAfterSavingAnExplicitProcessingFailure() throws Exception {
        var fixture = new Fixture();
        var input = new Message("pending");
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(0, false),
                List.of(input),
                List.of(),
                List.of());
        var failed = new Counter(0, true);
        fixture.handler = (snapshot, work) -> new HandleResult.Failed<>(failed,
                emptyOutput(),
                new IllegalStateException("Domain operation failed"));
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        var recovery = lane.resume();
        fixture.executor.runAll();
        assertEquals(failed, await(recovery));
        assertEquals(failed, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertFalse(lane.isClosed());
    }

    @Test
    void publishesLocalFailureBeforeCompletionAndClearsSaveFailureWithoutRepeatingTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var domainFailure = new IllegalStateException("Processing failed");
        var saveFailure = new IllegalStateException("Disk write failed");
        var failedSnapshot = new Counter(0, true);
        fixture.handler = (snapshot, work) -> new HandleResult.Failed<>(failedSnapshot, emptyOutput(), domainFailure);
        fixture.onSave = checkpoint -> { throw saveFailure; };

        var submitted = fixture.lane.submit(new Action("fail"));
        var observedAtCompletion = submitted.thenApply(ignored -> fixture.published.getLast());
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submitted));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertSame(saveFailure, pending.cause());
        assertSame(domainFailure, assertInstanceOf(ProcessingSummary.Failed.class, pending.processingSummary()).cause());
        assertEquals(new PublishedSnapshot(failedSnapshot, Optional.of(saveFailure), Optional.empty()), await(observedAtCompletion));
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());

        // An irrelevant input must not replace current local state with its older admission snapshot or clear the error.
        fixture.routes = (snapshot, input) -> List.of();
        var ignored = fixture.lane.submit(new Message("irrelevant"));
        fixture.executor.runAll();
        var waiting = assertInstanceOf(SubmissionResult.Pending.class, await(ignored));
        assertEquals(CoordinationPhase.PERSISTENCE, waiting.phase());
        assertInstanceOf(ProcessingSummary.Deferred.class, waiting.processingSummary());
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
                ? new ProcessingOutput<>(List.of(), List.of(new Message("raised")))
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
        var submitted = fixture.lane.submit(new Initialize(new Message("first")));
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

        assertEquals(new PublishedSnapshot(new Counter(1, false), Optional.empty(), Optional.empty()), await(observedAtCompletion));
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
                new ProcessingOutput<>(List.of(send("message")), List.of()));
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
        fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> { throw new IllegalStateException("Observer failed"); };
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
        fixture.handler = (snapshot, work) -> new HandleResult.Failed<>(new Counter(2, true),
                emptyOutput(),
                processingFailure);
        var failedInput = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(failedInput));
        assertEquals(new PublishedSnapshot(new Counter(2, true), Optional.empty(), Optional.empty()), fixture.published.getLast());
        assertFalse(fixture.lane.isClosed());
    }
}
