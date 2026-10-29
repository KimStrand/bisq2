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

import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.MockClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class InputSchedulerTest {
    private static final Instant NOW = Instant.parse("2026-09-22T12:00:00Z");

    private final ScheduledExecutorService timers = mock(ScheduledExecutorService.class);
    private final List<TimerTask> timerTasks = new ArrayList<>();
    private final ArrayDeque<Runnable> inbound = new ArrayDeque<>();
    private final List<String> received = new ArrayList<>();
    private final MockClock clock = new MockClock();
    private final InputScheduler<TestScheduledInput> scheduler =
            new InputScheduler<>(timers,
                    inbound::addLast,
                    scheduled -> received.add(scheduled.input().value()),
                    clock);

    @BeforeEach
    void captureTimers() {
        clock.setTime(NOW.toEpochMilli());
        when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    ScheduledFuture<?> future = mock(ScheduledFuture.class);
                    timerTasks.add(new TimerTask(invocation.getArgument(0), invocation.getArgument(1), future));
                    return future;
                });
    }

    @Test
    void expiryQueuesTheContainedInputAndDeliversItOnlyOnceOnTheOwner() {
        scheduler.schedule(new TestScheduledInput("timeout", NOW.plusSeconds(30), new TestInput("expired")));
        assertEquals(Duration.ofSeconds(30).toNanos(), timerTasks.getFirst().delayNanos());

        timerTasks.getFirst().task().run();

        assertTrue(received.isEmpty());
        assertEquals(1, inbound.size());
        inbound.removeFirst().run();
        assertEquals(List.of("expired"), received);
        timerTasks.getFirst().task().run();
        inbound.removeFirst().run();
        assertEquals(List.of("expired"), received);
    }

    @Test
    void newlyScheduledInputsUseTheCurrentClockTime() {
        var scheduled = new TestScheduledInput("timeout", NOW.plusSeconds(30), new TestInput("expired"));
        scheduler.schedule(scheduled);
        assertEquals(Duration.ofSeconds(30).toNanos(), timerTasks.getLast().delayNanos());

        clock.advanceTime(15_000);
        scheduler.schedule(scheduled);

        assertEquals(Duration.ofSeconds(15).toNanos(), timerTasks.getLast().delayNanos());
    }

    @Test
    void restoringTheSameWrapperPreservesItsAbsoluteDeadline() {
        var saved = new TestScheduledInput("timeout",
                NOW.plus(Duration.ofHours(24)).plusNanos(123_456_789),
                new TestInput("expired"));
        scheduler.schedule(saved);
        scheduler.close();

        try (var restored = schedulerAt(NOW.plus(Duration.ofHours(23)))) {
            restored.schedule(saved);
            assertEquals(Duration.ofHours(1).plusNanos(123_456_789).toNanos(), timerTasks.getLast().delayNanos());
            timerTasks.getLast().task().run();
            inbound.removeFirst().run();
            assertEquals(List.of("expired"), received);
        }
    }

    @Test
    void overdueAndCurrentlyDueInputsAreQueuedWithoutWaiting() {
        scheduler.schedule(new TestScheduledInput("overdue", NOW.minusSeconds(10), new TestInput("overdue")));
        scheduler.schedule(new TestScheduledInput("due", NOW, new TestInput("due")));

        assertTrue(timerTasks.stream().allMatch(task -> task.delayNanos() == 0));
        assertTrue(received.isEmpty());
        timerTasks.forEach(task -> task.task().run());
        while (!inbound.isEmpty()) {
            inbound.removeFirst().run();
        }
        assertEquals(List.of("overdue", "due"), received);
    }

    @Test
    void cancellationInvalidatesAQueuedCallbackAndDoesNotRequireATimerToExist() {
        scheduler.schedule(new TestScheduledInput("timeout", NOW.plusSeconds(30), new TestInput("expired")));
        timerTasks.getFirst().task().run();

        scheduler.cancel("timeout");
        scheduler.cancel("timeout");
        inbound.removeFirst().run();

        assertTrue(received.isEmpty());
        verify(timerTasks.getFirst().future()).cancel(false);
    }

    @Test
    void replacingATimerInvalidatesItsAlreadyQueuedCallback() {
        scheduler.schedule(new TestScheduledInput("timeout", NOW.plusSeconds(30), new TestInput("old")));
        timerTasks.getFirst().task().run();

        scheduler.schedule(new TestScheduledInput("timeout", NOW.plusSeconds(60), new TestInput("replacement")));
        timerTasks.getLast().task().run();
        while (!inbound.isEmpty()) {
            inbound.removeFirst().run();
        }

        assertEquals(List.of("replacement"), received);
        verify(timerTasks.getFirst().future()).cancel(false);
    }

    @Test
    void timerIdsAreScopedToTheirOwner() {
        var otherReceived = new ArrayList<String>();
        try (var other = new InputScheduler<TestScheduledInput>(timers,
                inbound::addLast,
                scheduled -> otherReceived.add(scheduled.input().value()),
                clock)) {
            scheduler.schedule(new TestScheduledInput("timeout", NOW, new TestInput("first")));
            other.schedule(new TestScheduledInput("timeout", NOW, new TestInput("second")));

            scheduler.cancel("timeout");
            timerTasks.forEach(task -> task.task().run());
            while (!inbound.isEmpty()) {
                inbound.removeFirst().run();
            }

            assertTrue(received.isEmpty());
            assertEquals(List.of("second"), otherReceived);
        }
    }

    @Test
    void aRejectedReplacementKeepsThePreviousTimer() {
        scheduler.schedule(new TestScheduledInput("timeout", NOW.plusSeconds(30), new TestInput("original")));
        when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenThrow(new RejectedExecutionException("timer executor stopped"));

        assertThrows(RejectedExecutionException.class,
                () -> scheduler.schedule(new TestScheduledInput("timeout",
                        NOW.plusSeconds(60),
                        new TestInput("replacement"))));
        timerTasks.getFirst().task().run();
        inbound.removeFirst().run();

        assertEquals(List.of("original"), received);
        verify(timerTasks.getFirst().future(), never()).cancel(false);
    }

    @Test
    void closingInvalidatesPendingCallbacksWithoutStoppingTheSharedExecutor() {
        scheduler.schedule(new TestScheduledInput("first", NOW, new TestInput("first")));
        scheduler.schedule(new TestScheduledInput("second", NOW, new TestInput("second")));
        timerTasks.getFirst().task().run();

        scheduler.close();
        scheduler.close();
        timerTasks.getLast().task().run();
        while (!inbound.isEmpty()) {
            inbound.removeFirst().run();
        }

        assertTrue(received.isEmpty());
        timerTasks.forEach(task -> verify(task.future()).cancel(false));
        verify(timers, never()).shutdown();
        verify(timers, never()).shutdownNow();
        assertThrows(IllegalStateException.class,
                () -> scheduler.schedule(new TestScheduledInput("closed", NOW, new TestInput("closed"))));
    }

    @Test
    void aRealTimerExecutorQueuesAnOverdueInput() throws Exception {
        var queue = new LinkedBlockingQueue<Runnable>();
        try (var timerExecutor = ExecutorFactory.newSingleThreadScheduledExecutor("scheduled-input-test");
             var realScheduler = new InputScheduler<TestScheduledInput>(timerExecutor,
                     queue::add,
                     scheduled -> received.add(scheduled.input().value()))) {
            realScheduler.schedule(new TestScheduledInput("overdue", Instant.EPOCH, new TestInput("expired")));

            Runnable delivery = queue.poll(10, TimeUnit.SECONDS);
            assertNotNull(delivery);
            assertTrue(received.isEmpty());
            delivery.run();
            assertEquals(List.of("expired"), received);
        }
    }

    private InputScheduler<TestScheduledInput> schedulerAt(Instant now) {
        MockClock restoredClock = new MockClock();
        restoredClock.setTime(now.toEpochMilli());
        return new InputScheduler<>(timers,
                inbound::addLast,
                scheduled -> received.add(scheduled.input().value()),
                restoredClock);
    }

    private record TestInput(String value) implements Input {
    }

    private record TestScheduledInput(String id, Instant triggerAt, TestInput input) implements ScheduledInput<TestInput> {
    }

    private record TimerTask(Runnable task, long delayNanos, ScheduledFuture<?> future) {
    }
}
