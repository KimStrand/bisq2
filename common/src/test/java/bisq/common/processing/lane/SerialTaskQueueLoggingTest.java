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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

@Isolated("Captures the log of SerialTaskQueue")
class SerialTaskQueueLoggingTest {
    private final Logger logger = (Logger) LoggerFactory.getLogger(SerialTaskQueue.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void setUp() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @ParameterizedTest
    @MethodSource("workerStartFailures")
    void workerStartFailureIsLoggedBeforeAnyoneObservesTheRejectedSubmission(Throwable cause) throws Exception {
        var submission = new CompletableFuture<Void>();
        var abortCause = new AtomicReference<Throwable>();
        var stopCalls = new AtomicInteger();
        var queue = new SerialTaskQueue(
                worker -> {
                    if (cause instanceof RuntimeException failure) {
                        throw failure;
                    }
                    throw (Error) cause;
                },
                new SerialTaskQueue.Listener() {
                    @Override
                    public void onAborted(Throwable failure) {
                        abortCause.set(failure);
                    }

                    @Override
                    public void onStopped() {
                        stopCalls.incrementAndGet();
                    }
                });
        queue.submit(new SerialTaskQueue.Task() {
            @Override
            public void run() {
                fail("The task must not run when its worker cannot start");
            }

            @Override
            public void onRejected(Throwable failure) {
                submission.completeExceptionally(failure);
            }
        });

        assertTrue(queue.isClosed());
        assertSame(cause, abortCause.get());
        assertEquals(1, stopCalls.get());
        assertEquals(1, appender.list.size());
        var event = appender.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        var loggedFailure = assertInstanceOf(ThrowableProxy.class, event.getThrowableProxy());
        assertSame(cause, loggedFailure.getThrowable());

        var rejected = assertThrows(ExecutionException.class, () -> submission.get(10, TimeUnit.SECONDS));
        assertSame(cause, rejected.getCause());
        queue.shutdown().get(10, TimeUnit.SECONDS);
        assertEquals(1, stopCalls.get());
        assertEquals(1, appender.list.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupFailureAfterAbortIsLoggedBeforeAnyoneObservesShutdown(boolean error) {
        var workers = new ArrayDeque<Runnable>();
        var stopCalls = new AtomicInteger();
        var abortCause = new IllegalStateException("Abort");
        Throwable cleanupFailure = error ? new AssertionError("Cleanup failed") : new IllegalStateException("Cleanup failed");
        var queue = new SerialTaskQueue(
                workers::addLast,
                new SerialTaskQueue.Listener() {
                    @Override
                    public void onAborted(Throwable failure) {
                        assertSame(abortCause, failure);
                    }

                    @Override
                    public void onStopped() {
                        stopCalls.incrementAndGet();
                        if (cleanupFailure instanceof RuntimeException failure) {
                            throw failure;
                        }
                        throw (Error) cleanupFailure;
                    }
                });
        queue.submit(new SerialTaskQueue.Task() {
            @Override
            public void run() {
                queue.abort(abortCause);
            }

            @Override
            public void onRejected(Throwable failure) {
                fail("The task must run before the queue aborts", failure);
            }
        });

        workers.removeFirst().run();

        assertTrue(queue.isClosed());
        assertEquals(1, stopCalls.get());
        assertEquals(1, appender.list.size());
        var event = appender.list.getFirst();
        assertEquals(Level.ERROR, event.getLevel());
        var loggedFailure = assertInstanceOf(ThrowableProxy.class, event.getThrowableProxy());
        assertSame(cleanupFailure, loggedFailure.getThrowable());

        var stopped = assertThrows(ExecutionException.class, () -> queue.shutdown().get(10, TimeUnit.SECONDS));
        assertSame(cleanupFailure, stopped.getCause());
        var stoppedAgain = assertThrows(ExecutionException.class, () -> queue.shutdown().get(10, TimeUnit.SECONDS));
        assertSame(cleanupFailure, stoppedAgain.getCause());
        assertEquals(1, stopCalls.get());
        assertEquals(1, appender.list.size());
    }

    private static Stream<Throwable> workerStartFailures() {
        return Stream.of(
                new RejectedExecutionException("Executor stopped"),
                new IllegalStateException("Worker scheduling failed"),
                new OutOfMemoryError("unable to create native thread"));
    }
}
