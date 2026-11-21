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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

/**
 * Scheduled inputs of a lane: saving, delivery, replacement, cancellation and recovery of timers.
 */
class ProcessingLaneTimerTest extends ProcessingLaneCounterFixture {
    @Test
    void persistsTheTimerBeforeSchedulingAndTransfersItToSavedWorkBeforeHandling() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.onSchedule = () -> {
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
            assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        };
        fixture.handler = (snapshot, work) -> {
            if (work.input() instanceof Action) {
                return applied(snapshot, new ProcessingOutput<>(List.of(), List.of(), List.of(timer)));
            }
            assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            assertEquals(List.of(work.input()), fixture.lastSaved().pendingInputs());
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
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

        assertEquals(failFirstSave ? 0 : 1, routeAttempts.get());
        assertFalse(fixture.lane.isClosed());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        if (failFirstSave) {
            assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
            fixture.onSave = checkpoint -> {};
            var retried = fixture.lane.resume();
            fixture.executor.runAll();
            assertInstanceOf(IllegalStateException.class, failure(retried));
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
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
    void anUnexpectedScheduledInputFailureKeepsItsSavedInput() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Action("unexpected"));
        fixture.startTimer(timer);
        var unexpected = new IllegalStateException("Unexpected routing failure");
        fixture.routes = (snapshot, input) -> { throw unexpected; };

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertTrue(fixture.lane.isClosed());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertEquals(List.of(timer.input()), fixture.lastSaved().pendingInputs());
        assertEquals(List.of(new Action("start-timer")), fixture.attemptedInputs());
    }

    @Test
    void aFailedTimerCheckpointDoesNotScheduleOrRepeatTheProducingHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(), List.of(), List.of(timer)));
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(1, fixture.attempted.size());

        fixture.onSave = checkpoint -> {};
        fixture.handler = (snapshot, work) -> {
            assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
            assertEquals(List.of(work.input()), fixture.lastSaved().pendingInputs());
            return applied(snapshot, emptyOutput());
        };
        var retried = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(retried));
        assertEquals(List.of(new Action("start-timer"), timer.input()), fixture.attemptedInputs());
        assertEquals(1, fixture.timerTasks.size());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void aDeferredTimerSurvivesRestartAsPendingWorkWithoutSchedulingAgain() throws Exception {
        var original = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        original.startTimer(timer);
        original.handler = (snapshot, work) -> work.input().equals(timer.input()) && snapshot.count() < 2
                ? new HandleResult.Deferred<>()
                : applied(snapshot, emptyOutput());
        original.timerTasks.getFirst().task().run();
        original.executor.runAll();

        var checkpoint = original.lastSaved();
        assertTrue(checkpoint.scheduledInputs().isEmpty());
        assertEquals(List.of(timer.input()), checkpoint.pendingInputs());
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
        assertTrue(restored.lastSaved().pendingInputs().isEmpty());
        assertTrue(restored.timerTasks.isEmpty());
    }

    @Test
    void replacementInvalidatesAnAlreadyQueuedTimerDelivery() throws Exception {
        var fixture = new Fixture();
        var original = new Timer("timeout", NOW.plusSeconds(30), new Message("old"));
        var replacement = new Timer("timeout", NOW.plusSeconds(60), new Message("replacement"));
        fixture.startTimer(original);
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(), List.of(), List.of(replacement))
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
                ? new ProcessingOutput<>(List.of(), List.of(), List.of(replacement))
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void aFailedSendStillAppliesCommittedTimerCancellation() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.startTimer(timer);
        var send = send("closing-message");
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send), List.of(), List.of(new CancelTimer(timer.id()))));
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
                new ProcessingOutput<>(List.of(send), List.of(), List.of(timer)));
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
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(1, false),
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }
}
