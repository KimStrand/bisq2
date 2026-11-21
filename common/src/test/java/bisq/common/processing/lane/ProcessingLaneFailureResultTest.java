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
import bisq.common.threading.ExecutorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a submission reports when its processing, its save or a later reaction fails.
 */
class ProcessingLaneFailureResultTest extends ProcessingLaneCounterFixture {
    @Test
    void aReportedProcessingFailureSavesItsSnapshotAndFinishesReactionsBeforeReturningFailed() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var processingFailure = new IllegalStateException("Operation failed");
        var reaction = new Message("reaction");
        fixture.handler = (snapshot, work) -> {
            if (work.input().equals(new Action("fail"))) {
                return new HandleResult.Failed<>(new Counter(1, true),
                        new ProcessingOutput<>(List.of(), List.of(reaction)),
                        processingFailure);
            }
            assertTrue(fixture.lastSaved().snapshot().failed());
            return applied(snapshot, emptyOutput());
        };
        var submission = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(submission));
        assertInstanceOf(ProcessingSummary.Failed.class, awaitProcessingSummary(submission));
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
        fixture.handler = (snapshot, work) -> new HandleResult.Failed<>(new Counter(1, true),
                emptyOutput(),
                processingFailure);
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var submission = fixture.lane.submit(new Action("fail"));
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        var failed = assertInstanceOf(ProcessingSummary.Failed.class, pending.processingSummary());
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
    void aQueuedInputsSaveFailureIsSeparateFromTheInitializersSavedProcessingFailure() throws Exception {
        var fixture = new Fixture();
        var processingFailure = new IllegalStateException("First input failed");
        var saveFailure = new IllegalStateException("Buffered input could not be saved");
        fixture.handler = (snapshot, work) -> work.input().equals(new Message("first"))
                ? new HandleResult.Failed<>(new Counter(1, true), emptyOutput(), processingFailure)
                : applied(snapshot, emptyOutput());
        fixture.onSave = checkpoint -> {
            if (checkpoint.snapshot().count() == 2) {
                throw saveFailure;
            }
        };
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var waiting = fixture.lane.submit(new Message("waiting"));
        fixture.executor.runAll();

        assertSame(processingFailure, failure(initialized));
        assertSame(saveFailure, failure(waiting));
        var failed = assertInstanceOf(ProcessingSummary.Failed.class, awaitProcessingSummary(initialized));
        assertEquals(new Counter(1, true), failed.snapshot());
        var waitingPending = assertInstanceOf(SubmissionResult.Pending.class, await(waiting));
        assertEquals(CoordinationPhase.PERSISTENCE, waitingPending.phase());
        assertEquals(0, processingFailure.getSuppressed().length);
        assertEquals(new Counter(1, true), fixture.lastSaved().snapshot());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void aFailedRemovalCheckpointTakesPrecedenceOverTheEarlierSendFailure() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("deadline", NOW.plusSeconds(30), new Notification());
        var send = send("message");
        var observation = new Observation("deposit");
        var sendFailure = new IllegalStateException("Send unavailable");
        var saveFailure = new IllegalStateException("Cannot save the removal of the observation");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send, observation), List.of(), List.of(timer)));
        fixture.onEffect = effect -> {
            if (effect.equals(send)) {
                throw sendFailure;
            }
        };
        fixture.onSave = checkpoint -> {
            if (fixture.executed.contains(observation)) {
                throw saveFailure;
            }
        };

        var submission = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
        assertSame(saveFailure, pending.cause());
        assertEquals(List.of(sendFailure), List.of(saveFailure.getSuppressed()));
        assertEquals(List.of(send, observation), fixture.lastSaved().pendingEffects());
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        assertEquals(1, fixture.timerTasks.size());

        fixture.onSave = checkpoint -> {};
        fixture.onEffect = effect -> {};
        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), await(retry));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, fixture.timerTasks.size());
        assertEquals(List.of(observation, send), fixture.executed);
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
                return applied(snapshot, new ProcessingOutput<>(List.of(), List.of(raised)));
            }
            if (work.input().equals(raised)) {
                return new HandleResult.Rejected<>(rejection);
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

        var applied = assertInstanceOf(ProcessingSummary.Applied.class, awaitProcessingSummary(nextResult));
        assertEquals(new Counter(2, false), applied.snapshot());
        assertEquals(List.of(first, raised, next), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
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
                        ? new HandleResult.Deferred<>()
                        : new HandleResult.Failed<>(new Counter(snapshot.count(), true), emptyOutput(), olderFailure);
            }
            return applied(snapshot, emptyOutput());
        };
        var earlyResult = fixture.lane.submit(early);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(earlyResult));

        var nextResult = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        var applied = assertInstanceOf(ProcessingSummary.Applied.class, awaitProcessingSummary(nextResult));
        assertEquals(new Counter(1, true), applied.snapshot());
        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(earlyResult));
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void anImmediateRaisedRejectionBelongsToItsOriginatingSubmission() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var raised = new Message("raised");
        var rejection = new IllegalStateException("Reaction rejected");
        fixture.handler = (snapshot, work) -> work.input().equals(raised)
                ? new HandleResult.Rejected<>(rejection)
                : applied(snapshot, new ProcessingOutput<>(List.of(), List.of(raised)));

        var submitted = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();

        var failed = assertInstanceOf(ProcessingSummary.Failed.class, awaitProcessingSummary(submitted));
        assertSame(rejection, failed.cause());
        assertEquals(new Counter(1, false), failed.snapshot());
        assertEquals(failed.snapshot(), fixture.lastSaved().snapshot());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void aFailedWholeInputReportsItsPartialProgress() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var rejection = new IllegalStateException("Right flow rejected");
        fixture.handler = (snapshot, work) -> new HandleResult.Failed<>(new Counter(1, false), emptyOutput(), rejection);

        var submitted = fixture.lane.submit(new Message("broadcast"));
        fixture.executor.runAll();

        var failed = assertInstanceOf(ProcessingSummary.Failed.class, awaitProcessingSummary(submitted));
        assertSame(rejection, failed.cause());
        assertEquals(new Counter(1, false), failed.snapshot());
        assertEquals(1, fixture.attempted.size());
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
                broken.initialize(lane);
                other.initialize(otherLane);
                var savedSnapshot = awaitSnapshot(lane.submit(new Action("advance")));
                var unexpected = new IllegalStateException("No valid failure snapshot");
                broken.handler = (snapshot, work) -> { throw unexpected; };
                var input = new Message("fail");

                assertSame(unexpected, failure(lane.submit(input)));

                assertTrue(lane.isClosed());
                var checkpoint = broken.lastSaved();
                assertEquals(new Counter(1, false), savedSnapshot);
                assertEquals(savedSnapshot, checkpoint.snapshot());
                assertEquals(List.of(input), checkpoint.pendingInputs());
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
}
