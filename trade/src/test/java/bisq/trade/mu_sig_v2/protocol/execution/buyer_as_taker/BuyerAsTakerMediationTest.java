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

package bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker;

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.processing.ProcessingResult;
import bisq.contract.ContractService;
import bisq.network.identity.NetworkId;
import bisq.offer.options.CollateralOption;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.protobuf.CustomCloseTradeResponse;
import bisq.trade.protobuf.CustomPayoutPsbt;
import bisq.user.profile.UserProfile;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertOperationFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyerAsTakerMediationTest extends BuyerAsTakerFixture {
    private static final String PAYOUT_TX_ID = "12".repeat(32);
    private static final LogEntry ACCEPTED_LOG = new LogEntry.Shared(new LogEntry.Localized(
            "muSig.mediation.result.accepted.tradeLogMessage",
            List.of("local-buyer")));
    private final KeyPair mediatorKeys = KeyGeneration.generateDefaultEcKeyPair();
    private final NetworkId mediatorNetworkId = networkId("mediator-profile");
    private final MuSigTradeLaneFixture<BuyerAsTakerState> mediated = createMediatedLane();
    private final MuSigMediationResult result = new MuSigMediationResult(ContractService.getContractHash(muSigContract),
            MediationResultReason.OTHER,
            MediationPayoutDistributionType.CUSTOM_PAYOUT,
            Optional.of(150_000L),
            Optional.of(50_000L),
            Optional.empty(),
            Optional.empty());

    @AfterEach
    void closeMediatedLane() {
        mediated.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localAcceptanceSignsFirstAndPeerAcceptanceCloses(boolean paymentInitiated) {
        prepareResult(paymentInitiated);
        var before = mediated.getTrade();
        int effectCount = effects.size();
        assertEquals(paymentInitiated ? 1 : 0, mediated.getSavedRecord().scheduledEvents().size());

        mediated.handle(new MediationResultAcceptedEvent());

        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                before.getMediation().orElseThrow().myDecision().state());
        assertSame(before.getExecution().data().getMyPartialSignatures(),
                mediated.getTrade().getExecution().data().getMyPartialSignatures());
        assertEquals(effectCount + 1, effects.size());
        var message = (MuSigCustomPayoutPsbtMessage) ((Effect.SendMessage<?, ?>) effects.getLast()).message();
        assertEquals(PAYOUT_TX_ID, message.getTxId());
        assertArrayEquals(new byte[]{6}, message.getPsbt());
        assertArrayEquals(MuSigMediationResultService.getMediationResultHash(result), message.getMediationResultHash());
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text("Buyer signed the mediated payout"))));
        assertTrue(logs.contains(ACCEPTED_LOG));
        assertTrue(mediated.getSavedRecord().scheduledEvents().isEmpty());
        verify(blockingStub()).signCustomPayoutTx(argThat(request ->
                request.getTradeId().equals(TRADE_ID) && request.getSellersPayoutAmountExcludingFee() == 50_000));
        verify(blockingStub(), never()).customCloseTrade(any());

        mediated.handle(peerAccepted());

        assertMediatedClosure();
        var closed = mediated.getTrade();
        mediated.handle(peerAccepted());
        mediated.handle(new MediationResultAcceptedEvent());
        assertSame(closed, mediated.getTrade());
        assertEquals(1L, logs.stream().filter(ACCEPTED_LOG::equals).count());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlyPeerAcceptanceIsRecordedAndWaitsForOurSignature(boolean paymentInitiated) {
        runSetup(mediated::handle);
        if (paymentInitiated) {
            mediated.handle(new PaymentInitiatedEvent());
        }
        var execution = mediated.getTrade().getExecution();
        mediated.handle(peerAccepted());
        mediated.handle(new MediationClosedEvent(closed()));
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());

        mediated.handle(new MediationOpenedEvent(opened()));

        assertSame(execution, mediated.getTrade().getExecution());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());

        mediated.handle(new MediationResultAcceptedEvent());

        assertMediatedClosure();
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void signingFailureTerminatesExecutionAndPreservesBothAcceptedDecisions() {
        prepareResult(false);
        mediated.handle(peerAccepted());
        var before = mediated.getTrade();
        RuntimeException failure = new IllegalStateException("Signing unavailable");
        when(blockingStub().signCustomPayoutTx(any())).thenThrow(failure);

        assertSame(failure,
                assertOperationFailure(() -> mediated.handle(new MediationResultAcceptedEvent())).getCause());

        assertInstanceOf(Failed.class, mediated.getExecutionState());
        assertSame(before.getMediation().orElseThrow().peerDecision(), mediation().peerDecision());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                Set.of()).contains(MediationResultAcceptedEvent.class));
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(mediated.getTrade().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
        assertFalse(logs.contains(ACCEPTED_LOG));
        assertEquals("Signing unavailable",
                mediated.getTrade().getExecution().data().getMyFailure().orElseThrow().errorMessage());
        var failed = mediated.getTrade();
        mediated.resume();
        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertSame(failed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void finalizationFailurePreservesBothDecisionsAndTerminatesExecution(boolean peerFirst) {
        prepareResult(false);
        RuntimeException failure = new IllegalStateException("Closing unavailable");
        when(blockingStub().customCloseTrade(any())).thenThrow(failure);
        if (peerFirst) {
            mediated.handle(peerAccepted());
            // Our acceptance applies. The older peer-acceptance fact fails when signing makes it ready.
            assertInstanceOf(ProcessingResult.Applied.class, mediated.handle(new MediationResultAcceptedEvent()));
        } else {
            mediated.handle(new MediationResultAcceptedEvent());
            assertSame(failure, assertOperationFailure(() -> mediated.handle(peerAccepted())).getCause());
        }

        assertInstanceOf(Failed.class, mediated.getExecutionState());
        assertEquals(failure.getMessage(),
                mediated.getTrade().getExecution().data().getMyFailure().orElseThrow().errorMessage());
        assertSame(mediated.getTrade(), mediated.getSavedRecord().trade());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(mediated.getTrade().getExecution().data().getMyCustomPayoutPsbt().isPresent());
        assertTrue(mediated.getTrade().getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
        var failed = mediated.getTrade();
        mediated.resume();
        assertSame(failed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void acceptanceBeforeDepositConfirmationIsRejectedAndNeverAppliedLater() {
        mediated.handle(new MuSigTakeOfferEvent());
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));

        assertSame(before, mediated.getTrade());
        completeSetup(mediated::handle);
        mediated.resume();
        assertInstanceOf(DepositTxConfirmed.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());

        mediated.handle(new MediationResultAcceptedEvent());
        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalConfirmationCannotCloseAfterCustomSigning(boolean paymentInitiated) {
        prepareResult(paymentInitiated);
        mediated.handle(new MediationResultAcceptedEvent());
        var signed = mediated.getTrade();
        var beforeEffects = List.copyOf(effects);

        mediated.handle(paymentReceivedMessage());
        mediated.resume();

        assertSame(signed, mediated.getTrade());
        assertEquals(beforeEffects, effects);
        verify(blockingStub(), never()).closeTrade(any());
        if (!paymentInitiated) {
            assertRejected(() -> mediated.handle(new PaymentInitiatedEvent()));
        }

        mediated.handle(peerAccepted());
        assertMediatedClosure();
    }

    @Test
    void normalClosurePreventsMediatedAcceptance() {
        prepareResult(false);
        runSettlement(mediated::handle);
        var closed = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));

        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
        assertSame(closed, mediated.getTrade());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
    }

    @Test
    void aKnownPayoutMismatchIsIgnoredBeforeRecordingPeerAcceptance() {
        prepareResult(false);
        mediated.handle(new MediationResultAcceptedEvent());
        var before = mediated.getTrade();
        int effectCount = effects.size();
        var invalid = new MuSigCustomPayoutPsbtMessage(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                MuSigMediationResultService.getMediationResultHash(result),
                "34".repeat(32),
                new byte[]{7});

        mediated.handle(invalid);

        assertSame(before, mediated.getTrade());
        assertEquals(effectCount, effects.size());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION, Set.of()).isEmpty());
        assertTrue(mediated.getTrade().getExecution().data().getCustomCloseTradeResponse().isEmpty());
        verify(blockingStub(), never()).customCloseTrade(any());

        mediated.handle(peerAccepted());

        assertMediatedClosure();
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    private void prepareResult(boolean paymentInitiated) {
        runSetup(mediated::handle);
        if (paymentInitiated) {
            mediated.handle(new PaymentInitiatedEvent());
        }
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));
    }

    private void assertMediatedClosure() {
        assertInstanceOf(CustomPayoutClosedTrade.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(mediated.getSavedRecord().scheduledEvents().isEmpty());
        var execution = mediated.getTrade().getExecution().data();
        assertEquals(PAYOUT_TX_ID, execution.getMyCustomPayoutPsbt().orElseThrow().getTxId());
        assertArrayEquals(new byte[]{7}, execution.getPeerCustomPayoutPsbt().orElseThrow().getPsbt());
        assertArrayEquals(new byte[]{9}, execution.getCustomCloseTradeResponse().orElseThrow().getCustomPayoutTx());
    }

    private MuSigV2Mediation<? extends MuSigMediationState> mediation() {
        return mediated.getTrade().getMediation().orElseThrow();
    }

    private MuSigTradeLaneFixture<BuyerAsTakerState> createMediatedLane() {
        UserProfile mediator = mock(UserProfile.class);
        PubKey pubKey = mock(PubKey.class);
        when(pubKey.getPublicKey()).thenReturn(mediatorKeys.getPublic());
        when(mediatorNetworkId.getPubKey()).thenReturn(pubKey);
        when(mediator.getNetworkId()).thenReturn(mediatorNetworkId);
        when(mediator.getPublicKey()).thenReturn(mediatorKeys.getPublic());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        doReturn(new byte[]{1, 2, 3}).when(muSigContract).serializeForHash();
        when(muSigContract.getBtcSideAmount()).thenReturn(100_000L);
        when(muSigContract.getOffer().getMarket().isBaseCurrencyBitcoin()).thenReturn(true);
        when(muSigContract.getOffer().getOfferOptions()).thenReturn(List.of(new CollateralOption(0.5, 0.5)));
        when(blockingStub().signCustomPayoutTx(any())).thenReturn(signedPayout());
        when(blockingStub().customCloseTrade(any())).thenReturn(closedPayout());
        return new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                BuyerAsTakerState.class,
                new BuyerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock);
    }

    private MuSigMediationStateChangeMessage opened() {
        return new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                mediatorNetworkId,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());
    }

    private MuSigMediationStateChangeMessage closed() {
        try {
            byte[] signature = MuSigMediationResultService.signMediationResult(result, mediatorKeys);
            return new MuSigMediationStateChangeMessage(uid(),
                    TRADE_ID,
                    mediatorNetworkId,
                    MediationCaseState.CLOSED,
                    Optional.of(result),
                    Optional.of(signature));
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
    }

    private MuSigCustomPayoutPsbtMessage peerAccepted() {
        return new MuSigCustomPayoutPsbtMessage(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                MuSigMediationResultService.getMediationResultHash(result),
                PAYOUT_TX_ID,
                new byte[]{7});
    }

    private CustomPayoutPsbt signedPayout() {
        return CustomPayoutPsbt.newBuilder().setTxId(PAYOUT_TX_ID).setPsbt(ByteString.copyFrom(new byte[]{6}))
                .setBuyersPayoutAmountIncludingFee(150_000).setSellersPayoutAmountIncludingFee(50_000).build();
    }

    private CustomCloseTradeResponse closedPayout() {
        return CustomCloseTradeResponse.newBuilder().setCustomPayoutTx(ByteString.copyFrom(new byte[]{9})).build();
    }
}
