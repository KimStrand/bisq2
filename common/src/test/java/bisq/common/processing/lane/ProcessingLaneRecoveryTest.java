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
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A restored lane: recovery effects, saved work and timers after a restart.
 */
class ProcessingLaneRecoveryTest extends ProcessingLaneCounterFixture {
    @Test
    void restoredRecoveryEffectsAreSavedAfterSetupBeforeOutgoingAndPendingWork() throws Exception {
        var fixture = new Fixture();
        var oldObservation = new Observation("obsolete");
        var observation = new Observation("needed");
        var send = send("saved-message");
        var timer = new Timer("deadline", NOW.plusSeconds(30), new Notification());
        var input = new Message("saved-input");
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(2, false),
                List.of(input),
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
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(2, true),
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
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(2, false),
                List.of(new Message("saved-input")),
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
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(2, false),
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anUnexpectedRecoveryExceptionStopsTheLaneWithoutDispatchingOldEffects(boolean previouslyFailed) {
        var fixture = new Fixture();
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(2, previouslyFailed),
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
        var logEntry = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Started"));
        var saveFailure = new IllegalStateException("Send removal could not be saved");
        original.onSetup = snapshot -> originalSetup.incrementAndGet();
        original.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start)
                ? new ProcessingOutput<>(List.of(send, logEntry), List.of(raised))
                : emptyOutput());
        original.onSave = saved -> {
            if (saved.snapshot().count() > 0 && saved.pendingEffects().isEmpty()) {
                throw saveFailure;
            }
        };
        var started = originalLane.submit(new Initialize(start));
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
        assertTrue(restored.lastSaved().pendingInputs().isEmpty());
        assertTrue(restored.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void restoredDeferredWorkDoesNotPreventResumeFromCompletingOrRewriteAnUnchangedCheckpoint() throws Exception {
        var original = new Fixture();
        original.initialize();
        original.handler = (snapshot, work) -> new HandleResult.Deferred<>();
        var early = new Message("early");
        var deferred = original.lane.submit(early);
        original.executor.runAll();
        await(deferred);
        var checkpoint = original.lastSaved();
        await(original.lane.shutdown());

        var restored = new Fixture();
        var setups = new AtomicInteger();
        restored.onSetup = snapshot -> setups.incrementAndGet();
        restored.handler = (snapshot, work) -> new HandleResult.Deferred<>();
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
        var initialized = originalLane.submit(new Initialize(command));
        original.executor.runAll();
        assertSame(setupFailure, failure(initialized));
        var checkpoint = original.lastSaved();
        assertEquals(List.of(command), checkpoint.pendingInputs());
        await(originalLane.shutdown());

        var restored = new Fixture();
        restored.handler = (snapshot, work) -> new HandleResult.Deferred<>();
        var lane = restored.restoreLane(checkpoint, restored.executor);
        var resumed = lane.resume();
        restored.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(resumed));
        assertEquals(List.of(command), restored.attemptedInputs());
        assertTrue(restored.lastSaved().pendingInputs().isEmpty());
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
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(4, previouslyFailed),
                List.of(pending),
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
        assertTrue(first.lastSaved().pendingInputs().isEmpty());
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
        assertTrue(first.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void restoresTimersOnlyAfterSetupAndKeepsTheirAbsoluteDeadlines() throws Exception {
        var fixture = new Fixture();
        fixture.clock.setTime(NOW.plusSeconds(30).toEpochMilli());
        var timer = new Timer("timeout", NOW.plusSeconds(60), new Message("timeout"));
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(1, false),
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
}
