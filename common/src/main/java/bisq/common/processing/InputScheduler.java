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

import bisq.common.timer.Clock;
import bisq.common.timer.SystemClock;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;

/**
 * Scheduled deliveries for one serial owner. The timer only enqueues work; delivery and its
 * stale-callback check run on the owner's serial executor, alongside schedule and cancel calls.
 * Closing cancels this owner's timers without shutting down the shared timer executor.
 * Persistence and restoration of deadlines are the owner's responsibility.
 *
 * @param <T> scheduled input descriptor, preserved when delivered to its owner
 */
public final class InputScheduler<T extends ScheduledInput<?>> implements AutoCloseable {
    private final ScheduledExecutorService timerExecutor;
    private final Executor ownerExecutor;
    private final Consumer<T> inputHandler;
    private final Clock clock;
    private final Map<String, Registration> registrations = new HashMap<>();
    private boolean closed;

    public InputScheduler(ScheduledExecutorService timerExecutor,
                          Executor ownerExecutor,
                          Consumer<T> inputHandler) {
        this(timerExecutor, ownerExecutor, inputHandler, new SystemClock());
    }

    public InputScheduler(ScheduledExecutorService timerExecutor,
                          Executor ownerExecutor,
                          Consumer<T> inputHandler,
                          Clock clock) {
        this.timerExecutor = timerExecutor;
        this.ownerExecutor = ownerExecutor;
        this.inputHandler = inputHandler;
        this.clock = clock;
    }

    /** Replaces this id's previous delivery. An overdue deadline is enqueued without waiting. */
    public synchronized void schedule(T scheduled) {
        checkState(!closed, "Input scheduler is closed");
        long delayNanos = Math.max(0,
                Duration.between(Instant.ofEpochMilli(clock.now()), scheduled.triggerAt()).toNanos());
        Object token = new Object();
        ScheduledFuture<?> future = timerExecutor.schedule(
                () -> ownerExecutor.execute(() -> deliver(scheduled, token)),
                delayNanos,
                TimeUnit.NANOSECONDS);
        Registration previous = registrations.put(scheduled.id(), new Registration(token, future));
        if (previous != null) {
            previous.future().cancel(false);
        }
    }

    public synchronized void cancel(String id) {
        Optional.ofNullable(registrations.remove(id)).ifPresent(registration -> registration.future().cancel(false));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        registrations.values().forEach(registration -> registration.future().cancel(false));
        registrations.clear();
    }

    private void deliver(T scheduled, Object token) {
        synchronized (this) {
            Registration current = registrations.get(scheduled.id());
            if (current == null || current.token() != token) {
                return;
            }
            registrations.remove(scheduled.id());
        }
        inputHandler.accept(scheduled);
    }

    private record Registration(Object token, ScheduledFuture<?> future) {
    }
}
