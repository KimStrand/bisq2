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

package bisq.trade.mu_sig_v2;

import bisq.common.processing.HandleResult;
import bisq.common.processing.Input;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.Processor;
import bisq.common.processing.lane.Initialization;
import bisq.common.processing.lane.LaneRouter;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.RejectedEntityRegistry;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.threading.ExecutorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs lanes on the production worker pool while all of its workers are busy. The earlier bounded pool rejected
 * worker requests beyond 100 waiting lanes, which closed those lanes permanently.
 */
class MuSigWorkerPoolTest {
    private static final int WAITING_LANES = 150;
    private static final long TIMEOUT_SECONDS = 10;

    private final List<String> tradeIds = IntStream.range(0, MuSigTradeService.WORKER_POOL_SIZE + WAITING_LANES)
            .mapToObj(index -> "trade-" + index)
            .toList();
    private final CountDownLatch allWorkersBusy = new CountDownLatch(MuSigTradeService.WORKER_POOL_SIZE);
    private final CountDownLatch release = new CountDownLatch(1);
    private final ExecutorService workerPool = MuSigTradeService.createWorkerPool();
    private final ScheduledExecutorService timerExecutor =
            ExecutorFactory.newSingleThreadScheduledExecutor("MuSigWorkerPoolTest-timers");
    private final LaneRouter<Count, Message> router = new LaneRouter<>(
            input -> {
            },
            ProcessingLaneDefinition.builder(Message.class, (Count snapshot) -> new BlockingProcessor(),
                    (ignoredSnapshot, ignoredInput) -> {})
                    .initializeOn(CreateLane.class, input -> new Initialization<>(new Count(0), new Message(false)))
                    .build(),
            workerPool,
            timerExecutor,
            System::currentTimeMillis,
            checkpoint -> {
            },
            (snapshot, effect) -> {
            },
            (snapshot, persistenceFailure, setupFailure) -> {
            },
            new RejectedEntityRegistry());

    @AfterEach
    void tearDown() {
        release.countDown();
        router.shutdown();
        workerPool.close();
        timerExecutor.shutdownNow();
    }

    @Test
    void laneWorkWaitsForABusyPoolAndTheLanesRemainUsable() throws Exception {
        startWithInitializedLanes();

        var waiting = submitToAllLanes(new Message(true));
        assertTrue(allWorkersBusy.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertTrue(waiting.stream().noneMatch(CompletableFuture::isDone),
                "Work for a lane without a free worker must wait instead of failing");

        release.countDown();
        assertAllApplied(waiting, 2);
        assertAllApplied(submitToAllLanes(new Message(false)), 3);
    }

    @Test
    void shutdownDrainsLaneWorkWaitingForAWorker() throws Exception {
        startWithInitializedLanes();
        var waiting = submitToAllLanes(new Message(true));
        assertTrue(allWorkersBusy.await(TIMEOUT_SECONDS, TimeUnit.SECONDS));

        var stopped = router.shutdown();
        assertFalse(stopped.isDone());
        release.countDown();

        stopped.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertAllApplied(waiting, 2);
        // The service closes its pool only after the router has drained.
        workerPool.shutdown();
        assertTrue(workerPool.awaitTermination(TIMEOUT_SECONDS, TimeUnit.SECONDS));
    }

    private void startWithInitializedLanes() throws Exception {
        router.start().get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        assertAllApplied(submitToAllLanes(new CreateLane()), 1);
    }

    private List<CompletableFuture<SubmissionResult<Count>>> submitToAllLanes(Input input) {
        return tradeIds.stream()
                .map(tradeId -> router.submit(tradeId, input))
                .toList();
    }

    private static void assertAllApplied(List<CompletableFuture<SubmissionResult<Count>>> submissions,
                                         int expectedCount) throws Exception {
        for (var submission : submissions) {
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    submission.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
            var applied = assertInstanceOf(ProcessingSummary.Applied.class, completed.processingSummary());
            assertEquals(new Count(expectedCount), applied.snapshot());
        }
    }

    private void awaitRelease() {
        try {
            if (!release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                throw new IllegalStateException("The test did not release the workers");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private final class BlockingProcessor implements Processor<Count, Message> {
        @Override
        public HandleResult<Count, Message> handle(Count snapshot, Message input) {
            if (input.waitForRelease()) {
                allWorkersBusy.countDown();
                awaitRelease();
            }
            return new HandleResult.Applied<>(new Count(snapshot.value() + 1),
                    new ProcessingOutput<>(List.of(), List.of()));
        }
    }

    private record CreateLane() implements Input {
    }

    private record Message(boolean waitForRelease) implements Input {
    }

    private record Count(int value) {
    }
}
