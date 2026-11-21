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

import bisq.common.processing.ProcessingOutput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * How a lane pauses the pending inputs which its definition does not allow now, and how it continues with them.
 */
class ProcessingLanePauseTest extends ProcessingLaneCounterFixture {
    @Test
    void aPausedMessageStaysSavedWhileTheLaneHandlesItsOtherInputs() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var paused = new Message("paused");
        var other = new Message("other");
        fixture.pauseRule = paused::equals;

        var submitted = fixture.lane.submit(paused);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(submitted));
        assertEquals(List.of(paused), fixture.lastSaved().pendingInputs());
        assertTrue(fixture.attempted.isEmpty());

        var handled = fixture.lane.submit(other);
        fixture.executor.runAll();
        assertInstanceOf(ProcessingSummary.Applied.class, awaitProcessingSummary(handled));
        assertEquals(List.of(other), fixture.attemptedInputs());
        assertEquals(List.of(paused), fixture.lastSaved().pendingInputs());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
    }

    @Test
    void resumingHandlesAPausedInputWhichIsAllowedNow() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var paused = new Message("paused");
        fixture.pauseRule = paused::equals;
        fixture.lane.submit(paused);
        fixture.executor.runAll();
        fixture.pauseRule = input -> false;

        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertTrue(await(resumed));
        assertEquals(List.of(paused), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
    }

    @Test
    void resumingChangesNothingWhileTheInputIsStillPaused() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var paused = new Message("paused");
        fixture.pauseRule = paused::equals;
        fixture.lane.submit(paused);
        fixture.executor.runAll();
        int saves = fixture.saved.size();

        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertFalse(await(resumed));
        assertEquals(saves, fixture.saved.size());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(List.of(paused), fixture.lastSaved().pendingInputs());
    }

    @Test
    void whileARetryIsPendingResumingRepeatsNoSendAndTheRetryHandlesTheAllowedInput() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var paused = new Message("paused");
        fixture.pauseRule = paused::equals;
        var sendAttempts = new AtomicInteger();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(paused)
                ? emptyOutput()
                : new ProcessingOutput<>(List.of(send("message")), List.of()));
        fixture.onEffect = effect -> {
            sendAttempts.incrementAndGet();
            throw new IllegalStateException("Sender unavailable");
        };
        fixture.lane.submit(new Message("sends"));
        fixture.executor.runAll();
        fixture.lane.submit(paused);
        fixture.executor.runAll();
        fixture.pauseRule = input -> false;
        int attempts = sendAttempts.get();
        int saves = fixture.saved.size();

        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertFalse(await(resumed));
        assertEquals(attempts, sendAttempts.get());
        assertEquals(saves, fixture.saved.size());
        assertFalse(fixture.attemptedInputs().contains(paused));

        fixture.retryTasks.getLast().task().run();
        fixture.executor.runAll();

        assertEquals(attempts + 1, sendAttempts.get());
        assertTrue(fixture.attemptedInputs().contains(paused));
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void resumingDoesNotRepeatAFailedSetupWhichARetryWaitsFor() throws Exception {
        var fixture = new Fixture();
        var pending = new Message("pending");
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(5, false),
                List.of(pending),
                List.of(),
                List.of());
        var setupAttempts = new AtomicInteger();
        var setupFailure = new IllegalStateException("Setup failed");
        fixture.onSetup = snapshot -> {
            setupAttempts.incrementAndGet();
            throw setupFailure;
        };
        var lane = fixture.restoreLane(checkpoint, fixture.executor);
        var recovered = lane.resume();
        fixture.executor.runAll();
        assertSame(setupFailure, failure(recovered));
        assertEquals(1, setupAttempts.get());

        var resumed = lane.resumePausedInputs();
        fixture.executor.runAll();

        assertFalse(await(resumed));
        assertEquals(1, setupAttempts.get());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSetup = snapshot -> setupAttempts.incrementAndGet();
        fixture.retryTasks.getLast().task().run();
        fixture.executor.runAll();

        assertEquals(2, setupAttempts.get());
        assertEquals(List.of(pending), fixture.attemptedInputs());
    }

    @Test
    void theNextSubmissionHandlesAnAllowedInputFirstWithoutAResume() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var paused = new Message("paused");
        var next = new Message("next");
        fixture.pauseRule = paused::equals;
        fixture.lane.submit(paused);
        fixture.executor.runAll();
        fixture.pauseRule = input -> false;

        var submitted = fixture.lane.submit(next);
        fixture.executor.runAll();

        assertEquals(new Counter(2, false), awaitSnapshot(submitted));
        assertEquals(List.of(paused, next), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void aDueTimerWaitsAsSavedPendingInputWhileItsInputIsPaused() throws Exception {
        var fixture = new Fixture();
        var timeout = new Notification();
        fixture.startTimer(new Timer("timeout", NOW.plusSeconds(60), timeout));
        fixture.attempted.clear();
        fixture.pauseRule = timeout::equals;

        fixture.timerTasks.getFirst().task().run();
        fixture.executor.runAll();

        assertTrue(fixture.attempted.isEmpty());
        assertTrue(fixture.lastSaved().scheduledInputs().isEmpty());
        assertEquals(List.of(timeout), fixture.lastSaved().pendingInputs());

        fixture.pauseRule = input -> false;
        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertTrue(await(resumed));
        assertEquals(List.of(timeout), fixture.attemptedInputs());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(new Counter(2, false), fixture.lastSaved().snapshot());
    }

    @Test
    void aRestoredInputWaitsWhileItIsPausedAndRunsWhenItIsAllowed() throws Exception {
        var fixture = new Fixture();
        var paused = new Message("paused");
        var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(5, false),
                List.of(paused),
                List.of(),
                List.of());
        fixture.pauseRule = paused::equals;
        var lane = fixture.restoreLane(checkpoint, fixture.executor);

        var recovered = lane.resume();
        fixture.executor.runAll();
        assertEquals(new Counter(5, false), await(recovered));
        assertTrue(fixture.attempted.isEmpty());
        assertTrue(fixture.saved.isEmpty());

        fixture.pauseRule = input -> false;
        var resumed = lane.resumePausedInputs();
        fixture.executor.runAll();

        assertTrue(await(resumed));
        assertEquals(List.of(paused), fixture.attemptedInputs());
        assertEquals(new Counter(6, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    // The first input of a new entity is saved before its handler, also if its class is a command.
    @Test
    void aPausedFirstInputIsSavedWithTheNewEntityAndRunsWhenItIsAllowed() throws Exception {
        var fixture = new Fixture();
        var first = new Action("first");
        fixture.pauseRule = first::equals;

        var initialized = fixture.lane.submit(new Initialize(first));
        fixture.executor.runAll();

        assertInstanceOf(ProcessingSummary.Deferred.class, awaitProcessingSummary(initialized));
        assertEquals(new Counter(0, false), fixture.lastSaved().snapshot());
        assertEquals(List.of(first), fixture.lastSaved().pendingInputs());
        assertTrue(fixture.attempted.isEmpty());

        fixture.pauseRule = input -> false;
        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertTrue(await(resumed));
        assertEquals(List.of(first), fixture.attemptedInputs());
        assertEquals(new Counter(1, false), fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void resumingDoesNothingForALaneWhoseInitializerHasNotRun() throws Exception {
        var fixture = new Fixture();

        var resumed = fixture.lane.resumePausedInputs();
        fixture.executor.runAll();

        assertFalse(await(resumed));
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.published.isEmpty());
        assertFalse(fixture.lane.isClosed());
    }
}
