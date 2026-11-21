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

import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.i18n.Res;
import bisq.offer.Direction;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Mediation between two services: the chat, the acceptance in either order, and the mediated payout.
 */
class MuSigMediationPairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void mediationActivatesChatOnRequestAndPeerOpeningAndKeepsItActiveAfterClosure(boolean buyerAsMaker,
                                                                                   boolean buyerRequests) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        startTrade();
        var requester = buyerRequests ? buyer : seller;
        var peer = buyerRequests ? seller : buyer;
        var requesterChannel = prepareTradeChat(requester);
        var peerChannel = prepareTradeChat(peer);
        assertEquals(MuSigDisputeAgentType.NONE, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.NONE, peerChannel.getDisputeAgentType());

        await(requester.service.requestMediation(tradeId));
        var requested = savedRecord(requester.service, tradeId).trade();

        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.NONE, peerChannel.getDisputeAgentType());
        assertEquals(1, mediationRequests.size());
        var sharedLogsBeforeOpening = List.copyOf(requester.logs);
        var opened = mediationOpenedMessage();
        await(submitMessage(requester.service, opened));
        assertTrue(mediationCaseData.isEmpty());
        await(submitMessage(peer.service, opened));
        assertEquals(1, mediationCaseData.size());
        var report = mediationCaseData.element();
        assertEquals(tradeId, report.getTradeId());
        assertEquals(peer.identity.getNetworkId(), report.getSenderNetworkId());
        assertArrayEquals(ContractService.getContractHash(contract), report.getContractHash());
        assertEquals(List.copyOf(peerChannel.getChatMessages()), report.getChatMessages());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, peerChannel.getDisputeAgentType());
        assertEquals(sharedLogsBeforeOpening, List.copyOf(requester.logs));
        assertTrue(peer.logs.isEmpty());

        var closed = new MuSigMediationClosedMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId(),
                mediationResult,
                MuSigMediationResultService.signMediationResult(mediationResult, mediatorKeys));
        for (var trader : List.of(requester, peer)) {
            await(submitMessage(trader.service, closed));
            var completed = savedRecord(trader.service, closed.getTradeId()).trade();
            assertInstanceOf(MuSigMediationState.Closed.class, completed.getMediation().orElseThrow().state());
            await(submitMessage(trader.service, opened));
            assertSame(completed, savedRecord(trader.service, opened.getTradeId()).trade());
            await(submitMessage(trader.service, closed));
            assertSame(completed, savedRecord(trader.service, closed.getTradeId()).trade());
            assertTrue(trader.saved().pendingEffects().isEmpty());
        }
        assertEquals(MuSigDisputeAgentType.MEDIATOR, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, peerChannel.getDisputeAgentType());
        verify(requester.services.getChatService().getMuSigOpenTradeChannelService(), times(2))
                .setDisputeAgentType(requesterChannel, MuSigDisputeAgentType.MEDIATOR);
        verify(peer.services.getChatService().getMuSigOpenTradeChannelService())
                .setDisputeAgentType(peerChannel, MuSigDisputeAgentType.MEDIATOR);
        String mediatorId = contract.getMediator().orElseThrow().getId();
        verify(requester.services.getChatService().getMuSigOpenTradeChannelService())
                .addLocalTradeLogMessage(Res.encode("authorizedRole.mediator.message.toRequester"),
                        mediatorId,
                        requesterChannel);
        verify(peer.services.getChatService().getMuSigOpenTradeChannelService())
                .addLocalTradeLogMessage(Res.encode("authorizedRole.mediator.message.toNonRequester"),
                        mediatorId,
                        peerChannel);
        assertEquals(1, mediationCaseData.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingNoticesAreBestEffortAndAreNotReplayedAfterRestart(boolean postingFails) throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        String notice = Res.encode("authorizedRole.mediator.message.toNonRequester");
        String mediatorId = contract.getMediator().orElseThrow().getId();
        if (postingFails) {
            doThrow(new IllegalStateException("Could not add opening notice"))
                    .when(channels).addLocalTradeLogMessage(notice, mediatorId, channel);
        }
        var before = savedRecord(buyer.service, tradeId).trade();
        var opened = mediationOpenedMessage();

        await(submitMessage(buyer.service, opened));
        var trade = savedRecord(buyer.service, opened.getTradeId()).trade();

        assertInstanceOf(MuSigMediationState.Open.class, trade.getMediation().orElseThrow().state());
        assertInstanceOf(MuSigTradeState.Active.class, trade.getState());
        assertSame(before.getExecution(), trade.getExecution());
        assertTrue(buyer.saved().pendingEffects().isEmpty());
        assertSame(trade, savedRecord(buyer.service, tradeId).trade());
        await(buyer.service.shutdown());
        buyer.start(seller);
        await(submitMessage(buyer.service, opened));

        assertEquals(trade.toProto(false), buyer.saved().trade().toProto(false));
        verify(channels).addLocalTradeLogMessage(notice, mediatorId, channel);
        verify(channels, never()).sendTradeLogMessage(anyString(), eq(channel));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aFailedMediationChatActivationIsLoggedAndDoesNotBlockTheTrade(boolean localRequest) throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        doThrow(new IllegalStateException("Chat activation failed"))
                .when(channels).setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
        chatActivationFails = true;

        await(localRequest ? buyer.service.requestMediation(tradeId) : submitMessage(buyer.service, mediationOpenedMessage()));

        var saved = buyer.saved();
        assertEquals(localRequest ? new MuSigMediationState.Requested() : new MuSigMediationState.Open(),
                saved.trade().getMediation().orElseThrow().state());
        assertInstanceOf(MuSigTradeState.Active.class, saved.trade().getState());
        assertTrue(saved.pendingEffects().isEmpty());
        assertEquals(localRequest ? 1 : 0, mediationRequests.size());
        assertEquals(localRequest ? 0 : 1, mediationCaseData.size());
        assertEquals(MuSigDisputeAgentType.NONE, channel.getDisputeAgentType());
        verify(channels).setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
    }

    @Test
    void aMissingChatDoesNotBlockTheMediationCaseReport() throws Exception {
        startTrade();
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.empty());

        await(submitMessage(buyer.service, mediationOpenedMessage()));

        var saved = buyer.saved();
        assertInstanceOf(MuSigMediationState.Open.class, saved.trade().getMediation().orElseThrow().state());
        assertTrue(saved.pendingEffects().isEmpty());
        var report = assertInstanceOf(MuSigDisputeCaseDataMessage.class, mediationCaseData.element());
        assertTrue(report.getChatMessages().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"true, false, true", "false, false, true", "true, true, true", "false, true, true",
            "true, false, false", "false, false, false", "true, true, false", "false, true, false"})
    void eitherTraderCanAcceptFirstAndBothCloseWithTheMediatedPayout(boolean buyerFirst,
                                                                     boolean paymentInitiated,
                                                                     boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        prepareMediation(paymentInitiated);
        var first = buyerFirst ? buyer : seller;
        var second = buyerFirst ? seller : buyer;

        await(first.service.acceptMediationResult(tradeId));
        awaitDelivery(second, MuSigCustomPayoutPsbtMessage.class);

        var committed = savedRecord(first.service, tradeId).trade();
        var waiting = savedRecord(second.service, tradeId).trade();
        assertInstanceOf(CustomPayoutSigned.class, committed.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                committed.getMediation().orElseThrow().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                committed.getMediation().orElseThrow().peerDecision().state());
        var expectedWaitingState = !paymentInitiated ? DepositTxConfirmed.class
                : buyerFirst ? PaymentInitiatedMessageReceived.class : PaymentInitiated.class;
        assertInstanceOf(expectedWaitingState, waiting.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                waiting.getMediation().orElseThrow().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                waiting.getMediation().orElseThrow().peerDecision().state());
        assertEquals(1, second.saved().pendingInputs().size());
        assertInstanceOf(MediationResultByPeerAcceptedEvent.class, second.saved().pendingInputs().getFirst());
        verify(first.backend).signCustomPayoutTx(argThat(request ->
                request.getTradeId().equals(tradeId) && request.getSellersPayoutAmountExcludingFee() == 600_000));
        verify(second.backend, never()).signCustomPayoutTx(any());
        verify(first.backend, never()).customCloseTrade(any());
        verify(second.backend, never()).customCloseTrade(any());

        await(second.service.acceptMediationResult(tradeId));
        awaitDelivery(first, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
        for (var peer : List.of(buyer, seller)) {
            var closed = savedRecord(peer.service, tradeId).trade();
            await(peer.service.acceptMediationResult(tradeId));
            var peerAcceptance = messages.stream().filter(MuSigCustomPayoutPsbtMessage.class::isInstance)
                    .filter(message -> message.getReceiver().equals(peer.identity.getNetworkId())).findFirst().orElseThrow();
            await(submitMessage(peer.service, peerAcceptance));
            assertSame(closed, savedRecord(peer.service, peerAcceptance.getTradeId()).trade());
            verify(peer.backend, times(1)).signCustomPayoutTx(any());
            verify(peer.backend, times(1)).customCloseTrade(any());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true,false", "true,true,true", "true,false,false", "true,false,true",
            "false,true,false", "false,true,true", "false,false,false", "false,false,true"})
    void signingFailureIsNotifiedAndBothFailuresSurviveRestart(boolean buyerAsMaker,
                                                               boolean buyerFails,
                                                               boolean peerFirst) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        prepareMediation(false);
        var local = buyerFails ? buyer : seller;
        var peer = buyerFails ? seller : buyer;
        if (peerFirst) {
            await(peer.service.acceptMediationResult(tradeId));
            awaitDelivery(local, MuSigCustomPayoutPsbtMessage.class);
        }
        var before = savedRecord(local.service, tradeId).trade();
        RuntimeException backendFailure = new IllegalStateException("Signing unavailable");
        when(local.backend.signCustomPayoutTx(any())).thenThrow(backendFailure);

        var completion = assertThrows(ExecutionException.class,
                () -> await(local.service.acceptMediationResult(tradeId)));
        var failure = assertInstanceOf(MuSigProtocolException.class, completion.getCause());
        assertEquals(MuSigFailureCategory.OPERATION_FAILED, failure.getCategory());
        assertSame(backendFailure, failure.getCause());
        awaitDelivery(peer, MuSigReportErrorMessage.class);

        var failed = savedRecord(local.service, tradeId).trade();
        var failedAtPeer = savedRecord(peer.service, tradeId).trade();
        assertInstanceOf(Failed.class, failed.getExecution().state());
        assertInstanceOf(FailedAtPeer.class, failedAtPeer.getExecution().state());
        assertSame(before.getMediation().orElseThrow().peerDecision(),
                failed.getMediation().orElseThrow().peerDecision());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                failed.getMediation().orElseThrow().myDecision().state());
        assertTrue(local.saved().processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION, Set.of())
                .contains(MediationResultAcceptedEvent.class));
        assertEquals(peerFirst ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Init(),
                failed.getMediation().orElseThrow().peerDecision().state());
        var data = failed.getExecution().data();
        var myFailure = data.getMyFailure().orElseThrow();
        assertEquals(TradeProtocolFailure.UNKNOWN, myFailure.tradeProtocolFailure());
        assertEquals("Signing unavailable", myFailure.errorMessage());
        assertTrue(myFailure.errorStackTrace().startsWith(IllegalStateException.class.getName()));
        assertTrue(data.getMyCustomPayoutPsbt().isEmpty());
        var report = sent(MuSigReportErrorMessage.class);
        var peerData = failedAtPeer.getExecution().data();
        var peerFailure = peerData.getPeerFailure().orElseThrow();
        assertEquals(report.getTradeProtocolFailure(), peerFailure.tradeProtocolFailure());
        assertEquals(report.getErrorMessage(), peerFailure.errorMessage());
        assertEquals(report.getStackTrace(), peerFailure.errorStackTrace());
        assertTrue(peerData.getMyFailure().isEmpty());
        var peerReadModel = peer.service.getTradeReadModel(tradeId);
        assertEquals(Optional.of(peerFailure), peerReadModel.getPeerFailure());
        assertTrue(peerReadModel.getMyFailure().isEmpty());
        assertTrue(local.saved().pendingInputs().isEmpty());
        assertTrue(peer.saved().pendingInputs().isEmpty());
        assertTrue(local.saved().scheduledEvents().isEmpty());
        assertTrue(peer.saved().scheduledEvents().isEmpty());
        await(submitMessage(peer.service, report));
        assertSame(failedAtPeer, savedRecord(peer.service, report.getTradeId()).trade());
        assertFailure(MuSigProtocolException.class, local.service.acceptMediationResult(tradeId));
        assertSame(failed, savedRecord(local.service, tradeId).trade());
        verify(local.backend, times(1)).signCustomPayoutTx(any());
        verify(local.backend, never()).customCloseTrade(any());
        int messageCount = messages.size();
        buyer.service.shutdown().join();
        seller.service.shutdown().join();
        clearInvocations(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        buyer.start(seller);
        seller.start(buyer);

        assertEquals(failed.toProto(false), savedRecord(local.service, tradeId).trade().toProto(false));
        assertEquals(failedAtPeer.toProto(false), savedRecord(peer.service, tradeId).trade().toProto(false));
        assertEquals(messageCount, messages.size());
        verifyNoInteractions(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothServicesResumeMediatedSettlementAfterRestartBetweenAcceptances(boolean buyerFirst) throws Exception {
        prepareMediation(false);
        var first = buyerFirst ? buyer : seller;
        var second = buyerFirst ? seller : buyer;
        await(first.service.acceptMediationResult(tradeId));
        awaitDelivery(second, MuSigCustomPayoutPsbtMessage.class);
        var beforeBuyer = savedRecord(buyer.service, tradeId).trade().toProto(false);
        var beforeSeller = savedRecord(seller.service, tradeId).trade().toProto(false);
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        int messageCount = messages.size();
        buyer.service.shutdown().join();
        seller.service.shutdown().join();
        clearInvocations(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        buyer.start(seller);
        seller.start(buyer);

        assertEquals(beforeBuyer, savedRecord(buyer.service, tradeId).trade().toProto(false));
        assertEquals(beforeSeller, savedRecord(seller.service, tradeId).trade().toProto(false));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        assertEquals(1, second.saved().pendingInputs().size());
        assertInstanceOf(MediationResultByPeerAcceptedEvent.class, second.saved().pendingInputs().getFirst());
        assertEquals(messageCount, messages.size());
        verifyNoInteractions(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        await(second.service.acceptMediationResult(tradeId));
        awaitDelivery(first, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
        verify(first.backend, never()).signCustomPayoutTx(any());
        verify(second.backend, times(1)).signCustomPayoutTx(any());
        verify(first.backend, times(1)).customCloseTrade(any());
        verify(second.backend, times(1)).customCloseTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void makerCannotCloseNormallyAfterSigningTheCustomPayout(boolean paymentInitiated) throws Exception {
        prepareMediation(paymentInitiated);
        assertEquals(paymentInitiated ? 1 : 0, buyer.saved().scheduledEvents().size());
        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);
        var signed = savedRecord(buyer.service, tradeId).trade();
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        int messageCount = messages.size();
        if (paymentInitiated) {
            assertEquals(1, buyer.timers.size());
            // A callback already released by the timer must be ignored after custom signing cancels its deadline.
            buyer.timers.getFirst().run();
        }
        var lateConfirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{6}, new byte[]{7})));

        await(submitMessage(buyer.service, lateConfirmation));
        assertSame(signed, savedRecord(buyer.service, lateConfirmation.getTradeId()).trade());
        assertEquals(messageCount, messages.size());
        verify(buyer.backend, never()).closeTrade(any());
        if (!paymentInitiated) {
            assertFailure(MuSigProtocolException.class, buyer.service.paymentInitiated(tradeId));
        }

        await(seller.service.acceptMediationResult(tradeId));
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertBothClosedWithMediation();
    }

    @Test
    void makerAcceptanceBeforeDepositConfirmationIsRejectedAndNotReplayed() throws Exception {
        await(initializeTrade(seller.service, initializeTrade(contract, seller.identity, seller.account.getAccountPayload())));
        awaitDelivery(seller, SendAccountPayloadMessage.class);
        publishMediationResult();
        var before = savedRecord(buyer.service, tradeId).trade();

        assertFailure(MuSigProtocolException.class, buyer.service.acceptMediationResult(tradeId));
        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        buyer.confirmDeposit();
        seller.confirmDeposit();
        var confirmed = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(DepositTxConfirmed.class, confirmed.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                confirmed.getMediation().orElseThrow().myDecision().state());
        verify(buyer.backend, never()).signCustomPayoutTx(any());

        await(buyer.service.acceptMediationResult(tradeId));
        assertInstanceOf(CustomPayoutSigned.class,
                savedRecord(buyer.service, tradeId).trade().getExecution().state());
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);
    }
}
