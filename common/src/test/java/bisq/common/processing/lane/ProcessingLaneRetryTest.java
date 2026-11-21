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

import bisq.common.processing.EffectFailureRule;
import bisq.common.processing.HandleResult;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Automatic retries of a lane after a failed save, send or required effect.
 */
class ProcessingLaneRetryTest extends ProcessingLaneCounterFixture {
    @Test
    void inputsBehindAFailedInitialSendRetryItAndKeepTheAutomaticRetry() throws Exception {
        var fixture = new Fixture();
        var message = send("initial");
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(message), List.of()) : emptyOutput());
        var sendAttempts = new AtomicInteger();
        fixture.onEffect = effect -> {
            sendAttempts.incrementAndGet();
            throw new IllegalStateException("Send unavailable");
        };
        // The router submits pending inputs right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Action("start")));
        var first = fixture.lane.submit(new Message("first"));
        var second = fixture.lane.submit(new Message("second"));
        fixture.executor.runAll();

        assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        assertInstanceOf(SubmissionResult.Pending.class, await(first));
        assertInstanceOf(SubmissionResult.Pending.class, await(second));
        assertEquals(new Counter(3, false), fixture.lastSaved().snapshot());
        // Each queued submission may retry the pending send once. One automatic retry stays scheduled.
        assertEquals(3, sendAttempts.get());
        assertEquals(List.of(message), fixture.lastSaved().pendingEffects());
        assertEquals(1, fixture.retryTasks.size());

        fixture.onEffect = effect -> {};
        fixture.retryTasks.getFirst().task().run();
        fixture.executor.runAll();
        assertEquals(List.of(message), fixture.executed);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(3, fixture.attempted.size());
    }

    @Test
    void failedResultSaveRetriesAutomaticallyWithoutRepeatingTheHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.onSave = checkpoint -> { throw new IllegalStateException("Disk unavailable"); };
        var submission = fixture.lane.submit(new Action("increment"));
        fixture.executor.runAll();

        assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, fixture.retryTasks.size());
        fixture.onSave = checkpoint -> {};
        fixture.retryTasks.getFirst().task().run();
        // The timer enqueues recovery; it never runs persistence or handlers itself.
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(1, fixture.attempted.size());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(1, fixture.retryTasks.size());
    }

    @Test
    void failedReceptionSaveRetriesBeforeTheFirstHandler() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.onSave = checkpoint -> { throw new IllegalStateException("Disk unavailable"); };
        var input = new Message("received");
        var submission = fixture.lane.submit(input);
        fixture.executor.runAll();

        assertInstanceOf(SubmissionResult.Pending.class, await(submission));
        assertTrue(fixture.attempted.isEmpty());
        fixture.onSave = checkpoint -> {};
        fixture.handler = (snapshot, work) -> {
            assertEquals(List.of(input), fixture.lastSaved().pendingInputs());
            return applied(snapshot, emptyOutput());
        };
        fixture.retryTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertEquals(1, fixture.attempted.size());
    }

    @Test
    void aFailedBestEffortEffectIsDroppedWithoutStoppingLaterEffectsOrInputs() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var notice = new Notice("chat");
        var observation = new Observation("deposit");
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(notice, observation), List.of()) : emptyOutput());
        fixture.onEffect = effect -> {
            if (effect instanceof Notice) {
                throw new IllegalStateException("Chat unavailable");
            }
        };

        var started = fixture.lane.submit(new Action("start"));
        fixture.executor.runAll();
        assertInstanceOf(SubmissionResult.Completed.class, await(started));
        var received = fixture.lane.submit(new Message("peer-message"));
        fixture.executor.runAll();

        assertInstanceOf(SubmissionResult.Completed.class, await(received));
        assertEquals(List.of(observation), fixture.executed);
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertTrue(fixture.retryTasks.isEmpty());
    }

    @Test
    void failedObservationAllowsReceptionAndAutomaticallyResumesInputHandling() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var observation = new Observation("deposit");
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(observation), List.of()) : emptyOutput());
        fixture.onEffect = effect -> { throw new IllegalStateException("Observation unavailable"); };
        var start = fixture.lane.submit(new Action("observe"));
        fixture.executor.runAll();
        assertInstanceOf(SubmissionResult.Pending.class, await(start));

        var message = new Message("peer-message");
        var received = fixture.lane.submit(message);
        fixture.executor.runAll();
        var pending = assertInstanceOf(SubmissionResult.Pending.class, await(received));
        assertEquals(CoordinationPhase.OUTGOING_WORK, pending.phase());
        assertEquals(List.of(message), fixture.lastSaved().pendingInputs());
        assertEquals(1, fixture.attempted.size());
        assertEquals(1, fixture.retryTasks.size());

        fixture.onEffect = effect -> {};
        fixture.retryTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(List.of(observation), fixture.executed);
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertEquals(2, fixture.attempted.size());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void automaticSendRetryPreservesOrderWithoutBlockingIncomingMessagesOrLocalEffects() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var first = send("first");
        var second = send("second");
        var observation = new Observation("deposit");
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input() instanceof Action
                ? new ProcessingOutput<>(List.of(first, second, observation), List.of()) : emptyOutput());
        fixture.onEffect = effect -> {
            if (effect.equals(first)) {
                throw new IllegalStateException("Send unavailable");
            }
        };
        fixture.lane.submit(new Action("send"));
        fixture.executor.runAll();
        fixture.lane.submit(new Message("peer-message"));
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(observation), fixture.executed);
        assertEquals(List.of(first, second), fixture.lastSaved().pendingEffects());
        assertEquals(1, fixture.retryTasks.size());

        fixture.onEffect = effect -> {};
        fixture.retryTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertEquals(List.of(observation, first, second), fixture.executed);
        assertEquals(2, fixture.attempted.size());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
    }

    @Test
    void obsoleteRetryCannotInterfereWithALaterFailureAndShutdownCancelsRetries() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var observation = new Observation("deposit");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(observation), List.of()));
        fixture.onEffect = effect -> { throw new IllegalStateException("Observation unavailable"); };
        fixture.lane.submit(new Action("first"));
        fixture.executor.runAll();
        var obsolete = fixture.retryTasks.getFirst();

        fixture.onEffect = effect -> {};
        var resumed = fixture.lane.resume();
        fixture.executor.runAll();
        await(resumed);
        verify(obsolete.future()).cancel(false);

        fixture.onEffect = effect -> { throw new IllegalStateException("Observation unavailable again"); };
        fixture.lane.submit(new Action("second"));
        fixture.executor.runAll();
        var current = fixture.retryTasks.getLast();
        obsolete.task().run();
        fixture.executor.runAll();
        verify(current.future(), never()).cancel(false);
        assertEquals(2, fixture.retryTasks.size());
        assertEquals(1, fixture.executed.size());

        await(fixture.lane.shutdown());
        verify(current.future()).cancel(false);
        current.task().run();
        fixture.executor.runAll();
        assertEquals(1, fixture.executed.size());
        assertEquals(List.of(observation), fixture.lastSaved().pendingEffects());
    }

    @Test
    void deferredBusinessInputDoesNotScheduleInfrastructureRetries() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        fixture.handler = (snapshot, work) -> new HandleResult.Deferred<>();
        var input = new Message("too-early");
        var submission = fixture.lane.submit(input);
        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(submission));
        assertEquals(List.of(input), fixture.lastSaved().pendingInputs());
        assertEquals(1, fixture.attempted.size());
        assertTrue(fixture.retryTasks.isEmpty());
    }

    private record Notice(String id) implements TestEffect {
        @Override
        public EffectFailureRule failureRule() {
            return EffectFailureRule.BEST_EFFORT;
        }
    }
}
