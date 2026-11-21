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

import bisq.common.processing.Effect;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The effects of a step: they follow the save, a failed handoff keeps them, and log entries are best effort.
 */
class ProcessingLaneEffectTest extends ProcessingLaneCounterFixture {
    @Test
    void aFailedTransitionSaveBlocksEffectsAndReactionsAndRetryDoesNotRepeatTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var send = send("original-message");
        var start = new Action("start");
        var raised = new Message("raised");
        var entry = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Started"));
        var writtenLogs = new ArrayList<ProtocolLogMessage>();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start)
                ? new ProcessingOutput<>(List.of(send, entry), List.of(raised))
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
            assertEquals(List.of(raised), fixture.lastSaved().pendingInputs());
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
        var entry = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Incremented"));
        var writtenLogs = new ArrayList<ProtocolLogMessage>();
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(send, entry), List.of()));
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
        assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
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
    void failedInitialMessageHandoffStillProcessesTheFirstReactionAndQueuedMessages() throws Exception {
        var fixture = new Fixture();
        var first = new Message("first");
        var raised = new Message("raised");
        var waiting = new Message("waiting");
        var send = send("initial-message");
        var sendFailure = new IllegalStateException("Send unavailable");
        var handoffs = new AtomicInteger();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(first)
                ? new ProcessingOutput<>(List.of(send), List.of(raised))
                : emptyOutput());
        fixture.onEffect = effect -> {
            handoffs.incrementAndGet();
            throw sendFailure;
        };

        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(first));
        var early = fixture.lane.submit(waiting);
        fixture.executor.runAll();

        for (var submission : List.of(initialized, early)) {
            var pending = assertInstanceOf(SubmissionResult.Pending.class, await(submission));
            assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
            assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
            assertSame(sendFailure, pending.cause());
        }
        assertEquals(List.of(first, raised, waiting), fixture.attemptedInputs());
        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(send), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        // The queued message is its own submission, so it may retry the pending send once.
        assertEquals(2, handoffs.get());
    }

    @Test
    void sendFailuresAllowCommandsMessagesAndRaisedEventsWhileKeepingOutgoingFifo() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = send("first");
        var second = new SendMessage<>(new Payload("second"), new Payload("mediator"));
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
                return applied(snapshot, new ProcessingOutput<>(List.of(first), List.of(raised)));
            }
            if (work.input().equals(raised)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(second), List.of()));
            }
            if (work.input().equals(incoming)) {
                return applied(snapshot, new ProcessingOutput<>(List.of(third), List.of()));
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
            var applied = assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
            assertEquals(new Counter(expectedCount++, false), applied.snapshot());
            assertSame(sendFailure, pending.cause());
        }
        assertEquals(List.of(start, raised, archive, incoming), fixture.attemptedInputs());
        assertEquals(List.of(first, first, first), handoffs);
        assertTrue(fixture.executed.isEmpty());
        assertEquals(new Counter(4, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(first, second, third), fixture.lastSaved().pendingEffects());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());

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
        var observation = new Observation("deposit");
        var start = new Action("start");
        var raised = new Message("raised");
        var incoming = new Message("incoming");
        var sendFailure = new IllegalStateException("Send unavailable");
        var setupFailure = new IllegalStateException("Required effect unavailable");
        ProcessingOutput<CounterInput> output = timerFailure
                ? new ProcessingOutput<>(List.of(send), List.of(raised), List.of(timer))
                : new ProcessingOutput<>(List.of(send, observation), List.of(raised));
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(start) ? output : emptyOutput());
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
        assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
        assertEquals(List.of(start), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(timerFailure ? List.of(send) : List.of(send, observation), fixture.lastSaved().pendingEffects());
        assertEquals(timerFailure ? List.of(timer) : List.of(), fixture.lastSaved().scheduledInputs());
        assertEquals(List.of(raised, incoming), fixture.lastSaved().pendingInputs());

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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(timerFailure ? List.of(timer) : List.of(), fixture.lastSaved().scheduledInputs());
    }

    @Test
    void aFailedRemovalSaveDoesNotResendTheHandedOffEffectInTheRunningProcess() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = send("first");
        var second = send("second");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(first, second), List.of()));
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
    void logsShareTheEffectExecutorButRemainBestEffortWhenRequiredWorkFails() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var observation = new Observation("deposit");
        var first = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("First"));
        var second = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Second"));
        var attempts = new ArrayList<Effect>();
        var effectFailure = new IllegalStateException("Observer unavailable");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(first, observation, second), List.of()));
        fixture.onEffect = effect -> {
            attempts.add(effect);
            assertEquals(List.of(observation), fixture.lastSaved().pendingEffects());
            throw effectFailure;
        };
        fixture.onLog = entry -> {
            attempts.add(entry);
            assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
            if (entry.equals(first)) {
                throw new IllegalStateException("First log failed");
            }
        };

        var submission = fixture.lane.submit(new Action("observe"));
        fixture.executor.runAll();

        assertSame(effectFailure, failure(submission));
        assertEquals(List.of(observation, first, second), attempts);
        assertEquals(List.of(observation), fixture.lastSaved().pendingEffects());
        assertFalse(fixture.lane.isClosed());

        fixture.onEffect = attempts::add;
        var retry = fixture.lane.resume();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), awaitSnapshot(retry));
        assertEquals(List.of(observation, first, second, observation), attempts);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void logsAreBestEffortAndAreNotRetried() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var entry = new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Incremented"));
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(entry), List.of()));
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
}
