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

import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.HandleResult;
import bisq.common.processing.Input;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.Processor;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.MockClock;
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class LaneRouterTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void submissionRejectionDoesNotBufferAllocateOrBlockTheId(boolean started) throws Exception {
        var rejectedEntities = new RejectedEntityRegistry();
        var fixture = new Fixture(rejectedEntities);
        if (started) {
            await(fixture.router.start());
        }
        var rejection = new IllegalArgumentException("Submissions are currently restricted");
        fixture.onCheck = input -> { throw rejection; };

        for (var input : List.of(new Initialize(), new Message("early"), new Increment())) {
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    await(fixture.router.submit("new", input)));
            assertSame(rejection, assertInstanceOf(ProcessingSummary.Rejected.class, completed.processingSummary()).cause());
        }
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertFalse(rejectedEntities.isRejected("new"));

        fixture.onCheck = input -> {};
        if (!started) {
            await(fixture.router.start());
        }
        var initialized = fixture.router.submit("new", new Initialize());
        fixture.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(List.of(new Increment()), fixture.attempted);
        await(fixture.router.shutdown());
    }

    @Test
    void submissionRejectionLeavesAnExistingLaneUnchanged() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("existing", checkpoint(10)));
        var recovery = fixture.router.start();
        fixture.executor.runAll();
        await(recovery);
        int saveCount = fixture.saved.size();
        var rejection = new IllegalArgumentException("Submissions are currently restricted");
        fixture.onCheck = input -> { throw rejection; };

        assertSame(rejection, failure(fixture.router.submit("existing", new Increment())));
        assertSame(rejection, failure(fixture.router.submit("existing", new Message("incoming"))));
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(saveCount, fixture.saved.size());

        fixture.onCheck = input -> {};
        var accepted = fixture.router.submit("existing", new Increment());
        fixture.executor.runAll();
        assertEquals(new Counter(11), awaitSnapshot(accepted));
        assertEquals(List.of(new Increment()), fixture.attempted);
        await(fixture.router.shutdown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startupReleasePendingHandoverAndRetryDoNotRepeatSubmissionChecks(boolean started) throws Exception {
        var fixture = new Fixture();
        var saveAttempts = new AtomicInteger();
        fixture.onSave = checkpoint -> {
            if (saveAttempts.getAndIncrement() == 0) {
                throw new IllegalStateException("Initial save failed");
            }
        };
        var checked = new ArrayList<Input>();
        fixture.onCheck = checked::add;
        if (started) {
            await(fixture.router.start());
        }
        var message = new Message("early");
        var initializer = new Initialize();
        var early = fixture.router.submit("new", message);
        var initialized = fixture.router.submit("new", initializer);
        assertEquals(List.of(message, initializer), checked);
        fixture.onCheck = input -> { throw new IllegalArgumentException("Restricted after admission"); };

        if (!started) {
            await(fixture.router.start());
        }
        fixture.executor.runAll();
        assertInstanceOf(SubmissionResult.Pending.class, await(initialized));
        // The handed-over input is an ordinary submission: its own attempt retries the failed initial save.
        assertEquals(new Counter(2), awaitSnapshot(early));
        var retry = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.timers).schedule(retry.capture(), anyLong(), eq(TimeUnit.SECONDS));
        retry.getValue().run();
        fixture.executor.runAll();
        assertEquals(new Counter(2), awaitSnapshot(early));
        assertEquals(List.of(message, initializer), checked);
        assertEquals(List.of(new Increment(), message), fixture.attempted);
        await(fixture.router.shutdown());
    }

    @Test
    void submissionCheckRunsOutsideTheRouterLockAndCannotOvertakeShutdown() throws Exception {
        var fixture = new Fixture();
        await(fixture.router.start());
        var checking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.onCheck = input -> {
            checking.countDown();
            awaitLatch(release);
        };
        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-admission", 2)) {
            var submitting = CompletableFuture.supplyAsync(() -> fixture.router.submit("new", new Initialize()), callers);
            try {
                awaitLatch(checking);
                await(await(CompletableFuture.supplyAsync(fixture.router::shutdown, callers)));
            } finally {
                release.countDown();
            }
            assertInstanceOf(RejectedExecutionException.class, failure(await(submitting)));
            assertTrue(fixture.executor.tasks.isEmpty());
        }
    }

    @Test
    void anIdRejectedDuringSubmissionAdmissionCannotCreateAnotherLane() throws Exception {
        var rejectedEntities = new RejectedEntityRegistry();
        var fixture = new Fixture(rejectedEntities);
        await(fixture.router.start());
        fixture.onCheck = input -> rejectedEntities.record("rejected");

        assertInstanceOf(ProcessingSummary.Rejected.class,
                assertInstanceOf(SubmissionResult.Completed.class,
                        await(fixture.router.submit("rejected", new Initialize()))).processingSummary());
        fixture.onCheck = input -> { throw new AssertionError("Blocked IDs must skip admission"); };
        assertInstanceOf(ProcessingSummary.Rejected.class,
                assertInstanceOf(SubmissionResult.Completed.class,
                        await(fixture.router.submit("rejected", new Initialize()))).processingSummary());
        await(fixture.router.shutdown());
    }

    @Test
    void shutdownBeforeStartFailsHeldInputsAndClosesRestoredLanesWithoutResuming() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("restored", checkpoint(10, new Message("saved"))));
        var restoredInput = fixture.router.submit("restored", new Message("incoming"));
        var early = fixture.router.submit("new", new Message("early"));
        var initialized = fixture.router.submit("new", new Initialize());

        await(fixture.router.shutdown());

        for (var submission : List.of(restoredInput, early, initialized)) {
            assertInstanceOf(RejectedExecutionException.class, failure(submission));
        }
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertEquals(0, fixture.preparations.get());
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.router.submit("new", new Initialize())));
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.router.submit("restored", new Increment())));
        assertThrows(IllegalStateException.class, fixture.router::start);
        assertThrows(IllegalStateException.class, () -> fixture.router.restore(Map.of("restored", checkpoint(10))));
        await(fixture.router.shutdown());
    }

    @Test
    void shutdownWaitsForAcceptedWorkInEveryLane() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("first", checkpoint(10), "second", checkpoint(20)));
        var recovery = fixture.router.start();
        var first = fixture.router.submit("first", new Increment());
        var second = fixture.router.submit("second", new Increment());

        var stopped = fixture.router.shutdown();
        assertFalse(stopped.isDone());
        fixture.executor.runNext();
        assertTrue(first.isDone() != second.isDone());
        assertFalse(stopped.isDone());
        fixture.executor.runAll();

        assertEquals(new Counter(11), awaitSnapshot(first));
        assertEquals(new Counter(21), awaitSnapshot(second));
        await(recovery);
        await(stopped);
        assertEquals(List.of(new Increment(), new Increment()), fixture.attempted);
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.router.submit("first", new Increment())));
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.router.submit("third", new Initialize())));
        assertEquals(0, fixture.preparations.get());
    }

    @Test
    void shutdownFailsPreInitializationWaitersAndPreservesSavedDeferredWork() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("restored", checkpoint(10)));
        fixture.router.start();
        fixture.handler = (snapshot, input) -> new HandleResult.Deferred<>();
        var early = fixture.router.submit("new", new Message("early"));
        var deferred = fixture.router.submit("restored", new Message("deferred"));
        fixture.executor.runAll();
        assertFalse(early.isDone());
        assertEquals(new Counter(10), awaitSnapshot(deferred));
        var saved = fixture.saved.getLast();
        var saveCount = fixture.saved.size();

        await(fixture.router.shutdown());

        assertInstanceOf(RejectedExecutionException.class, failure(early));
        assertEquals(List.of(new Message("deferred")), saved.pendingInputs());
        assertEquals(saveCount, fixture.saved.size());
        assertEquals(List.of(new Message("deferred")), fixture.attempted);
        assertEquals(0, fixture.preparations.get());
    }

    @Test
    void shutdownDuringRecoveryQueuingStopsFurtherRecoveryAndBufferedInputRelease() throws Exception {
        var fixture = new Fixture();
        var queuing = new CountDownLatch(1);
        var continueQueuing = new CountDownLatch(1);
        var scheduled = new AtomicInteger();
        Executor pausedExecutor = task -> {
            scheduled.incrementAndGet();
            queuing.countDown();
            awaitLatch(continueQueuing);
            fixture.executor.execute(task);
        };
        fixture.laneExecutor = pausedExecutor;
        fixture.router.restore(Map.of("first", checkpoint(10, new Message("saved-first")),
                "second", checkpoint(20, new Message("saved-second"))));
        var incoming = fixture.router.submit("first", new Message("incoming"));
        var initialized = fixture.router.submit("new", new Initialize());

        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-startup-shutdown", 2)) {
            var starting = CompletableFuture.supplyAsync(fixture.router::start, callers);
            try {
                awaitLatch(queuing);
                var stopped = await(CompletableFuture.supplyAsync(fixture.router::shutdown, callers));

                assertFalse(stopped.isDone());
                assertInstanceOf(RejectedExecutionException.class, failure(incoming));
                assertInstanceOf(RejectedExecutionException.class, failure(initialized));
                continueQueuing.countDown();
                var recovery = await(starting);
                assertEquals(1, scheduled.get());
                fixture.executor.runAll();

                await(stopped);
                assertInstanceOf(RejectedExecutionException.class, failure(recovery));
                assertEquals(1, fixture.attempted.size());
                assertTrue(List.of(new Message("saved-first"), new Message("saved-second"))
                        .contains(fixture.attempted.getFirst()));
                assertEquals(0, fixture.preparations.get());
                assertInstanceOf(RejectedExecutionException.class,
                        failure(fixture.router.submit("new", new Initialize())));
            } finally {
                continueQueuing.countDown();
            }
        }
    }

    @Test
    void shutdownFromAStartupCallbackClearsTheBufferAndDoesNotReopenRouting() throws Exception {
        var fixture = new Fixture();
        var invalid = fixture.router.submit("unknown", new Increment());
        var initialized = fixture.router.submit("new", new Initialize());
        var early = fixture.router.submit("new", new Message("early"));
        var stopped = new AtomicReference<CompletableFuture<Void>>();
        invalid.whenComplete((snapshot, failure) -> stopped.set(fixture.router.shutdown()));

        var recovery = fixture.router.start();

        assertInstanceOf(IllegalStateException.class, failure(invalid));
        assertInstanceOf(RejectedExecutionException.class, failure(recovery));
        assertInstanceOf(RejectedExecutionException.class, failure(initialized));
        assertInstanceOf(RejectedExecutionException.class, failure(early));
        await(stopped.get());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertInstanceOf(RejectedExecutionException.class, failure(fixture.router.submit("new", new Initialize())));
    }

    @Test
    void shutdownDrainsARunningHandlerAndQueuedWorkWithoutStoppingTheSharedExecutor() throws Exception {
        var fixture = new Fixture();
        var handling = new CountDownLatch(1);
        var continueHandling = new CountDownLatch(1);
        fixture.handler = (snapshot, input) -> {
            if (snapshot.count() == 0) {
                handling.countDown();
                awaitLatch(continueHandling);
            }
            return new HandleResult.Applied<>(new Counter(snapshot.count() + 1),
                    new ProcessingOutput<>(List.of(), List.of()));
        };

        try (var worker = ExecutorFactory.newSingleThreadExecutor("lane-router-shutdown")) {
            fixture.laneExecutor = worker;
            fixture.router.start();
            var initialized = fixture.router.submit("counter", new Initialize());
            try {
                awaitLatch(handling);
                var queued = fixture.router.submit("counter", new Increment());
                var cancelledObserver = fixture.router.shutdown();
                var stopped = fixture.router.shutdown();

                assertTrue(cancelledObserver.cancel(false));
                assertFalse(stopped.isDone());
                assertFalse(worker.isShutdown());
                assertInstanceOf(RejectedExecutionException.class,
                        failure(fixture.router.submit("counter", new Increment())));
                assertInstanceOf(RejectedExecutionException.class,
                        failure(fixture.router.submit("other", new Initialize())));
                continueHandling.countDown();

                assertEquals(new Counter(1), awaitSnapshot(initialized));
                assertEquals(new Counter(2), awaitSnapshot(queued));
                await(stopped);
                await(fixture.router.shutdown());
                assertEquals(1, fixture.preparations.get());
                assertEquals(List.of(new Increment(), new Increment()), fixture.attempted);
                assertFalse(worker.isShutdown());
                assertTrue(await(CompletableFuture.supplyAsync(() -> true, worker)));
            } finally {
                continueHandling.countDown();
            }
        }
    }

    @Test
    void resumingPausedInputsLetsEveryLaneContinueWithTheInputsWhichAreAllowedNow() throws Exception {
        var fixture = new Fixture();
        fixture.pauseRule = Message.class::isInstance;
        fixture.router.restore(Map.of("first", checkpoint(1, new Message("first")),
                "second", checkpoint(2, new Message("second")),
                "idle", checkpoint(3)));
        var recovery = fixture.router.start();
        fixture.executor.runAll();
        await(recovery);
        assertTrue(fixture.attempted.isEmpty());
        assertTrue(fixture.saved.isEmpty());

        var stillPaused = fixture.router.resumePausedInputs();
        fixture.executor.runAll();
        await(stillPaused);
        assertTrue(fixture.attempted.isEmpty());
        assertTrue(fixture.saved.isEmpty());

        fixture.pauseRule = input -> false;
        var resumed = fixture.router.resumePausedInputs();
        assertFalse(resumed.isDone());
        fixture.executor.runAll();
        await(resumed);

        assertEquals(Set.of(new Message("first"), new Message("second")), Set.copyOf(fixture.attempted));
        assertEquals(Set.of(new Counter(2), new Counter(3)),
                fixture.saved.stream().map(Checkpoint::snapshot).collect(Collectors.toSet()));
        assertTrue(fixture.saved.stream().allMatch(saved -> saved.pendingInputs().isEmpty()));
    }

    @Test
    void resumingPausedInputsDoesNothingBeforeTheStartAndAfterShutdown() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("counter", checkpoint(1, new Message("saved"))));

        await(fixture.router.resumePausedInputs());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.attempted.isEmpty());

        var stopped = fixture.router.shutdown();
        fixture.executor.runAll();
        await(stopped);
        await(fixture.router.resumePausedInputs());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void holdsSubmissionsWithoutAllocatingLanesUntilStart() throws Exception {
        var fixture = new Fixture();
        var early = fixture.router.submit("counter", new Message("early"));
        var initialized = fixture.router.submit("counter", new Initialize());

        assertFalse(early.isDone());
        assertFalse(initialized.isDone());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertEquals(0, fixture.preparations.get());

        var recovery = fixture.router.start();
        assertTrue(recovery.isDone());
        assertFalse(initialized.isDone());
        fixture.executor.runAll();

        assertEquals(new Counter(2), awaitSnapshot(early));
        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(List.of(new Increment(), new Message("early")), fixture.attempted);
    }

    @Test
    void restoresPassivelyAndProcessesSavedWorkBeforeHeldAndLiveInputs() throws Exception {
        var fixture = new Fixture();
        var early = fixture.router.submit("counter", new Message("early"));
        var checkpoint = checkpoint(10, new Message("saved"));
        fixture.router.restore(Map.of("counter", checkpoint));
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());

        var recovery = fixture.router.start();
        var live = fixture.router.submit("counter", new Message("live"));
        assertFalse(recovery.isDone());
        assertFalse(early.isDone());
        fixture.executor.runAll();

        await(recovery);
        assertEquals(new Counter(12), awaitSnapshot(early));
        assertEquals(new Counter(13), awaitSnapshot(live));
        assertEquals(List.of(new Message("saved"), new Message("early"), new Message("live")), fixture.attempted);
        assertEquals(0, fixture.preparations.get());
    }

    @Test
    void publishesAllRestoredSnapshotsBeforeQueuingRecoveryWithoutRunningWorkers() throws Exception {
        var fixture = new Fixture();
        var published = new ArrayList<Counter>();
        var publicationThreads = new ArrayList<Thread>();
        var queuedAtPublication = new ArrayList<Integer>();
        var publicationFailures = new ArrayList<RuntimeException>();
        fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {
            published.add(snapshot);
            publicationThreads.add(Thread.currentThread());
            queuedAtPublication.add(fixture.executor.tasks.size());
            persistenceFailure.ifPresent(publicationFailures::add);
            setupFailure.ifPresent(publicationFailures::add);
        };
        fixture.router.restore(Map.of("first", checkpoint(10), "second", checkpoint(20)));
        assertTrue(published.isEmpty());

        var recovery = fixture.router.start();

        assertEquals(List.of(10, 20), published.stream().map(Counter::count).sorted().toList());
        assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), publicationThreads);
        assertEquals(List.of(0, 0), queuedAtPublication);
        assertTrue(publicationFailures.isEmpty());
        assertEquals(2, fixture.executor.tasks.size());
        assertFalse(recovery.isDone());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());

        fixture.executor.runAll();
        await(recovery);
        await(fixture.router.shutdown());
    }

    @Test
    void startupObserverCanSubmitFromAnotherThreadAndItsInputWaitsBehindRecovery() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("counter", checkpoint(10, new Message("saved"))));
        var submission = new CompletableFuture<CompletableFuture<SubmissionResult<Counter>>>();
        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-publication", 1)) {
            fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) ->
                    submission.completeAsync(() -> fixture.router.submit("counter", new Message("observer")), callers)
                            .orTimeout(5, TimeUnit.SECONDS)
                            .join();

            var recovery = fixture.router.start();
            var observerInput = await(submission);
            assertFalse(observerInput.isDone());
            assertTrue(fixture.attempted.isEmpty());

            fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {};
            fixture.executor.runAll();
            await(recovery);
            assertEquals(new Counter(12), awaitSnapshot(observerInput));
            assertEquals(List.of(new Message("saved"), new Message("observer")), fixture.attempted);
            await(fixture.router.shutdown());
        }
    }

    @Test
    void publicationFailureDoesNotPreventOtherRestoredSnapshotsOrRecovery() throws Exception {
        var fixture = new Fixture();
        var published = new ArrayList<Counter>();
        fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {
            published.add(snapshot);
            throw new IllegalStateException("Observer failed");
        };
        fixture.router.restore(Map.of("first", checkpoint(10, new Message("first")),
                "second", checkpoint(20, new Message("second"))));

        var recovery = fixture.router.start();
        assertEquals(List.of(10, 20), published.stream().map(Counter::count).sorted().toList());

        fixture.executor.runAll();
        await(recovery);
        assertEquals(List.of(10, 11, 20, 21), published.stream().map(Counter::count).sorted().toList());
        assertEquals(2, fixture.attempted.size());
        await(fixture.router.shutdown());
    }

    @Test
    void shutdownFromStartupPublicationStopsFurtherPublicationAndRecovery() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("first", checkpoint(10, new Message("saved-first")),
                "second", checkpoint(20, new Message("saved-second"))));
        var buffered = fixture.router.submit("first", new Message("buffered"));
        var published = new ArrayList<Counter>();
        var stopped = new AtomicReference<CompletableFuture<Void>>();
        fixture.onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {
            published.add(snapshot);
            stopped.set(fixture.router.shutdown());
        };

        var recovery = fixture.router.start();

        await(stopped.get());
        assertInstanceOf(RejectedExecutionException.class, failure(recovery));
        assertInstanceOf(RejectedExecutionException.class, failure(buffered));
        assertEquals(1, published.size());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
    }

    @Test
    void rejectsDuplicateRestorationAndRestorationOrStartAfterStarting() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("counter", checkpoint(4)));

        var duplicateBatch = new LinkedHashMap<String, Checkpoint<Counter, CounterInput>>();
        duplicateBatch.put("other", checkpoint(20));
        duplicateBatch.put("counter", checkpoint(90));
        assertThrows(IllegalStateException.class, () -> fixture.router.restore(duplicateBatch));
        fixture.router.restore(Map.of("other", checkpoint(30)));
        var recovery = fixture.router.start();
        assertThrows(IllegalStateException.class, () -> fixture.router.restore(Map.of("counter", checkpoint(90))));
        assertThrows(IllegalStateException.class, fixture.router::start);
        var incremented = fixture.router.submit("counter", new Increment());
        var other = fixture.router.submit("other", new Increment());
        fixture.executor.runAll();

        await(recovery);
        assertEquals(new Counter(5), awaitSnapshot(incremented));
        assertEquals(new Counter(31), awaitSnapshot(other));
        assertEquals(0, fixture.preparations.get());
    }

    @Test
    void invalidCheckpointDoesNotRegisterAnyLaneFromTheBatch() throws Exception {
        var fixture = new Fixture();
        var constructed = new ArrayList<Counter>();
        var invalid = new IllegalArgumentException("Invalid saved state");
        fixture.onCreateProcessor = snapshot -> {
            constructed.add(snapshot);
            if (snapshot.count() == 90) {
                throw invalid;
            }
        };
        var checkpoints = new LinkedHashMap<String, Checkpoint<Counter, CounterInput>>();
        checkpoints.put("first", checkpoint(10));
        checkpoints.put("second", checkpoint(90));

        assertSame(invalid, assertThrows(IllegalArgumentException.class, () -> fixture.router.restore(checkpoints)));
        assertEquals(List.of(new Counter(10), new Counter(90)), constructed);
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onCreateProcessor = snapshot -> {};
        fixture.router.restore(Map.of("first", checkpoint(40), "second", checkpoint(50)));
        var recovery = fixture.router.start();
        var first = fixture.router.submit("first", new Increment());
        var second = fixture.router.submit("second", new Increment());
        fixture.executor.runAll();

        await(recovery);
        assertEquals(new Counter(41), awaitSnapshot(first));
        assertEquals(new Counter(51), awaitSnapshot(second));
        assertEquals(0, fixture.preparations.get());
        await(fixture.router.shutdown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startingOrClosingDuringRestorationPreventsLateRegistration(boolean close) throws Exception {
        var fixture = new Fixture();
        var constructing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.onCreateProcessor = snapshot -> {
            constructing.countDown();
            awaitLatch(release);
        };
        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-restore", 2)) {
            var restoring = CompletableFuture.runAsync(
                    () -> fixture.router.restore(Map.of("counter", checkpoint(10))), callers);
            try {
                awaitLatch(constructing);
                await(await(CompletableFuture.supplyAsync(
                        () -> close ? fixture.router.shutdown() : fixture.router.start(), callers)));
            } finally {
                release.countDown();
            }
            assertInstanceOf(IllegalStateException.class, failure(restoring));
        }
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
        fixture.onCreateProcessor = snapshot -> {};
        var initialized = fixture.router.submit("counter", new Initialize());
        if (close) {
            assertInstanceOf(RejectedExecutionException.class, failure(initialized));
        } else {
            fixture.executor.runAll();
            assertEquals(new Counter(1), awaitSnapshot(initialized));
        }
        await(fixture.router.shutdown());
    }

    @Test
    void queuesEveryRecoveryBeforeReleasingInputsWithoutWaitingForAllRecoveriesToFinish() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("first", checkpoint(10, new Message("saved-first")),
                "second", checkpoint(20, new Message("saved-second"))));
        var initialized = fixture.router.submit("new", new Initialize());
        var first = fixture.router.submit("first", new Message("incoming-first"));
        var second = fixture.router.submit("second", new Message("incoming-second"));

        var recovery = fixture.router.start();
        assertEquals(3, fixture.executor.tasks.size());
        // The new lane's work was queued after both recoveries, and can finish independently of them.
        fixture.executor.runLast();
        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertFalse(recovery.isDone());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        assertEquals(List.of(new Increment()), fixture.attempted);

        fixture.executor.runNext();
        assertTrue(first.isDone() != second.isDone());
        assertFalse(recovery.isDone());
        fixture.executor.runAll();

        await(recovery);
        assertEquals(new Counter(12), awaitSnapshot(first));
        assertEquals(new Counter(22), awaitSnapshot(second));
        assertTrue(fixture.attempted.indexOf(new Message("saved-first")) <
                fixture.attempted.indexOf(new Message("incoming-first")));
        assertTrue(fixture.attempted.indexOf(new Message("saved-second")) <
                fixture.attempted.indexOf(new Message("incoming-second")));
        assertEquals(1, fixture.preparations.get());
    }

    @Test
    void submissionsFromStartupFailureCallbacksStayBehindPreviouslyHeldInputs() throws Exception {
        var fixture = new Fixture();
        var rejected = fixture.router.submit("unknown", new Increment());
        var initialized = fixture.router.submit("counter", new Initialize());
        var buffered = fixture.router.submit("counter", new Message("buffered"));
        var duringRelease = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        rejected.whenComplete((snapshot, failure) ->
                duringRelease.set(fixture.router.submit("counter", new Message("during-release"))));

        fixture.router.start();
        fixture.executor.runAll();

        assertInstanceOf(IllegalStateException.class, failure(rejected));
        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(new Counter(2), awaitSnapshot(buffered));
        assertEquals(new Counter(3), awaitSnapshot(duringRelease.get()));
        assertEquals(List.of(new Increment(), new Message("buffered"), new Message("during-release")), fixture.attempted);
    }

    @Test
    void rejectedInitializationAlsoClearsInputsStillHeldByTheRouter() throws Exception {
        var fixture = new Fixture();
        var rejection = new IllegalArgumentException("Invalid initialization");
        fixture.onPrepare = input -> { throw rejection; };
        var early = fixture.router.submit("counter", new Message("early"));
        var initialized = fixture.router.submit("counter", new Initialize());
        var buffered = fixture.router.submit("counter", new Message("buffered"));
        var bufferedInitializer = fixture.router.submit("counter", new Initialize());
        var other = fixture.router.submit("other", new Initialize());
        var retry = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        early.whenComplete((snapshot, failure) -> {
            fixture.onPrepare = input -> {};
            retry.set(fixture.router.submit("counter", new Initialize()));
        });

        try (var worker = ExecutorFactory.newSingleThreadExecutor("lane-router-fast-initialization")) {
            // Let the worker finish each turn before the router forwards the next startup submission.
            fixture.laneExecutor = task -> assertDoesNotThrow(() -> await(CompletableFuture.runAsync(task, worker)));
            await(fixture.router.start());

            for (var submission : List.of(early, initialized, buffered, bufferedInitializer)) {
                assertSame(rejection, failure(submission));
            }
            assertEquals(new Counter(1), awaitSnapshot(other));
            assertEquals(new Counter(1), awaitSnapshot(retry.get()));
            assertEquals(3, fixture.preparations.get());
            assertEquals(List.of(new Increment(), new Increment()), fixture.attempted);
        }
    }

    @Test
    void aRecoveryFailureIsReportedWithoutBlockingOtherLanesOrReplacingTheOwner() throws Exception {
        var fixture = new Fixture();
        var sendFailure = new IllegalStateException("Sender unavailable");
        fixture.onEffect = effect -> { throw sendFailure; };
        var failedCheckpoint = new Checkpoint<Counter, CounterInput>(new Counter(10),
                List.of(),
                List.of(new Send()),
                List.of());
        fixture.router.restore(Map.of("first", failedCheckpoint,
                "second", checkpoint(20, new Message("saved"))));
        var incoming = fixture.router.submit("second", new Message("incoming"));

        var recovery = fixture.router.start();
        fixture.executor.runAll();

        assertSame(sendFailure, failure(recovery));
        assertEquals(new Counter(22), awaitSnapshot(incoming));

        fixture.onEffect = effect -> {};
        var retried = fixture.router.submit("first", new Increment());
        fixture.executor.runAll();
        assertEquals(new Counter(11), awaitSnapshot(retried));
        assertEquals(0, fixture.preparations.get());
    }

    @Test
    void submissionsDuringRecoveryQueuingRemainHeldUntilAllRecoveryTasksAreQueued() throws Exception {
        var fixture = new Fixture();
        var queuing = new CountDownLatch(1);
        var continueQueuing = new CountDownLatch(1);
        Executor pausedExecutor = task -> {
            queuing.countDown();
            awaitLatch(continueQueuing);
            fixture.executor.execute(task);
        };
        fixture.laneExecutor = pausedExecutor;
        fixture.router.restore(Map.of("counter", checkpoint(10, new Message("saved"))));

        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-startup", 2)) {
            var starting = CompletableFuture.supplyAsync(fixture.router::start, callers);
            try {
                awaitLatch(queuing);
                var incoming = await(CompletableFuture.supplyAsync(
                        () -> fixture.router.submit("counter", new Message("during-start")),
                        callers));
                var initialized = await(CompletableFuture.supplyAsync(
                        () -> fixture.router.submit("new", new Initialize()),
                        callers));
                assertFalse(incoming.isDone());
                assertFalse(initialized.isDone());
                assertTrue(fixture.executor.tasks.isEmpty());
                assertThrows(IllegalStateException.class, fixture.router::start);

                continueQueuing.countDown();
                var recovery = await(starting);
                assertFalse(recovery.isDone());
                fixture.executor.runAll();

                await(recovery);
                assertEquals(new Counter(12), awaitSnapshot(incoming));
                assertEquals(new Counter(1), awaitSnapshot(initialized));
                assertEquals(List.of(new Message("saved"), new Message("during-start"), new Increment()), fixture.attempted);
            } finally {
                continueQueuing.countDown();
            }
        }
    }

    @Test
    void pendingInputsAreBoundedAndTheOldestIsDroppedWithoutCreatingLanes() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var pending = new ArrayList<CompletableFuture<SubmissionResult<Counter>>>();
        for (int i = 0; i < LaneRouter.MAX_PENDING_INPUTS; i++) {
            pending.add(fixture.router.submit("id-" + i, new Message("early")));
        }
        assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));
        // An equal input is a separate entry and counts toward the limit.
        var equalCopy = fixture.router.submit("id-1", new Message("early"));

        assertInstanceOf(RejectedExecutionException.class, failure(pending.getFirst()));
        assertTrue(pending.stream().skip(1).noneMatch(CompletableFuture::isDone));
        assertFalse(equalCopy.isDone());
        assertTrue(fixture.executor.tasks.isEmpty());
        assertEquals(0, fixture.preparations.get());

        var droppedOwner = fixture.router.submit("id-0", new Initialize());
        var keptOwner = fixture.router.submit("id-1", new Initialize());
        fixture.executor.runAll();

        assertEquals(new Counter(1), awaitSnapshot(droppedOwner));
        assertEquals(new Counter(1), awaitSnapshot(keptOwner));
        assertEquals(new Counter(2), awaitSnapshot(pending.get(1)));
        assertEquals(new Counter(3), awaitSnapshot(equalCopy));
        assertEquals(List.of(new Increment(), new Increment(), new Message("early"), new Message("early")),
                fixture.attempted);
        var stillPending = pending.get(2);
        await(fixture.router.shutdown());
        assertInstanceOf(RejectedExecutionException.class, failure(stillPending));
    }

    @Test
    void equalCopiesOfAPendingInputCountTowardTheLimit() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var copies = new ArrayList<CompletableFuture<SubmissionResult<Counter>>>();
        for (int i = 0; i < LaneRouter.MAX_PENDING_INPUTS + 10; i++) {
            copies.add(fixture.router.submit("counter", new Message("early")));
        }
        // The oldest copies are dropped, so the router keeps at most the limit.
        for (var dropped : copies.subList(0, 10)) {
            assertInstanceOf(RejectedExecutionException.class, failure(dropped));
        }
        assertTrue(copies.subList(10, copies.size()).stream().noneMatch(CompletableFuture::isDone));

        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(new Counter(LaneRouter.MAX_PENDING_INPUTS + 1), awaitSnapshot(copies.getLast()));
        assertEquals(LaneRouter.MAX_PENDING_INPUTS + 1, fixture.attempted.size());
    }

    @Test
    void inputsArrivingDuringTheHandoverStayBehindOlderPendingInputs() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var early = fixture.router.submit("counter", new Message("early"));
        var requestingWorker = new CountDownLatch(1);
        var continueRequesting = new CountDownLatch(1);
        // Pause the new lane's worker request until a concurrent input for the same entity has arrived.
        fixture.laneExecutor = task -> {
            requestingWorker.countDown();
            awaitLatch(continueRequesting);
            fixture.executor.execute(task);
        };
        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-handover", 2)) {
            var initializing = CompletableFuture.supplyAsync(
                    () -> fixture.router.submit("counter", new Initialize()),
                    callers);
            awaitLatch(requestingWorker);
            var concurrent = await(CompletableFuture.supplyAsync(
                    () -> fixture.router.submit("counter", new Message("concurrent")),
                    callers));
            continueRequesting.countDown();
            var initialized = await(initializing);
            fixture.executor.runAll();

            assertEquals(new Counter(1), awaitSnapshot(initialized));
            assertEquals(new Counter(2), awaitSnapshot(early));
            assertEquals(new Counter(3), awaitSnapshot(concurrent));
            assertEquals(List.of(new Increment(), new Message("early"), new Message("concurrent")), fixture.attempted);
        } finally {
            continueRequesting.countDown();
        }
    }

    @Test
    void earlyMessagesAndInitializationUseTheSameLane() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var early = fixture.router.submit("counter", new Message("early"));
        fixture.executor.runAll();
        assertEquals(0, fixture.preparations.get());
        assertFalse(early.isDone());
        assertTrue(fixture.saved.isEmpty());

        var initialized = fixture.router.submit("counter", new Initialize());
        var later = fixture.router.submit("counter", new Message("later"));
        fixture.executor.runAll();

        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(new Counter(2), awaitSnapshot(early));
        assertEquals(new Counter(3), awaitSnapshot(later));
        assertEquals(List.of(new Increment(), new Message("early"), new Message("later")), fixture.attempted);
        assertEquals(1, fixture.preparations.get());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void cancellingAPendingSubmissionPreservesItsInputAndOtherSubmissions(boolean beforeRouterStart) throws Exception {
        var fixture = new Fixture();
        if (!beforeRouterStart) {
            fixture.router.start();
        }
        var cancelled = fixture.router.submit("one", new Message("first"));
        var equal = fixture.router.submit("one", new Message("first"));
        var different = fixture.router.submit("one", new Message("second"));
        var otherId = fixture.router.submit("two", new Message("first"));
        fixture.executor.runAll();

        assertTrue(cancelled.cancel(false));
        assertFalse(equal.isDone());
        assertFalse(different.isDone());
        assertFalse(otherId.isDone());

        if (beforeRouterStart) {
            fixture.router.start();
        }
        var firstInitialized = fixture.router.submit("one", new Initialize());
        fixture.executor.runAll();

        // Equal inputs stay separate pending inputs: pending copies are not collapsed.
        assertEquals(new Counter(1), awaitSnapshot(firstInitialized));
        assertEquals(new Counter(3), awaitSnapshot(equal));
        assertEquals(new Counter(4), awaitSnapshot(different));
        assertTrue(cancelled.isCancelled());
        assertFalse(otherId.isDone());
        assertEquals(List.of(new Increment(), new Message("first"), new Message("first"), new Message("second")),
                fixture.attempted);

        var secondInitialized = fixture.router.submit("two", new Initialize());
        fixture.executor.runAll();

        assertEquals(new Counter(1), awaitSnapshot(secondInitialized));
        assertEquals(new Counter(2), awaitSnapshot(otherId));
        assertEquals(List.of(new Increment(), new Message("first"), new Message("first"), new Message("second"),
                        new Increment(), new Message("first")),
                fixture.attempted);
        assertEquals(2, fixture.preparations.get());
    }

    @Test
    void unavailableCommandsAndUndeclaredInputsDoNotAllocateLanes() {
        var fixture = new Fixture();
        fixture.router.start();

        assertInstanceOf(IllegalStateException.class, failure(fixture.router.submit("counter", new Increment())));
        assertInstanceOf(IllegalStateException.class, failure(fixture.router.submit("counter", new Notification())));
        assertInstanceOf(IllegalStateException.class, failure(fixture.router.submit("counter", new Input() {})));
        assertEquals(0, fixture.preparations.get());
        assertTrue(fixture.executor.tasks.isEmpty());
    }

    @Test
    void finalRejectionBlocksReentrantAndLaterInputsBeforeCreatingAnotherLane() throws Exception {
        var registry = new RejectedEntityRegistry();
        var fixture = new Fixture(registry);
        fixture.router.start();
        var cause = new IllegalArgumentException("Initialization was declined");
        fixture.onPrepare = input -> { throw new InitializationRejectedException(cause); };
        var early = fixture.router.submit("counter", new Message("early"));
        fixture.executor.runAll();
        var replay = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        early.whenComplete((snapshot, failure) -> {
            assertTrue(registry.isRejected("counter"));
            fixture.onPrepare = input -> {};
            replay.set(fixture.router.submit("counter", new Initialize()));
        });
        var initialized = fixture.router.submit("counter", new Initialize());
        var queued = fixture.router.submit("counter", new Message("queued"));
        fixture.executor.runAll();

        for (var submission : List.of(early, initialized, queued)) {
            assertSame(cause, failure(submission));
        }
        for (var submission : List.of(replay.get(),
                fixture.router.submit("counter", new Initialize()),
                fixture.router.submit("counter", new Message("later")))) {
            var completed = assertInstanceOf(SubmissionResult.Completed.class, await(submission));
            var rejected = assertInstanceOf(ProcessingSummary.Rejected.class, completed.processingSummary());
            assertInstanceOf(IllegalStateException.class, rejected.cause());
            assertEquals("Initialization was already rejected for this entity ID", rejected.cause().getMessage());
        }
        assertEquals(1, fixture.preparations.get());
        assertTrue(fixture.saved.isEmpty());
        assertTrue(fixture.attempted.isEmpty());

        var fresh = fixture.router.submit("fresh", new Initialize());
        fixture.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(fresh));
    }

    @Test
    void finalRejectionClearsInputsStillBufferedDuringStartup() throws Exception {
        var registry = new RejectedEntityRegistry();
        var fixture = new Fixture(registry);
        var cause = new IllegalArgumentException("Initialization was declined");
        fixture.onPrepare = input -> { throw new InitializationRejectedException(cause); };
        var early = fixture.router.submit("counter", new Message("early"));
        var initialized = fixture.router.submit("counter", new Initialize());
        var buffered = fixture.router.submit("counter", new Message("buffered"));
        var replay = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        early.whenComplete((snapshot, failure) -> replay.set(fixture.router.submit("counter", new Initialize())));

        try (var worker = ExecutorFactory.newSingleThreadExecutor("lane-router-final-rejection")) {
            fixture.laneExecutor = task -> assertDoesNotThrow(() -> await(CompletableFuture.runAsync(task, worker)));
            await(fixture.router.start());

            for (var submission : List.of(early, initialized, buffered)) {
                assertSame(cause, failure(submission));
            }
            assertEquals("Initialization was already rejected for this entity ID", failure(replay.get()).getMessage());
            assertEquals(1, fixture.preparations.get());
        }
    }

    @Test
    void finalRejectionSurvivesRouterReplacementWithoutReopeningTheOldRouter() throws Exception {
        var registry = new RejectedEntityRegistry();
        var first = new Fixture(registry);
        first.router.start();
        first.onPrepare = input -> { throw new InitializationRejectedException(new IllegalArgumentException("Declined")); };
        var initialized = first.router.submit("counter", new Initialize());
        first.executor.runAll();
        assertInstanceOf(IllegalArgumentException.class, failure(initialized));
        await(first.router.shutdown());

        var second = new Fixture(registry);
        // A known rejection does not even wait in the replacement router's startup buffer.
        var replay = second.router.submit("counter", new Initialize());
        assertTrue(replay.isDone());
        assertEquals("Initialization was already rejected for this entity ID", failure(replay).getMessage());
        second.router.start();
        assertInstanceOf(RejectedExecutionException.class, failure(first.router.submit("fresh", new Initialize())));
        assertThrows(IllegalStateException.class, first.router::start);

        var fresh = second.router.submit("fresh", new Initialize());
        second.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(fresh));
        assertEquals(1, second.preparations.get());

        // A full application restart creates new memory, so the same ID is no longer blocked.
        var restarted = new Fixture();
        restarted.router.start();
        var afterRestart = restarted.router.submit("counter", new Initialize());
        restarted.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(afterRestart));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void initializationFailureRemovesTheLaneAndAllowsAnotherAttempt(boolean interrupted) throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var other = fixture.router.submit("other", new Initialize());
        fixture.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(other));

        var rejected = interrupted
                ? new RejectedExecutionException("Initialization interrupted")
                : new IllegalArgumentException("Unexpected initialization failure");
        fixture.onPrepare = input -> { throw rejected; };
        var early = fixture.router.submit("counter", new Message("early"));
        fixture.executor.runAll();
        var retry = new AtomicReference<CompletableFuture<SubmissionResult<Counter>>>();
        early.whenComplete((snapshot, failure) -> {
            fixture.onPrepare = input -> {};
            retry.set(fixture.router.submit("counter", new Initialize()));
        });

        var initialized = fixture.router.submit("counter", new Initialize());
        var queuedMessage = fixture.router.submit("counter", new Message("queued"));
        var queuedInitializer = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        for (var submission : List.of(early, initialized, queuedMessage, queuedInitializer)) {
            assertSame(rejected, failure(submission));
        }
        assertEquals(new Counter(1), awaitSnapshot(retry.get()));
        assertEquals(3, fixture.preparations.get());
        assertEquals(List.of(new Increment(), new Increment()), fixture.attempted);

        var later = fixture.router.submit("counter", new Increment());
        var otherLater = fixture.router.submit("other", new Increment());
        fixture.executor.runAll();
        assertEquals(new Counter(2), awaitSnapshot(later));
        assertEquals(new Counter(2), awaitSnapshot(otherLater));
    }

    @Test
    void aFailedInitialCheckpointKeepsPreparedOwnerAndHandedOverInputForRetry() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var early = fixture.router.submit("counter", new Message("early"));
        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertSame(saveFailure, failure(initialized));
        // The handed-over input is retained in memory, but its own save attempt failed as well.
        var earlyPending = assertInstanceOf(SubmissionResult.Pending.class, await(early));
        assertSame(saveFailure, earlyPending.cause());
        assertInstanceOf(ProcessingSummary.Deferred.class, earlyPending.processingSummary());
        assertEquals(1, fixture.preparations.get());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        var retried = fixture.router.submit("counter", new Increment());
        fixture.executor.runAll();

        assertEquals(new Counter(3), awaitSnapshot(retried));
        assertEquals(1, fixture.preparations.get());
        assertEquals(List.of(new Increment(), new Message("early"), new Increment()), fixture.attempted);
    }

    @Test
    void aFailureHandlingTheFirstInputKeepsItsSavedPreparedOwner() {
        var fixture = new Fixture();
        fixture.router.start();
        var routingFailure = new IllegalStateException("Cannot route the first input");
        fixture.routes = (snapshot, input) -> { throw routingFailure; };
        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertSame(routingFailure, failure(initialized));
        assertEquals(1, fixture.saved.size());
        assertEquals(List.of(new Increment()), fixture.saved.getFirst().pendingInputs());
        assertInstanceOf(RejectedExecutionException.class,
                failure(fixture.router.submit("counter", new Initialize())));
        assertEquals(1, fixture.preparations.get());
    }

    @Test
    void aFailureProcessingTheFirstInputKeepsPreparedOwner() {
        var fixture = new Fixture();
        fixture.router.start();
        var processingFailure = new IllegalStateException("Unexpected handler failure");
        fixture.handler = (snapshot, input) -> { throw processingFailure; };
        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertSame(processingFailure, failure(initialized));
        assertEquals(new Counter(0), fixture.saved.getLast().snapshot());
        assertInstanceOf(RejectedExecutionException.class,
                failure(fixture.router.submit("counter", new Initialize())));
        assertEquals(1, fixture.preparations.get());
    }

    @Test
    void concurrentPendingSubmissionsForTheSameIdEndUpInOneLane() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var start = new CountDownLatch(1);
        try (var callers = ExecutorFactory.newFixedThreadPool("lane-router-callers", 4)) {
            var submissions = new ArrayList<CompletableFuture<CompletableFuture<SubmissionResult<Counter>>>>();
            for (int i = 0; i < 24; i++) {
                var message = new Message("message-" + i);
                submissions.add(CompletableFuture.supplyAsync(() -> {
                    awaitLatch(start);
                    return fixture.router.submit("counter", message);
                }, callers));
            }
            start.countDown();
            var pending = new ArrayList<CompletableFuture<SubmissionResult<Counter>>>();
            for (var submission : submissions) {
                pending.add(await(submission));
            }

            // Inputs for an unknown ID wait in the router; only the initializer creates the lane.
            assertTrue(fixture.executor.tasks.isEmpty());
            var initialized = fixture.router.submit("counter", new Initialize());
            fixture.executor.runAll();

            assertEquals(new Counter(1), awaitSnapshot(initialized));
            for (var submission : pending) {
                await(submission);
            }
            assertEquals(1, fixture.preparations.get());
            assertEquals(25, fixture.attempted.size());
        }
    }

    private static Checkpoint<Counter, CounterInput> checkpoint(int count, CounterInput... pending) {
        return new Checkpoint<>(new Counter(count),
                List.of(pending).stream().map(input -> input).toList(),
                List.of(),
                List.of());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Counter awaitSnapshot(CompletableFuture<?> future) throws Exception {
        return switch (await(future)) {
            case Counter counter -> counter;
            case SubmissionResult.Completed<?> completed -> switch (completed.processingSummary()) {
                case ProcessingSummary.Applied<?> applied -> assertInstanceOf(Counter.class, applied.snapshot());
                case ProcessingSummary.Deferred<?> deferred -> assertInstanceOf(Counter.class, deferred.snapshot());
                case ProcessingSummary.Ignored<?> ignored -> assertInstanceOf(Counter.class, ignored.snapshot());
                default -> throw new AssertionError("Expected a successful snapshot");
            };
            default -> throw new AssertionError("Expected a successful snapshot");
        };
    }

    private static Throwable failure(CompletableFuture<?> future) {
        try {
            return switch (await(future)) {
                case SubmissionResult.Completed<?> completed -> switch (completed.processingSummary()) {
                    case ProcessingSummary.Rejected<?> rejected -> rejected.cause();
                    case ProcessingSummary.Failed<?> failed -> failed.cause();
                    default -> throw new AssertionError("Expected a failure");
                };
                case SubmissionResult.Pending<?> pending -> pending.cause();
                case SubmissionResult.NotProcessed<?> notProcessed -> notProcessed.cause();
                default -> throw new AssertionError("Expected a failure");
            };
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            throw new AssertionError(exception);
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

    private interface CounterInput extends Input {}

    private record Initialize() implements Command {}

    private record Increment() implements CounterInput, Command {}

    private record Message(String name) implements CounterInput {}

    private record Notification() implements CounterInput {}

    private record Counter(int count) {}

    private record Send() implements PersistableEffect {
        @Override
        public StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue("saved-message");
        }
    }

    private static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        private void runNext() {
            tasks.removeFirst().run();
        }

        private void runLast() {
            tasks.removeLast().run();
        }

        private void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }

    private static final class Fixture {
        private final ManualExecutor executor = new ManualExecutor();
        private final ScheduledExecutorService timers = mock(ScheduledExecutorService.class, org.mockito.Mockito.RETURNS_MOCKS);
        private final MockClock clock = new MockClock();
        private final List<Checkpoint<Counter, CounterInput>> saved = new ArrayList<>();
        private final List<CounterInput> attempted = new ArrayList<>();
        private final AtomicInteger preparations = new AtomicInteger();
        private Predicate<CounterInput> pauseRule = input -> false;
        private final ProcessingLaneDefinition<Counter, CounterInput> definition =
                ProcessingLaneDefinition.<Counter, CounterInput>builder(CounterInput.class, this::createProcessor,
                        (ignoredSnapshot, ignoredInput) -> {})
                        .initializeOn(Initialize.class, this::prepare)
                        .stashUntilInitialized(Message.class)
                        .build();
        private final LaneRouter<Counter, CounterInput> router;
        private Executor laneExecutor = executor;
        private Consumer<Counter> onCreateProcessor = snapshot -> {};
        private Consumer<Input> onCheck = input -> {};
        private Consumer<Input> onPrepare = input -> {};
        private Consumer<Checkpoint<Counter, CounterInput>> onSave = checkpoint -> {};
        private Consumer<Effect> onEffect = effect -> {};
        private SnapshotListener<Counter> onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {};
        private BiFunction<Counter, CounterInput, List<CounterInput>> routes = (snapshot, input) -> List.of(input);
        private BiFunction<Counter, CounterInput, HandleResult<Counter, CounterInput>> handler = (snapshot, input) ->
                new HandleResult.Applied<>(new Counter(snapshot.count() + 1),
                        new ProcessingOutput<>(List.of(), List.of()));

        private Fixture() {
            this(new RejectedEntityRegistry());
        }

        private Fixture(RejectedEntityRegistry rejectedEntities) {
            router = new LaneRouter<>(
                    new InputPolicy() {
                        @Override
                        public void check(Input input) {
                            onCheck.accept(input);
                        }

                        @Override
                        public boolean isPaused(Input input) {
                            return input instanceof CounterInput counterInput && pauseRule.test(counterInput);
                        }
                    },
                    definition,
                    task -> laneExecutor.execute(task),
                    timers,
                    clock,
                    this::saveCheckpoint,
                    (snapshot, effect) -> onEffect.accept(effect),
                    (snapshot, persistenceFailure, setupFailure) -> onSnapshot.onSnapshot(snapshot, persistenceFailure, setupFailure),
                    rejectedEntities);
        }

        private void saveCheckpoint(Checkpoint<Counter, CounterInput> checkpoint) {
            onSave.accept(checkpoint);
            saved.add(checkpoint);
        }

        private Initialization<Counter, CounterInput> prepare(Input input) {
            preparations.incrementAndGet();
            onPrepare.accept(input);
            return new Initialization<>(new Counter(0), new Increment());
        }

        private Processor<Counter, CounterInput> createProcessor(Counter snapshot) {
            onCreateProcessor.accept(snapshot);
            return new Processor<>() {
                @Override
                public HandleResult<Counter, CounterInput> handle(Counter snapshot, CounterInput input) {
                    if (routes.apply(snapshot, input).isEmpty()) {
                        return input instanceof Command
                                ? new HandleResult.Rejected<>(new IllegalStateException("No destination for command"))
                                : new HandleResult.Ignored<>();
                    }
                    attempted.add(input);
                    return handler.apply(snapshot, input);
                }
            };
        }
    }
}
