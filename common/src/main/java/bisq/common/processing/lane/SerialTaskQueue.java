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

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * Runs tasks in acceptance order on a supplied executor, with at most one task running per queue.
 * The executor may be shared; this queue never shuts it down. Task results belong to the caller and
 * may remain pending after a task returns.
 * <p>
 * Callbacks run outside the queue lock. Lifecycle callbacks have exclusive access, except that a running
 * task can invoke {@link #abort(Throwable)} and receive its abort notification synchronously. Rejection
 * callbacks may run concurrently on submitting threads and must not access the owner's mutable state.
 * Callbacks may submit more tasks or request shutdown, but must not wait for this queue to finish work.
 */
@Slf4j
final class SerialTaskQueue {
    private final Object lock = new Object();
    private final Executor executor;
    private final Listener listener;
    private final ArrayDeque<Task> tasks = new ArrayDeque<>();
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    private Optional<Thread> runningThread = Optional.empty();
    // Covers a scheduled worker and its abort/rejection callbacks, until it releases ownership.
    private boolean draining;
    private boolean closed;
    private boolean aborted;
    private boolean stopStarted;

    SerialTaskQueue(Executor executor, Listener listener) {
        this.executor = requireNonNull(executor);
        this.listener = requireNonNull(listener);
    }

    /**
     * Closed queues reject the task on the submitting thread; accepted tasks are never run reentrantly.
     * A failure requesting a worker aborts the queue and rejects queued tasks with the original cause.
     */
    void submit(Task task) {
        requireNonNull(task);
        boolean rejected;
        boolean start = false;
        synchronized (lock) {
            rejected = closed;
            if (!rejected) {
                tasks.addLast(task);
                if (!draining) {
                    draining = true;
                    start = true;
                }
            }
        }
        if (rejected) {
            reject(task, new RejectedExecutionException("Serial task queue is closed"));
        } else if (start) {
            try {
                executor.execute(this::drain);
            } catch (RuntimeException | Error failure) {
                log.error("Could not start serial task queue worker; aborting queue", failure);
                abortQueue(failure);
                synchronized (lock) {
                    draining = false;
                }
                finishShutdownIfIdle();
            }
        }
    }

    /** Stops admission, drains accepted tasks and observes cleanup without exposing the internal future. */
    CompletableFuture<Void> shutdown() {
        synchronized (lock) {
            closed = true;
        }
        finishShutdownIfIdle();
        return stopped.copy();
    }

    /**
     * Only the currently executing task may abort. Notifies the owner once, then rejects queued tasks
     * with the original cause. The current task keeps running; cleanup waits until it returns.
     */
    void abort(Throwable failure) {
        requireNonNull(failure);
        synchronized (lock) {
            checkState(runningThread.filter(thread -> thread == Thread.currentThread()).isPresent(),
                    "Only the running task can abort this queue");
        }
        abortQueue(failure);
    }

    /** True once admission has closed, even if tasks or cleanup are still running. */
    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void drain() {
        while (true) {
            Task task;
            synchronized (lock) {
                task = tasks.pollFirst();
                if (task == null) {
                    draining = false;
                } else {
                    runningThread = Optional.of(Thread.currentThread());
                }
            }
            if (task == null) {
                finishShutdownIfIdle();
                return;
            }
            try {
                task.run();
            } catch (RuntimeException | Error failure) {
                // Report even after an earlier abort: this is a separate failure escaping the task wrapper.
                log.error("Unhandled serial task failure", failure);
                abortQueue(failure);
            } finally {
                synchronized (lock) {
                    runningThread = Optional.empty();
                }
            }
        }
    }

    // Called with drain ownership, either by the running task or after its worker request failed.
    private void abortQueue(Throwable failure) {
        List<Task> rejected;
        synchronized (lock) {
            if (aborted) {
                return;
            }
            closed = true;
            aborted = true;
            rejected = List.copyOf(tasks);
            tasks.clear();
        }
        try {
            listener.onAborted(failure);
        } catch (RuntimeException | Error notificationFailure) {
            if (notificationFailure != failure) {
                failure.addSuppressed(notificationFailure);
            }
        }
        rejected.forEach(task -> reject(task, failure));
    }

    private void reject(Task task, Throwable failure) {
        try {
            task.onRejected(failure);
        } catch (RuntimeException | Error notificationFailure) {
            if (notificationFailure != failure) {
                failure.addSuppressed(notificationFailure);
            }
            log.warn("Serial task rejection callback failed", notificationFailure);
        }
    }

    private void finishShutdownIfIdle() {
        synchronized (lock) {
            if (!closed || draining || stopStarted) {
                return;
            }
            // Claim cleanup before calling the listener, including reentrant and concurrent shutdown calls.
            stopStarted = true;
        }
        try {
            listener.onStopped();
            stopped.complete(null);
        } catch (RuntimeException | Error failure) {
            stopped.completeExceptionally(failure);
            log.error("Serial task queue cleanup failed", failure);
        }
    }

    interface Task {
        /** Handles its own processing failures and submission result; an escaping failure aborts the queue. */
        void run();

        /** Called only if this task never started; preserves abort/executor causes or reports queue closure. */
        void onRejected(Throwable failure);
    }

    interface Listener {
        /** Called at most once, before rejecting queued tasks. Throwing does not prevent their rejection. */
        void onAborted(Throwable failure);

        /** Called exactly once after tasks and abort notifications finish. Failures are logged and fail shutdown. */
        void onStopped();
    }
}
