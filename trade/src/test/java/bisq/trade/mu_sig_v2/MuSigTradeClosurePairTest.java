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

import bisq.offer.Direction;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.awaitSavedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The closing timeout of the buyer and of the seller, and the forced closure by the seller.
 */
class MuSigTradeClosurePairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buyerTimeoutKeepsTradeOpenAcrossRestartAndAllowsLaterClosure(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        var waiting = savedRecord(buyer.service, tradeId).trade();
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        var timeout = buyer.saved().scheduledEvents().getFirst();
        assertInstanceOf(BuyersCloseTradeTimeoutEvent.class, timeout.event());

        buyer.service.shutdown().join();
        buyer.start(seller);
        assertEquals(List.of(timeout), buyer.saved().scheduledEvents());
        assertEquals(1, buyer.timers.size());

        var timeoutCallback = buyer.timers.getFirst();
        timeoutCallback.run();
        awaitSavedRecord(buyer.service, tradeId, record -> record.scheduledEvents().isEmpty() &&
                record.processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).contains(BuyersCloseTradeTimeoutEvent.class));
        var afterTimeout = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(PaymentInitiated.class, afterTimeout.getExecution().state());
        assertEquals(waiting.toProto(false), afterTimeout.toProto(false));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(buyer.saved().processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of())
                .contains(BuyersCloseTradeTimeoutEvent.class));
        verify(buyer.backend, never()).closeTrade(any());

        timeoutCallback.run();
        buyer.service.shutdown().join();
        assertSame(afterTimeout, savedRecord(buyer.service, tradeId).trade());
        buyer.start(seller);
        var restored = savedRecord(buyer.service, tradeId).trade();
        assertEquals(afterTimeout.toProto(false), restored.toProto(false));
        assertTrue(buyer.timers.isEmpty());
        timeoutCallback.run();
        assertSame(restored, savedRecord(buyer.service, tradeId).trade());
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        verify(buyer.backend, never()).closeTrade(any());

        await(seller.service.paymentReceiptConfirmed(tradeId));
        awaitDelivery(buyer, PaymentReceivedMessage_F.class);
        awaitDelivery(seller, CooperativeClosureMessage_G.class);
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(buyer.service, tradeId, TradeClosed.class)));
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(seller.service, tradeId, TradeClosed.class)));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
    }

    @Test
    void sellerAsMakerCanForceCloseAfterPaymentReceiptConfirmation() throws Exception {
        configureOffer(Direction.SELL);
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        automaticDelivery = false;
        await(seller.service.paymentReceiptConfirmed(tradeId));
        assertInstanceOf(PaymentReceiptConfirmed.class,
                await(whenExecutionState(seller.service, tradeId, PaymentReceiptConfirmed.class)));
        assertEquals(1, seller.saved().scheduledEvents().size());

        assertEquals(1, seller.timers.size());
        seller.timers.getFirst().run();

        assertInstanceOf(SellerForceClosedTrade.class,
                await(whenExecutionState(seller.service, tradeId, SellerForceClosedTrade.class)));
        verify(seller.backend).closeTrade(argThat(request -> request.getMyOutputPeersPrvKeyShare().isEmpty()));
        verify(buyer.backend, never()).closeTrade(any());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        assertInstanceOf(SellerForceClosedTrade.class, seller.saved().trade().getExecution().state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sellerAsMakerDoesNotScheduleCloseTimeoutAfterCustomSigningAndRestart(boolean paymentInitiated) throws Exception {
        configureOffer(Direction.SELL);
        prepareMediation(paymentInitiated);
        await(seller.service.acceptMediationResult(tradeId));
        var signed = savedRecord(seller.service, tradeId).trade();
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertInstanceOf(CustomPayoutSigned.class, signed.getExecution().state());

        assertTrue(seller.timers.isEmpty());
        assertSame(signed, savedRecord(seller.service, tradeId).trade());
        assertEquals(signed.toProto(false), seller.saved().trade().toProto(false));
        assertTrue(seller.saved().pendingInputs().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        verify(seller.backend, never()).closeTrade(any());
        verify(seller.backend, never()).customCloseTrade(any());

        seller.service.shutdown().join();
        seller.start(buyer);
        var restored = savedRecord(seller.service, tradeId).trade();
        assertEquals(signed.toProto(false), restored.toProto(false));
        assertTrue(seller.timers.isEmpty());
        assertSame(restored, savedRecord(seller.service, tradeId).trade());
        assertTrue(seller.saved().pendingInputs().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        verify(seller.backend, never()).closeTrade(any());
        verify(seller.backend, never()).customCloseTrade(any());

        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
    }
}
