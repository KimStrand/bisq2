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

import bisq.account.accounts.fiat.RevolutAccountPayload;
import bisq.common.data.ByteArray;
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.SendMessage;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable.Rule;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.StateMachineResolver;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.contract.ContractSignatureData;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigTradeEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigV2EventType;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigBuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.MuSigBuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.MuSigBuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.MuSigSellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.TRADE_COMPLETED_DATE;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.acceptedByMyself;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saved format of a trade record: every state, event and effect survives a round trip, and incomplete or
 * unknown data is rejected.
 */
class MuSigTradeSerializationTest extends MuSigTradePersistenceFixture {
    @Test
    void everyCombinationOfDecisionStatesAndTheirIndependentHistoriesRoundTrips() throws Exception {
        List<MuSigMediationDecisionState> states = List.of(new MuSigMediationDecisionState.Init(),
                new MuSigMediationDecisionState.Accepted(),
                new MuSigMediationDecisionState.Rejected(),
                new MuSigMediationDecisionState.Failed());
        for (var mine : states) {
            for (var peer : states) {
                Class<?>[] myTypes = switch (mine) {
                    case MuSigMediationDecisionState.Init ignored -> new Class<?>[0];
                    case MuSigMediationDecisionState.Accepted ignored -> new Class<?>[]{MediationResultAcceptedEvent.class};
                    case MuSigMediationDecisionState.Rejected ignored -> new Class<?>[]{MediationResultRejectedEvent.class};
                    case MuSigMediationDecisionState.Failed ignored -> new Class<?>[]{MuSigProcessingFailedEvent.class};
                };
                Class<?>[] peerTypes = switch (peer) {
                    case MuSigMediationDecisionState.Init ignored -> new Class<?>[0];
                    case MuSigMediationDecisionState.Accepted ignored -> new Class<?>[]{MuSigCustomPayoutPsbtMessage.class};
                    case MuSigMediationDecisionState.Rejected ignored -> new Class<?>[]{MuSigMediationResultRejectionMessage.class};
                    case MuSigMediationDecisionState.Failed ignored -> new Class<?>[]{MuSigProcessingFailedEvent.class};
                };
                var mediation = new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                        new ByteArray(new byte[]{1, 2}))), Optional.empty());
                var trade = tradeAt(new DepositTxConfirmed())
                        .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(), mediation))
                        .withMyMediationDecision(decisionSnapshot(mine))
                        .withPeerMediationDecision(decisionSnapshot(peer));
                var record = new MuSigV2TradeRecord(trade,
                        Map.of(Flow.MY_MEDIATION_DECISION, Set.of(myTypes),
                                Flow.PEER_MEDIATION_DECISION, Set.of(peerTypes)),
                        List.of(),
                        List.of(),
                        List.of());
                var proto = record.toProto(false);
                var restoredRecord = MuSigV2TradeRecord.fromProto(
                        bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
                var restored = restoredRecord.trade().getMediation().orElseThrow();
                assertEquals(record.processedEventTypes(), restoredRecord.processedEventTypes());
                assertEquals(decisionSnapshot(mine), restored.myDecision());
                assertEquals(decisionSnapshot(peer), restored.peerDecision());
                assertEquals(proto, restoredRecord.toProto(false));
            }
        }
    }

    @Test
    void recordRoundTripPreservesEveryArtifactAndProcessedType() throws UnresolvableProtobufException {
        var signature = new ContractSignatureData(new byte[20],
                new byte[70],
                myIdentity.getKeyBundle().getKeyPair().getPublic());
        var data = MuSigV2TradeExecutionData.builder()
                .myPubKeyShares(Optional.of(PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance())))
                .myContractSignature(Optional.of(signature))
                .peerContractSignature(Optional.of(new ContractSignatureData(new byte[20],
                        new byte[71],
                        contract.getMaker().getNetworkId().getPubKey().getPublicKey())))
                .peerPubKeyShares(Optional.of(PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance())))
                .peerNonceShares(Optional.of(NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance())))
                .myNonceShares(Optional.of(NonceSharesMessage.fromProto(bisq.trade.protobuf.NonceSharesMessage.getDefaultInstance())))
                .myPartialSignatures(Optional.of(PartialSignaturesMessage.fromProto(bisq.trade.protobuf.PartialSignaturesMessage.getDefaultInstance())))
                .peerRedactedPartialSignatures(Optional.of(PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance())))
                .peerPartialSignatures(Optional.of(PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance())))
                .myDepositPsbt(Optional.of(new DepositPsbt(new byte[]{5})))
                .peerDepositPsbt(Optional.of(new DepositPsbt(new byte[]{6})))
                .peerAccountPayload(Optional.of(new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR"))))
                .mySwapTxSignatureResponse(Optional.of(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})))
                .peersOutputPrvKeyShare(Optional.of(new ByteArray(new byte[]{9})))
                .myCloseTradeResponse(Optional.of(new CloseTradeResponse(new byte[]{10})))
                .myCustomPayoutPsbt(Optional.of(new CustomPayoutPsbt(new byte[]{11}, TX_ID, 90_000, 60_000)))
                .peerCustomPayoutPsbt(Optional.of(new PeerCustomPayoutPsbt(TX_ID, new byte[]{12})))
                .customCloseTradeResponse(Optional.of(new CustomCloseTradeResponse(new byte[]{13})))
                .myFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.UNKNOWN,
                        "Local failure",
                        "Local stack trace")))
                .peerFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                        "Peer failure",
                        "Peer stack trace")))
                .tradeCompletedDate(Optional.of(TRADE_COMPLETED_DATE))
                .build();
        var trade = withTradeState(tradeAt(new CustomPayoutClosedTrade())
                        .withExecution(FlowSnapshot.of(new CustomPayoutClosedTrade(),
                                data))
                        .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                                new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                                        new ByteArray(new byte[]{14}))), Optional.empty())))
                        .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()))
                        .withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected())),
                new MuSigTradeState.Historical());
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.EXECUTION, Set.of(DepositTxConfirmedEvent.class, MediationResultByPeerAcceptedEvent.class),
                        Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class),
                        Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class),
                        Flow.PEER_MEDIATION_DECISION, Set.of(MuSigMediationResultRejectionMessage.class)),
                List.of(),
                List.of(),
                List.of());
        var restoredRecord = MuSigV2TradeRecord.fromProto(record.toProto(false));
        assertEquals(record.toProto(false), restoredRecord.toProto(false));
        var proto = restoredRecord.trade().toProto(false);
        var restored = restoredRecord.trade();
        assertEquals(proto, restored.toProto(false));
        assertEquals(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTCLOSEDTRADE,
                proto.getExecution().getState().getMessageCase());
        assertEquals(List.of(bisq.trade.protobuf.MuSigV2EventType.MUSIGV2EVENTTYPE_DEPOSIT_CONFIRMED,
                        bisq.trade.protobuf.MuSigV2EventType.MUSIGV2EVENTTYPE_MEDIATION_RESULT_BY_PEER_ACCEPTED),
                record.toProto(false).getProcessedEventTypesOrThrow(Flow.EXECUTION.name()).getTypesList());
        assertEquals(List.of(bisq.trade.protobuf.MuSigV2EventType.MUSIGV2EVENTTYPE_MEDIATION_OPENED,
                        bisq.trade.protobuf.MuSigV2EventType.MUSIGV2EVENTTYPE_MEDIATION_CLOSED),
                record.toProto(false).getProcessedEventTypesOrThrow(Flow.MEDIATION.name()).getTypesList());
        assertEquals(contract.toProto(false), proto.getData().getContract());
        assertEquals(myIdentity.toProto(false), proto.getData().getMyIdentity());
        assertEquals(myAccountPayload.toProto(false), proto.getData().getMyAccountPayload());
        assertEquals(new MuSigTradeState.Historical(), restored.getState());
        assertEquals(2, restoredRecord.processedEventTypes().get(Flow.EXECUTION).size());
        assertEquals(Set.of(MuSigMediationOpenedMessage.class, MuSigMediationClosedMessage.class),
                restoredRecord.processedEventTypes().get(Flow.MEDIATION));
        assertEquals(signature.toProto(false),
                restored.getExecution().data().getMyContractSignature().orElseThrow().toProto(false));
        assertEquals(data.getMyFailure(), restored.getExecution().data().getMyFailure());
        assertEquals(data.getPeerFailure(), restored.getExecution().data().getPeerFailure());
        assertArrayEquals(new byte[]{14},
                restored.getMediation().orElseThrow().data().signedResult().orElseThrow().signature().getBytes());
    }

    @Test
    void everySellerStateSurvivesTradeSerialization() throws Exception {
        List<MuSigSellerAsTakerState> states = List.of(
                new Init(),
                new TradeInitialized(),
                new NonceSharesAndPartialSignaturesCreated(),
                new DepositTxSignedAndPublished(),
                new AccountPayloadReceived(),
                new DepositTxConfirmed(),
                new PaymentInitiatedMessageReceived(),
                new PaymentReceiptConfirmed(),
                new CustomPayoutSigned(),
                new CustomPayoutClosedTrade(),
                new SellerForceClosedTrade(),
                new TradeClosed(),
                new Failed(),
                new FailedAtPeer());
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.class);
        for (MuSigSellerAsTakerState state : states) {
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(tradeAt(state).serialize());
            coveredCases.add(proto.getExecution().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(state, restored.getExecution().state());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.MESSAGE_NOT_SET,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PAYMENTINITIATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADEINITIALIZEDANDNONCESHARESCREATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PARTIALSIGNATURESCREATEDANDDEPOSITTXSIGNED)), coveredCases);
    }

    @Test
    void everyMediationStateSurvivesTradeSerialization() throws Exception {
        List<MuSigMediationState> states = List.of(new MuSigMediationState.Init(),
                new MuSigMediationState.Requested(),
                new MuSigMediationState.Open(),
                new MuSigMediationState.Closed(),
                new MuSigMediationState.Failed());
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigMediationState.MessageCase.class);
        for (MuSigMediationState state : states) {
            var trade = tradeAt(new DepositTxConfirmed())
                    .withMediation(FlowSnapshot.of(state, MuSigV2MediationData.initial()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            coveredCases.add(proto.getMediation().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(state, restored.getMediation().orElseThrow().state());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigMediationState.MessageCase.MESSAGE_NOT_SET)),
                coveredCases);
    }

    @Test
    void everyBuyerStateAndThePeerSwapSignatureSurviveSerialization() throws Exception {
        List<MuSigBuyerAsTakerState> states = List.of(new Init(),
                new TradeInitialized(),
                new NonceSharesAndPartialSignaturesCreated(),
                new DepositTxSignedAndPublished(),
                new AccountPayloadReceived(),
                new DepositTxConfirmed(),
                new PaymentInitiated(),
                new CustomPayoutSigned(),
                new CustomPayoutClosedTrade(),
                new TradeClosed(),
                new Failed(),
                new FailedAtPeer());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.class);
        for (var state : states) {
            var trade = buyerTradeAt(state);
            trade = trade.withExecution(FlowSnapshot.of(state, trade.getExecution().data().toBuilder()
                    .peerSwapTxSignature(Optional.of(signature)).build()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            coveredCases.add(proto.getExecution().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(TradeRole.BUYER_AS_TAKER, restored.getMyRole());
            assertEquals(state, restored.getExecution().state());
            assertEquals(signature, restored.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.of(
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.INIT,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADEINITIALIZED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.NONCESHARESANDPARTIALSIGNATURESCREATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.DEPOSITTXSIGNEDANDPUBLISHED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.ACCOUNTPAYLOADRECEIVED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.DEPOSITTXCONFIRMED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PAYMENTINITIATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTSIGNED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTCLOSEDTRADE,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADECLOSED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.FAILED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.FAILEDATPEER), coveredCases);
    }

    @Test
    void persistedStateMustBelongToThePersistedRole() {
        var seller = tradeAt(new PaymentReceiptConfirmed()).toProto(false);
        var buyer = buyerTradeAt(new PaymentInitiated()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(seller.toBuilder()
                .setData(seller.getData().toBuilder().setMyRole(TradeRole.BUYER_AS_TAKER.toProtoEnum())).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(buyer.toBuilder()
                .setData(buyer.getData().toBuilder().setMyRole(TradeRole.SELLER_AS_TAKER.toProtoEnum())).build()));
    }

    @Test
    void everyProtocolAndPersistedEventTypeRoundTripsWithItsPayloadAndProcessedType() throws Exception {
        List<Event> events = persistableEvents();
        Set<Class<?>> eventClasses = events.stream().map(Object::getClass).collect(Collectors.toSet());
        Set<Class<?>> declaredEventClasses = Stream.of(
                        new MuSigTradeProtocol().rules(),
                        new MuSigBuyerAsMakerProtocol().rules(),
                        new MuSigBuyerAsTakerProtocol().rules(),
                        new MuSigSellerAsMakerProtocol().rules(),
                        new MuSigSellerAsTakerProtocol().rules(),
                        new MuSigMediationProtocol().rules(),
                        new MuSigMyMediationDecisionProtocol().rules(),
                        new MuSigPeerMediationDecisionProtocol().rules())
                .flatMap(table -> table.getRules().stream())
                .map(Rule::on)
                .collect(Collectors.toSet());
        for (Class<?> eventClass : declaredEventClasses) {
            var eventType = MuSigV2EventType.fromEventClass(eventClass);
            assertEquals(eventClass, MuSigV2EventType.fromProto(eventType.toProtoEnum()).getEventClass());
            assertTrue(eventClasses.contains(eventClass),
                    () -> "Missing round-trip fixture for " + eventClass.getName());
        }

        var trade = tradeAt(new DepositTxConfirmed());
        var inputs = events.stream().map(event -> event).toList();
        var record = new MuSigV2TradeRecord(trade, Map.of(Flow.EXECUTION, eventClasses), inputs, List.of(), List.of());
        var checkpoint = record.toCheckpoint();
        assertSame(trade, checkpoint.snapshot().document());
        assertEquals(record.processedEventTypes(), checkpoint.snapshot().processedEventTypes());
        var proto = record.toProto(false);
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(checkpoint).toProto(false));
        var restoredRecord = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        var restoredCheckpoint = restoredRecord.toCheckpoint();
        var coveredTypes = new HashSet<String>();
        var coveredTradeEventCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeEvent.EventCase.class);
        assertEquals(events.size(), restoredRecord.pendingInputs().size());
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            Event restored = restoredRecord.pendingInputs().get(i);
            var persistedEvent = proto.getPendingInputs(i);
            coveredTypes.add(persistedEvent.getPayload().getTypeUrl());
            if (event instanceof MuSigTradeEvent) {
                assertTrue(persistedEvent.getPayload().is(bisq.trade.protobuf.TradeEvent.class));
                coveredTradeEventCases.add(persistedEvent.getPayload().unpack(bisq.trade.protobuf.TradeEvent.class)
                        .getMuSigTradeEvent().getEventCase());
            }
            assertEquals(event.getClass(), restored.getClass());
            assertEquals(event.toProto(false), restored.toProto(false));
            assertSame(restoredRecord.pendingInputs().get(i), restoredCheckpoint.pendingInputs().get(i));
            assertEquals(event instanceof Command, (restoredCheckpoint.pendingInputs().get(i) instanceof Command));
        }
        assertEquals(Set.of("type.googleapis.com/trade.TradeEvent",
                "type.googleapis.com/network.EnvelopePayloadMessage"),
                coveredTypes);
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigTradeEvent.EventCase.EVENT_NOT_SET)),
                coveredTradeEventCases);
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigV2EventType.MUSIGV2EVENTTYPE_UNSPECIFIED,
                        bisq.trade.protobuf.MuSigV2EventType.UNRECOGNIZED)),
                EnumSet.copyOf(proto.getProcessedEventTypesOrThrow(Flow.EXECUTION.name()).getTypesList()));
        assertEquals(eventClasses, restoredRecord.processedEventTypes().get(Flow.EXECUTION));
        assertEquals(proto, restoredRecord.toProto(false));
        assertEquals(restoredCheckpoint, MuSigV2TradeRecord.fromCheckpoint(restoredCheckpoint).toCheckpoint());
    }

    @Test
    void outgoingMessagesAndScheduledWorkRoundTripWithTheirOriginalData() throws Exception {
        List<PersistableEffect> effects = new ArrayList<>();
        for (Event event : persistableEvents()) {
            if (event instanceof MuSigTradeMessage message) {
                effects.add(new SendMessage<>(message, message.getReceiver()));
            }
        }
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var request = new MuSigMediationRequest(tradeId,
                contract,
                userProfile("taker", myIdentity.getNetworkId()),
                userProfile("maker", contract.getMaker().getNetworkId()),
                List.of(),
                mediator);
        effects.add(new SendMessage<>(request, mediator));
        var report = new MuSigDisputeCaseDataMessage(tradeId,
                myIdentity.getNetworkId(),
                ContractService.getContractHash(contract),
                List.of());
        effects.add(new SendMessage<>(report, mediator));
        var response = new MuSigDisputeCasePaymentDetailsResponse(tradeId,
                myIdentity.getNetworkId(),
                myAccountPayload,
                new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR")));
        effects.add(new SendMessage<>(response, mediator));
        var scheduled = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                Instant.parse("2030-01-01T00:00:00.123456789Z"),
                new SellersCloseTradeTimeoutEvent());
        effects.add(new ActivateMediationChat());
        effects.add(new ObserveDepositTxConfirmation());
        var record = new MuSigV2TradeRecord(tradeAt(new DepositTxConfirmed()),
                Map.of(),
                List.of(),
                List.of(scheduled),
                effects);
        var proto = record.toProto(false);
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(record.toCheckpoint()).toProto(false));
        var restored = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        var checkpoint = restored.toCheckpoint();
        assertEquals(restored.pendingEffects(), checkpoint.pendingEffects());
        assertEquals(List.of(scheduled), checkpoint.scheduledInputs());
        assertEquals(effects.size(), restored.pendingEffects().size());
        for (int i = 0; i < effects.size(); i++) {
            assertEquals(effects.get(i).getClass(), restored.pendingEffects().get(i).getClass());
        }
        assertEquals(List.of(scheduled), restored.scheduledEvents());
        assertEquals(proto, restored.toProto(false));
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(checkpoint).toProto(false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laneCheckpointPreservesCommandTypeWithoutASeparateFlag(boolean command) throws Exception {
        Event event = command ? new PaymentReceiptConfirmedEvent() : new DepositTxConfirmedEvent();
        var record = new MuSigV2TradeRecord(tradeAt(new DepositTxConfirmed()), Map.of(),
                List.of(event), List.of(), List.of());
        var restored = MuSigV2TradeRecord.fromProto(record.toProto(false)).toCheckpoint();

        assertEquals(command, restored.pendingInputs().getFirst() instanceof Command);
        assertEquals(event.getClass(), restored.pendingInputs().getFirst().getClass());
    }

    @Test
    void incompleteTradesAndUnknownPersistedTypesAreRejected() {
        var proto = tradeAt(new DepositTxConfirmed()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .clearData().build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setExecution(proto.getExecution().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setMediation(proto.getMediation().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearContract()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearMyIdentity()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearMyAccountPayload()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setExecution(proto.getExecution().toBuilder().clearState()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setMediation(proto.getMediation().toBuilder().clearState()).build()));
        var record = initialRecord(tradeAt(new DepositTxConfirmed())).toProto(false);
        for (var flow : Flow.values()) {
            for (int eventTypeValue : List.of(0, 999)) {
                var types = bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.newBuilder()
                        .addTypesValue(eventTypeValue).build();
                assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(record.toBuilder()
                        .putProcessedEventTypes(flow.name(), types).build()));
            }
        }
        for (String flow : List.of("", "UNKNOWN")) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(record.toBuilder()
                    .putProcessedEventTypes(flow,
                            bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.getDefaultInstance()).build()));
        }
    }

    @Test
    void missingProcessingHistoryIsRejectedInsteadOfResettingDuplicateProtection() {
        var proto = initialRecord(tradeAt(new DepositTxConfirmed())).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .clearProcessedEventTypes().build()));
        for (var flow : Flow.values()) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                    .removeProcessedEventTypes(flow.name()).build()));
        }
    }

    @Test
    void aSavedTradeNamesItsFlowsWithTheseFiveNames() {
        var saved = initialRecord(tradeAt(new DepositTxConfirmed())).toProto(false);

        // Another name makes every trade unreadable which was saved before.
        assertEquals(Set.of("TRADE", "EXECUTION", "MEDIATION", "MY_MEDIATION_DECISION", "PEER_MEDIATION_DECISION"),
                saved.getProcessedEventTypesMap().keySet());
    }

    @Test
    void missingAndUnknownStateVariantsAreRejected() throws Exception {
        var proto = tradeAt(new DepositTxConfirmed()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .clearState().build()));
        byte[] unknownState = UnknownFieldSet.newBuilder()
                .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                .build().toByteArray();
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                    .setState(state).build()));
        }
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeExecutionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeExecutionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setExecution(proto.getExecution().toBuilder().setState(state)).build()));
        }
        var buyer = buyerTradeAt(new Init()).toProto(false);
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeExecutionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeExecutionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(buyer.toBuilder()
                        .setExecution(buyer.getExecution().toBuilder().setState(state)).build()));
        }
        for (var state : List.of(bisq.trade.protobuf.MuSigMediationState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigMediationState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setMediation(proto.getMediation().toBuilder().setState(state)).build()));
        }
    }

    @Test
    void incompleteAndUnknownDecisionSnapshotsAreRejected() throws Exception {
        var proto = MuSigV2Mediation.initial().toProto(false);
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder().clearMyDecision().build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder().clearPeerDecision().build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                        .setMyDecision(proto.getMyDecision().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                        .setPeerDecision(proto.getPeerDecision().toBuilder().clearData()).build()));
        byte[] unknownState = UnknownFieldSet.newBuilder()
                .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                .build().toByteArray();
        for (var state : List.of(bisq.trade.protobuf.MuSigMediationDecisionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigMediationDecisionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                    .setMyDecision(proto.getMyDecision().toBuilder().setState(state)).build()));
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                    .setPeerDecision(proto.getPeerDecision().toBuilder().setState(state)).build()));
        }

    }

    @Test
    void allPendingInputDestinationsRoundTrip() throws Exception {
        List<Event> pending = List.of(
                new TradeArchivedEvent(),
                new DepositTxConfirmedEvent(),
                new DepositConfirmationSkippedEvent(),
                new MediationRequestedEvent(),
                new MediationResultAcceptedEvent(),
                peerDecision(true, MuSigMediationResultService.getMediationResultHash(result)));
        assertEquals(6, pending.size());
        var record = new MuSigV2TradeRecord(tradeAt(new DepositTxConfirmed()), Map.of(), pending, List.of(), List.of());
        var proto = record.toProto(false);
        var restored = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        assertEquals(pending.stream().map(Object::getClass).toList(),
                restored.pendingInputs().stream().map(Object::getClass).toList());
        assertEquals(proto, restored.toProto(false));
    }

    @Test
    void missingUnknownAndMalformedEventPayloadsAreRejected() {
        assertThrows(UnresolvableProtobufException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.getDefaultInstance()));
        var unknown = Any.newBuilder().setTypeUrl("type.googleapis.com/future.Event").build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(unknown).build()));
        var malformed = Any.newBuilder().setTypeUrl("type.googleapis.com/trade.TradeEvent")
                .setValue(ByteString.copyFrom(new byte[]{(byte) 0xff})).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(malformed).build()));
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder()
                        .setPayload(Any.pack(bisq.trade.protobuf.TradeMessage.getDefaultInstance())).build()));
    }

    @Test
    void missingAndUnknownTradeEventFamiliesAreRejected() throws Exception {
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder()
                        .setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.getDefaultInstance())).build()));
        var futureTradeEvent = bisq.trade.protobuf.TradeEvent.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                        .build())
                .build();
        var envelope = bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(futureTradeEvent)).build();
        var parsed = bisq.common.protobuf.Event.parseFrom(envelope.toByteArray());

        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(parsed));
    }

    @Test
    void missingAndUnknownTradeEventVariantsAreRejected() throws Exception {
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.newBuilder()
                        .setMuSigTradeEvent(bisq.trade.protobuf.MuSigTradeEvent.getDefaultInstance()).build())).build()));
        var futureTradeEvent = bisq.trade.protobuf.MuSigTradeEvent.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                        .build())
                .build();
        var envelope = bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.newBuilder()
                .setMuSigTradeEvent(futureTradeEvent).build())).build();
        var parsed = bisq.common.protobuf.Event.parseFrom(envelope.toByteArray());
        assertTrue(parsed.getPayload().is(bisq.trade.protobuf.TradeEvent.class));
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(parsed));
    }

    @Test
    void unknownFlowNamesAndPayloadsInTheWrongCategoryAreRejected() {
        var proto = initialRecord(tradeAt(new DepositTxConfirmed())).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingInputs(bisq.common.protobuf.Event.getDefaultInstance()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .putProcessedEventTypes("UNKNOWN", bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.getDefaultInstance())
                .build()));
        assertThrows(UnresolvableProtobufException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(new ObserveDepositTxConfirmation().toProto(false))).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder()
                        .setPayload(Any.pack(new DepositTxConfirmedEvent().toProto(false)))).build()));
        var send = bisq.common.protobuf.SendMessage.newBuilder()
                .setMessage(Any.pack(new DepositTxConfirmedEvent().toProto(false)))
                .setReceiver(Any.pack(myIdentity.getNetworkId().toProto(false))).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder().setPayload(Any.pack(send))).build()));
        var message = persistableEvents().stream().filter(MuSigTradeMessage.class::isInstance).findFirst().orElseThrow();
        var wrongReceiver = send.toBuilder()
                .setMessage(Any.pack(message.toProto(false)))
                .setReceiver(Any.pack(new DepositTxConfirmedEvent().toProto(false))).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder().setPayload(Any.pack(wrongReceiver))).build()));
    }

    private static Event eventFromProto(bisq.common.protobuf.Event proto) throws UnresolvableProtobufException {
        return Event.fromProto(proto, StateMachineResolver::eventFromAny);
    }

    private List<Event> persistableEvents() {
        NetworkId sender = myIdentity.getNetworkId();
        NetworkId receiver = contract.getMaker().getNetworkId();
        String version = "1.0.0";
        String id = StringUtils.createUid();
        var signature = new ContractSignatureData(new byte[20],
                new byte[70],
                myIdentity.getKeyBundle().getKeyPair().getPublic());
        var pubKeys = assertDoesNotThrow(() -> PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance()));
        var nonces = assertDoesNotThrow(() -> NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance()));
        var partialSignatures = assertDoesNotThrow(() -> PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance()));
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        byte[] resultHash = MuSigMediationResultService.getMediationResultHash(result);
        return List.of(
                new MuSigTakeOfferEvent(),
                new DepositTxConfirmedEvent(),
                new DepositConfirmationSkippedEvent(),
                new PaymentReceiptConfirmedEvent(),
                new PaymentInitiatedEvent(),
                new SellersCloseTradeTimeoutEvent(),
                new BuyersCloseTradeTimeoutEvent(),
                new MediationResultAcceptedEvent(),
                new MediationResultRejectedEvent(),
                new MediationRequestedEvent(),
                new TradeArchivedEvent(),
                new ExecutionFailedEvent(),
                new MuSigProcessingFailedEvent(TradeProtocolFailure.UNKNOWN, "Local failure", "Local stack trace"),
                new MuSigReportErrorMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.UNKNOWN),
                new MuSigMediationOpenedMessage(id,
                        tradeId,
                        mediator),
                new MuSigMediationClosedMessage(id,
                        tradeId,
                        mediator,
                        result,
                        new byte[70]),
                new MuSigDisputeCasePaymentDetailsRequest(tradeId, mediator),
                acceptedByMyself(result),
                new MediationResultByPeerAcceptedEvent(new PeerCustomPayoutPsbt(TX_ID, new byte[]{1, 2})),
                new SetupTradeMessage_A(id, tradeId, version, sender, receiver, contract, signature, pubKeys),
                new SetupTradeMessage_B(id, tradeId, version, sender, receiver, contract, signature, pubKeys, nonces),
                new SetupTradeMessage_C(id, tradeId, version, sender, receiver, nonces, partialSignatures),
                new SetupTradeMessage_D(id, tradeId, version, sender, receiver, partialSignatures, new byte[]{3}),
                new SendAccountPayloadAndDepositTxMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        new ByteArray(new byte[]{4}),
                        myAccountPayload),
                new SendAccountPayloadMessage(id, tradeId, version, sender, receiver, myAccountPayload),
                new PaymentInitiatedMessage_E(id, tradeId, version, sender, receiver, new byte[]{5}),
                new PaymentReceivedMessage_F(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{6}, new byte[]{7}))),
                new CooperativeClosureMessage_G(id, tradeId, version, sender, receiver, new ByteArray(new byte[]{8})),
                new MuSigCustomPayoutPsbtMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        resultHash,
                        TX_ID,
                        new byte[]{9}),
                new MuSigMediationResultRejectionMessage(id, tradeId, version, sender, receiver, resultHash));
    }
}
