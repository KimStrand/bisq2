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
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.security.keys.KeyGeneration;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeRequest;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecision;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.acceptedByMyself;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mediation decisions and the mediated payout across saves and restarts.
 */
class MuSigMediationPersistenceTest extends MuSigTradePersistenceFixture {
    @Test
    void signingWaitsForTheLocalAcceptanceCheckpointToSucceed() throws GeneralSecurityException {
        var trade = tradeWithMediationResult();
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class)),
                List.of(),
                List.of(),
                List.of());
        var saved = new AtomicReference<>(record);
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable");
        var effects = new ArrayList<Effect>();
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, record.processedEventTypes()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
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
                checkpoint -> {
                    var next = MuSigV2TradeRecord.fromCheckpoint(checkpoint);
                    if (failWrites.get() && next.pendingInputs().stream()
                            .anyMatch(input -> input instanceof MediationResultByMyselfAcceptedEvent)) {
                        throw saveFailure;
                    }
                    saved.set(next);
                },
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage) {
                        return;
                    }
                    assertSame(current.document(), saved.get().trade());
                    assertInstanceOf(CustomPayoutSigned.class, saved.get().trade().getExecution().state());
                    assertEquals(effect, saved.get().pendingEffects().getFirst());
                    effects.add(effect);
                },
                (current, persistenceFailure, setupFailure) -> published.set(current));
        when(backend.signCustomPayoutTx(any())).thenAnswer(invocation -> {
            var checkpoint = saved.get();
            assertInstanceOf(DepositTxConfirmed.class, checkpoint.trade().getExecution().state());
            assertTrue(checkpoint.trade().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    checkpoint.trade().getMediation().orElseThrow().myDecision().state());
            assertEquals(Set.of(MediationResultAcceptedEvent.class),
                    checkpoint.processedEventTypes().get(Flow.MY_MEDIATION_DECISION));
            assertFalse(checkpoint.processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByMyselfAcceptedEvent.class));
            assertEquals(List.of(acceptedByMyself(result)),
                    checkpoint.pendingInputs());
            return new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false);
        });
        try {
            lane.resume().join();
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new MediationResultAcceptedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var deferred = assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
            assertSame(published.get(), deferred.snapshot());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    published.get().document().getMediation().orElseThrow().myDecision().state());
            assertInstanceOf(DepositTxConfirmed.class, published.get().document().getExecution().state());
            assertTrue(published.get().document().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
            verify(backend, never()).signCustomPayoutTx(any());
            assertTrue(effects.isEmpty());
            assertEquals(record.toProto(false), saved.get().toProto(false));

            failWrites.set(false);
            var resumed = lane.resume().join();

            assertSame(resumed.document(), saved.get().trade());
            assertInstanceOf(CustomPayoutSigned.class, resumed.document().getExecution().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    saved.get().trade().getMediation().orElseThrow().myDecision().state());
            assertTrue(saved.get().pendingInputs().isEmpty());
            assertTrue(saved.get().pendingEffects().isEmpty());
            assertTrue(saved.get().processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByMyselfAcceptedEvent.class));
            assertEquals(1, effects.size());
            assertInstanceOf(MuSigCustomPayoutPsbtMessage.class, ((SendMessage<?, ?>) effects.getFirst()).message());
            verify(backend, times(1)).signCustomPayoutTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedLocalAcceptanceSurvivesRestartAndSigningFailure(boolean signingFails) throws Exception {
        var trade = tradeWithMediationResult();
        trade = trade.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class,
                        MuSigMediationClosedMessage.class), Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(acceptedByMyself(result)),
                List.of(),
                List.of()));
        if (signingFails) {
            when(backend.signCustomPayoutTx(any())).thenThrow(new IllegalStateException("Signing unavailable"));
        } else {
            when(backend.signCustomPayoutTx(any())).thenReturn(
                    new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false));
        }
        var effects = new ArrayList<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var restored = savedRecord(service, tradeId).trade();
                var decision = restored.getMediation().orElseThrow().myDecision();
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class, decision.state());
                assertEquals(Set.of(MediationResultAcceptedEvent.class),
                        saved(service).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                                Set.of()));
                if (signingFails) {
                    assertInstanceOf(Failed.class, restored.getExecution().state());
                    assertEquals(new MuSigTradeState.Failed(), restored.getState());
                    assertTrue(restored.getExecution().data().getMyCustomPayoutPsbt().isEmpty());
                    assertEquals("Signing unavailable",
                            restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                } else {
                    assertInstanceOf(CustomPayoutSigned.class, restored.getExecution().state());
                    assertEquals(new MuSigTradeState.Active(), restored.getState());
                    assertEquals(TX_ID, restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().getTxId());
                }
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(1, effects.size());
                assertEquals(signingFails ? MuSigReportErrorMessage.class : MuSigCustomPayoutPsbtMessage.class,
                        ((SendMessage<?, ?>) effects.getFirst()).message().getClass());
                verify(backend, times(1)).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "missingResult, A signed mediation result is not available",
            "wrongContract, Invalid mediator signature or contract hash",
            "wrongSignature, Invalid mediator signature or contract hash",
            "invalidAmounts, Custom payout amounts must equal totalPayoutAmount",
            "differentProposal, The accepted proposal does not match the stored signed mediation result",
            "noPayout, The mediator did not propose a payout"
    })
    void savedAcceptanceIsRevalidatedBeforeSigning(String invalidData, String expectedError) throws Exception {
        var resultBuilder = result.toProto(false).toBuilder();
        switch (invalidData) {
            case "wrongContract" -> {
                byte[] wrongHash = result.getContractHash();
                wrongHash[0] ^= 1;
                resultBuilder.setContractHash(ByteString.copyFrom(wrongHash));
            }
            case "invalidAmounts" -> resultBuilder.setProposedSellerPayoutAmount(60_001);
            case "noPayout" -> resultBuilder
                    .setMediationPayoutDistributionType(MediationPayoutDistributionType.NO_PAYOUT.toProtoEnum())
                    .clearProposedBuyerPayoutAmount()
                    .clearProposedSellerPayoutAmount();
        }
        var storedResult = MuSigMediationResult.fromProto(resultBuilder.build());
        var signingKeys = invalidData.equals("wrongSignature") ? KeyGeneration.generateDefaultEcKeyPair() : mediatorKeys;
        var signedResult = new MuSigV2MediationData.SignedResult(storedResult,
                new ByteArray(MuSigMediationResultService.signMediationResult(storedResult, signingKeys)));
        var mediationData = invalidData.equals("missingResult") ? MuSigV2MediationData.initial() :
                MuSigV2MediationData.initial().withResult(signedResult);
        var trade = tradeAt(new DepositTxConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(), mediationData))
                .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
        var acceptedResult = invalidData.equals("differentProposal")
                ? MuSigMediationResult.fromProto(storedResult.toProto(false).toBuilder()
                        .setSummaryNotes("Another proposal").build())
                : storedResult;
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class),
                        Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(acceptedByMyself(acceptedResult)),
                List.of(),
                List.of()));
        var effects = new ArrayList<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, restored.getExecution().state());
                assertInstanceOf(MuSigTradeState.Failed.class, restored.getState());
                assertEquals(expectedError, restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                assertTrue(restored.getExecution().data().getMyCustomPayoutPsbt().isEmpty());
                assertInstanceOf(MuSigMediationState.Closed.class, restored.getMediation().orElseThrow().state());
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                        restored.getMediation().orElseThrow().myDecision().state());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(1, effects.size());
                assertInstanceOf(MuSigReportErrorMessage.class,
                        ((SendMessage<?, ?>) effects.getFirst()).message());
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void finalizationWaitsForThePeerAcceptanceCheckpointToSucceed() {
        var signed = signedCustomPayoutTrade();
        var trade = signed.withPeerMediationDecision(MuSigV2MediationDecision.initial());
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(),
                List.of(),
                List.of());
        var saved = new AtomicReference<>(record);
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable");
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow>, Event>(
                new StateMachineSnapshot<>(trade, record.processedEventTypes()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
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
                checkpoint -> {
                    var next = MuSigV2TradeRecord.fromCheckpoint(checkpoint);
                    if (failWrites.get() && next.pendingInputs().stream()
                            .anyMatch(input -> input instanceof MediationResultByPeerAcceptedEvent)) {
                        throw saveFailure;
                    }
                    saved.set(next);
                },
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage) {
                        return;
                    }
                    fail("No outgoing message is expected");
                },
                (current, persistenceFailure, setupFailure) -> published.set(current));
        when(backend.customCloseTrade(any())).thenAnswer(invocation -> {
            var checkpoint = saved.get();
            var execution = checkpoint.trade().getExecution();
            assertInstanceOf(CustomPayoutSigned.class, execution.state());
            assertTrue(execution.data().getPeerCustomPayoutPsbt().isEmpty());
            assertFalse(checkpoint.processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    checkpoint.trade().getMediation().orElseThrow().peerDecision().state());
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    checkpoint.processedEventTypes().get(Flow.PEER_MEDIATION_DECISION));
            assertEquals(List.of(new MediationResultByPeerAcceptedEvent(new PeerCustomPayoutPsbt(TX_ID, new byte[]{7}))), checkpoint.pendingInputs());
            return bisq.trade.protobuf.CustomCloseTradeResponse.newBuilder()
                    .setCustomPayoutTx(ByteString.copyFrom(new byte[]{11})).build();
        });
        try {
            lane.resume().join();
            var message = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
            var pending = assertInstanceOf(SubmissionResult.Pending.class, lane.submit(message).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var deferred = assertInstanceOf(ProcessingSummary.Deferred.class, pending.processingSummary());
            assertSame(published.get(), deferred.snapshot());

            verify(backend, never()).customCloseTrade(any());
            assertInstanceOf(CustomPayoutSigned.class, published.get().document().getExecution().state());
            assertTrue(published.get().document().getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    published.get().document().getMediation().orElseThrow().peerDecision().state());
            assertEquals(trade.toProto(false), saved.get().trade().toProto(false));
            assertEquals(record.processedEventTypes(), saved.get().processedEventTypes());
            assertEquals(List.of(message), saved.get().pendingInputs());
            assertTrue(saved.get().pendingEffects().isEmpty());

            failWrites.set(false);
            var resumed = lane.resume().join();

            assertSame(resumed.document(), saved.get().trade());
            assertInstanceOf(CustomPayoutClosedTrade.class, resumed.document().getExecution().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    saved.get().trade().getMediation().orElseThrow().peerDecision().state());
            assertTrue(saved.get().pendingInputs().isEmpty());
            assertTrue(saved.get().pendingEffects().isEmpty());
            assertTrue(saved.get().processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByPeerAcceptedEvent.class));
            var peerPsbt = saved.get().trade().getExecution().data().getPeerCustomPayoutPsbt().orElseThrow();
            assertEquals(TX_ID, peerPsbt.getTxId());
            assertArrayEquals(new byte[]{7}, peerPsbt.getPsbt());
            verify(backend, times(1)).customCloseTrade(any());
            verify(backend, never()).signCustomPayoutTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aSavedPeerAcceptanceFinalizesAfterRestart() throws Exception {
        var peerPsbt = new PeerCustomPayoutPsbt(TX_ID, new byte[]{7});
        seed(pendingCustomSettlement(TX_ID));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("Finalization does not send a message"));
            try {
                var closed = savedRecord(service, tradeId).trade();
                assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
                assertEquals(TX_ID, closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getTxId());
                assertArrayEquals(peerPsbt.getPsbt(),
                        closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getPsbt());
                assertEquals(Set.of(MediationResultByPeerAcceptedEvent.class),
                        saved(service).processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()));
                assertTrue(saved(service).pendingInputs().isEmpty());
                verify(backend, times(1)).customCloseTrade(new CustomCloseTradeRequest(tradeId, peerPsbt.getPsbt()).toProto(false));
                verify(backend, never()).signCustomPayoutTx(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "missingMediation, false",
            "missingResult, false",
            "noPayout, false",
            "myInit, false",
            "myRejected, false",
            "myFailed, false",
            "peerInit, false",
            "peerRejected, false",
            "peerFailed, false",
            "myRejected, true"
    })
    void savedFinalizationRequiresRecordedAgreementWithoutBlockingOtherTrades(String invalidAgreement,
                                                                              boolean mismatchingTxId) throws Exception {
        var signed = signedCustomPayoutTrade();
        MuSigV2Trade<?> trade = switch (invalidAgreement) {
            case "missingMediation" -> MuSigV2Trade.fromProto(signed.toProto(false).toBuilder().clearMediation().build());
            case "missingResult" -> signed.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                    MuSigV2MediationData.initial()));
            case "noPayout" -> {
                var noPayout = MuSigMediationResult.fromProto(result.toProto(false).toBuilder()
                        .setMediationPayoutDistributionType(MediationPayoutDistributionType.NO_PAYOUT.toProtoEnum())
                        .clearProposedBuyerPayoutAmount()
                        .clearProposedSellerPayoutAmount()
                        .build());
                yield signed.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(noPayout,
                                new ByteArray(MuSigMediationResultService.signMediationResult(noPayout, mediatorKeys))))));
            }
            case "myInit" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Init()));
            case "myRejected" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected()));
            case "myFailed" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Failed()));
            case "peerInit" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Init()));
            case "peerRejected" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected()));
            case "peerFailed" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Failed()));
            default -> throw new IllegalArgumentException(invalidAgreement);
        };
        var pending = pendingCustomSettlement(mismatchingTxId ? "34".repeat(32) : TX_ID);
        var other = buyerTradeAt(new PaymentInitiated());
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})));
        seed(new MuSigV2TradeRecord(trade,
                        trade.getMediation().isPresent() ? pending.processedEventTypes() : Map.of(),
                        pending.pendingInputs(),
                        List.of(),
                        List.of()),
                new MuSigV2TradeRecord(other,
                        Map.of(),
                        List.of(confirmation),
                        List.of(),
                        List.of()));
        var effects = new ConcurrentLinkedQueue<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> {
                var messageSent = assertInstanceOf(MuSigTradeMessage.class, ((SendMessage<?, ?>) effect).message());
                if (messageSent.getTradeId().equals(tradeId)) {
                    effects.add(effect);
                }
            });
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, restored.getExecution().state());
                assertInstanceOf(MuSigTradeState.Failed.class, restored.getState());
                assertEquals("Cannot finalize custom payout without a signed payout proposal accepted by both traders",
                        restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                assertEquals(trade.getMediation().map(mediation -> mediation.toProto(false)),
                        restored.getMediation().map(mediation -> mediation.toProto(false)));
                assertEquals(trade.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().toProto(false),
                        restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().toProto(false));
                assertTrue(restored.getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
                assertTrue(restored.getExecution().data().getCustomCloseTradeResponse().isEmpty());
                var savedRecord = service.getPersistence().read().orElseThrow().getTrades().stream()
                        .filter(record -> record.trade().getId().equals(tradeId)).findFirst().orElseThrow();
                assertEquals(restored.toProto(false), savedRecord.trade().toProto(false));
                assertTrue(savedRecord.pendingInputs().isEmpty());
                assertTrue(savedRecord.pendingEffects().isEmpty());
                assertFalse(savedRecord.processedEventTypes().get(Flow.EXECUTION)
                        .contains(MediationResultByPeerAcceptedEvent.class));
                assertEquals(1, effects.size());
                assertInstanceOf(MuSigReportErrorMessage.class,
                        ((SendMessage<?, ?>) effects.element()).message());
                assertInstanceOf(TradeClosed.class,
                        await(whenExecutionState(service, other.getId(), TradeClosed.class)));
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void aPeerFirstPayoutMismatchSurvivesRestartWithoutBlockingOtherTrades() throws Exception {
        var record = pendingCustomSettlement("34".repeat(32));
        var other = buyerTradeAt(new PaymentInitiated());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(record, new MuSigV2TradeRecord(other,
                Map.of(),
                List.of(confirmation),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 3; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var unresolved = savedRecord(service, tradeId).trade();
                assertInstanceOf(CustomPayoutSigned.class, unresolved.getExecution().state());
                assertSame(unresolved, savedRecord(service, tradeId).trade());
                await(submitMessage(service,
                        peerDecision(true, MuSigMediationResultService.getMediationResultHash(result))));
                assertSame(unresolved, savedRecord(service, tradeId).trade());
                var savedRecord = service.getPersistence().read().orElseThrow().getTrades().stream()
                        .filter(candidate -> candidate.trade().getId().equals(tradeId)).findFirst().orElseThrow();
                assertEquals(record.toProto(false), savedRecord.toProto(false));
                assertInstanceOf(TradeClosed.class,
                        await(whenExecutionState(service, other.getId(), TradeClosed.class)));
                assertEquals(restart == 0 ? 1 : 0, effects.size());
                if (restart == 0) {
                    assertInstanceOf(CooperativeClosureMessage_G.class,
                            ((SendMessage<?, ?>) effects.element()).message());
                }
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
                verify(backend, times(1)).closeTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void aKnownPayoutMismatchIsIgnoredAcrossRestartWithoutConsumingAcceptance() throws Exception {
        var signed = pendingCustomSettlement(TX_ID).trade();
        var trade = signed.withPeerMediationDecision(MuSigV2MediationDecision.initial());
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        var wrong = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                hash,
                "34".repeat(32),
                new byte[]{7});
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(wrong),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> fail("Ignored messages must not produce effects"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertEquals(initialRecord(trade).toProto(false), saved(service).toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("Finalization does not send a message"));
        try {
            assertEquals(initialRecord(trade).toProto(false), saved(restoredService).toProto(false));
            var valid = peerDecision(true, hash);
            await(submitMessage(restoredService, valid));
            var closed = savedRecord(restoredService, valid.getTradeId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            var peerDecision = closed.getMediation().orElseThrow().peerDecision();
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, peerDecision.state());
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            assertEquals(TX_ID, closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getTxId());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            await(submitMessage(restoredService, valid));
            assertSame(closed, savedRecord(restoredService, valid.getTradeId()).trade());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Completed work must not be replayed"));
        try {
            assertInstanceOf(CustomPayoutClosedTrade.class,
                    savedRecord(restartedService, tradeId).trade().getExecution().state());
            assertTrue(saved(restartedService).pendingInputs().isEmpty());
            verify(backend, never()).signCustomPayoutTx(any());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void peerDecisionsForNoPayoutAreIgnoredAndRemovedFromPendingWork(boolean accepted,
                                                                   boolean beforeCaseClosure) throws Exception {
        var noPayout = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.TRADE_ALREADY_SETTLED,
                MediationPayoutDistributionType.NO_PAYOUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        var trade = tradeAt(new DepositTxConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Open(), MuSigV2MediationData.initial()));
        try (var lane = new MuSigTradeLaneFixture<>(services, grpcClient,
                trade,
                MuSigSellerAsTakerState.class,
                new MuSigSellerAsTakerProtocol(),
                effect -> fail("Ignoring a peer decision must not produce effects"),
                entry -> fail("Ignoring a peer decision must not produce a trade log"),
                contract::getTakeOfferDate)) {
            var message = peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(noPayout));
            if (beforeCaseClosure) {
                assertInstanceOf(ProcessingSummary.Deferred.class, lane.handle(message));
                assertSame(trade, lane.getTrade());
                assertEquals(List.of(message),
                        lane.getSavedRecord().pendingInputs());
                assertTrue(lane.getSavedRecord().processedEventTypes().get(Flow.PEER_MEDIATION_DECISION).isEmpty());
            }
            var caseClosed = new MuSigMediationClosedMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId(),
                    noPayout,
                    MuSigMediationResultService.signMediationResult(noPayout, mediatorKeys));
            assertInstanceOf(ProcessingSummary.Applied.class, lane.handle(caseClosed));
            var closed = lane.getTrade();
            if (!beforeCaseClosure) {
                assertInstanceOf(ProcessingSummary.Ignored.class, lane.handle(message));
            }
            assertTrue(lane.getSavedRecord().pendingInputs().isEmpty());
            lane.resume();

            assertSame(closed, lane.getTrade());
            assertSame(trade.getExecution(), closed.getExecution());
            assertInstanceOf(MuSigTradeState.Active.class, closed.getState());
            var mediation = closed.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationState.Closed.class, mediation.state());
            assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.peerDecision().state());
            var saved = lane.getSavedRecord();
            assertEquals(Set.of(MuSigMediationClosedMessage.class), saved.processedEventTypes().get(Flow.MEDIATION));
            assertTrue(saved.processedEventTypes().get(Flow.PEER_MEDIATION_DECISION).isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertTrue(saved.pendingEffects().isEmpty());
            assertEquals(closed.toProto(false), saved.trade().toProto(false));
            verifyNoInteractions(backend);
        }
    }

    @Test
    void aFailedParentDiscardsPeerDecisionInputsWithoutChangingItsChildren() throws Exception {
        var noPayout = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.NO_PAYOUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        var failed = withTradeState(tradeAt(new Failed()), new MuSigTradeState.Failed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(noPayout,
                                new ByteArray(new byte[]{1, 2}))),
                                Optional.empty())));
        var message = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                MuSigMediationResultService.getMediationResultHash(noPayout),
                TX_ID,
                new byte[]{7});
        seed(new MuSigV2TradeRecord(failed,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class,
                        MuSigMediationClosedMessage.class)),
                List.of(message),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("No report is expected"));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertEquals(failed.toProto(false).getExecution(),
                        restored.toProto(false).getExecution());
                assertEquals(new MuSigTradeState.Failed(), restored.getState());
                var mediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Closed.class, mediation.state());
                assertTrue(mediation.data().failure().isEmpty());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
                var peer = mediation.peerDecision();
                assertInstanceOf(MuSigMediationDecisionState.Init.class, peer.state());
                assertTrue(peer.data().failure().isEmpty());
                assertTrue(saved(service).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                        Set.of()).isEmpty());
                await(submitMessage(service, message));
                assertSame(restored, savedRecord(service, message.getTradeId()).trade());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
            } finally {
                service.shutdown().join();
            }
        }
        verifyNoInteractions(backend);
    }

    @Test
    void aCaseFailureStopsItsChildrenAndRecoveryContinuesNormalSettlement() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Open(), MuSigV2MediationData.initial()));
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        byte[] wrongSignature = MuSigMediationResultService.signMediationResult(result, KeyGeneration.generateDefaultEcKeyPair());
        var invalidResult = new MuSigMediationClosedMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                result,
                wrongSignature);
        var peerAcceptance = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        var closure = new CooperativeClosureMessage_G(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new ByteArray(new byte[]{7}));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(
                        peerAcceptance,
                        invalidResult,
                        closure),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> assertFalse(effect instanceof SendMessage));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(TradeClosed.class, restored.getExecution().state());
                assertEquals(new MuSigTradeState.Active(), restored.getState());
                assertTrue(restored.getExecution().data().getMyFailure().isEmpty());
                var mediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Failed.class, mediation.state());
                assertTrue(mediation.data().failure().isPresent());
                assertTrue(mediation.data().signedResult().isEmpty());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.peerDecision().state());
                assertTrue(mediation.myDecision().data().failure().isEmpty());
                assertTrue(mediation.peerDecision().data().failure().isEmpty());
                var corrected = new MuSigMediationClosedMessage(StringUtils.createUid(),
                        tradeId,
                        mediator,
                        result,
                        MuSigMediationResultService.signMediationResult(result, mediatorKeys));
                await(submitMessage(service, corrected));
                assertSame(restored, savedRecord(service, corrected.getTradeId()).trade());
                await(submitMessage(service, peerAcceptance));
                assertSame(restored, savedRecord(service, peerAcceptance.getTradeId()).trade());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                verify(backend, times(1)).closeTrade(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void anOwnDecisionFailureSurvivesRestartAndDoesNotStopThePeerDecision() throws Exception {
        var details = new MuSigV2FailureDetails(TradeProtocolFailure.UNKNOWN,
                "Decision processing failed",
                "Decision trace");
        var own = new MuSigV2MediationDecision<>(new MuSigMediationDecisionState.Failed(),
                new MuSigV2MediationDecisionData(Optional.of(details)));
        var mediation = MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                new ByteArray(MuSigMediationResultService.signMediationResult(result, mediatorKeys))));
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(
                FlowSnapshot.of(new MuSigMediationState.Closed(),
                        mediation)).withMyMediationDecision(own);
        var peerAcceptance = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class,
                        MuSigMediationClosedMessage.class), Flow.MY_MEDIATION_DECISION, Set.of(MuSigProcessingFailedEvent.class)),
                List.of(peerAcceptance),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("No outgoing work is expected"));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
                assertEquals(new MuSigTradeState.Active(), restored.getState());
                var restoredMediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Closed.class, restoredMediation.state());
                assertTrue(restoredMediation.data().failure().isEmpty());
                assertEquals(own, restoredMediation.myDecision());
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                        restoredMediation.peerDecision().state());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
            } finally {
                service.shutdown().join();
            }
        }
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localRejectionDiscardsPeerAcceptanceWorkAcrossRestartInEitherOrder(boolean peerFirst) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                        new ByteArray(new byte[]{1, 2})))));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class)),
                List.of(),
                List.of(),
                List.of()));
        var peer = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        var effects = new ConcurrentLinkedQueue<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            if (peerFirst) {
                await(submitMessage(service, peer));
                var pending = saved(service).pendingInputs();
                assertEquals(1, pending.size());
                assertInstanceOf(MediationResultByPeerAcceptedEvent.class, pending.getFirst());
            } else {
                await(service.rejectMediationResult(tradeId));
                assertTrue(saved(service).pendingInputs().isEmpty());
            }
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            if (peerFirst) {
                assertEquals(1, saved(restoredService).pendingInputs().size());
                await(restoredService.rejectMediationResult(tradeId));
            } else {
                await(submitMessage(restoredService, peer));
            }
            var updated = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            var mediation = updated.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation.peerDecision().state());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertEquals(updated.toProto(false), saved(restoredService).trade().toProto(false));
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Discarded work must not be replayed"));
        try {
            var restored = savedRecord(restartedService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
            assertFalse(saved(restartedService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
            var mediation = restored.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation.peerDecision().state());
            assertEquals(Set.of(MediationResultRejectedEvent.class),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                            Set.of()));
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            await(submitMessage(restartedService, peer));
            assertSame(restored, savedRecord(restartedService, peer.getTradeId()).trade());
            await(restartedService.rejectMediationResult(tradeId));
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
            assertTrue(saved(restartedService).pendingInputs().isEmpty());
        } finally {
            restartedService.shutdown().join();
        }
        assertEquals(1, effects.size());
        var send = assertInstanceOf(SendMessage.class, effects.element());
        assertInstanceOf(MuSigMediationResultRejectionMessage.class, send.message());
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ignoringASavedPeerDecisionPersistsItsRemovalWithoutConsumingTheType(boolean accepted) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                        new ByteArray(new byte[]{1, 2}))),
                        Optional.empty())));
        byte[] wrongHash = MuSigMediationResultService.getMediationResultHash(result);
        wrongHash[0] ^= 1;
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class,
                        MuSigMediationClosedMessage.class)),
                List.of(peerDecision(accepted, wrongHash)),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> fail("Ignored messages must not produce effects"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            var record = saved(service);
            assertTrue(record.pendingInputs().isEmpty());
            assertTrue(record.pendingEffects().isEmpty());
            assertEquals(trade.toProto(false), record.trade().toProto(false));
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            var valid = peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(result));
            await(submitMessage(restoredService, valid));
            var updated = savedRecord(restoredService, valid.getTradeId()).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            assertEquals(accepted ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                    updated.getMediation().orElseThrow().peerDecision().state());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                    Set.of()).contains(valid.getClass()));
            assertEquals(updated.toProto(false), saved(restoredService).trade().toProto(false));
        } finally {
            restoredService.shutdown().join();
        }
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlyContradictoryPeerDecisionsSurviveRestartAndOnlyTheFirstBecomesFinal(boolean acceptedFirst) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Open(),
                MuSigV2MediationData.initial()));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class)),
                List.of(),
                List.of(),
                List.of()));
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        var first = peerDecision(acceptedFirst, hash);
        var conflicting = peerDecision(!acceptedFirst, hash);

        var service = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            await(submitMessage(service, first));
            await(submitMessage(service, conflicting));
            var saved = saved(service);
            assertEquals(2, saved.pendingInputs().size());
            assertEquals(List.of(first, conflicting), saved.pendingInputs());
            assertInstanceOf(MuSigMediationDecisionState.Init.class,
                    saved.trade().getMediation().orElseThrow().peerDecision().state());
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            assertEquals(2, saved(restoredService).pendingInputs().size());
            var closed = new MuSigMediationClosedMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId(),
                    result,
                    MuSigMediationResultService.signMediationResult(result, mediatorKeys));
            await(submitMessage(restoredService, closed));
            var updated = savedRecord(restoredService, closed.getTradeId()).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            var mediation = updated.getMediation().orElseThrow();
            assertEquals(Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of()));
            assertEquals(acceptedFirst ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                    mediation.peerDecision().state());
            assertEquals(Set.of(first.getClass()),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                    Set.of()).isEmpty());
            assertTrue(saved(restoredService).pendingInputs().stream().noneMatch(input ->
                    Set.of(first.getClass(), conflicting.getClass()).contains(input.getClass())));
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            var before = savedRecord(restartedService, tradeId).trade();
            await(submitMessage(restartedService, conflicting));
            assertSame(before, savedRecord(restartedService, conflicting.getTradeId()).trade());
            await(submitMessage(restartedService, first));
            assertSame(before, savedRecord(restartedService, first.getTradeId()).trade());
            assertEquals(Set.of(first.getClass()),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION, Set.of()));
        } finally {
            restartedService.shutdown().join();
        }
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buyerMediatedSettlementSurvivesRestartInEitherAcceptanceOrder(boolean peerFirst) throws Exception {
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        var buyerResult = new MuSigMediationResult(ContractService.getContractHash(buyer.getContract()),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(90_000L),
                Optional.of(60_000L),
                Optional.empty(),
                Optional.empty());
        buyer = buyer.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(buyerResult,
                        new ByteArray(MuSigMediationResultService.signMediationResult(buyerResult, mediatorKeys)))),
                        Optional.empty())));
        var peerAcceptance = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                buyer.getId(),
                "1.0.0",
                buyer.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                MuSigMediationResultService.getMediationResultHash(buyerResult),
                TX_ID,
                new byte[]{7});
        when(backend.signCustomPayoutTx(any())).thenReturn(
                new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false));
        var effects = new ArrayList<Effect>();

        seed(new MuSigV2TradeRecord(buyer,
                Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class)),
                List.of(),
                List.of(),
                List.of()));
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {

            if (peerFirst) {
                await(submitMessage(service, peerAcceptance));
                var waiting = savedRecord(service, peerAcceptance.getTradeId()).trade();
                assertInstanceOf(DepositTxConfirmed.class, waiting.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Init(),
                        waiting.getMediation().orElseThrow().myDecision().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        waiting.getMediation().orElseThrow().peerDecision().state());
                assertEquals(1, saved(service).pendingInputs().size());
                assertInstanceOf(MediationResultByPeerAcceptedEvent.class,
                        saved(service).pendingInputs().getFirst());
                verify(backend, never()).signCustomPayoutTx(any());
            } else {
                await(service.acceptMediationResult(buyer.getId()));
                var signed = savedRecord(service, buyer.getId()).trade();
                assertInstanceOf(CustomPayoutSigned.class, signed.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        signed.getMediation().orElseThrow().myDecision().state());
                assertEquals(new MuSigMediationDecisionState.Init(),
                        signed.getMediation().orElseThrow().peerDecision().state());
                assertTrue(saved(service).pendingInputs().isEmpty());
            }
            assertTrue(saved(service).scheduledEvents().isEmpty());
            verify(backend, never()).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(restoredService, buyer.getId()).trade();
            if (peerFirst) {
                assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        restored.getMediation().orElseThrow().peerDecision().state());
                assertEquals(1, saved(restoredService).pendingInputs().size());
                await(restoredService.acceptMediationResult(buyer.getId()));
            } else {
                assertInstanceOf(CustomPayoutSigned.class, restored.getExecution().state());
                assertEquals(TX_ID, restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().getTxId());
                await(submitMessage(restoredService, peerAcceptance));
            }
            var closed = savedRecord(restoredService, buyer.getId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    closed.getMediation().orElseThrow().myDecision().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    closed.getMediation().orElseThrow().peerDecision().state());
            assertArrayEquals(new byte[]{7}, closed.getExecution().data().getPeerCustomPayoutPsbt()
                    .orElseThrow().getPsbt());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            assertTrue(saved(restoredService).scheduledEvents().isEmpty());
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("No completed send should replay"));
        try {
            var closed = savedRecord(restartedService, buyer.getId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            await(submitMessage(restartedService, peerAcceptance));
            assertSame(closed, savedRecord(restartedService, peerAcceptance.getTradeId()).trade());
            await(restartedService.acceptMediationResult(buyer.getId()));
            assertSame(closed, savedRecord(restartedService, buyer.getId()).trade());
        } finally {
            restartedService.shutdown().join();
        }
        assertTrue(effects.stream().noneMatch(ScheduledEvent.class::isInstance));
        assertEquals(1, effects.stream().filter(SendMessage.class::isInstance).count());
        verify(backend, times(1)).signCustomPayoutTx(any());
        verify(backend, times(1)).customCloseTrade(any());
        verify(backend, never()).closeTrade(any());
    }

    private MuSigV2Trade<MuSigSellerAsTakerState> tradeWithMediationResult() throws GeneralSecurityException {
        return tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                        new ByteArray(MuSigMediationResultService.signMediationResult(result, mediatorKeys))))));
    }
}
