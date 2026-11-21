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

import bisq.bonded_roles.release.AppType;
import bisq.common.observable.ReadOnlyObservable;
import bisq.common.observable.map.HashMapObserver;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.Clock;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;

import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Helpers for service inputs, initialization and observing published trade results in tests. */
public final class MuSigTradeServiceTestSupport {
    private MuSigTradeServiceTestSupport() {}

    public static MuSigTradeService serviceWithClock(ServiceProvider services,
                                                     MusigGrpcClient grpcClient,
                                                     Clock clock) {
        return new MuSigTradeService(services, grpcClient, AppType.DESKTOP,
                clock,
                ExecutorFactory.newSingleThreadScheduledExecutor("MuSigTradeServiceV2-timers"));
    }

    /** Runs business deadlines normally while giving tests control over retry delivery. */
    public static MuSigTradeService serviceWithClock(ServiceProvider services,
                                                     MusigGrpcClient grpcClient,
                                                     Clock clock,
                                                     BlockingQueue<Runnable> retries) {
        var realTimers = ExecutorFactory.newSingleThreadScheduledExecutor("MuSigTradeServiceV2-timers");
        var timerExecutor = mock(ScheduledExecutorService.class, delegatesTo(realTimers));
        captureRetries(timerExecutor, retries);
        return new MuSigTradeService(services, grpcClient, AppType.DESKTOP, clock, timerExecutor);
    }

    /**
     * Captures business deadline callbacks, retaining cancelled or replaced callbacks for stale-delivery checks.
     * The list counts scheduled deadlines, not active registrations. Retry callbacks are captured separately.
     * Running a callback attempts delivery on the owning lane.
     */
    public static MuSigTradeService serviceWithCapturedTimers(ServiceProvider services,
                                                              MusigGrpcClient grpcClient,
                                                              Clock clock,
                                                              List<Runnable> timers) {
        return serviceWithCapturedTimers(services, grpcClient, clock, timers, new LinkedBlockingQueue<>());
    }

    public static MuSigTradeService serviceWithCapturedTimers(ServiceProvider services,
                                                              MusigGrpcClient grpcClient,
                                                              Clock clock,
                                                              List<Runnable> timers,
                                                              BlockingQueue<Runnable> retries) {
        var timerExecutor = mock(ScheduledExecutorService.class, org.mockito.Mockito.RETURNS_MOCKS);
        when(timerExecutor.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                .thenAnswer(invocation -> {
                    timers.add(invocation.getArgument(0));
                    return mock(ScheduledFuture.class);
                });
        captureRetries(timerExecutor, retries);
        return new MuSigTradeService(services, grpcClient, AppType.DESKTOP, clock, timerExecutor);
    }

    private static void captureRetries(ScheduledExecutorService timerExecutor, BlockingQueue<Runnable> retries) {
        doAnswer(invocation -> {
            retries.add(invocation.getArgument(0));
            return mock(ScheduledFuture.class);
        }).when(timerExecutor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS));
    }

    public static Runnable takeNextRetry(BlockingQueue<Runnable> retries) throws InterruptedException {
        var retry = retries.poll(10, TimeUnit.SECONDS);
        assertNotNull(retry, "The lane must schedule a retry");
        return retry;
    }

    public static void runNextRetry(BlockingQueue<Runnable> retries) throws InterruptedException {
        takeNextRetry(retries).run();
    }

    public static void seed(MuSigTradeService service, MuSigV2TradeRecord record) {
        service.getPersistableStore().put(record);
    }

    /** Submits protocol inputs for tests that need a completion future; listener routing is covered separately. */
    public static CompletableFuture<Void> submitMessage(MuSigTradeService service, EnvelopePayloadMessage message) {
        return switch (message) {
            case MuSigTradeMessage tradeMessage -> service.submit(tradeMessage.getTradeId(), tradeMessage);
            case MuSigMediationOpenedMessage opened -> service.submit(opened.getTradeId(), opened);
            case MuSigMediationClosedMessage closed -> service.submit(closed.getTradeId(), closed);
            case MuSigDisputeCasePaymentDetailsRequest request -> service.submit(request.getTradeId(), request);
            default -> CompletableFuture.completedFuture(null);
        };
    }

    /** Call on a fresh service, before initialize. */
    public static CompletableFuture<MuSigV2Trade<?>> restoreTrade(MuSigTradeService service, MuSigV2TradeRecord record) {
        try {
            seed(service, record);
            return service.initialize().thenCompose(ignored -> service.getRecoveryFuture())
                    .thenApply(ignored -> savedRecord(service, record.trade().getId()).trade());
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    /**
     * Reads the in-memory record store after the caller has awaited the relevant operation.
     * This does not drain the queue or prove a successful disk write; file-persistence tests read the file separately.
     */
    public static MuSigV2TradeRecord savedRecord(MuSigTradeService service, String tradeId) {
        return service.getPersistableStore().getTrades().stream()
                .filter(record -> record.trade().getId().equals(tradeId))
                .findFirst().orElseThrow();
    }

    /** Waits for a committed record when effect-only or timer work does not change an observable state. */
    public static MuSigV2TradeRecord awaitSavedRecord(MuSigTradeService service,
                                                     String tradeId,
                                                     Predicate<MuSigV2TradeRecord> expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        do {
            var record = savedRecord(service, tradeId);
            if (expected.test(record)) {
                return record;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        } while (System.nanoTime() < deadline);
        throw new AssertionError("Expected saved state was not reached for " + tradeId);
    }

    public static <T> CompletableFuture<T> whenValue(ReadOnlyObservable<T> observable, Predicate<T> expected) {
        var completion = new CompletableFuture<T>();
        var pin = observable.addObserver(value -> {
            if (expected.test(value)) {
                completion.complete(value);
            }
        });
        completion.whenComplete((value, failure) -> pin.unbind());
        return completion;
    }

    public static CompletableFuture<MuSigTradeReadModel> whenReadModel(MuSigTradeService service, String tradeId) {
        var completion = new CompletableFuture<MuSigTradeReadModel>();
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String id, MuSigTradeReadModel model) {
                if (tradeId.equals(id)) {
                    completion.complete(model);
                }
            }
        });
        completion.whenComplete((value, failure) -> pin.unbind());
        return completion;
    }

    public static CompletableFuture<MuSigTradeExecutionState> whenExecutionState(
            MuSigTradeService service,
            String tradeId,
            Class<? extends MuSigTradeExecutionState> stateType) {
        return whenReadModel(service, tradeId)
                .thenCompose(model -> whenValue(model.executionStateObservable(), stateType::isInstance));
    }
}
