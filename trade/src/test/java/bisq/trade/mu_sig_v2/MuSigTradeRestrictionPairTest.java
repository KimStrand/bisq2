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
import bisq.bonded_roles.security_manager.alert.AlertType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.common.observable.collection.ObservableSet;
import bisq.offer.Direction;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Trading restrictions and bans: which commands and requests they refuse, which kept inputs wait, and what stays
 * available.
 */
class MuSigTradeRestrictionPairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedPreparationDoesNotRegisterOrPersistATrade(boolean haltTrading) throws Exception {
        var taker = taker();
        taker.alerts.add(restriction(haltTrading));

        var failure = assertInstanceOf(TradeRestrictedException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(taker.service,
                        initializeTrade(contract, taker.identity, taker.account.getAccountPayload())))).getCause());
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertTrue(taker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(taker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend, taker.asyncBackend);
        verifyNoInteractions(taker.services.getChatService().getMuSigOpenTradeChannelService());
        assertTrue(messages.isEmpty());
        assertTrue(taker.logs.isEmpty());

        taker.alerts.clear();
        assertTrue(taker.retries.isEmpty());
        var prepared = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        assertInstanceOf(TradeInitialized.class, prepared.getExecution().state());
        assertSame(prepared, savedRecord(taker.service, tradeId).trade());
        assertEquals(1, taker.service.getPersistableStore().getTrades().size());
        verify(taker.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void takerAdmissionRechecksRestrictionsAfterSubmission(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var taker = taker();
        var admitting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenAnswer(invocation -> {
                    admitting.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return taker.alerts;
                });
        var command = initializeTrade(contract, taker.identity, taker.account.getAccountPayload());
        var pending = taker.service.initializeTrade(command);
        try {
            assertTrue(admitting.await(15, TimeUnit.SECONDS));
            taker.alerts.add(restriction(haltTrading));
        } finally {
            release.countDown();
        }

        var failure = assertFailure(TradeRestrictedException.class, pending);
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertTrue(taker.service.getPersistableStore().getTrades().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend, taker.asyncBackend);
        verifyNoInteractions(taker.services.getChatService().getMuSigOpenTradeChannelService());
        assertTrue(messages.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerAdmissionRechecksRestrictionsAfterSubmission(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var maker = maker();
        var request = request();
        var restricted = new ObservableSet<AuthorizedAlertData>();
        restricted.add(restriction(haltTrading));
        // The first read is the check at submission. The restriction arrives before the lane creates the trade.
        when(maker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(maker.alerts)
                .thenReturn(restricted);

        var failure = assertFailure(TradeRestrictedException.class, submitMessage(maker.service, request));
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        assertTrue(messages.isEmpty());
        verifyNoInteractions(maker.backend, maker.asyncBackend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());

        // The refusal is not final: without the restriction the same request starts the trade.
        when(maker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(maker.alerts);
        await(submitMessage(maker.service, request));
        assertEquals(tradeId, savedRecord(maker.service, request.getTradeId()).trade().getId());
        verify(maker.backend, times(1)).initTrade(any());
        assertTrue(messages.stream().noneMatch(MuSigReportErrorMessage.class::isInstance));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSavedMessageWaitsAtAStartDuringARestrictionAndRunsWhenItEnds(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var taker = taker();
        await(initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        await(submitMessage(maker().service, sent(SetupTradeMessage_A.class)));
        var answer = sent(SetupTradeMessage_B.class);
        var saved = taker.saved();
        // The lane saved the message, and the node stopped before the lane handled it.
        var record = new MuSigV2TradeRecord(saved.trade(), saved.processedEventTypes(), List.of(answer), List.of(), List.of());
        int sentMessages = messages.size();
        clearInvocations(taker.backend);
        taker.alerts.add(restriction(haltTrading));

        await(taker.restoreTrade(record));

        assertInstanceOf(TradeInitialized.class, savedRecord(taker.service, tradeId).trade().getExecution().state());
        assertEquals(List.of(answer), savedRecord(taker.service, tradeId).pendingInputs());
        assertEquals(sentMessages, messages.size());
        verifyNoInteractions(taker.backend);

        taker.alerts.clear();
        await(whenExecutionState(taker.service, tradeId, NonceSharesAndPartialSignaturesCreated.class));
        assertTrue(savedRecord(taker.service, tradeId).pendingInputs().isEmpty());
        verify(taker.backend, times(1)).getNonceShares(any());
        assertEquals(1, messages.stream().filter(SetupTradeMessage_C.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theTakersSavedFirstStepWaitsAtAStartDuringARestrictionAndRunsWhenItEnds(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var taker = taker();
        TradeRole role = offer.getTakersDirection().isBuy() ? TradeRole.BUYER_AS_TAKER : TradeRole.SELLER_AS_TAKER;
        var trade = MuSigV2Trade.create(contract, taker.identity, role, taker.account.getAccountPayload());
        // The lane saved the new trade with its first step, and the node stopped before the first step ran.
        var record = new MuSigV2TradeRecord(trade, Map.of(), List.of(new MuSigTakeOfferEvent()), List.of(), List.of());
        taker.alerts.add(restriction(haltTrading));

        await(taker.restoreTrade(record));

        assertInstanceOf(Init.class, savedRecord(taker.service, tradeId).trade().getExecution().state());
        assertEquals(1, savedRecord(taker.service, tradeId).pendingInputs().size());
        assertInstanceOf(MuSigTakeOfferEvent.class, savedRecord(taker.service, tradeId).pendingInputs().getFirst());
        assertTrue(messages.isEmpty());
        verifyNoInteractions(taker.backend);

        taker.alerts.clear();
        await(whenExecutionState(taker.service, tradeId, TradeInitialized.class));
        assertTrue(savedRecord(taker.service, tradeId).pendingInputs().isEmpty());
        verify(taker.backend, times(1)).initTrade(any());
        assertEquals(1, messages.stream().filter(SetupTradeMessage_A.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aMessageWhichWaitedForTheDepositDoesNotRunWhenTheDepositConfirmsDuringARestriction(boolean haltTrading)
            throws Exception {
        startTrade();
        buyer.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        assertEquals(1, seller.saved().pendingInputs().size());
        seller.alerts.add(restriction(haltTrading));

        // The confirmation is a fact from the blockchain and is not restricted. The peer's message stays paused.
        seller.confirmDeposit();

        assertInstanceOf(DepositTxConfirmed.class, seller.saved().trade().getExecution().state());
        assertEquals(1, seller.saved().pendingInputs().size());
        assertInstanceOf(PaymentInitiatedMessage_E.class, seller.saved().pendingInputs().getFirst());

        seller.alerts.clear();
        await(whenExecutionState(seller.service, tradeId, PaymentInitiatedMessageReceived.class));
        assertTrue(seller.saved().pendingInputs().isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theSellersCloseTimerWaitsDuringARestrictionAndAcrossARestart(boolean haltTrading) throws Exception {
        configureOffer(Direction.SELL);
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        automaticDelivery = false;
        await(seller.service.paymentReceiptConfirmed(tradeId));
        await(whenExecutionState(seller.service, tradeId, PaymentReceiptConfirmed.class));
        seller.alerts.add(restriction(haltTrading));

        seller.timers.getFirst().run();
        // The shutdown drains the lane, so the lane has taken the due timer.
        await(seller.service.shutdown());

        assertCloseTimerWaits();

        seller.start(buyer);
        assertCloseTimerWaits();
        assertTrue(seller.timers.isEmpty());

        seller.alerts.clear();
        await(whenExecutionState(seller.service, tradeId, SellerForceClosedTrade.class));
        assertTrue(seller.saved().pendingInputs().isEmpty());
        verify(seller.backend, times(1)).closeTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSavedOwnAcceptanceDoesNotSignThePayoutAtAStartDuringARestriction(boolean haltTrading) throws Exception {
        prepareMediation(false);
        automaticDelivery = false;
        var response = buyer.backend.signCustomPayoutTx(null);
        var signing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(buyer.backend.signCustomPayoutTx(any())).thenAnswer(invocation -> {
            signing.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return response;
        });
        var accepting = buyer.service.acceptMediationResult(tradeId);
        MuSigV2TradeRecord record;
        try {
            assertTrue(signing.await(15, TimeUnit.SECONDS));
            // The lane saved the decision with its reaction. A node which stops now restarts from this record.
            record = buyer.saved();
        } finally {
            release.countDown();
        }
        await(accepting);
        await(buyer.service.shutdown());
        assertEquals(1, record.pendingInputs().size());
        assertInstanceOf(MediationResultByMyselfAcceptedEvent.class, record.pendingInputs().getFirst());
        assertInstanceOf(DepositTxConfirmed.class, record.trade().getExecution().state());
        long sentPayouts = messages.stream().filter(MuSigCustomPayoutPsbtMessage.class::isInstance).count();
        clearInvocations(buyer.backend);
        buyer.alerts.add(restriction(haltTrading));

        await(buyer.restoreTrade(record));

        assertInstanceOf(DepositTxConfirmed.class, savedRecord(buyer.service, tradeId).trade().getExecution().state());
        assertEquals(record.pendingInputs(), savedRecord(buyer.service, tradeId).pendingInputs());
        assertEquals(sentPayouts, messages.stream().filter(MuSigCustomPayoutPsbtMessage.class::isInstance).count());
        verify(buyer.backend, never()).signCustomPayoutTx(any());

        buyer.alerts.clear();
        await(whenExecutionState(buyer.service, tradeId, CustomPayoutSigned.class));
        assertTrue(savedRecord(buyer.service, tradeId).pendingInputs().isEmpty());
        verify(buyer.backend, times(1)).signCustomPayoutTx(any());
        assertEquals(sentPayouts + 1, messages.stream().filter(MuSigCustomPayoutPsbtMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aSavedPeerAcceptanceDoesNotFinalizeThePayoutAtAStartDuringARestriction(boolean haltTrading) throws Exception {
        prepareMediation(false);
        // The buyer accepts first. Its payout data waits at the seller until the seller has signed.
        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);
        automaticDelivery = false;
        var response = seller.backend.customCloseTrade(null);
        var finalizing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(seller.backend.customCloseTrade(any())).thenAnswer(invocation -> {
            finalizing.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return response;
        });
        var accepting = seller.service.acceptMediationResult(tradeId);
        MuSigV2TradeRecord record;
        try {
            assertTrue(finalizing.await(15, TimeUnit.SECONDS));
            // The seller signed, and the lane saved that. A node which stops now restarts from this record.
            record = seller.saved();
        } finally {
            release.countDown();
        }
        await(accepting);
        await(seller.service.shutdown());
        assertEquals(1, record.pendingInputs().size());
        assertInstanceOf(MediationResultByPeerAcceptedEvent.class, record.pendingInputs().getFirst());
        assertInstanceOf(CustomPayoutSigned.class, record.trade().getExecution().state());
        clearInvocations(seller.backend);
        seller.alerts.add(restriction(haltTrading));

        await(seller.restoreTrade(record));

        assertInstanceOf(CustomPayoutSigned.class, savedRecord(seller.service, tradeId).trade().getExecution().state());
        assertEquals(record.pendingInputs(), savedRecord(seller.service, tradeId).pendingInputs());
        verify(seller.backend, never()).customCloseTrade(any());

        seller.alerts.clear();
        await(whenExecutionState(seller.service, tradeId, CustomPayoutClosedTrade.class));
        assertTrue(savedRecord(seller.service, tradeId).pendingInputs().isEmpty());
        verify(seller.backend, times(1)).customCloseTrade(any());
    }

    private void assertCloseTimerWaits() {
        var saved = seller.saved();
        assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
        assertEquals(1, saved.pendingInputs().size());
        assertInstanceOf(SellersCloseTradeTimeoutEvent.class, saved.pendingInputs().getFirst());
        assertTrue(saved.scheduledEvents().isEmpty());
        verify(seller.backend, never()).closeTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void initializationSucceedsWhileARestrictionPausesTheFirstStep(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var taker = taker();
        var restricted = new ObservableSet<AuthorizedAlertData>();
        restricted.add(restriction(haltTrading));
        // The router and the initializer see no restriction. It arrives before the lane starts the first step.
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenReturn(taker.alerts)
                .thenReturn(restricted);

        await(taker.service.initializeTrade(
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));

        assertInstanceOf(Init.class, taker.saved().trade().getExecution().state());
        assertEquals(1, taker.saved().pendingInputs().size());
        assertInstanceOf(MuSigTakeOfferEvent.class, taker.saved().pendingInputs().getFirst());
        assertTrue(messages.isEmpty());
        verifyNoInteractions(taker.backend);

        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts);
        // Any change of the alerts lets the lanes continue. This alert restricts nothing.
        taker.alerts.add(mock(AuthorizedAlertData.class));
        await(whenExecutionState(taker.service, tradeId, TradeInitialized.class));
        verify(taker.backend, times(1)).initTrade(any());
        assertEquals(1, messages.stream().filter(SetupTradeMessage_A.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void takerAdmissionKeepsTheSubmittedTermsWhileTheRequestWaits(boolean buyerAsTaker) throws Exception {
        configureOffer(buyerAsTaker ? Direction.SELL : Direction.BUY);
        automaticDelivery = false;
        var taker = taker();
        var admitting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenAnswer(invocation -> {
                    admitting.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return taker.alerts;
                });
        var command = initializeTrade(contract, taker.identity, taker.account.getAccountPayload());
        var pending = taker.service.initializeTrade(command);
        try {
            assertTrue(admitting.await(15, TimeUnit.SECONDS));
            when(taker.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
            when(taker.services.getMuSigTradeService().getMuSigTraderArbitrationService()
                    .selectArbitrator(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
            when(taker.services.getBondedRolesService().getMarketPriceService().findMarketPrice(any()))
                    .thenReturn(Optional.empty());
        } finally {
            release.countDown();
        }
        await(pending);

        assertEquals(contract, taker.saved().trade().getContract());
        assertEquals(contract, sent(SetupTradeMessage_A.class).getContract());
        verify(taker.services.getMuSigTradeService().getMuSigTraderMediationService(), never())
                .selectMediator(anyString(), anyString(), anyString());
        verify(taker.services.getMuSigTradeService().getMuSigTraderArbitrationService(), never())
                .selectArbitrator(anyString(), anyString(), anyString(), any());
        verify(taker.services.getBondedRolesService().getMarketPriceService(), never()).findMarketPrice(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRestrictedInitializerIsNotRetainedOrReplayed(boolean haltTrading) throws Exception {
        var taker = taker();
        taker.alerts.add(restriction(haltTrading));
        assertFailure(TradeRestrictedException.class,
                initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        taker.alerts.clear();
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend);
        await(initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        verify(taker.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"banned-sender", "trading-halted", "minimum-version"})
    void aRefusedMakerRequestDoesNotCreateATradeOrSendARejection(String reason) throws Exception {
        automaticDelivery = false;
        var maker = maker();
        var request = request();
        var bans = maker.services.getUserService().getBannedUserService();
        boolean bannedSender = reason.equals("banned-sender");
        if (bannedSender) {
            when(bans.isUserProfileBanned(request.getSender())).thenReturn(true);
        } else {
            maker.alerts.add(restriction(reason.equals("trading-halted")));
        }

        if (bannedSender) {
            var failure = assertFailure(MuSigProtocolException.class, submitMessage(maker.service, request));
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        } else {
            var failure = assertFailure(TradeRestrictedException.class, submitMessage(maker.service, request));
            assertEquals(reason.equals("trading-halted") ? TradeRestrictedException.Restriction.HALT_TRADING
                    : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        }
        maker.service.onMessage(request);
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        assertTrue(messages.isEmpty());
        verifyNoInteractions(maker.backend, maker.asyncBackend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());

        when(bans.isUserProfileBanned(request.getSender())).thenReturn(false);
        maker.alerts.clear();
        assertTrue(maker.retries.isEmpty());
        await(submitMessage(maker.service, request));
        var trade = savedRecord(maker.service, request.getTradeId()).trade();
        assertEquals(tradeId, trade.getId());
        verify(maker.backend, times(1)).initTrade(any());
        assertTrue(messages.stream().noneMatch(MuSigReportErrorMessage.class::isInstance));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedPaymentCommandsDoNotChangeTheTradeOrRunLater(boolean haltTrading) throws Exception {
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();

        assertTradingCommandRejected(buyer, haltTrading, () -> buyer.service.paymentInitiated(tradeId));
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);

        assertTradingCommandRejected(seller, haltTrading, () -> seller.service.paymentReceiptConfirmed(tradeId));
        await(seller.service.paymentReceiptConfirmed(tradeId));
        awaitDelivery(seller, CooperativeClosureMessage_G.class);
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(buyer.service, tradeId, TradeClosed.class)));
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(seller.service, tradeId, TradeClosed.class)));
        verify(seller.backend, times(1)).signSwapTx(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedMediationAcceptanceDoesNotCommitOrSign(boolean haltTrading) throws Exception {
        prepareMediation(false);

        assertTradingCommandRejected(buyer, haltTrading, () -> buyer.service.acceptMediationResult(tradeId));
        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);

        assertTradingCommandRejected(seller, haltTrading, () -> seller.service.acceptMediationResult(tradeId));
        await(seller.service.acceptMediationResult(tradeId));
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertBothClosedWithMediation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mediationRequestAndRejectionRemainAvailableDuringTradingRestrictions(boolean haltTrading) throws Exception {
        startTrade();
        automaticDelivery = false;
        prepareTradeChat(buyer);
        buyer.alerts.add(restriction(haltTrading));
        seller.alerts.add(restriction(haltTrading));

        await(buyer.service.requestMediation(tradeId));
        var requested = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(1, mediationRequests.size());

        publishMediationResult();
        await(buyer.service.rejectMediationResult(tradeId));
        var rejected = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationDecisionState.Rejected.class,
                rejected.getMediation().orElseThrow().myDecision().state());
        assertInstanceOf(MuSigTradeState.Active.class, rejected.getState());
        assertEquals(1, messages.stream().filter(MuSigMediationResultRejectionMessage.class::isInstance).count());
        verify(buyer.backend, never()).signCustomPayoutTx(any());
    }

    @Test
    void aLocalBanRejectsMediationWithoutRetainingTheRequestButAPeerBanDoesNot() throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var before = savedRecord(buyer.service, tradeId).trade();
        var saved = buyer.saved().toProto(false);
        var bans = buyer.services.getUserService().getBannedUserService();
        when(bans.isUserProfileBanned(buyer.identity.getNetworkId())).thenReturn(true);
        clearInvocations(buyer.backend, buyer.asyncBackend);

        assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                assertFailure(MuSigProtocolException.class, buyer.service.requestMediation(tradeId)).getCategory());

        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        assertEquals(saved, buyer.saved().toProto(false));
        assertEquals(MuSigDisputeAgentType.NONE, channel.getDisputeAgentType());
        assertTrue(mediationRequests.isEmpty());
        verifyNoInteractions(buyer.backend, buyer.asyncBackend);

        when(bans.isUserProfileBanned(buyer.identity.getNetworkId())).thenReturn(false);
        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        await(buyer.service.shutdown());
        buyer.confirmation = new CompletableFuture<>();
        buyer.start(seller);
        assertEquals(saved, buyer.saved().toProto(false));
        assertTrue(mediationRequests.isEmpty());
        verifyNoInteractions(buyer.backend);

        when(bans.isUserProfileBanned(seller.identity.getNetworkId())).thenReturn(true);
        await(buyer.service.requestMediation(tradeId));
        var requested = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(1, mediationRequests.size());
    }

    @Test
    void aRestrictedCommandDoesNotWaitForTheTradeWorker() throws Exception {
        var taker = taker();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.backend.initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance();
        });
        var starting = initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload()));
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            taker.alerts.add(restriction(true));

            var rejected = taker.service.paymentReceiptConfirmed(tradeId);
            assertTrue(rejected.isCompletedExceptionally());
            assertFailure(TradeRestrictedException.class, rejected);
            assertFalse(starting.isDone());
        } finally {
            taker.alerts.clear();
            release.countDown();
        }
        await(starting);
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        verify(taker.backend, times(1)).initTrade(any());
    }

    private void assertTradingCommandRejected(Peer peer,
                                              boolean haltTrading,
                                              Supplier<CompletableFuture<Void>> command) throws Exception {
        var before = savedRecord(peer.service, tradeId).trade();
        var saved = peer.saved().toProto(false);
        var sentMessages = List.copyOf(messages);
        int logCount = peer.logs.size();
        clearInvocations(peer.backend, peer.asyncBackend);
        peer.alerts.add(restriction(haltTrading));

        var rejected = command.get();
        assertTrue(rejected.isCompletedExceptionally());
        var failure = assertFailure(TradeRestrictedException.class, rejected);
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertEquals(haltTrading ? Optional.empty() : Optional.of("999.0.0"), failure.findMinRequiredVersion());
        assertSame(before, savedRecord(peer.service, tradeId).trade());
        assertEquals(saved, peer.saved().toProto(false));
        verifyNoInteractions(peer.backend, peer.asyncBackend);

        peer.alerts.clear();
        assertSame(before, savedRecord(peer.service, tradeId).trade());
        verifyNoInteractions(peer.backend, peer.asyncBackend);
        await(peer.service.shutdown());
        peer.confirmation = new CompletableFuture<>();
        peer.start(peer == buyer ? seller : buyer);
        assertEquals(saved, peer.saved().toProto(false));
        assertEquals(sentMessages, List.copyOf(messages));
        assertEquals(logCount, peer.logs.size());
        verifyNoInteractions(peer.backend);
    }

    private static AuthorizedAlertData restriction(boolean haltTrading) {
        var alert = mock(AuthorizedAlertData.class);
        when(alert.getAlertType()).thenReturn(AlertType.EMERGENCY);
        when(alert.getAppType()).thenReturn(AppType.DESKTOP);
        when(alert.isHaltTrading()).thenReturn(haltTrading);
        when(alert.isRequireVersionForTrading()).thenReturn(!haltTrading);
        when(alert.getMinVersion()).thenReturn(Optional.of("999.0.0"));
        return alert;
    }
}
