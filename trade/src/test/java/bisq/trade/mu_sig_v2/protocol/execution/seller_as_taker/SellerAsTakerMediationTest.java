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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeMessage;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.options.CollateralOption;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertOperationFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertProtocolFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SellerAsTakerMediationTest extends SellerAsTakerFixture {
    private static final String PAYOUT_TX_ID = "12".repeat(32);
    private static final LogEntry ACCEPTED_LOG = new LogEntry.Shared(new LogEntry.Localized(
            "muSig.mediation.result.accepted.tradeLogMessage",
            List.of("local-seller")));
    private static final LogEntry REJECTED_LOG = new LogEntry.Shared(new LogEntry.Localized(
            "muSig.mediation.result.rejected.tradeLogMessage",
            List.of("local-seller")));
    private final KeyPair mediatorKeys = KeyGeneration.generateDefaultEcKeyPair();
    private final NetworkId mediatorNetworkId = networkId("mediator-profile");
    private final MuSigTradeLaneFixture<SellerAsTakerState> mediated = createMediatedLane();
    private final MuSigMediationResult result = result(150_000, 50_000);

    @AfterEach
    void closeMediatedLane() {
        mediated.close();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paymentDetailsWaitUntilMediationIsRequestedOrOpened(boolean requestLocally) {
        runSetup(mediated::handle);
        effects.clear();
        var before = mediated.getTrade();
        var request = new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorNetworkId);

        mediated.handle(new MediationPaymentDetailsRequestedEvent(request));
        mediated.resume();
        assertSame(before, mediated.getTrade());
        assertTrue(effects.isEmpty());

        if (requestLocally) {
            mediated.handle(new MediationRequestedEvent());
        } else {
            mediated.handle(new MediationOpenedEvent(opened()));
        }
        var answered = mediated.getTrade();
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                .contains(MediationPaymentDetailsRequestedEvent.class));
        mediated.handle(new MediationPaymentDetailsRequestedEvent(request));
        assertSame(answered, mediated.getTrade());
        assertSame(before.getExecution(), answered.getExecution());
        if (requestLocally) {
            mediated.handle(new MediationOpenedEvent(opened()));
        }
        mediated.handle(new MediationClosedEvent(closed()));
        mediated.handle(new MediationPaymentDetailsRequestedEvent(request));
        assertEquals(1, sentMessageTypes().stream()
                .filter(MuSigDisputeCasePaymentDetailsResponse.class::equals).count());
    }

    @Test
    void missingPaymentDetailsAreIgnoredWithoutConsumingTheRequestType() {
        mediated.handle(new MuSigTakeOfferEvent());
        mediated.handle(setupMessage());
        mediated.handle(depositSetupMessage());
        mediated.handle(new MediationOpenedEvent(opened()));
        effects.clear();
        var before = mediated.getTrade();
        var request = new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorNetworkId);

        mediated.handle(new MediationPaymentDetailsRequestedEvent(request));
        assertSame(before, mediated.getTrade());
        assertTrue(effects.isEmpty());
        mediated.handle(accountPayloadMessage(peerAccountPayload));
        mediated.resume();
        assertEquals(1, effects.size());
        assertInstanceOf(ObserveDepositTxConfirmation.class, effects.getFirst());
        assertFalse(mediated.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                .contains(MediationPaymentDetailsRequestedEvent.class));

        mediated.handle(new MediationPaymentDetailsRequestedEvent(request));
        assertEquals(List.of(MuSigDisputeCasePaymentDetailsResponse.class), sentMessageTypes());
    }

    @Test
    void anUnauthorizedPaymentDetailsRequestIsRejectedBeforeBeingRetained() {
        runSetup(mediated::handle);
        effects.clear();
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationPaymentDetailsRequestedEvent(
                new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, peerNetworkId))));
        assertSame(before, mediated.getTrade());
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.resume();
        assertFalse(sentMessageTypes().contains(MuSigDisputeCasePaymentDetailsResponse.class));

        mediated.handle(new MediationPaymentDetailsRequestedEvent(
                new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorNetworkId)));
        assertEquals(List.of(MuSigDisputeCaseDataMessage.class,
                MuSigDisputeCasePaymentDetailsResponse.class), sentMessageTypes());
    }

    @Test
    void paymentDetailsAreNotExposedWhenNoMediatorIsAssigned() {
        when(muSigContract.getMediator()).thenReturn(Optional.empty());
        try (var withoutMediator = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new DepositTxConfirmed()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock)) {
            var before = withoutMediator.getTrade();

            assertRejected(() -> withoutMediator.handle(new MediationPaymentDetailsRequestedEvent(
                    new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorNetworkId))));
            withoutMediator.resume();

            assertSame(before, withoutMediator.getTrade());
            assertTrue(effects.isEmpty());
        }
    }

    @Test
    void requestingRequiresAMediatorAndAPublishedDepositAndAnEarlyRequestIsNotReplayed() {
        assertTrue(protocol.getTrade().getMediation().isEmpty());
        assertRejected(() -> protocol.handle(new MediationRequestedEvent()));
        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));
        mediated.handle(new MuSigTakeOfferEvent());
        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));
        mediated.handle(setupMessage());
        var beforeDeposit = mediated.getTrade();
        int effectCount = effects.size();
        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));
        assertSame(beforeDeposit, mediated.getTrade());
        assertEquals(effectCount, effects.size());

        mediated.handle(depositSetupMessage());
        mediated.resume();
        assertInstanceOf(DepositTxSignedAndPublished.class, mediated.getExecutionState());
        assertInstanceOf(MuSigMediationState.Init.class, mediationState());
        assertFalse(mediated.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                .contains(MediationRequestedEvent.class));
        assertFalse(sentMessageTypes().contains(MuSigMediationRequest.class));
        var execution = mediated.getTrade().getExecution();
        var chatMessage = chatMessage("Could you check the payment?");
        chatMessages.add(chatMessage);

        mediated.handle(new MediationRequestedEvent());
        mediated.handle(new MediationRequestedEvent());

        assertSame(execution, mediated.getTrade().getExecution());
        assertInstanceOf(MuSigMediationState.Requested.class, mediationState());
        var send = mediationRequestEffect();
        assertSame(mediatorNetworkId, send.receiver());
        var request = assertInstanceOf(MuSigMediationRequest.class, send.message());
        assertEquals(TRADE_ID, request.getTradeId());
        assertSame(muSigContract, request.getContract());
        assertSame(myUserProfile, request.getRequester());
        assertSame(peerUserProfile, request.getPeer());
        assertSame(mediatorNetworkId, request.getMediatorNetworkId());
        assertEquals(List.of(chatMessage), request.getChatMessages());
        chatMessages.add(chatMessage("A later message"));
        assertEquals(List.of(chatMessage), request.getChatMessages());
        mediated.handle(new MediationOpenedEvent(opened()));
        assertInstanceOf(MuSigMediationState.Open.class, mediationState());
    }

    @Test
    void requestingRemainsAvailableAfterNormalSettlementCommitment() {
        runSetup(mediated::handle);
        runToPaymentReceiptConfirmed(mediated::handle);
        var execution = mediated.getTrade().getExecution();

        mediated.handle(new MediationRequestedEvent());

        assertSame(execution, mediated.getTrade().getExecution());
        assertInstanceOf(MuSigMediationState.Requested.class, mediationState());
        mediationRequestEffect();
    }

    @Test
    void anAlreadyOpenCaseCannotBeRequested() {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        var before = mediated.getTrade();
        int effectCount = effects.size();

        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));

        assertSame(before, mediated.getTrade());
        assertEquals(effectCount, effects.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mediationReportsPruneOnlyTheOutgoingChatSnapshot(boolean requestLocally) {
        runSetup(mediated::handle);
        chatMessages.add(chatMessage("a".repeat(9_500)));
        chatMessages.add(chatMessage("b".repeat(9_500)));

        List<MuSigOpenTradeMessage> reportedMessages;
        if (requestLocally) {
            mediated.handle(new MediationRequestedEvent());
            var request = assertInstanceOf(MuSigMediationRequest.class, mediationRequestEffect().message());
            reportedMessages = request.getChatMessages();
        } else {
            mediated.handle(new MediationOpenedEvent(opened()));
            var send = assertInstanceOf(Effect.SendMessage.class, effects.getLast());
            var report = assertInstanceOf(MuSigDisputeCaseDataMessage.class, send.message());
            reportedMessages = report.getChatMessages();
        }

        assertEquals(1, reportedMessages.size());
        assertTrue(chatMessages.containsAll(reportedMessages));
        assertEquals(2, chatMessages.size());
    }

    @Test
    void requestPreparationFailureTerminatesOnlyMediation() {
        runSetup(mediated::handle);
        var before = mediated.getTrade();
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(TRADE_ID)).thenReturn(Optional.empty());

        assertInstanceOf(NoSuchElementException.class,
                assertProtocolFailure(() -> mediated.handle(new MediationRequestedEvent())).getCause());

        assertSame(before.getExecution(), mediated.getTrade().getExecution());
        assertEquals(new MuSigTradeState.Active(), mediated.getTrade().getState());
        assertInstanceOf(MuSigMediationState.Failed.class, mediationState());
        assertTrue(mediation().data().failure().isPresent());
        assertSame(before.getMediation().orElseThrow().myDecision(), mediation().myDecision());
        assertSame(before.getMediation().orElseThrow().peerDecision(), mediation().peerDecision());
        assertFalse(sentMessageTypes().contains(MuSigReportErrorMessage.class));
        when(channels.findChannelByTradeId(TRADE_ID)).thenReturn(Optional.of(channel));
        var failed = mediated.getTrade();
        mediated.resume();
        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));
        assertSame(failed, mediated.getTrade());
        runSettlement(mediated::handle);
        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
        assertInstanceOf(MuSigMediationState.Failed.class, mediationState());
    }

    @Test
    void theRequestIsSentAfterCommitAndAnImmediateMediatorReplyWaitsForTheStep() {
        var owner = new AtomicReference<MuSigTradeLaneFixture<SellerAsTakerState>>();
        var callbackSubmission = new AtomicReference<CompletableFuture<?>>();
        try (var callbacks = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effect -> {
                    effects.add(effect);
                    if (effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof MuSigMediationRequest) {
                        var saved = owner.get().getSavedRecord();
                        var requested = saved.trade().getMediation().orElseThrow();
                        assertInstanceOf(MuSigMediationState.Requested.class, requested.state());
                        assertTrue(saved.processedEventTypes().getOrDefault(Flow.MEDIATION,
                                Set.of()).contains(MediationRequestedEvent.class));
                        assertTrue(saved.pendingEffects().contains(effect), "the send must already be checkpointed");
                        var submission = owner.get().submit(new MediationOpenedEvent(opened()));
                        callbackSubmission.set(submission);
                        assertFalse(submission.isDone(), "callback input must wait for the current step");
                        assertSame(requested, owner.get().getSavedRecord().trade().getMediation().orElseThrow());
                    }
                },
                logs::add,
                clock)) {
            owner.set(callbacks);
            runSetup(callbacks::handle);

            callbacks.handle(new MediationRequestedEvent());

            assertTrue(callbackSubmission.get().isDone());
            var completed = assertInstanceOf(SubmissionResult.Completed.class, callbackSubmission.get().join());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, completed.processingResult());
            var snapshot = assertInstanceOf(StateMachineSnapshot.class, applied.snapshot());
            assertSame(callbacks.getTrade(), snapshot.document());
            mediationRequestEffect();
            assertInstanceOf(MuSigMediationState.Open.class, callbacks.getTrade().getMediation().orElseThrow().state());
            assertTrue(callbacks.getSavedRecord().pendingEffects().isEmpty());
        }
    }

    @Test
    void normalTradingContinuesWhileMediationIsOpenAndCaseUpdatesSurviveTradeClosure() {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        runSettlement(mediated::handle);
        var execution = mediated.getTrade().getExecution();
        assertInstanceOf(TradeClosed.class, execution.state());
        assertInstanceOf(MuSigMediationState.Open.class, mediationState());

        mediated.handle(new MediationClosedEvent(closed()));
        mediated.handle(peerAccepted());

        assertSame(execution, mediated.getTrade().getExecution());
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertRejected(() -> mediated.handle(new MediationRequestedEvent()));
        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @Test
    void localAcceptanceSignsFirstAndPeerAcceptanceCloses() {
        prepareResult();
        var before = mediated.getTrade();

        mediated.handle(new MediationResultAcceptedEvent());

        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        assertSame(before.getMediation().orElseThrow().data().signedResult(), mediation().data().signedResult());
        assertEquals(new MuSigMediationDecisionState.Init(),
                before.getMediation().orElseThrow().myDecision().state());
        assertTrue(logs.contains(ACCEPTED_LOG));
        assertTrue(mediated.getSavedRecord().scheduledEvents().isEmpty());
        verify(blockingStub(), never()).closeTrade(any());
        verify(blockingStub(), never()).customCloseTrade(any());
        mediated.handle(peerAccepted());

        assertMediatedClosure();
        assertTrue(mediated.getSavedRecord().scheduledEvents().isEmpty());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void anEarlyPeerAcceptanceIsValidatedWhenTheCaseClosesThenWaitsForOurSignature() {
        runSetup(mediated::handle);
        mediated.handle(peerAccepted());
        mediated.handle(new MediationClosedEvent(closed()));
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        assertInstanceOf(MuSigMediationState.Init.class, mediationState());

        mediated.handle(new MediationOpenedEvent(opened()));

        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        assertInstanceOf(DepositTxConfirmed.class, mediated.getExecutionState());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());

        mediated.handle(new MediationResultAcceptedEvent());

        assertMediatedClosure();
        var history = mediated.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of());
        assertEquals(Set.of(MediationOpenedEvent.class, MediationClosedEvent.class), history);
        assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                        Set.of()));
        assertEquals(Set.of(MediationResultAcceptedEvent.class),
                mediated.getProcessedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                        Set.of()));
        assertFalse(history.contains(MediationResultByPeerAcceptedEvent.class));
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
    }

    @Test
    void duplicateMessageTypesDoNotRepeatSigningOrClosing() {
        prepareResult();
        mediated.handle(peerAccepted());
        mediated.handle(peerAccepted());
        mediated.handle(new MediationResultAcceptedEvent());
        var before = mediated.getTrade();
        var logsBefore = List.copyOf(logs);

        mediated.handle(peerAccepted());
        mediated.handle(new MediationResultAcceptedEvent());
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));

        assertSame(before, mediated.getTrade());
        assertEquals(logsBefore, logs);
        assertEquals(1L, logs.stream().filter(ACCEPTED_LOG::equals).count());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void signingFailureTerminatesExecutionAndPreservesTheAcceptedDecision() {
        prepareResult();
        RuntimeException failure = new IllegalStateException("signing unavailable");
        when(blockingStub().signCustomPayoutTx(any())).thenThrow(failure);

        assertSame(failure,
                assertOperationFailure(() -> mediated.handle(new MediationResultAcceptedEvent())).getCause());

        assertInstanceOf(Failed.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                Set.of()).contains(MediationResultAcceptedEvent.class));
        assertTrue(mediated.getTrade().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
        assertFalse(logs.contains(ACCEPTED_LOG));
        assertTrue(sentMessageTypes().contains(MuSigReportErrorMessage.class));
        var failed = mediated.getTrade();
        mediated.resume();
        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertSame(failed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
    }

    @Test
    void failedDeferredFinalizationPreservesAcceptanceAndTerminatesExecution() {
        prepareResult();
        mediated.handle(peerAccepted());
        RuntimeException failure = new IllegalStateException("closing unavailable");
        when(blockingStub().customCloseTrade(any())).thenThrow(failure);

        // Our acceptance applies. The older peer-acceptance fact fails when signing makes it ready.
        assertInstanceOf(ProcessingResult.Applied.class, mediated.handle(new MediationResultAcceptedEvent()));

        assertInstanceOf(Failed.class, mediated.getExecutionState());
        assertEquals(failure.getMessage(),
                mediated.getTrade().getExecution().data().getMyFailure().orElseThrow().errorMessage());
        assertSame(mediated.getTrade(), mediated.getSavedRecord().trade());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                Set.of()).contains(MuSigCustomPayoutPsbtMessage.class));
        var failed = mediated.getTrade();
        mediated.resume();
        assertSame(failed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void aClosingFailurePreservesPeerAcceptanceAndTerminatesExecution() {
        prepareResult();
        mediated.handle(new MediationResultAcceptedEvent());
        RuntimeException failure = new IllegalStateException("closing unavailable");
        when(blockingStub().customCloseTrade(any())).thenThrow(failure);

        assertSame(failure, assertOperationFailure(() -> mediated.handle(peerAccepted())).getCause());

        assertInstanceOf(Failed.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                Set.of()).contains(MuSigCustomPayoutPsbtMessage.class));
        var failed = mediated.getTrade();
        mediated.resume();
        assertSame(failed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), times(1)).customCloseTrade(any());
    }

    @Test
    void customSigningPreventsNormalAndForcedClosure() {
        prepareResult();
        mediated.handle(new MediationResultAcceptedEvent());
        var signed = mediated.getTrade();

        mediated.handle(paymentInitiatedMessage());
        assertRejected(() -> mediated.handle(new PaymentReceiptConfirmedEvent()));
        closeCooperatively(mediated::handle);
        mediated.handle(new SellersCloseTradeTimeoutEvent());
        mediated.resume();

        assertSame(signed, mediated.getTrade());
        verify(blockingStub(), never()).signSwapTx(any());
        verify(blockingStub(), never()).closeTrade(any());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());

        mediated.handle(peerAccepted());

        assertMediatedClosure();
        verify(blockingStub(), never()).closeTrade(any());
    }

    @Test
    void normalCommitmentPreventsMediatedAcceptance() {
        prepareResult();
        runToPaymentReceiptConfirmed(mediated::handle);
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));

        assertSame(before, mediated.getTrade());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        closeCooperatively(mediated::handle);
        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
    }

    @Test
    void mediationCanOpenBeforeDepositConfirmationButAcceptanceMustWaitForExecution() {
        mediated.handle(new MuSigTakeOfferEvent());
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));

        assertSame(before, mediated.getTrade());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        completeSetup(mediated::handle);
        mediated.handle(new MediationResultAcceptedEvent());
        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
    }

    @Test
    void mediationCanStillBeAcceptedAfterTheBuyerInitiatedPayment() {
        prepareResult();
        mediated.handle(paymentInitiatedMessage());
        var partialSignatures = mediated.getTrade().getExecution().data().getPeerPartialSignatures();

        mediated.handle(new MediationResultAcceptedEvent());
        assertRejected(() -> mediated.handle(new PaymentReceiptConfirmedEvent()));

        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(partialSignatures, mediated.getTrade().getExecution().data().getPeerPartialSignatures());
        verify(blockingStub(), times(1)).signSwapTx(any());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
    }

    @Test
    void aCaseClosedWithoutAPayoutCannotBeAcceptedOrRejectedAsAPayout() {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        var noPayout = new MuSigMediationResult(ContractService.getContractHash(muSigContract),
                MediationResultReason.TRADE_ALREADY_SETTLED,
                MediationPayoutDistributionType.NO_PAYOUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        mediated.handle(new MediationClosedEvent(closed(noPayout, sign(noPayout))));
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));

        assertSame(before, mediated.getTrade());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
    }

    @Test
    void earlyRejectionIsNotReplayedWhenTheDepositBecomesAvailable() {
        mediated.handle(new MuSigTakeOfferEvent());
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));
        mediated.handle(setupMessage());
        var before = mediated.getTrade();
        int effectCount = effects.size();

        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));

        assertSame(before, mediated.getTrade());
        assertEquals(effectCount, effects.size());
        mediated.handle(depositSetupMessage());
        mediated.resume();
        assertInstanceOf(DepositTxSignedAndPublished.class, mediated.getExecutionState());
        assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation().myDecision().state());
        assertFalse(mediated.getProcessedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                Set.of()).contains(MediationResultRejectedEvent.class));
        assertFalse(sentMessageTypes().contains(MuSigMediationResultRejectionMessage.class));

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        var execution = mediated.getTrade().getExecution();
        mediated.handle(new MediationResultRejectedEvent());

        assertSame(execution, mediated.getTrade().getExecution());
        assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation().myDecision().state());
        assertTrue(sentMessageTypes().contains(MuSigMediationResultRejectionMessage.class));
        verify(blockingStub(), never()).signCustomPayoutTx(any());
    }

    @Test
    void rejectionRemainsAvailableAfterPaymentReceiptConfirmation() {
        prepareResult();
        runToPaymentReceiptConfirmed(mediated::handle);
        var execution = mediated.getTrade().getExecution();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        mediated.handle(new MediationResultRejectedEvent());

        assertSame(execution, mediated.getTrade().getExecution());
        assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation().myDecision().state());
        assertTrue(sentMessageTypes().contains(MuSigMediationResultRejectionMessage.class));
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        closeCooperatively(mediated::handle);
        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
    }

    @Test
    void localRejectionIsFinalButDoesNotBlockLearningPeerAcceptanceOrNormalSettlement() {
        prepareResult();
        mediated.handle(new MediationResultRejectedEvent());
        assertTrue(logs.contains(REJECTED_LOG));
        var logsAfterRejection = List.copyOf(logs);
        mediated.handle(new MediationResultRejectedEvent());
        assertEquals(logsAfterRejection, logs);

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        mediated.handle(peerAccepted());

        assertEquals(new MuSigMediationDecisionState.Rejected(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        assertTrue(sentMessageTypes().contains(MuSigMediationResultRejectionMessage.class));
        runSettlement(mediated::handle);
        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingChatChannelDoesNotBlockTheDecision(boolean accepted) {
        prepareResult();
        when(services.getChatService().getMuSigOpenTradeChannelService().findChannelByTradeId(TRADE_ID))
                .thenReturn(Optional.empty());

        if (accepted) {
            mediated.handle(new MediationResultAcceptedEvent());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation().myDecision().state());
            assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        } else {
            mediated.handle(new MediationResultRejectedEvent());
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation().myDecision().state());
        }

        assertFalse(logs.contains(ACCEPTED_LOG));
        assertFalse(logs.contains(REJECTED_LOG));
    }

    @Test
    void knownPeerRejectionPreventsLocalAcceptanceAndCannotBeReplacedByAcceptance() {
        prepareResult();
        mediated.handle(peerRejected());
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertSame(before, mediated.getTrade());
        mediated.handle(peerAccepted());

        assertSame(before, mediated.getTrade());
        assertInstanceOf(DepositTxConfirmed.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Rejected(),
                mediation().peerDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
    }

    @Test
    void knownPeerAcceptanceCannotBeReplacedByRejection() {
        prepareResult();
        mediated.handle(peerAccepted());
        var before = mediated.getTrade();

        mediated.handle(peerRejected());

        assertSame(before, mediated.getTrade());
        assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation().peerDecision().state());
        assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                        Set.of()));
        mediated.handle(new MediationResultAcceptedEvent());
        assertMediatedClosure();
    }

    @Test
    void rejectionAfterOurAcceptanceDoesNotUndoOurCommitment() {
        prepareResult();
        mediated.handle(new MediationResultAcceptedEvent());
        mediated.handle(peerRejected());

        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));
        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Rejected(),
                mediation().peerDecision().state());
        var signed = mediated.getTrade();
        mediated.handle(new SellersCloseTradeTimeoutEvent());
        mediated.resume();
        assertSame(signed, mediated.getTrade());
        verify(blockingStub(), never()).closeTrade(any());
    }

    @Test
    void decisionsBeforeAClosedProposalAreRejectedRatherThanQueuedForLater() {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));
        mediated.handle(new MediationClosedEvent(closed()));
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
    }

    @ParameterizedTest
    @CsvSource({"OPEN, trade", "OPEN, sender", "CLOSED, trade", "CLOSED, sender"})
    void mediatorNotificationsRequireMatchingTradeAndMediator(MediationCaseState caseState, String problem) {
        runSetup(mediated::handle);
        var before = mediated.getTrade();
        var effectsBefore = List.copyOf(effects);
        var logsBefore = List.copyOf(logs);
        var valid = caseState == MediationCaseState.OPEN ? opened() : closed();
        var invalid = new MuSigMediationStateChangeMessage(uid(),
                problem.equals("trade") ? uid() : TRADE_ID,
                problem.equals("sender") ? peerNetworkId : mediatorNetworkId,
                caseState,
                valid.getMuSigMediationResult(),
                valid.getMediationResultSignature());
        Event wrapped = caseState == MediationCaseState.OPEN
                ? new MediationOpenedEvent(invalid)
                : new MediationClosedEvent(invalid);

        assertRejected(() -> mediated.handle(wrapped));
        assertSame(before, mediated.getTrade());
        assertEquals(effectsBefore, effects);
        assertEquals(logsBefore, logs);

        mediated.handle(new MediationOpenedEvent(opened()));
        assertInstanceOf(MuSigMediationState.Open.class, mediationState());
        mediated.handle(new MediationClosedEvent(closed()));
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        var after = mediated.getTrade();
        assertRejected(() -> mediated.handle(wrapped));
        mediated.handle(caseState == MediationCaseState.OPEN
                ? new MediationOpenedEvent(valid)
                : new MediationClosedEvent(valid));
        assertSame(after, mediated.getTrade());
    }

    @Test
    void mediatorNotificationsRequireAnAssignedMediator() {
        when(muSigContract.getMediator()).thenReturn(Optional.empty());
        var before = protocol.getTrade();
        assertTrue(before.getMediation().isEmpty());

        assertRejected(() -> protocol.handle(new MediationOpenedEvent(opened())));
        assertRejected(() -> protocol.handle(new MediationClosedEvent(closed())));

        assertSame(before, protocol.getTrade());
        assertTrue(effects.isEmpty());
        assertTrue(logs.isEmpty());
    }

    @Test
    void wrappedMediatorNotificationsMustMatchTheirCaseStateBeforeRetention() {
        runSetup(mediated::handle);
        var before = mediated.getTrade();

        assertRejected(() -> mediated.handle(new MediationClosedEvent(opened())));
        assertRejected(() -> mediated.handle(new MediationOpenedEvent(closed())));

        assertSame(before, mediated.getTrade());
        mediated.handle(new MediationOpenedEvent(opened()));
        assertInstanceOf(MuSigMediationState.Open.class, mediationState());
        mediated.handle(new MediationClosedEvent(closed()));
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
    }

    @Test
    void aValidClosingNotificationWaitsForTheOpeningNotification() {
        runSetup(mediated::handle);
        var before = mediated.getTrade();

        mediated.handle(new MediationClosedEvent(closed()));

        assertSame(before, mediated.getTrade());
        assertInstanceOf(MuSigMediationState.Init.class, mediationState());
        mediated.handle(new MediationOpenedEvent(opened()));
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        assertEquals(result, mediation().data().signedResult().orElseThrow().result());
    }

    @ParameterizedTest
    @ValueSource(strings = {"signature", "contract", "amounts"})
    void anInvalidCaseResultStopsTheCaseAndItsChildren(String invalidPart) throws GeneralSecurityException {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        var before = mediated.getTrade();
        byte[] wrongSignature = MuSigMediationResultService.signMediationResult(result,
                KeyGeneration.generateDefaultEcKeyPair());
        var wrongContract = new MuSigMediationResult(new byte[20],
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(150_000L),
                Optional.of(50_000L),
                Optional.empty(),
                Optional.empty());
        var wrongAmounts = result(150_000, 60_000);

        var invalid = switch (invalidPart) {
            case "signature" -> closed(result, wrongSignature);
            case "contract" -> closed(wrongContract, sign(wrongContract));
            default -> closed(wrongAmounts, sign(wrongAmounts));
        };
        assertProtocolFailure(() -> mediated.handle(new MediationClosedEvent(invalid)));

        assertSame(before.getExecution(), mediated.getTrade().getExecution());
        assertEquals(new MuSigTradeState.Active(), mediated.getTrade().getState());
        assertInstanceOf(MuSigMediationState.Failed.class, mediationState());
        assertTrue(mediation().data().failure().isPresent());
        assertTrue(mediation().data().signedResult().isEmpty());
        assertSame(before.getMediation().orElseThrow().myDecision(), mediation().myDecision());
        assertSame(before.getMediation().orElseThrow().peerDecision(), mediation().peerDecision());
        var failed = mediated.getTrade();
        mediated.handle(new MediationClosedEvent(closed()));
        mediated.handle(peerAccepted());
        mediated.handle(peerRejected());
        mediated.resume();
        assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
        assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));
        assertSame(failed, mediated.getTrade());
        assertFalse(sentMessageTypes().contains(MuSigReportErrorMessage.class));
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aDecisionFailureLeavesItsParentAndSiblingRunning(boolean mine) {
        prepareResult();
        var before = mediated.getTrade();
        var peerMessage = peerAccepted();
        var cause = new IllegalStateException("Proposal hashing failed");
        effects.clear();
        try (var resultService = mockStatic(MuSigMediationResultService.class, CALLS_REAL_METHODS)) {
            resultService.when(() -> MuSigMediationResultService.getMediationResultHash(result)).thenThrow(cause);
            assertSame(cause, assertProtocolFailure(() -> {
                if (mine) {
                    mediated.handle(new MediationResultRejectedEvent());
                } else {
                    mediated.handle(peerMessage);
                }
            }).getCause());
        }

        assertSame(before.getExecution(), mediated.getTrade().getExecution());
        assertEquals(new MuSigTradeState.Active(), mediated.getTrade().getState());
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        assertTrue(mediation().data().failure().isEmpty());
        assertSame(before.getMediation().orElseThrow().data().signedResult(), mediation().data().signedResult());
        var failedDecision = mine ? mediation().myDecision() : mediation().peerDecision();
        assertInstanceOf(MuSigMediationDecisionState.Failed.class, failedDecision.state());
        assertEquals("Proposal hashing failed", failedDecision.data().failure().orElseThrow().errorMessage());
        assertTrue(effects.isEmpty());

        if (mine) {
            assertSame(before.getMediation().orElseThrow().peerDecision(), mediation().peerDecision());
            assertRejected(() -> mediated.handle(new MediationResultRejectedEvent()));
            mediated.handle(peerAccepted());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation().peerDecision().state());
            assertSame(failedDecision, mediation().myDecision());
        } else {
            assertSame(before.getMediation().orElseThrow().myDecision(), mediation().myDecision());
            var failed = mediated.getTrade();
            mediated.handle(peerAccepted());
            mediated.handle(peerRejected());
            assertSame(failed, mediated.getTrade());
            assertRejected(() -> mediated.handle(new MediationResultAcceptedEvent()));
            mediated.handle(new MediationResultRejectedEvent());
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation().myDecision().state());
            assertSame(failedDecision, mediation().peerDecision());
        }
        mediated.resume();
        assertSame(before.getExecution(), mediated.getTrade().getExecution());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
        runSettlement(mediated::handle);
        assertInstanceOf(TradeClosed.class, mediated.getExecutionState());
    }

    @Test
    void aPeerDecisionFailureAfterSigningDoesNotEscalateOrAllowLaterPayoutProcessing() {
        prepareResult();
        mediated.handle(new MediationResultAcceptedEvent());
        var signed = mediated.getTrade();
        var message = peerAccepted();
        effects.clear();
        try (var resultService = mockStatic(MuSigMediationResultService.class, CALLS_REAL_METHODS)) {
            resultService.when(() -> MuSigMediationResultService.getMediationResultHash(result))
                    .thenThrow(new IllegalStateException("Proposal hashing failed"));
            assertProtocolFailure(() -> mediated.handle(message));
        }

        assertSame(signed.getExecution(), mediated.getTrade().getExecution());
        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(new MuSigTradeState.Active(), mediated.getTrade().getState());
        assertInstanceOf(MuSigMediationState.Closed.class, mediationState());
        assertSame(signed.getMediation().orElseThrow().myDecision(), mediation().myDecision());
        assertInstanceOf(MuSigMediationDecisionState.Failed.class, mediation().peerDecision().state());
        var failed = mediated.getTrade();
        mediated.handle(peerAccepted());
        mediated.resume();
        assertSame(failed, mediated.getTrade());
        assertTrue(effects.isEmpty());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @Test
    void peerDecisionsRequireTheRightTradeSenderReceiverAndVersion() {
        prepareResult();
        var before = mediated.getTrade();
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        List<MuSigCustomPayoutPsbtMessage> wrongMessages = List.of(
                peerAccepted(uid(), "1.0.0", peerNetworkId, myNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "1.0.0", myNetworkId, myNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "1.0.0", peerNetworkId, peerNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "2.0.0", peerNetworkId, myNetworkId, hash, PAYOUT_TX_ID));
        for (var message : wrongMessages) {
            assertRejected(() -> mediated.handle(message));
            assertSame(before, mediated.getTrade());
        }
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        mediated.handle(peerAccepted());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aWrongProposalHashIsIgnoredWithoutConsumingThePeerDecisionType(boolean accepted) {
        prepareResult();
        var before = mediated.getTrade();
        effects.clear();
        logs.clear();
        byte[] wrongHash = MuSigMediationResultService.getMediationResultHash(result);
        wrongHash[0] ^= 1;

        mediated.handle(peerDecision(accepted, wrongHash));

        assertSame(before, mediated.getTrade());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        assertTrue(effects.isEmpty());
        assertTrue(logs.isEmpty());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());

        var valid = peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(result));
        mediated.handle(valid);
        assertEquals(accepted ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                mediation().peerDecision().state());
        assertInstanceOf(DepositTxConfirmed.class, mediated.getExecutionState());
        assertTrue(mediated.getProcessedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                Set.of()).contains(valid.getClass()));
        var after = mediated.getTrade();
        mediated.handle(valid);
        assertSame(after, mediated.getTrade());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anEarlyWrongProposalDoesNotBlockAValidPeerDecisionBehindIt(boolean accepted) {
        runSetup(mediated::handle);
        var before = mediated.getTrade();
        byte[] wrongHash = MuSigMediationResultService.getMediationResultHash(result);
        wrongHash[0] ^= 1;
        mediated.handle(peerDecision(accepted, wrongHash));
        mediated.handle(peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(result)));
        assertSame(before, mediated.getTrade());

        mediated.handle(new MediationOpenedEvent(opened()));
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        mediated.handle(new MediationClosedEvent(closed()));

        assertInstanceOf(DepositTxConfirmed.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().myDecision().state());
        assertEquals(accepted ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                mediation().peerDecision().state());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @Test
    void invalidPeerDecisionsAreRejectedBeforeTheCaseOpens() {
        var before = mediated.getTrade();
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        List<MuSigCustomPayoutPsbtMessage> wrongMessages = List.of(
                peerAccepted(uid(), "1.0.0", peerNetworkId, myNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "1.0.0", myNetworkId, myNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "1.0.0", peerNetworkId, peerNetworkId, hash, PAYOUT_TX_ID),
                peerAccepted(TRADE_ID, "2.0.0", peerNetworkId, myNetworkId, hash, PAYOUT_TX_ID));
        for (var message : wrongMessages) {
            assertRejected(() -> mediated.handle(message));
            assertSame(before, mediated.getTrade());
        }
        assertTrue(effects.isEmpty());
        assertTrue(logs.isEmpty());

        prepareResult();
        mediated.resume();
        assertEquals(new MuSigMediationDecisionState.Init(), mediation().peerDecision().state());
        mediated.handle(peerAccepted());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
    }

    @Test
    void aPeerFirstPayoutMismatchKeepsExecutionSigned() {
        prepareResult();
        var first = peerAccepted(TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                MuSigMediationResultService.getMediationResultHash(result),
                "34".repeat(32));
        mediated.handle(first);

        mediated.handle(new MediationResultAcceptedEvent());

        assertInstanceOf(CustomPayoutSigned.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        var signed = mediated.getTrade();
        assertFalse(mediated.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
        verify(blockingStub(), never()).customCloseTrade(any());

        mediated.resume();
        mediated.handle(peerAccepted());
        mediated.handle(new SellersCloseTradeTimeoutEvent());

        assertSame(signed, mediated.getTrade());
        verify(blockingStub(), times(1)).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
        verify(blockingStub(), never()).closeTrade(any());
    }

    @Test
    void externalCallersCannotForgeInternalAcceptanceFacts() {
        prepareResult();
        var before = mediated.getTrade();
        assertRejected(() -> mediated.handle(new MediationResultByMyselfAcceptedEvent(result)));
        assertSame(before, mediated.getTrade());
        verify(blockingStub(), never()).signCustomPayoutTx(any());
        verify(blockingStub(), never()).customCloseTrade(any());
    }

    @Test
    void effectsObserveBothCommittedFlowsAndReentrantPeerInputWaitsForAllEffects() {
        var owner = new AtomicReference<MuSigTradeLaneFixture<SellerAsTakerState>>();
        var callbackSubmission = new AtomicReference<CompletableFuture<?>>();
        when(blockingStub().customCloseTrade(any())).thenAnswer(invocation -> {
            var saved = owner.get().getSavedRecord();
            assertInstanceOf(CustomPayoutSigned.class, saved.trade().getExecution().state());
            assertTrue(saved.pendingEffects().isEmpty(), "the previous transition's effects must finish before closure");
            assertTrue(logs.contains(ACCEPTED_LOG));
            assertFalse(callbackSubmission.get().isDone());
            return closedPayout();
        });
        try (var callbacks = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effect -> {
                    effects.add(effect);
                    if (effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof MuSigCustomPayoutPsbtMessage) {
                        var saved = owner.get().getSavedRecord();
                        var committed = saved.trade();
                        assertInstanceOf(CustomPayoutSigned.class, committed.getExecution().state());
                        assertEquals(new MuSigMediationDecisionState.Accepted(),
                                committed.getMediation().orElseThrow().myDecision().state());
                        assertTrue(saved.pendingEffects().contains(effect), "the send must already be checkpointed");
                        var submission = owner.get().submit(peerAccepted());
                        callbackSubmission.set(submission);
                        assertFalse(submission.isDone(), "callback input must wait for the current step");
                        assertSame(committed, owner.get().getSavedRecord().trade());
                    }
                },
                logs::add,
                clock)) {
            owner.set(callbacks);
            runSetup(callbacks::handle);
            callbacks.handle(new MediationOpenedEvent(opened()));
            callbacks.handle(new MediationClosedEvent(closed()));
            callbacks.handle(new MediationResultAcceptedEvent());

            assertTrue(callbackSubmission.get().isDone());
            var completed = assertInstanceOf(SubmissionResult.Completed.class, callbackSubmission.get().join());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, completed.processingResult());
            var snapshot = assertInstanceOf(StateMachineSnapshot.class, applied.snapshot());
            assertSame(callbacks.getTrade(), snapshot.document());
            assertInstanceOf(CustomPayoutClosedTrade.class, callbacks.getExecutionState());
            assertTrue(callbacks.getSavedRecord().pendingEffects().isEmpty());
            assertEquals(List.of(ObserveDepositTxConfirmation.class, ActivateMediationChat.class), nonMessageEffectTypes());
            verify(blockingStub(), times(1)).signCustomPayoutTx(any());
            verify(blockingStub(), times(1)).customCloseTrade(any());
        }
    }

    @Test
    void theServicePersistsTerminalFinalizationFailureWithoutReplayingPeerAcceptance() throws Exception {
        when(blockingStub().customCloseTrade(any()))
                .thenThrow(new IllegalStateException("backend temporarily unavailable"))
                .thenReturn(closedPayout());
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            effects.add(new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        var service = serviceWithClock(services, grpcClient, serviceClock);
        try {
            service.initialize().get(10, TimeUnit.SECONDS);
            initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                    .get(10, TimeUnit.SECONDS);
            submitMessage(service, setupMessage()).get(10, TimeUnit.SECONDS);
            submitMessage(service, depositSetupMessage()).get(10, TimeUnit.SECONDS);
            submitMessage(service, accountPayloadMessage(peerAccountPayload)).get(10, TimeUnit.SECONDS);
            service.onDepositTxConfirmed(TRADE_ID).get(10, TimeUnit.SECONDS);
            submitMessage(service, opened()).get(10, TimeUnit.SECONDS);
            submitMessage(service, closed()).get(10, TimeUnit.SECONDS);

            submitMessage(service, peerAccepted()).get(10, TimeUnit.SECONDS);
            var peerAccepted = savedRecord(service, TRADE_ID).trade();
            assertInstanceOf(DepositTxConfirmed.class, peerAccepted.getExecution().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    peerAccepted.getMediation().orElseThrow().peerDecision().state());
            var acceptance = service.acceptMediationResult(TRADE_ID);
            // Our acceptance applied. The older, retained peer fact failed when that made it ready.
            acceptance.get(10, TimeUnit.SECONDS);
            var signed = savedRecord(service, TRADE_ID).trade();
            assertInstanceOf(Failed.class, signed.getExecution().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    signed.getMediation().orElseThrow().myDecision().state());

            resumeLane(service, TRADE_ID).get(10, TimeUnit.SECONDS);
            assertInstanceOf(Failed.class, service.getTradeReadModel(TRADE_ID).getExecutionState());
            assertSame(signed, savedRecord(service, TRADE_ID).trade());
            verify(blockingStub(), times(1)).signCustomPayoutTx(any());
            verify(blockingStub(), times(1)).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void twoCallingThreadsStillShareOneSynchronousOwner() throws Exception {
        prepareResult();
        var before = mediated.getTrade();
        CountDownLatch signing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(blockingStub().signCustomPayoutTx(any())).thenAnswer(invocation -> {
            signing.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return signedPayout();
        });
        try (var executor = Executors.newFixedThreadPool(2)) {
            var acceptance = executor.submit(() -> mediated.handle(new MediationResultAcceptedEvent()));
            try {
                assertTrue(signing.await(10, TimeUnit.SECONDS));
                var peerInput = executor.submit(() -> mediated.handle(peerAccepted()));
                // The signing latch follows the acceptance checkpoint; publication waits for the lane step to finish.
                var accepted = mediated.getSavedRecord().trade();
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        accepted.getMediation().orElseThrow().myDecision().state());
                assertSame(before.getExecution(), accepted.getExecution(), "signing has not completed yet");
                assertSame(before.getMediation().orElseThrow().peerDecision(),
                        accepted.getMediation().orElseThrow().peerDecision());
                assertFalse(peerInput.isDone(), "another input must wait for the current handler");
                release.countDown();
                acceptance.get(10, TimeUnit.SECONDS);
                peerInput.get(10, TimeUnit.SECONDS);
                assertMediatedClosure();
            } finally {
                release.countDown();
            }
        }
    }

    private void prepareResult() {
        runSetup(mediated::handle);
        mediated.handle(new MediationOpenedEvent(opened()));
        mediated.handle(new MediationClosedEvent(closed()));
    }

    private void assertMediatedClosure() {
        assertEquals(TRADE_ID, mediated.getTrade().getId());
        assertInstanceOf(CustomPayoutClosedTrade.class, mediated.getExecutionState());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(), mediation().peerDecision().state());
        var execution = mediated.getTrade().getExecution().data();
        assertTrue(execution.getMyCustomPayoutPsbt().isPresent());
        assertTrue(execution.getPeerCustomPayoutPsbt().isPresent());
        assertArrayEquals(new byte[]{9}, execution.getCustomCloseTradeResponse().orElseThrow().getCustomPayoutTx());
    }

    private MuSigV2Mediation<? extends MuSigMediationState> mediation() {
        return mediated.getTrade().getMediation().orElseThrow();
    }

    private MuSigMediationState mediationState() {
        return mediated.getTrade().getMediation().orElseThrow().state();
    }

    private Effect.SendMessage<?, ?> mediationRequestEffect() {
        var requests = effects.stream()
                .filter(effect -> effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof MuSigMediationRequest)
                .toList();
        assertEquals(1, requests.size());
        return assertInstanceOf(Effect.SendMessage.class, requests.getFirst());
    }

    private MuSigOpenTradeMessage chatMessage(String text) {
        MuSigOpenTradeMessage message = mock(MuSigOpenTradeMessage.class);
        String id = StringUtils.createUid();
        when(message.getId()).thenReturn(id);
        when(message.getTextOrNA()).thenReturn(text);
        when(message.toValueProto(false)).thenReturn(bisq.chat.protobuf.ChatMessage.newBuilder().setId(id).setText(text).build());
        return message;
    }

    private MuSigTradeLaneFixture<SellerAsTakerState> createMediatedLane() {
        UserProfile mediator = mock(UserProfile.class);
        PubKey pubKey = mock(PubKey.class);
        when(pubKey.getPublicKey()).thenReturn(mediatorKeys.getPublic());
        when(mediatorNetworkId.getPubKey()).thenReturn(pubKey);
        when(mediator.getNetworkId()).thenReturn(mediatorNetworkId);
        when(mediator.getPublicKey()).thenReturn(mediatorKeys.getPublic());
        when(mediator.toProto(anyBoolean())).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        when(muSigContract.getBtcSideAmount()).thenReturn(100_000L);
        when(muSigContract.getOffer().getMarket().isBaseCurrencyBitcoin()).thenReturn(true);
        var offerOptions = new ArrayList<>(muSigContract.getOffer().getOfferOptions());
        offerOptions.add(new CollateralOption(0.5, 0.5));
        when(muSigContract.getOffer().getOfferOptions()).thenReturn(offerOptions);
        when(blockingStub().signCustomPayoutTx(any())).thenReturn(signedPayout());
        when(blockingStub().customCloseTrade(any())).thenReturn(closedPayout());
        return new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock);
    }

    private MuSigMediationResult result(long buyer, long seller) {
        return new MuSigMediationResult(ContractService.getContractHash(muSigContract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(buyer),
                Optional.of(seller),
                Optional.empty(),
                Optional.empty());
    }

    private byte[] sign(MuSigMediationResult result) {
        try {
            return MuSigMediationResultService.signMediationResult(result, mediatorKeys);
        } catch (GeneralSecurityException exception) {
            throw new AssertionError(exception);
        }
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
        return closed(result, sign(result));
    }

    private MuSigMediationStateChangeMessage closed(MuSigMediationResult result, byte[] signature) {
        return new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                mediatorNetworkId,
                MediationCaseState.CLOSED,
                Optional.of(result),
                Optional.of(signature));
    }

    private MuSigCustomPayoutPsbtMessage peerAccepted() {
        return peerAccepted(TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                MuSigMediationResultService.getMediationResultHash(result),
                PAYOUT_TX_ID);
    }

    private MuSigCustomPayoutPsbtMessage peerAccepted(String tradeId,
                                                      String version,
                                                      NetworkId sender,
                                                      NetworkId receiver,
                                                      byte[] hash,
                                                      String txId) {
        return new MuSigCustomPayoutPsbtMessage(uid(), tradeId, version, sender, receiver, hash, txId, new byte[]{7});
    }

    private MuSigMediationResultRejectionMessage peerRejected() {
        return new MuSigMediationResultRejectionMessage(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                MuSigMediationResultService.getMediationResultHash(result));
    }

    private Event peerDecision(boolean accepted, byte[] resultHash) {
        return accepted
                ? peerAccepted(TRADE_ID, "1.0.0", peerNetworkId, myNetworkId, resultHash, PAYOUT_TX_ID)
                : new MuSigMediationResultRejectionMessage(uid(),
                        TRADE_ID,
                        "1.0.0",
                        peerNetworkId,
                        myNetworkId,
                        resultHash);
    }

    private CustomPayoutPsbt signedPayout() {
        return CustomPayoutPsbt.newBuilder().setTxId(PAYOUT_TX_ID).setPsbt(ByteString.copyFrom(new byte[]{6}))
                .setBuyersPayoutAmountIncludingFee(150_000).setSellersPayoutAmountIncludingFee(50_000).build();
    }

    private CustomCloseTradeResponse closedPayout() {
        return CustomCloseTradeResponse.newBuilder().setCustomPayoutTx(ByteString.copyFrom(new byte[]{9})).build();
    }
}
