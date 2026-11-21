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
import bisq.common.processing.Effect;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.ProcessingLane;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.runNextRetry;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Failed saves of a trade step: what the trade keeps, what it repeats and what recovery reports.
 */
class MuSigTradeCheckpointFailureTest extends MuSigTradePersistenceFixture {
    @Test
    void failedCheckpointPreventsEffectsAndRetryDoesNotRerunSigning() {
        var failWrites = new AtomicBoolean(false);
        var saveFailure = new IllegalStateException("Disk unavailable");
        var effects = new ArrayList<Effect>();
        var logs = new ArrayList<ProtocolLogMessage>();
        var writes = new ArrayList<MuSigV2TradeRecord>();
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        Clock clock = contract::getTakeOfferDate;
        var timerExecutor = mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS);
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var checkpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(checkpoint.snapshot());
        writes.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>builder(Event.class,
                        ignored -> processor,
                        inputValidator)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(checkpoint),
                Runnable::run,
                timerExecutor,
                clock,
                definition,
                input -> {},
                saved -> {
                    var record = MuSigV2TradeRecord.fromCheckpoint(saved);
                    if (failWrites.get() && record.trade().getExecution().state() instanceof PaymentReceiptConfirmed) {
                        throw saveFailure;
                    }
                    writes.add(record);
                },
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage entry) {
                        logs.add(entry);
                        return;
                    }
                    var saved = writes.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    assertEquals(1, saved.scheduledEvents().size());
                    effects.add(effect);
                },
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            failWrites.set(true);
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var applied = assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
            assertSame(published.get(), applied.snapshot());
            var calculatedTrade = published.get().document();
            assertInstanceOf(PaymentReceiptConfirmed.class, calculatedTrade.getExecution().state());
            assertTrue(calculatedTrade.getExecution().data().getMySwapTxSignatureResponse().isPresent());
            assertSame(trade, writes.getLast().trade());
            assertTrue(effects.isEmpty());
            assertTrue(logs.isEmpty());
            verify(timerExecutor, never()).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), eq(TimeUnit.NANOSECONDS));
            verify(timerExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));

            var blocked = assertInstanceOf(SubmissionResult.NotProcessed.class,
                    lane.submit(new MediationRequestedEvent()).join());
            assertSame(saveFailure, blocked.cause());
            assertSame(trade, writes.getLast().trade());
            assertTrue(effects.isEmpty());
            assertTrue(logs.isEmpty());
            verify(timerExecutor, never()).schedule(any(Runnable.class), org.mockito.ArgumentMatchers.anyLong(), eq(TimeUnit.NANOSECONDS));
            verify(timerExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));

            failWrites.set(false);
            lane.resume().join();
            var saved = writes.getLast();
            assertSame(calculatedTrade, saved.trade());
            assertEquals(1, effects.size());
            assertInstanceOf(PaymentReceivedMessage_F.class, ((SendMessage<?, ?>) effects.getFirst()).message());
            assertTrue(saved.pendingEffects().isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertInstanceOf(MuSigMediationState.Init.class, saved.trade().getMediation().orElseThrow().state());
            assertEquals(1, saved.scheduledEvents().size());
            var scheduled = saved.scheduledEvents().getFirst();
            assertEquals(CLOSE_TRADE_TIMEOUT_ID, scheduled.id());
            assertEquals(Instant.ofEpochMilli(clock.now()).plus(CLOSE_TRADE_TIMEOUT), scheduled.deadline());
            assertInstanceOf(SellersCloseTradeTimeoutEvent.class, scheduled.event());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            assertFalse(logs.isEmpty());
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aCheckpointFailureAfterSendingDoesNotFailTheTradeOrRepeatTheSend() {
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable after sending");
        var effects = new ArrayList<Effect>();
        var writes = new ArrayList<MuSigV2TradeRecord>();
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        Clock clock = contract::getTakeOfferDate;
        var timerExecutor = mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS);
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var checkpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(checkpoint.snapshot());
        writes.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>builder(Event.class,
                        ignored -> processor,
                        inputValidator)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(checkpoint),
                Runnable::run,
                timerExecutor,
                clock,
                definition,
                input -> {},
                saved -> {
                    var record = MuSigV2TradeRecord.fromCheckpoint(saved);
                    if (failWrites.get() && !effects.isEmpty()) {
                        assertTrue(record.pendingEffects().isEmpty());
                        assertEquals(1, record.scheduledEvents().size());
                        throw saveFailure;
                    }
                    writes.add(record);
                },
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage) {
                        return;
                    }
                    var saved = writes.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    effects.add(effect);
                },
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var applied = assertInstanceOf(ProcessingSummary.Applied.class, pending.processingSummary());
            assertSame(published.get(), applied.snapshot());
            var confirmedTrade = published.get().document();
            assertInstanceOf(PaymentReceiptConfirmed.class, confirmedTrade.getExecution().state());
            assertInstanceOf(MuSigTradeState.Active.class, confirmedTrade.getState());
            assertEquals(1, effects.size());
            assertInstanceOf(PaymentReceivedMessage_F.class, ((SendMessage<?, ?>) effects.getFirst()).message());
            var savedBeforeRemoval = writes.getLast();
            assertSame(confirmedTrade, savedBeforeRemoval.trade());
            assertEquals(List.of(effects.getFirst()), savedBeforeRemoval.pendingEffects());
            assertEquals(1, savedBeforeRemoval.scheduledEvents().size());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            verify(timerExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));

            var blocked = assertInstanceOf(SubmissionResult.NotProcessed.class,
                    lane.submit(new MediationRequestedEvent()).join());
            assertSame(saveFailure, blocked.cause());
            assertSame(savedBeforeRemoval, writes.getLast());
            assertEquals(1, effects.size());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            verify(timerExecutor).schedule(any(Runnable.class), eq(1L), eq(TimeUnit.SECONDS));

            failWrites.set(false);
            lane.resume().join();
            var saved = writes.getLast();
            assertSame(confirmedTrade, saved.trade());
            assertEquals(1, effects.size());
            assertTrue(saved.pendingEffects().isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertInstanceOf(MuSigMediationState.Init.class, saved.trade().getMediation().orElseThrow().state());
            assertEquals(1, saved.scheduledEvents().size());
            var scheduled = saved.scheduledEvents().getFirst();
            assertEquals(CLOSE_TRADE_TIMEOUT_ID, scheduled.id());
            assertEquals(Instant.ofEpochMilli(clock.now()).plus(CLOSE_TRADE_TIMEOUT), scheduled.deadline());
            assertInstanceOf(SellersCloseTradeTimeoutEvent.class, scheduled.event());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCheckpointIsNotIncludedInOtherSaves(boolean saveOtherTrade) throws Exception {
        var persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigV2TradeStore> persistence = mock(Persistence.class);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        doReturn(persistence).when(persistenceService).getOrCreatePersistence(
                any(),
                any(DbSubDirectory.class),
                anyString(),
                any(MuSigV2TradeStore.class));
        var lastWritten = new AtomicReference<MuSigV2TradeStore>();
        var failNextWrite = new AtomicBoolean();
        var diskFailure = new IOException("Disk unavailable");
        when(persistence.persistAsync(any())).thenAnswer(invocation -> {
            if (failNextWrite.getAndSet(false)) {
                return CompletableFuture.failedFuture(diskFailure);
            }
            lastWritten.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        var initial = initialRecord(tradeAt(new TradeClosed()));
        var other = buyerTradeAt(new TradeClosed());
        var service = serviceWithClock(services, grpcClient, contract::getTakeOfferDate, retries);
        try {
            service.getPersistableStore().put(initial);
            service.getPersistableStore().put(initialRecord(other));
            lastWritten.set(service.getPersistableStore().getClone());
            assertTrue(await(service.initialize()));
            await(service.getRecoveryFuture());

            failNextWrite.set(true);
            var failure = assertThrows(ExecutionException.class, () -> await(service.archiveTrade(tradeId)));
            assertSame(diskFailure, bisq.common.util.ExceptionUtil.getRootCause(failure));
            assertInstanceOf(MuSigTradeState.Historical.class, service.getTradeReadModel(tradeId).getState());
            assertTrue(service.getTradeReadModel(tradeId).getPersistenceError().isPresent());

            if (saveOtherTrade) {
                await(service.archiveTrade(other.getId()));
            } else {
                assertTrue(await(service.persist()));
            }
            var written = lastWritten.get().toProto(false).getTradeByIdOrThrow(tradeId);
            assertEquals(initial.trade().getState().toProto(false), written.getTrade().getState());
            assertEquals(initial.toProto(false), written);
            assertEquals(initial.toProto(false), savedRecord(service, tradeId).toProto(false));

            runNextRetry(retries);
            await(whenValue(service.getTradeReadModel(tradeId).persistenceErrorObservable(), Optional::isEmpty));
            assertInstanceOf(MuSigTradeState.Historical.class, savedRecord(service, tradeId).trade().getState());
            assertEquals(saveOtherTrade ? new MuSigTradeState.Historical() : new MuSigTradeState.Active(),
                    savedRecord(service, other.getId()).trade().getState());
            assertEquals(service.getPersistableStore().toProto(false), lastWritten.get().toProto(false));
            assertTrue(service.getTradeReadModel(tradeId).getPersistenceError().isEmpty());
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistenceFailureDuringErrorHandlingIsReportedByRecovery(boolean failAfterReportSent) throws Exception {
        var persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigV2TradeStore> persistence = mock(Persistence.class);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        doReturn(persistence).when(persistenceService).getOrCreatePersistence(
                any(),
                any(DbSubDirectory.class),
                anyString(),
                any(MuSigV2TradeStore.class));
        var lastWritten = new AtomicReference<MuSigV2TradeStore>();
        var report = new AtomicReference<MuSigReportErrorMessage>();
        var diskFailure = new IOException("Disk unavailable");
        when(persistence.persistAsync(any())).thenAnswer(invocation -> {
            MuSigV2TradeStore store = invocation.getArgument(0);
            boolean failed = store.getTrades().getFirst().trade().getExecution().state() instanceof Failed;
            if (failed && (!failAfterReportSent || report.get() != null)) {
                return CompletableFuture.failedFuture(diskFailure);
            }
            lastWritten.set(store);
            return CompletableFuture.completedFuture(null);
        });
        var backendFailure = new IllegalStateException("Backend unavailable");
        when(backend.customCloseTrade(any())).thenThrow(backendFailure);
        mockNetworkSend(effect ->
                report.set((MuSigReportErrorMessage) ((SendMessage<?, ?>) effect).message()));
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            service.getPersistableStore().put(pendingCustomSettlement(TX_ID));
            lastWritten.set(service.getPersistableStore().getClone());

            assertTrue(await(service.initialize()));
            var completion = assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture()));

            assertSame(diskFailure, bisq.common.util.ExceptionUtil.getRootCause(completion));
            assertTrue(service.getTradeReadModel(tradeId).getMyFailure().orElseThrow().errorMessage()
                    .contains("Backend unavailable"));
            assertInstanceOf(Failed.class,
                    await(whenExecutionState(service, tradeId, Failed.class)));
            var savedRecord = lastWritten.get().getTrades().getFirst();
            if (failAfterReportSent) {
                assertInstanceOf(Failed.class, savedRecord.trade().getExecution().state());
                var pending = (MuSigReportErrorMessage) ((SendMessage<?, ?>) savedRecord.pendingEffects().getFirst()).message();
                assertEquals(report.get().getId(), pending.getId());
            } else {
                assertInstanceOf(CustomPayoutSigned.class, savedRecord.trade().getExecution().state());
                assertNull(report.get(), "Nothing may be sent before the failure is durably recorded");
            }
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void errorReportDeliveryFailureWhileResumingIsReportedByRecovery() throws Exception {
        seed(pendingCustomSettlement(TX_ID));
        var backendFailure = new IllegalStateException("Backend unavailable");
        when(backend.customCloseTrade(any())).thenThrow(backendFailure);
        var rejectedReport = new AtomicReference<MuSigReportErrorMessage>();
        var deliveryFailure = new IllegalStateException("Transport unavailable");

        var completion = assertThrows(ExecutionException.class, () -> startAndAwaitRecovery(effect -> {
            rejectedReport.set((MuSigReportErrorMessage) ((SendMessage<?, ?>) effect).message());
            throw deliveryFailure;
        }));

        assertSame(deliveryFailure, completion.getCause());
        assertTrue(rejectedReport.get().getErrorMessage().contains("Backend unavailable"));
        var effects = new ArrayList<Effect>();
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertInstanceOf(Failed.class,
                    await(whenExecutionState(service, tradeId, Failed.class)));
            assertEquals(1, effects.size());
            var replayed = (MuSigReportErrorMessage) ((SendMessage<?, ?>) effects.getFirst()).message();
            assertEquals(rejectedReport.get().getId(), replayed.getId());
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }
    }
}
