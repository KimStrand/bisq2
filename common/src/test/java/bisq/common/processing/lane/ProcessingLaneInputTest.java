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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a lane admits, saves, defers and ignores its inputs.
 */
class ProcessingLaneInputTest extends ProcessingLaneCounterFixture {
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
                return new HandleResult.Deferred<>();
            }
            if (work.input().equals(ignored)) {
                return new HandleResult.Ignored<>();
            }
            if (work.input().equals(raised)) {
                assertEquals(1, fixture.lastSaved().snapshot().count());
                assertEquals(List.of(work.input(), early),
                        fixture.lastSaved().pendingInputs());
            }
            return applied(snapshot, new ProcessingOutput<>(List.of(),
                    work.input().equals(change) ? List.of(raised) : List.of()));
        };

        var deferred = fixture.lane.submit(early);
        fixture.executor.runAll();
        assertEquals(new Counter(0, false), awaitSnapshot(deferred));
        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(deferred));
        assertEquals(List.of(early), fixture.lastSaved().pendingInputs());
        var ignoredSubmission = fixture.lane.submit(ignored);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Ignored.class, awaitProcessingSummary(ignoredSubmission));
        assertEquals(List.of(early, ignored), fixture.attemptedInputs());

        var result = fixture.lane.submit(change);
        fixture.executor.runAll();
        assertEquals(new Counter(3, false), awaitSnapshot(result));
        assertEquals(List.of(early, ignored, change, raised, early), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void explicitRetryReconsidersDeferredWorkOnceWithoutChangingItsOrder() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.handler = (snapshot, work) -> new HandleResult.Deferred<>();
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
        int savedCount = fixture.saved.size();
        var rejection = new IllegalStateException("Unavailable action");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Action("reject"))) {
                return new HandleResult.Rejected<>(rejection);
            }
            if (work.input().equals(new Action("defer"))) {
                return new HandleResult.Deferred<>();
            }
            return applied(snapshot, emptyOutput());
        };
        var rejected = fixture.lane.submit(new Action("reject"));
        var incorrectlyDeferred = fixture.lane.submit(new Action("defer"));
        fixture.executor.runAll();

        assertSame(rejection, failure(rejected));
        assertInstanceOf(ProcessingSummary.Rejected.class, awaitProcessingSummary(rejected));
        assertInstanceOf(IllegalStateException.class, failure(incorrectlyDeferred));
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(savedCount, fixture.saved.size());
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
        int savedCount = fixture.saved.size();
        fixture.routes = (snapshot, input) -> List.of();
        var command = fixture.lane.submit(new Action("unknown"));
        var message = fixture.lane.submit(new Message("unknown"));
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(command));
        assertFalse(message.isCompletedExceptionally());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(savedCount + 2, fixture.saved.size());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void invalidInputsAreRejectedBeforeRoutingOrSavingWithoutClosingTheLane() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        int savedCount = fixture.saved.size();
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
        assertInstanceOf(ProcessingSummary.Rejected.class, awaitProcessingSummary(rejected));
        assertEquals(savedCount, fixture.saved.size());
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
        assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
        assertSame(saveFailure, pending.cause());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        fixture.handler = (snapshot, work) -> {
            assertEquals(List.of(work.input()), fixture.lastSaved().pendingInputs());
            return applied(snapshot, emptyOutput());
        };
        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(List.of(message), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void messagesArrivingDuringAnObservationFailureAreSavedAndRecoveredInOrder() throws Exception {
        var original = new Fixture();
        original.initialize();
        var observation = new Observation("deposit");
        var observationFailure = new IllegalStateException("Observation unavailable");
        original.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(observation), List.of()));
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
            assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
            assertSame(observationFailure, pending.cause());
        }
        assertEquals(List.of(new Action("start")), original.attemptedInputs());
        var checkpoint = original.lastSaved();
        assertEquals(List.of(first,
                second), checkpoint.pendingInputs());
        assertEquals(List.of(observation), checkpoint.pendingEffects());

        var restored = new Fixture();
        restored.recoveryEffects = snapshot -> List.of(observation);
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertEquals(new Counter(3, false), awaitSnapshot(resumed));
        assertEquals(List.of(first, second), restored.attemptedInputs());
        assertEquals(List.of(observation), restored.executed);
        assertTrue(restored.lastSaved().pendingInputs().isEmpty());
        assertTrue(restored.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void admissionUsesSavedHistoryAndProcessingRechecksAfterAnUnsavedTransition() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var routedCounts = new ArrayList<Integer>();
        var validatedCounts = new ArrayList<Integer>();
        fixture.onValidate = (snapshot, input) -> validatedCounts.add(snapshot.count());
        var backendCalls = new AtomicInteger();
        fixture.routes = (snapshot, input) -> {
            routedCounts.add(snapshot.count());
            return snapshot.count() == 0 ? List.of(new Work("counter", input)) : List.of();
        };
        fixture.handler = (snapshot, work) -> {
            // The count represents successful processing history for this simple processor.
            if (snapshot.count() > 0) {
                return new HandleResult.Ignored<>();
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
        assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
        assertEquals(List.of(0, 0), validatedCounts);
        assertEquals(List.of(0), routedCounts);
        assertEquals(1, backendCalls.get());

        fixture.onSave = checkpoint -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(1, false), awaitSnapshot(resumed));
        assertEquals(1, backendCalls.get());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());

        int savedCount = fixture.saved.size();
        var alreadyProcessed = fixture.lane.submit(new Message("another-copy"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Ignored.class, awaitProcessingSummary(alreadyProcessed));
        assertEquals(savedCount + 2, fixture.saved.size());
        assertEquals(1, backendCalls.get());
    }

    @Test
    void aMessageWhichCanNoLongerBeHandledIsIgnoredWithoutSavingOrHandlingIt() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var checkedSnapshots = new ArrayList<Counter>();
        fixture.stillHandleable = (snapshot, input) -> {
            checkedSnapshots.add(snapshot);
            return false;
        };
        int savedCount = fixture.saved.size();
        int attemptedCount = fixture.attemptedInputs().size();

        var copy = fixture.lane.submit(new Message("copy"));
        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Ignored.class, awaitProcessingSummary(copy));
        assertEquals(savedCount, fixture.saved.size());
        assertEquals(attemptedCount, fixture.attemptedInputs().size());
        assertEquals(List.of(fixture.lastSaved().snapshot()), checkedSnapshots);
    }

    @Test
    void anIgnoredMessageIsNotSavedButStillRetriesEarlierUnfinishedWork() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var response = send("response");
        fixture.handler = (snapshot, work) -> applied(snapshot, new ProcessingOutput<>(List.of(response), List.of()));
        var sendFailure = new IllegalStateException("Send unavailable");
        var sendAttempts = new AtomicInteger();
        fixture.onEffect = effect -> {
            sendAttempts.incrementAndGet();
            throw sendFailure;
        };
        var request = fixture.lane.submit(new Message("request"));
        fixture.executor.runAll();
        assertInstanceOf(SubmissionResult.Pending.class, await(request));
        fixture.stillHandleable = (snapshot, input) -> false;
        int savedCount = fixture.saved.size();

        var copy = fixture.lane.submit(new Message("copy"));
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(copy));
        assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
        assertInstanceOf(ProcessingSummary.Ignored.class, pending.processingSummary());
        assertSame(sendFailure, pending.cause());
        assertEquals(2, sendAttempts.get());
        assertEquals(savedCount, fixture.saved.size());
    }

    @Test
    void theCheckUsesTheSavedStateSoAnUnsavedTransitionCannotDropAMessage() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        // The count represents successful processing history for this simple processor: a message of this type can
        // still be handled only while the count is 0.
        fixture.stillHandleable = (snapshot, input) -> snapshot.count() == 0;
        var saveFailure = new IllegalStateException("Cannot save the processing result");
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() > 0) {
                throw saveFailure;
            }
        };
        var first = fixture.lane.submit(new Message("first"));
        fixture.executor.runAll();
        assertSame(saveFailure, failure(first));

        var copy = fixture.lane.submit(new Message("copy"));
        fixture.executor.runAll();

        // Only the unsaved state shows the first message as handled, so the copy is kept instead of ignored.
        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(copy));
        assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
    }

    @Test
    void commandsDoNotUseTheCheckBeforeSaving() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.stillHandleable = (snapshot, input) -> false;

        var command = fixture.lane.submit(new Action("act"));
        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Applied.class, awaitProcessingSummary(command));
        assertTrue(fixture.attemptedInputs().contains(new Action("act")));
    }

    @Test
    void processorDecidesWhetherASavedInputIsStillNeeded() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        int savedCount = fixture.saved.size();
        fixture.routes = (snapshot, input) -> input instanceof Message
                ? List.of(new Work("interested-and-reachable", input))
                : List.of();
        fixture.onEffect = effect -> { throw new AssertionError("No effects expected"); };
        fixture.handler = (snapshot, work) -> {
            assertEquals("interested-and-reachable", work.destination());
            assertEquals(List.of(work.input()), fixture.lastSaved().pendingInputs());
            return new HandleResult.Deferred<>();
        };

        var uninteresting = fixture.lane.submit(new Notification());
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Ignored.class, awaitProcessingSummary(uninteresting));
        assertEquals(savedCount + 2, fixture.saved.size());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());

        var needed = fixture.lane.submit(new Message("broadcast"));
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(needed));
        assertEquals(List.of(new Message("broadcast")),
                fixture.lastSaved().pendingInputs());
    }

    @Test
    void newMessagesDoNotOvertakeQueuedMessagesWhileSetupIsBlocked() throws Exception {
        var fixture = new Fixture();
        var lane = fixture.createLane(fixture.executor, true);
        var setupFailure = new IllegalStateException("Setup unavailable");
        fixture.onSetup = snapshot -> { throw setupFailure; };
        var first = new Message("first");
        var early = new Message("early");
        var second = new Message("second");
        var late = new Message("late");
        // The router submits pending messages right behind the initializer.
        lane.submit(new Initialize(first));
        var earlyResult = lane.submit(early);
        var secondResult = lane.submit(second);
        fixture.executor.runAll();

        var lateResult = lane.submit(late);
        fixture.executor.runAll();
        for (var result : List.of(earlyResult, secondResult, lateResult)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(result));
            assertEquals(CoordinationPhase.SETUP, pending.phase());
            assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
        }
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(List.of(first, early, second, late), fixture.lastSaved().pendingInputs());

        fixture.onSetup = snapshot -> {};
        var resumed = lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(4, false), awaitSnapshot(resumed));
        assertEquals(List.of(first, early, second, late), fixture.attemptedInputs());
    }
}
