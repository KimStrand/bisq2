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

import bisq.common.data.ByteArray;
import bisq.common.processing.Effect;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.ProcessingLane;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.persistence.PersistenceService;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Failed and archived trades across saves and restarts.
 */
class MuSigTradeFailureAndArchiveTest extends MuSigTradePersistenceFixture {
    @Test
    void executionFailureStopsTheParentAndDiscardsPendingMediationInputs() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        var payment = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new byte[]{7});
        var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId());
        // The missing redacted signatures cause execution to fail before the mediation input runs.
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(payment,
                        opened),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> {});
        try {
            var resumed = savedRecord(service, tradeId).trade();
            assertInstanceOf(Failed.class, resumed.getExecution().state());
            assertEquals(new MuSigTradeState.Failed(), resumed.getState());
            assertEquals(Set.of(ExecutionFailedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertEquals(trade.toProto(false).getMediation(),
                    resumed.toProto(false).getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertEquals(resumed.toProto(false), saved(service).trade().toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void failureReportsAndTheirAbsenceSurviveRestart(boolean hasMyFailure, boolean hasPeerFailure) throws Exception {
        var builder = MuSigV2TradeExecutionData.builder();
        if (hasMyFailure) {
            builder.myFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                    "Local failure",
                    "Local stack trace")));
        }
        if (hasPeerFailure) {
            builder.peerFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.PRICE_DEVIATION,
                    "Peer failure",
                    "Peer stack trace")));
        }
        var data = builder.build();
        MuSigSellerAsTakerState state = new Init();
        if (hasMyFailure) {
            state = new Failed();
        } else if (hasPeerFailure) {
            state = new FailedAtPeer();
        }
        var trade = tradeAt(state).withExecution(FlowSnapshot.of(state, data));
        seed(initialRecord(trade));

        var service = startAndAwaitRecovery(effect -> fail("Restoring failure details must not produce effects"));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(state, restored.getExecution().state());
            assertEquals(data.getMyFailure(), restored.getExecution().data().getMyFailure());
            assertEquals(data.getPeerFailure(), restored.getExecution().data().getPeerFailure());
            assertEquals(trade.toProto(false), saved(service).trade().toProto(false));
        } finally {
            service.shutdown().join();
        }
        verifyNoInteractions(backend, asyncBackend);
    }

    @Test
    void archivingRejectsAnUnfinishedTradeWithoutChangingItsDocument() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        seed(initialRecord(trade));

        var service = startAndAwaitRecovery(effect -> fail("No effects expected"));
        try {
            var before = savedRecord(service, tradeId).trade();
            var failure = assertThrows(ExecutionException.class, () -> await(service.archiveTrade(tradeId)));

            assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                    assertInstanceOf(MuSigProtocolException.class, failure.getCause()).getCategory());
            assertSame(before, savedRecord(service, tradeId).trade());
            assertEquals(initialRecord(trade).toProto(false), saved(service).toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void archivingPreservesChildrenAndDiscardsPendingAndLateBusinessInputsAcrossRestart() throws Exception {
        var trade = tradeAt(new TradeClosed());
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                tradeId,
                mediator);
        var closed = new MuSigMediationClosedMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                result,
                MuSigMediationResultService.signMediationResult(result, mediatorKeys));
        var peer = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        seed(initialRecord(trade));
        bisq.trade.protobuf.MuSigV2Trade archived;

        var service = startAndAwaitRecovery(effect -> fail("Archiving must not produce effects"));
        try {
            var before = savedRecord(service, tradeId).trade();
            assertEquals(new MuSigTradeState.Active(), before.getState());
            await(submitMessage(service, closed));
            await(submitMessage(service, peer));
            assertEquals(2, saved(service).pendingInputs().size());

            await(service.archiveTrade(tradeId));
            var historical = savedRecord(service, tradeId).trade();

            assertEquals(new MuSigTradeState.Historical(), historical.getState());
            assertInstanceOf(MuSigTradeState.Historical.class, historical.getState());
            assertEquals(Set.of(TradeArchivedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertSame(before.getExecution(), historical.getExecution());
            assertSame(before.getMediation(), historical.getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            archived = historical.toProto(false);
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("Historical children must stay stopped"));
        try {
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(archived, restored.toProto(false));
            await(submitMessage(restoredService, opened));
            assertSame(restored, savedRecord(restoredService, opened.getTradeId()).trade());
            await(submitMessage(restoredService, closed));
            assertSame(restored, savedRecord(restoredService, closed.getTradeId()).trade());
            await(submitMessage(restoredService, peer));
            assertSame(restored, savedRecord(restoredService, peer.getTradeId()).trade());
            await(restoredService.onDepositTxConfirmed(tradeId));
            assertSame(restored, savedRecord(restoredService, tradeId).trade());
            for (var command : List.of(restoredService.archiveTrade(tradeId),
                    restoredService.requestMediation(tradeId),
                    restoredService.acceptMediationResult(tradeId),
                    restoredService.rejectMediationResult(tradeId),
                    restoredService.paymentReceiptConfirmed(tradeId))) {
                var failure = assertThrows(ExecutionException.class, () -> await(command));
                assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                        assertInstanceOf(MuSigProtocolException.class, failure.getCause()).getCategory());
            }
            assertSame(restored, savedRecord(restoredService, tradeId).trade());
            assertEquals(archived, saved(restoredService).trade().toProto(false));
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void executionAndParentFailureCommitSeparatelyAndTheFailedParentCanBeArchived() throws UnresolvableProtobufException {
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        var records = new ArrayList<MuSigV2TradeRecord>();
        var effects = new ArrayList<Effect>();
        var failure = new IllegalStateException("Signing unavailable");
        when(backend.signSwapTx(any())).thenThrow(failure);
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
        records.add(MuSigV2TradeRecord.fromCheckpoint(initialCheckpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>builder(Event.class,
                        ignored -> processor,
                        inputValidator)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                input -> {},
                checkpoint -> records.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint)),
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage) {
                        return;
                    }
                    // The execution failure and its parent notification are saved before sending the report.
                    var saved = records.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(MuSigTradeState.Active.class, saved.trade().getState());
                    assertInstanceOf(Failed.class, saved.trade().getExecution().state());
                    assertEquals(List.of(new ExecutionFailedEvent()), saved.pendingInputs());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    assertInstanceOf(MuSigReportErrorMessage.class, ((SendMessage<?, ?>) effect).message());
                    effects.add(effect);
                },
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            var failedResult = assertInstanceOf(ProcessingSummary.Failed.class, completed.processingSummary());
            var reported = assertInstanceOf(MuSigProtocolException.class, failedResult.cause());

            assertSame(failure, reported.getCause());
            assertSame(published.get(), failedResult.snapshot());
            var failed = published.get().document();
            assertInstanceOf(MuSigTradeState.Failed.class, failed.getState());
            assertEquals(Set.of(ExecutionFailedEvent.class), records.getLast().processedEventTypes().get(Flow.TRADE));
            assertSame(trade.getMediation(), failed.getMediation());
            var executionFailure = records.stream()
                    .filter(record -> record.trade().getExecution().state() instanceof Failed)
                    .findFirst().orElseThrow();
            assertInstanceOf(MuSigTradeState.Active.class, executionFailure.trade().getState());
            assertEquals(List.of(new ExecutionFailedEvent()), executionFailure.pendingInputs());
            assertSame(executionFailure.trade().getExecution(), failed.getExecution());
            assertSame(failed, records.getLast().trade());
            assertTrue(records.getLast().pendingInputs().isEmpty());
            assertTrue(records.getLast().pendingEffects().isEmpty());
            assertEquals(1, effects.size());

            var archived = assertInstanceOf(SubmissionResult.Completed.class, lane.submit(new TradeArchivedEvent()).join());
            var applied = assertInstanceOf(ProcessingSummary.Applied.class, archived.processingSummary());

            assertSame(published.get(), applied.snapshot());
            var historical = published.get().document();
            assertInstanceOf(MuSigTradeState.Historical.class, historical.getState());
            assertSame(failed.getExecution(), historical.getExecution());
            assertSame(failed.getMediation(), historical.getMediation());
            assertSame(historical, records.getLast().trade());
            assertEquals(Set.of(ExecutionFailedEvent.class, TradeArchivedEvent.class),
                    records.getLast().processedEventTypes().get(Flow.TRADE));
            assertEquals(historical.toProto(false), MuSigV2Trade.fromProto(historical.toProto(false)).toProto(false));
            assertEquals(1, effects.size());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aSavedExecutionFailureNotifiesTheParentAfterRestart() throws Exception {
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        var records = new ArrayList<MuSigV2TradeRecord>();
        when(backend.signSwapTx(any())).thenThrow(new IllegalStateException("Signing unavailable"));
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>builder(Event.class,
                        ignored -> processor,
                        inputValidator)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                input -> {},
                checkpoint -> records.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint)),
                (current, effect) -> {},
                (current, persistenceFailure, setupFailure) -> {});
        try {
            lane.resume().join();
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertInstanceOf(ProcessingSummary.Failed.class, completed.processingSummary());
        } finally {
            lane.shutdown().join();
        }

        // Simulate a restart from the checkpoint before sending the report and notifying the parent.
        var beforeParentReaction = records.stream()
                .filter(record -> record.trade().getExecution().state() instanceof Failed &&
                        record.trade().getState() instanceof MuSigTradeState.Active)
                .findFirst().orElseThrow();
        assertEquals(List.of(new ExecutionFailedEvent()), beforeParentReaction.pendingInputs());
        assertTrue(beforeParentReaction.processedEventTypes().getOrDefault(Flow.TRADE, Set.of()).isEmpty());
        assertEquals(1, beforeParentReaction.pendingEffects().size());
        assertTrue(beforeParentReaction.scheduledEvents().isEmpty());
        var errorReport = assertInstanceOf(SendMessage.class, beforeParentReaction.pendingEffects().getFirst());
        assertInstanceOf(MuSigReportErrorMessage.class, errorReport.message());
        seed(beforeParentReaction);
        var effects = new ArrayList<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(new MuSigTradeState.Failed(), restored.getState());
            assertEquals(Set.of(ExecutionFailedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertEquals(beforeParentReaction.trade().toProto(false).getExecution(),
                    restored.toProto(false).getExecution());
            assertEquals(beforeParentReaction.trade().toProto(false).getMediation(),
                    restored.toProto(false).getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertTrue(saved(service).pendingEffects().isEmpty());
            assertEquals(List.of(errorReport), effects);
            await(service.archiveTrade(tradeId));
            assertEquals(new MuSigTradeState.Historical(), savedRecord(service, tradeId).trade().getState());
            assertEquals(List.of(errorReport), effects);
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void archivingDoesNotLoseAnOutgoingMessageWhenSendingStillFails() throws Exception {
        var trade = buyerTradeAt(new TradeClosed());
        var message = new CooperativeClosureMessage_G(StringUtils.createUid(),
                trade.getId(),
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                new ByteArray(new byte[]{8}));
        var outgoing = new SendMessage<>(message, contract.getMaker().getNetworkId());
        var record = new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(), List.of(outgoing));
        seed(record);

        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        var attempts = new ConcurrentLinkedQueue<Effect>();
        var sendFailure = new IllegalStateException("Transport unavailable");
        mockNetworkSend(effect -> {
            attempts.add(effect);
            throw sendFailure;
        });
        bisq.trade.protobuf.MuSigV2Trade archived;
        var service = serviceWithClock(services, grpcClient, contract::getTakeOfferDate);
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertTrue(await(service.initialize()));
            assertSame(sendFailure,
                    assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture())).getCause());
            assertEquals(List.of(outgoing), List.copyOf(attempts));
            var before = savedRecord(service, trade.getId()).trade();

            assertSame(sendFailure,
                    assertThrows(ExecutionException.class, () -> await(service.archiveTrade(trade.getId()))).getCause());

            var historical = savedRecord(service, trade.getId()).trade();
            assertEquals(new MuSigTradeState.Historical(), historical.getState());
            assertSame(before.getExecution(), historical.getExecution());
            assertSame(before.getMediation(), historical.getMediation());
            assertEquals(historical.getState(),
                    service.getTradeReadModel(trade.getId()).stateObservable().get());
            var saved = saved(service);
            archived = historical.toProto(false);
            assertEquals(archived, saved.trade().toProto(false));
            assertEquals(List.of(outgoing), saved.pendingEffects());
            assertTrue(saved.pendingInputs().isEmpty());
            assertEquals(Set.of(TradeArchivedEvent.class), saved.processedEventTypes().get(Flow.TRADE));
            assertEquals(List.of(outgoing, outgoing), List.copyOf(attempts));
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }

        var effects = new ConcurrentLinkedQueue<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertEquals(archived, savedRecord(restoredService, trade.getId()).trade().toProto(false));
            assertEquals(1, effects.size());
            var sent = assertInstanceOf(SendMessage.class, effects.element());
            assertEquals(message.toProto(false), ((CooperativeClosureMessage_G) sent.message()).toProto(false));
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Completed sends must not replay"));
        try {
            assertEquals(archived, savedRecord(restartedService, trade.getId()).trade().toProto(false));
            assertTrue(saved(restartedService).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedParentSkipsChildRecoveryButRetriesCommittedOutgoingMessages(boolean historical) throws Exception {
        var tradeState = historical ? new MuSigTradeState.Historical() : new MuSigTradeState.Failed();
        var trade = withTradeState(tradeAt(new AccountPayloadReceived()), tradeState);
        var report = new MuSigReportErrorMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                "Saved failure",
                "Saved stack trace",
                TradeProtocolFailure.UNKNOWN);
        var outgoing = new SendMessage<>(report, contract.getMaker().getNetworkId());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(new DepositTxConfirmedEvent()),
                List.of(),
                List.of(outgoing, new ObserveDepositTxConfirmation())));

        for (int restart = 0; restart < 2; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(restart == 0 ? 1 : 0, effects.size());
                if (restart == 0) {
                    var sent = assertInstanceOf(SendMessage.class, effects.element());
                    assertEquals(report.toProto(false), ((MuSigReportErrorMessage) sent.message()).toProto(false));
                }
                verifyNoInteractions(backend, asyncBackend);
            } finally {
                service.shutdown().join();
            }
        }
    }
}
