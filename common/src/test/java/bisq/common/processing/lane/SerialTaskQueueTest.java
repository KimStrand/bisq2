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

import bisq.common.threading.ExecutorFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SerialTaskQueueTest {
    @Test
    void preservesAcceptanceOrderAndQueuesReentrantSubmissionsBehindAcceptedWork() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var order = new ArrayList<String>();
        var reentrant = new TestTask(() -> order.add("reentrant"));
        var first = new TestTask(() -> {
            order.add("first-start");
            fixture.queue.submit(reentrant);
            order.add("first-end");
        });
        var second = new TestTask(() -> order.add("second"));

        fixture.queue.submit(first);
        fixture.queue.submit(second);

        assertTrue(order.isEmpty());
        assertEquals(1, executor.workers.size());
        executor.runAll();
        for (var task : List.of(first, second, reentrant)) {
            await(task.completion);
            assertEquals(1, task.runs.get());
            assertEquals(0, task.rejections.get());
        }
        assertEquals(List.of("first-start", "first-end", "second", "reentrant"), order);
        assertFalse(fixture.queue.isClosed());

        var later = new TestTask(() -> order.add("later"));
        fixture.queue.submit(later);
        assertEquals(1, executor.workers.size());
        executor.runAll();
        await(later.completion);
        await(fixture.queue.shutdown());
    }

    @Test
    void aTaskMayLeaveItsSubmissionPendingWhileTheQueueContinues() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var submission = new CompletableFuture<String>();
        var stashed = new TestTask(() -> {});
        fixture.queue.submit(stashed);
        executor.runAll();

        await(stashed.completion);
        assertFalse(submission.isDone());

        var initialized = new TestTask(() -> submission.complete("saved"));
        fixture.queue.submit(initialized);
        executor.runAll();

        await(initialized.completion);
        assertEquals("saved", await(submission));
        await(fixture.queue.shutdown());
    }

    @Test
    void shutdownDrainsAcceptedWorkAndCancellationDoesNotCancelOtherObservers() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var order = new ArrayList<String>();
        var first = new TestTask(() -> order.add("first"));
        var second = new TestTask(() -> order.add("second"));
        fixture.onStopped = () -> order.add("stopped");
        fixture.queue.submit(first);
        fixture.queue.submit(second);

        var cancelled = fixture.queue.shutdown();
        var stopped = fixture.queue.shutdown();
        assertNotSame(cancelled, stopped);
        assertTrue(cancelled.cancel(false));
        assertFalse(stopped.isDone());
        assertTrue(fixture.queue.isClosed());
        var late = new TestTask(() -> {});
        fixture.queue.submit(late);
        assertInstanceOf(RejectedExecutionException.class, failure(late.completion));
        assertEquals(0, late.runs.get());
        assertEquals(1, late.rejections.get());

        executor.runAll();

        await(first.completion);
        await(second.completion);
        await(stopped);
        await(fixture.queue.shutdown());
        assertTrue(cancelled.isCancelled());
        assertEquals(List.of("first", "second", "stopped"), order);
        assertEquals(1, fixture.stopCalls.get());
        assertTrue(fixture.aborts.isEmpty());
    }

    @Test
    void shutdownRequestedInsideATaskWaitsForThatTaskAndPreviouslyAcceptedWork() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var shutdown = new AtomicReference<CompletableFuture<Void>>();
        var first = new TestTask(() -> {
            shutdown.set(fixture.queue.shutdown());
            assertFalse(shutdown.get().isDone());
            assertEquals(0, fixture.stopCalls.get());
        });
        var second = new TestTask(() -> assertFalse(shutdown.get().isDone()));
        fixture.queue.submit(first);
        fixture.queue.submit(second);

        executor.runAll();

        await(first.completion);
        await(second.completion);
        await(shutdown.get());
        assertEquals(1, fixture.stopCalls.get());
    }

    @Test
    void idleShutdownCallsCleanupOnceEvenWhenCleanupRequestsShutdownAgain() throws Exception {
        var fixture = new Fixture(new ManualExecutor());
        var nestedShutdown = new AtomicReference<CompletableFuture<Void>>();
        fixture.onStopped = () -> {
            assertTrue(fixture.queue.isClosed());
            nestedShutdown.set(fixture.queue.shutdown());
            assertFalse(nestedShutdown.get().isDone());
            assertThrows(IllegalStateException.class, () -> fixture.queue.abort(new IllegalStateException()));
        };

        await(fixture.queue.shutdown());
        await(nestedShutdown.get());
        await(fixture.queue.shutdown());

        assertEquals(1, fixture.stopCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortNotifiesOnceBeforeRejectingTasksAndCanEscalateGracefulShutdown(boolean alreadyClosing) throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var cause = new IllegalStateException("Cannot continue");
        var order = new ArrayList<String>();
        var nestedShutdown = new AtomicReference<CompletableFuture<Void>>();
        var rejected = new TestTask(() -> {});
        rejected.onRejected = failure -> order.add("rejected");
        fixture.onAborted = failure -> {
            order.add("aborted");
            assertFalse(rejected.completion.isDone());
            nestedShutdown.set(fixture.queue.shutdown());
            assertFalse(nestedShutdown.get().isDone());
            fixture.queue.abort(new IllegalStateException("Repeated abort from notification"));
        };
        fixture.onStopped = () -> order.add("stopped");
        var running = new TestTask(() -> {
            order.add("start");
            fixture.queue.abort(cause);
            fixture.queue.abort(new IllegalStateException("Repeated abort from task"));
            assertFalse(nestedShutdown.get().isDone());
            order.add("end");
        });
        fixture.queue.submit(running);
        fixture.queue.submit(rejected);
        if (alreadyClosing) {
            assertFalse(fixture.queue.shutdown().isDone());
        }

        executor.runAll();

        await(running.completion);
        await(nestedShutdown.get());
        await(fixture.queue.shutdown());
        assertSame(cause, failure(rejected.completion));
        assertEquals(0, running.rejections.get());
        assertEquals(0, rejected.runs.get());
        assertEquals(1, rejected.rejections.get());
        assertEquals(List.of(cause), fixture.aborts);
        assertEquals(0, cause.getSuppressed().length);
        assertEquals(List.of("start", "aborted", "rejected", "end", "stopped"), order);

        var late = new TestTask(() -> {});
        fixture.queue.submit(late);
        assertInstanceOf(RejectedExecutionException.class, failure(late.completion));
        assertNotSame(cause, failure(late.completion));
    }

    @Test
    void abortRequiresTheRunningTaskOfThisQueueAndStopsBeingAllowedDuringCleanup() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var other = new Fixture(executor);
        var cause = new IllegalStateException("Abort");
        assertThrows(IllegalStateException.class, () -> fixture.queue.abort(cause));
        assertFalse(fixture.queue.isClosed());
        var task = new TestTask(() -> {
            assertThrows(IllegalStateException.class, () -> other.queue.abort(cause));
            fixture.queue.abort(cause);
        });
        fixture.onStopped = () -> assertThrows(IllegalStateException.class, () -> fixture.queue.abort(cause));
        fixture.queue.submit(task);

        executor.runAll();

        await(task.completion);
        await(fixture.queue.shutdown());
        assertFalse(other.queue.isClosed());
        assertTrue(other.aborts.isEmpty());
        await(other.queue.shutdown());
    }

    @ParameterizedTest
    @MethodSource("executorFailures")
    void executorFailurePreservesTheCauseAndCompletesShutdownWithoutRunningTheTask(Throwable cause) throws Exception {
        var fixture = new Fixture(worker -> throwFailure(cause));
        var order = new ArrayList<String>();
        var task = new TestTask(() -> {});
        task.onRejected = failure -> order.add("rejected");
        fixture.onAborted = failure -> {
            assertFalse(task.completion.isDone());
            order.add("aborted");
        };
        fixture.onStopped = () -> order.add("stopped");

        fixture.queue.submit(task);

        assertSame(cause, failure(task.completion));
        assertEquals(List.of(cause), fixture.aborts);
        assertEquals(0, cause.getSuppressed().length);
        assertEquals(0, task.runs.get());
        assertEquals(1, task.rejections.get());
        assertEquals(List.of("aborted", "rejected", "stopped"), order);
        await(fixture.queue.shutdown());
        assertEquals(1, fixture.stopCalls.get());

        var late = new TestTask(() -> {});
        fixture.queue.submit(late);
        assertInstanceOf(RejectedExecutionException.class, failure(late.completion));
        assertEquals(0, late.runs.get());
        assertEquals(1, late.rejections.get());
        assertEquals(1, fixture.stopCalls.get());
    }

    @ParameterizedTest
    @MethodSource("executorFailures")
    void concurrentShutdownWaitsForExecutorFailureAndAbortNotification(Throwable cause) throws Exception {
        var scheduling = new CountDownLatch(1);
        var rejectWorker = new CountDownLatch(1);
        var notifying = new CountDownLatch(1);
        var finishNotification = new CountDownLatch(1);
        var fixture = new Fixture(worker -> {
            scheduling.countDown();
            awaitLatch(rejectWorker);
            throwFailure(cause);
        });
        fixture.onAborted = failure -> {
            notifying.countDown();
            awaitLatch(finishNotification);
        };
        var first = new TestTask(() -> {});
        var second = new TestTask(() -> {});
        try (var caller = ExecutorFactory.newSingleThreadExecutor("queue-rejection-caller")) {
            var submitting = CompletableFuture.runAsync(() -> fixture.queue.submit(first), caller);
            try {
                awaitLatch(scheduling);
                fixture.queue.submit(second);
                var stopped = fixture.queue.shutdown();
                assertFalse(stopped.isDone());
                rejectWorker.countDown();
                awaitLatch(notifying);

                var anotherObserver = fixture.queue.shutdown();
                assertFalse(anotherObserver.isDone());
                assertFalse(first.completion.isDone());
                assertFalse(second.completion.isDone());
                assertEquals(0, fixture.stopCalls.get());
                finishNotification.countDown();

                await(submitting);
                await(stopped);
                await(anotherObserver);
                assertSame(cause, failure(first.completion));
                assertSame(cause, failure(second.completion));
                assertEquals(0, first.runs.get());
                assertEquals(0, second.runs.get());
                assertEquals(1, first.rejections.get());
                assertEquals(1, second.rejections.get());
                assertEquals(List.of(cause), fixture.aborts);
                assertEquals(0, cause.getSuppressed().length);
                assertEquals(1, fixture.stopCalls.get());
            } finally {
                rejectWorker.countDown();
                finishNotification.countDown();
                await(submitting);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void abortListenerFailureDoesNotPreventRejectionAndAvoidsSelfSuppression(boolean sameFailure) throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var cause = new IllegalStateException("Abort");
        Throwable notificationFailure = sameFailure ? cause : new AssertionError("Notification failed");
        fixture.onAborted = failure -> throwFailure(notificationFailure);
        var first = new TestTask(() -> fixture.queue.abort(cause));
        var second = new TestTask(() -> {});
        var third = new TestTask(() -> {});
        fixture.queue.submit(first);
        fixture.queue.submit(second);
        fixture.queue.submit(third);

        executor.runAll();

        await(first.completion);
        assertSame(cause, failure(second.completion));
        assertSame(cause, failure(third.completion));
        assertEquals(sameFailure ? List.of() : List.of(notificationFailure), List.of(cause.getSuppressed()));
        await(fixture.queue.shutdown());
        assertEquals(1, fixture.stopCalls.get());
    }

    @Test
    void rejectionCallbackFailureDoesNotStrandOtherTasksOrShutdown() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var cause = new IllegalStateException("Abort");
        var notificationFailure = new IllegalStateException("Cannot notify caller");
        var first = new TestTask(() -> fixture.queue.abort(cause));
        var second = new TestTask(() -> {});
        second.onRejected = failure -> { throw notificationFailure; };
        var third = new TestTask(() -> {});
        fixture.queue.submit(first);
        fixture.queue.submit(second);
        fixture.queue.submit(third);

        executor.runAll();

        await(first.completion);
        assertSame(cause, failure(second.completion));
        assertSame(cause, failure(third.completion));
        assertEquals(List.of(notificationFailure), List.of(cause.getSuppressed()));
        await(fixture.queue.shutdown());
        assertEquals(1, fixture.stopCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopCallbackFailureCompletesAllShutdownObserversExceptionally(boolean error) throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        Throwable cause = error ? new AssertionError("Stop failed") : new IllegalStateException("Stop failed");
        fixture.onStopped = () -> throwFailure(cause);
        var task = new TestTask(() -> {});
        fixture.queue.submit(task);
        var first = fixture.queue.shutdown();
        var second = fixture.queue.shutdown();

        executor.runAll();

        await(task.completion);
        assertSame(cause, failure(first));
        assertSame(cause, failure(second));
        assertSame(cause, failure(fixture.queue.shutdown()));
        assertEquals(1, fixture.stopCalls.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEscapingTaskFailureAbortsWithoutRejectingTheStartedTask(boolean error) throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        Throwable cause = error ? new AssertionError("Task failed") : new IllegalStateException("Task failed");
        var running = new TestTask(() -> throwFailure(cause));
        var queued = new TestTask(() -> {});
        fixture.queue.submit(running);
        fixture.queue.submit(queued);

        executor.runAll();

        assertSame(cause, failure(running.completion));
        assertSame(cause, failure(queued.completion));
        assertEquals(1, running.runs.get());
        assertEquals(0, running.rejections.get());
        assertEquals(0, queued.runs.get());
        assertEquals(1, queued.rejections.get());
        assertEquals(List.of(cause), fixture.aborts);
        await(fixture.queue.shutdown());
        assertEquals(1, fixture.stopCalls.get());
    }

    @Test
    void aFailureAfterAbortDoesNotRepeatAbortNotificationOrReplaceTheOriginalCause() throws Exception {
        var executor = new ManualExecutor();
        var fixture = new Fixture(executor);
        var abortCause = new IllegalStateException("Abort");
        var escaped = new IllegalStateException("Finalization failed");
        var running = new TestTask(() -> {
            fixture.queue.abort(abortCause);
            throw escaped;
        });
        var queued = new TestTask(() -> {});
        fixture.queue.submit(running);
        fixture.queue.submit(queued);

        executor.runAll();

        assertSame(escaped, failure(running.completion));
        assertSame(abortCause, failure(queued.completion));
        assertEquals(0, running.rejections.get());
        assertEquals(1, queued.rejections.get());
        assertEquals(List.of(abortCause), fixture.aborts);
        await(fixture.queue.shutdown());
        assertEquals(1, fixture.stopCalls.get());
    }

    @Test
    void concurrentIdleShutdownDoesNotRepeatOrOverlapCleanupAndCallbacksDoNotHoldTheQueueLock() throws Exception {
        var fixture = new Fixture(new ManualExecutor());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.onStopped = () -> {
            entered.countDown();
            awaitLatch(release);
        };
        try (var callers = ExecutorFactory.newFixedThreadPool("queue-shutdown-callers", 2)) {
            var firstCall = CompletableFuture.supplyAsync(fixture.queue::shutdown, callers);
            try {
                awaitLatch(entered);
                var second = await(CompletableFuture.supplyAsync(fixture.queue::shutdown, callers));
                assertFalse(second.isDone());
                var late = new TestTask(() -> {});
                await(CompletableFuture.runAsync(() -> fixture.queue.submit(late), callers));
                assertInstanceOf(RejectedExecutionException.class, failure(late.completion));
                assertEquals(1, fixture.stopCalls.get());
                release.countDown();

                await(await(firstCall));
                await(second);
                assertEquals(1, fixture.stopCalls.get());
            } finally {
                release.countDown();
                await(await(firstCall));
            }
        }
    }

    @Test
    void aSharedPoolSerializesConcurrentSubmissionsWhileAnotherQueueProgresses() throws Exception {
        try (var workers = ExecutorFactory.newFixedThreadPool("serial-queue-workers", 2);
             var callers = ExecutorFactory.newFixedThreadPool("serial-queue-callers", 4)) {
            var fixture = new Fixture(workers);
            var other = new Fixture(workers);
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var active = new AtomicInteger();
            var completed = new AtomicInteger();
            var running = new TestTask(() -> {
                assertEquals(1, active.incrementAndGet());
                try {
                    entered.countDown();
                    awaitLatch(release);
                    completed.incrementAndGet();
                } finally {
                    active.decrementAndGet();
                }
            });
            fixture.queue.submit(running);
            try {
                awaitLatch(entered);
                assertThrows(IllegalStateException.class, () -> fixture.queue.abort(new IllegalStateException()));
                var otherTask = new TestTask(() -> {});
                other.queue.submit(otherTask);
                await(otherTask.completion);

                var tasks = new ArrayList<TestTask>();
                var submissions = new ArrayList<CompletableFuture<Void>>();
                for (int i = 0; i < 30; i++) {
                    var task = new TestTask(() -> {
                        assertEquals(1, active.incrementAndGet());
                        try {
                            completed.incrementAndGet();
                        } finally {
                            active.decrementAndGet();
                        }
                    });
                    tasks.add(task);
                    submissions.add(CompletableFuture.runAsync(() -> fixture.queue.submit(task), callers));
                }
                for (var submission : submissions) {
                    await(submission);
                }
                assertTrue(tasks.stream().noneMatch(task -> task.completion.isDone()));
                var stopped = fixture.queue.shutdown();
                assertFalse(stopped.isDone());
                release.countDown();

                await(running.completion);
                for (var task : tasks) {
                    await(task.completion);
                    assertEquals(1, task.runs.get());
                }
                await(stopped);
                assertEquals(31, completed.get());
                assertEquals(0, active.get());
                assertEquals(1, fixture.stopCalls.get());
                assertTrue(fixture.aborts.isEmpty());
                assertEquals("usable", await(CompletableFuture.supplyAsync(() -> "usable", workers)));
            } finally {
                release.countDown();
                await(fixture.queue.shutdown());
                await(other.queue.shutdown());
            }
        }
    }

    private static Stream<Throwable> executorFailures() {
        return Stream.of(
                new RejectedExecutionException("Executor stopped"),
                new IllegalStateException("Worker scheduling failed"),
                new OutOfMemoryError("unable to create native thread"));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static Throwable failure(CompletableFuture<?> future) {
        return assertThrows(ExecutionException.class, () -> await(future)).getCause();
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for the other thread");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw (Error) failure;
    }

    private static final class TestTask implements SerialTaskQueue.Task {
        private final Runnable action;
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private final AtomicInteger runs = new AtomicInteger();
        private final AtomicInteger rejections = new AtomicInteger();
        private Consumer<Throwable> onRejected = failure -> {};

        private TestTask(Runnable action) {
            this.action = action;
        }

        @Override
        public void run() {
            runs.incrementAndGet();
            try {
                action.run();
                completion.complete(null);
            } catch (RuntimeException | Error failure) {
                completion.completeExceptionally(failure);
                throw failure;
            }
        }

        @Override
        public void onRejected(Throwable failure) {
            rejections.incrementAndGet();
            completion.completeExceptionally(failure);
            onRejected.accept(failure);
        }
    }

    private static final class Fixture {
        private final SerialTaskQueue queue;
        private final List<Throwable> aborts = new CopyOnWriteArrayList<>();
        private final AtomicInteger stopCalls = new AtomicInteger();
        private Consumer<Throwable> onAborted = failure -> {};
        private Runnable onStopped = () -> {};

        private Fixture(Executor executor) {
            queue = new SerialTaskQueue(
                    executor,
                    new SerialTaskQueue.Listener() {
                        @Override
                        public void onAborted(Throwable failure) {
                            aborts.add(failure);
                            onAborted.accept(failure);
                        }

                        @Override
                        public void onStopped() {
                            stopCalls.incrementAndGet();
                            onStopped.run();
                        }
                    });
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> workers = new ArrayDeque<>();

        @Override
        public void execute(Runnable worker) {
            workers.addLast(worker);
        }

        private void runAll() {
            while (!workers.isEmpty()) {
                workers.removeFirst().run();
            }
        }
    }
}
