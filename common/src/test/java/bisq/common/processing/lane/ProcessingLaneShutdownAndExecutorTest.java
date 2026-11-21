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
import bisq.common.threading.ExecutorFactory;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shutdown of a lane, a rejecting executor, and how lanes share one queue and one worker pool.
 */
class ProcessingLaneShutdownAndExecutorTest extends ProcessingLaneCounterFixture {
    @Test
    void shutdownCancelsLiveTimersButKeepsTheirSavedDeadlines() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.initialize();
        fixture.handler = (snapshot, work) -> applied(snapshot, work.input().equals(new Action("start-timer"))
                ? new ProcessingOutput<>(List.of(), List.of(), List.of(timer))
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
    void shutdownAndExecutorRejectionDoNotLeaveFuturesUnresolved() throws Exception {
        var fixture = new Fixture();
        await(fixture.lane.shutdown());
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.lane.submit(new Message("late"))));

        var executorFailure = new RejectedExecutionException("Executor stopped");
        var rejectedLane = fixture.createLane(task -> { throw executorFailure; });
        assertSame(executorFailure, failure(rejectedLane.submit(new Message("early"))));
        assertTrue(rejectedLane.isClosed());
        await(rejectedLane.shutdown());
    }

    @Test
    void retryCancellationFailureCompletesShutdownWithoutRepeatingCleanup() throws Exception {
        var fixture = new Fixture();
        fixture.onSave = checkpoint -> { throw new IllegalStateException("Disk unavailable"); };
        // The router submits pending messages right behind the initializer.
        var initialized = fixture.lane.submit(new Initialize(new Message("first")));
        var queued = fixture.lane.submit(new Message("queued"));
        fixture.executor.runAll();
        assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        assertInstanceOf(SubmissionResult.Pending.class, await(queued));
        var cancellationFailure = new IllegalStateException("Retry cancellation failed");
        var retry = fixture.retryTasks.getFirst().future();
        when(retry.cancel(false)).thenThrow(cancellationFailure);

        var stopped = fixture.lane.shutdown();

        assertSame(cancellationFailure, failure(stopped));
        assertSame(cancellationFailure, failure(fixture.lane.shutdown()));
        assertTrue(fixture.lane.isClosed());
        verify(retry).cancel(false);
    }

    @Test
    void timerCleanupFailureCompletesShutdownAndLeavesSavedDeadlinesAvailableForRestart() throws Exception {
        var fixture = new Fixture();
        var timer = new Timer("timeout", NOW.plusSeconds(30), new Message("timeout"));
        fixture.startTimer(timer);
        var cancellationFailure = new IllegalStateException("Timer cancellation failed");
        var scheduled = fixture.timerTasks.getFirst();
        when(scheduled.future().cancel(false)).thenThrow(cancellationFailure);

        assertSame(cancellationFailure, failure(fixture.lane.shutdown()));
        assertSame(cancellationFailure, failure(fixture.lane.shutdown()));
        assertEquals(List.of(timer), fixture.lastSaved().scheduledInputs());
        var attempted = fixture.attemptedInputs();
        scheduled.task().run();
        fixture.executor.runAll();
        assertEquals(attempted, fixture.attemptedInputs());
        verify(scheduled.future()).cancel(false);
    }

    @Test
    void unexpectedRetrySchedulingFailureClosesTheLaneAndRejectsQueuedWorkInsteadOfLeavingItPending() throws Exception {
        var fixture = new Fixture();
        fixture.initialize();
        var message = send("retained");
        fixture.handler = (snapshot, work) -> applied(snapshot,
                new ProcessingOutput<>(List.of(message), List.of()));
        fixture.onEffect = effect -> { throw new IllegalStateException("Send unavailable"); };
        var schedulingFailure = new IllegalStateException("Retry scheduler failed");
        when(fixture.timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                .thenThrow(schedulingFailure);
        var first = fixture.lane.submit(new Action("send"));
        var queued = fixture.lane.submit(new Message("queued"));
        var publicationsBefore = fixture.published.size();

        fixture.executor.runAll();

        assertInstanceOf(SubmissionResult.Pending.class, await(first));
        // The scheduling failure closes the lane without publishing the unchanged snapshot a second time.
        assertEquals(publicationsBefore + 1, fixture.published.size());
        assertSame(schedulingFailure, failure(queued));
        assertTrue(fixture.lane.isClosed());
        assertEquals(List.of(new Action("send")), fixture.attemptedInputs());
        assertEquals(List.of(message), fixture.lastSaved().pendingEffects());
        await(fixture.lane.shutdown());
    }

    @Test
    void executorRejectionFailsRecoveryAndEveryQueuedSubmissionWithoutRunningSetup() throws Exception {
        try (var caller = ExecutorFactory.newSingleThreadExecutor("rejected-lane-caller")) {
            var fixture = new Fixture();
            var checkpoint = new Checkpoint<Counter, CounterInput>(new Counter(3, false),
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
            fixture.initialize(lane);
            fixture.handler = (snapshot, work) -> applied(snapshot,
                    new ProcessingOutput<>(List.of(), List.of(), List.of(timer)));
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
            first.initialize(lane);
            second.initialize(otherLane);
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

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
