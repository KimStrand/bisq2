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
import com.google.protobuf.StringValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

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
        fixture.onCheckSubmission = input -> { throw rejection; };

        for (var input : List.of(new Initialize(), new Message("early"), new Increment())) {
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    await(fixture.router.submit("new", input)));
            assertSame(rejection, assertInstanceOf(ProcessingResult.Rejected.class, completed.processingResult()).cause());
        }
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.saved.isEmpty());
        assertFalse(rejectedEntities.isRejected("new"));

        fixture.onCheckSubmission = input -> {};
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
        fixture.onCheckSubmission = input -> { throw rejection; };

        assertSame(rejection, failure(fixture.router.submit("existing", new Increment())));
        assertSame(rejection, failure(fixture.router.submit("existing", new Message("incoming"))));
        assertTrue(fixture.executor.tasks.isEmpty());
        assertTrue(fixture.attempted.isEmpty());
        assertEquals(saveCount, fixture.saved.size());

        fixture.onCheckSubmission = input -> {};
        var accepted = fixture.router.submit("existing", new Increment());
        fixture.executor.runAll();
        assertEquals(new Counter(11), awaitSnapshot(accepted));
        assertEquals(List.of(new Increment()), fixture.attempted);
        await(fixture.router.shutdown());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void startupReleaseWaitingReplayAndResumeDoNotRepeatSubmissionChecks(boolean started) throws Exception {
        var fixture = new Fixture();
        var checked = new ArrayList<Input>();
        fixture.onCheckSubmission = checked::add;
        if (started) {
            await(fixture.router.start());
        }
        var message = new Message("early");
        var initializer = new Initialize();
        var early = fixture.router.submit("new", message);
        var initialized = fixture.router.submit("new", initializer);
        assertEquals(List.of(message, initializer), checked);
        fixture.onCheckSubmission = input -> { throw new IllegalArgumentException("Restricted after admission"); };

        if (!started) {
            await(fixture.router.start());
        }
        fixture.executor.runAll();
        assertEquals(new Counter(1), awaitSnapshot(initialized));
        assertEquals(new Counter(2), awaitSnapshot(early));
        var resumed = fixture.router.resume("new");
        fixture.executor.runAll();
        assertEquals(new Counter(2), await(resumed));
        assertEquals(List.of(new Increment(), message), fixture.attempted);
        await(fixture.router.shutdown());
    }

    @Test
    void submissionCheckRunsOutsideTheRouterLockAndCannotOvertakeShutdown() throws Exception {
        var fixture = new Fixture();
        await(fixture.router.start());
        var checking = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        fixture.onCheckSubmission = input -> {
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
        fixture.onCheckSubmission = input -> rejectedEntities.record("rejected");

        assertInstanceOf(ProcessingResult.Rejected.class,
                assertInstanceOf(SubmissionResult.Completed.class,
                        await(fixture.router.submit("rejected", new Initialize()))).processingResult());
        fixture.onCheckSubmission = input -> { throw new AssertionError("Blocked IDs must skip admission"); };
        assertInstanceOf(ProcessingResult.Rejected.class,
                assertInstanceOf(SubmissionResult.Completed.class,
                        await(fixture.router.submit("rejected", new Initialize()))).processingResult());
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
        fixture.handler = (snapshot, input) -> new StepResult.Deferred<>();
        var early = fixture.router.submit("new", new Message("early"));
        var deferred = fixture.router.submit("restored", new Message("deferred"));
        fixture.executor.runAll();
        assertFalse(early.isDone());
        assertEquals(new Counter(10), awaitSnapshot(deferred));
        var saved = fixture.saved.getLast();
        var saveCount = fixture.saved.size();

        await(fixture.router.shutdown());

        assertInstanceOf(RejectedExecutionException.class, failure(early));
        assertEquals(List.of(new PendingWork<CounterInput>(new Message("deferred"), false)), saved.pendingWork());
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
            return new StepResult.Applied<>(new Counter(snapshot.count() + 1),
                    new ProcessingOutput<>(List.of(), List.of(), List.of()));
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
    void rejectsDuplicateRestorationAndRestorationOrStartAfterStarting() throws Exception {
        var fixture = new Fixture();
        fixture.router.restore(Map.of("counter", checkpoint(4)));

        var duplicateBatch = new LinkedHashMap<String, Checkpoint<Counter, CounterInput, CounterInput>>();
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
        var checkpoints = new LinkedHashMap<String, Checkpoint<Counter, CounterInput, CounterInput>>();
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
        var failedCheckpoint = new Checkpoint<Counter, CounterInput, CounterInput>(new Counter(10),
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
    void cancellingAWaitingSubmissionPreservesItsMessageAndOtherSubmissions(boolean beforeRouterStart) throws Exception {
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
            var rejected = assertInstanceOf(ProcessingResult.Rejected.class, completed.processingResult());
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
    void aFailedInitialCheckpointKeepsPreparedOwnerForRetry() throws Exception {
        var fixture = new Fixture();
        fixture.router.start();
        var saveFailure = new IllegalStateException("Disk unavailable");
        fixture.onSave = checkpoint -> { throw saveFailure; };
        var early = fixture.router.submit("counter", new Message("early"));
        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertSame(saveFailure, failure(initialized));
        assertFalse(early.isDone());
        assertEquals(1, fixture.preparations.get());
        assertTrue(fixture.attempted.isEmpty());

        fixture.onSave = checkpoint -> {};
        var retried = fixture.router.submit("counter", new Increment());
        fixture.executor.runAll();

        assertEquals(new Counter(2), awaitSnapshot(early));
        assertEquals(new Counter(3), awaitSnapshot(retried));
        assertEquals(1, fixture.preparations.get());
        assertEquals(List.of(new Increment(), new Message("early"), new Increment()), fixture.attempted);
    }

    @Test
    void aFailureRoutingTheFirstInputKeepsPreparedOwner() {
        var fixture = new Fixture();
        fixture.router.start();
        var routingFailure = new IllegalStateException("Cannot route the first input");
        fixture.routes = (snapshot, input) -> { throw routingFailure; };
        var initialized = fixture.router.submit("counter", new Initialize());
        fixture.executor.runAll();

        assertSame(routingFailure, failure(initialized));
        assertTrue(fixture.saved.isEmpty());
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
    void concurrentSubmissionsForTheSameIdCreateOneLane() throws Exception {
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

            assertEquals(1, fixture.executor.tasks.size());
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

    private static Checkpoint<Counter, CounterInput, CounterInput> checkpoint(int count, CounterInput... pending) {
        return new Checkpoint<>(new Counter(count),
                List.of(pending).stream().map(input -> new PendingWork<>(input, input instanceof Command)).toList(),
                List.of(),
                List.of());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    private static Counter awaitSnapshot(CompletableFuture<?> future) throws Exception {
        return switch (await(future)) {
            case Counter counter -> counter;
            case SubmissionResult.Completed<?> completed -> switch (completed.processingResult()) {
                case ProcessingResult.Applied<?> applied -> assertInstanceOf(Counter.class, applied.snapshot());
                case ProcessingResult.Deferred<?> deferred -> assertInstanceOf(Counter.class, deferred.snapshot());
                case ProcessingResult.Ignored<?> ignored -> assertInstanceOf(Counter.class, ignored.snapshot());
                default -> throw new AssertionError("Expected a successful snapshot");
            };
            default -> throw new AssertionError("Expected a successful snapshot");
        };
    }

    private static Throwable failure(CompletableFuture<?> future) {
        try {
            return switch (await(future)) {
                case SubmissionResult.Completed<?> completed -> switch (completed.processingResult()) {
                    case ProcessingResult.Rejected<?> rejected -> rejected.cause();
                    case ProcessingResult.Failed<?> failed -> failed.cause();
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

    private record Send() implements Effect {
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
        private final ScheduledExecutorService timers = mock(ScheduledExecutorService.class);
        private final MockClock clock = new MockClock();
        private final List<Checkpoint<Counter, CounterInput, CounterInput>> saved = new ArrayList<>();
        private final List<CounterInput> attempted = new ArrayList<>();
        private final AtomicInteger preparations = new AtomicInteger();
        private final ProcessingLaneDefinition<Counter, CounterInput, CounterInput> definition =
                ProcessingLaneDefinition.<Counter, CounterInput, CounterInput>builder(CounterInput.class, this::createProcessor)
                        .checkSubmissionWith(input -> this.onCheckSubmission.accept(input))
                        .initializeOn(Initialize.class, this::prepare)
                        .stashUntilInitialized(Message.class)
                        .build();
        private final LaneRouter<Counter, CounterInput, CounterInput> router;
        private Executor laneExecutor = executor;
        private Consumer<Counter> onCreateProcessor = snapshot -> {};
        private Consumer<Input> onCheckSubmission = input -> {};
        private Consumer<Input> onPrepare = input -> {};
        private Consumer<Checkpoint<Counter, CounterInput, CounterInput>> onSave = checkpoint -> {};
        private Consumer<Effect> onEffect = effect -> {};
        private BiFunction<Counter, CounterInput, List<CounterInput>> routes = (snapshot, input) -> List.of(input);
        private BiFunction<Counter, CounterInput, StepResult<Counter, CounterInput>> handler = (snapshot, input) ->
                new StepResult.Applied<>(new Counter(snapshot.count() + 1),
                        new ProcessingOutput<>(List.of(), List.of(), List.of()));

        private Fixture() {
            this(new RejectedEntityRegistry());
        }

        private Fixture(RejectedEntityRegistry rejectedEntities) {
            router = new LaneRouter<>(definition,
                    task -> laneExecutor.execute(task),
                    timers,
                    clock,
                    this::saveCheckpoint,
                    (snapshot, effect) -> onEffect.accept(effect),
                    (entityId, entry) -> {},
                    (snapshot, persistenceFailure, setupFailure) -> {},
                    rejectedEntities);
        }

        private void saveCheckpoint(Checkpoint<Counter, CounterInput, CounterInput> checkpoint) {
            onSave.accept(checkpoint);
            saved.add(checkpoint);
        }

        private Initialization<Counter, CounterInput> prepare(Input input) {
            preparations.incrementAndGet();
            onPrepare.accept(input);
            return new Initialization<>(new Counter(0), Optional.of(new Increment()));
        }

        private Processor<Counter, CounterInput, CounterInput> createProcessor(Counter snapshot) {
            onCreateProcessor.accept(snapshot);
            return new Processor<>() {
                @Override
                public List<CounterInput> route(Counter snapshot, CounterInput input) {
                    return routes.apply(snapshot, input);
                }

                @Override
                public StepResult<Counter, CounterInput> processStep(Counter snapshot, CounterInput input) {
                    attempted.add(input);
                    return handler.apply(snapshot, input);
                }
            };
        }
    }
}
