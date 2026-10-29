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

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.network.SendMessageResult;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertOperationFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertProtocolFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithCapturedTimers;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BuyerAsTakerProtocolTest extends BuyerAsTakerFixture {
    private static final LogEntry PAYMENT_INITIATED_LOG = new LogEntry.Shared(new LogEntry.Localized(
            "muSig.trade.state.phase2a.tradeLogMessage.buyer",
            List.of("local-buyer", "USD")));

    @Test
    void cooperativeClosureReleasesTheCorrectSignaturesAndPreservesTheDocuments() {
        runSetup(protocol::handle);
        var beforePayment = protocol.getTrade();
        var setup = effects.stream()
                .filter(effect -> effect instanceof Effect.SendMessage<?, ?> send
                        && send.message() instanceof SetupTradeMessage_C)
                .map(effect -> (SetupTradeMessage_C) ((Effect.SendMessage<?, ?>) effect).message())
                .findFirst().orElseThrow();
        assertTrue(setup.getPartialSignatures().getSwapTxInputPartialSignature().isEmpty());
        assertArrayEquals(new byte[]{9}, beforePayment.getExecution().data().getPeerPartialSignatures()
                .orElseThrow().getSwapTxInputPartialSignature().orElseThrow());
        assertTrue(beforePayment.getExecution().data().getPeerRedactedPartialSignatures().isEmpty());

        protocol.handle(new PaymentInitiatedEvent());

        assertInstanceOf(PaymentInitiated.class, protocol.getExecutionState());
        assertTrue(logs.contains(PAYMENT_INITIATED_LOG));
        var deadlines = protocol.getSavedRecord().scheduledEvents();
        assertEquals(1, deadlines.size());
        var timeout = deadlines.getFirst();
        assertEquals(CLOSE_TRADE_TIMEOUT_ID, timeout.id());
        assertEquals(Instant.ofEpochMilli(TAKE_OFFER_DATE).plus(CLOSE_TRADE_TIMEOUT), timeout.triggerAt());
        assertInstanceOf(BuyersCloseTradeTimeoutEvent.class, timeout.event());
        var payment = (PaymentInitiatedMessage_E) ((Effect.SendMessage<?, ?>) effects.getLast()).message();
        assertArrayEquals(new byte[]{6}, payment.getSwapTxInputPartialSignature());
        assertArrayEquals(new byte[]{2}, beforePayment.getExecution().data().getMyPartialSignatures()
                .orElseThrow().getSwapTxInputPartialSignature().orElseThrow());
        var confirmation = paymentReceivedMessage();

        protocol.handle(confirmation);

        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        assertSame(confirmation.getSwapTxSignature(),
                protocol.getTrade().getExecution().data().getPeerSwapTxSignature().orElseThrow());
        assertTrue(protocol.getTrade().getExecution().data().getMyCloseTradeResponse().isPresent());
        assertSame(beforePayment.getExecution().data().getMyContractSignature(),
                protocol.getTrade().getExecution().data().getMyContractSignature());
        var closure = (CooperativeClosureMessage_G) ((Effect.SendMessage<?, ?>) effects.getLast()).message();
        assertArrayEquals(new byte[]{3}, closure.getPeerOutputPrvKeyShare().getBytes());
        assertEquals(List.of(SetupTradeMessage_A.class,
                SetupTradeMessage_C.class,
                SendAccountPayloadAndDepositTxMessage.class,
                PaymentInitiatedMessage_E.class,
                CooperativeClosureMessage_G.class), sentMessageTypes());
        assertTrue(protocol.getSavedRecord().scheduledEvents().isEmpty());
        assertEquals(List.of(ObserveDepositTxConfirmation.class), nonMessageEffectTypes());
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text("Taker (as buyer) created his pubKeyShares.\n"
                + "Taker (as buyer) sent his pubKeyShares to maker."))));
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text(
                "Taker (as buyer) received peers pubKeyShares and nonceShares.\n"
                        + "Taker (as buyer) created his nonceShares and partialSignatures.\n"
                        + "Taker (as buyer) sent his nonceShares and his partialSignatures to seller."))));
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text("Buyer saw deposit transaction confirmed"))));
        verify(blockingStub()).initTrade(argThat(request ->
                request.getMyRole() == bisq.trade.protobuf.Role.BUYER_AS_TAKER));
        verify(blockingStub()).closeTrade(argThat(request ->
                request.getMyOutputPeersPrvKeyShare().equals(ByteString.copyFrom(new byte[]{8}))));
    }

    @Test
    void aRedactedSellerSignatureIsRejectedBeforeSigningOrPublishingTheDeposit() {
        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());
        var invalid = new SetupTradeMessage_D(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                redactedPartialSignatures(),
                new byte[]{1});

        assertProtocolFailure(() -> protocol.handle(invalid));

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        verify(blockingStub(), never()).signDepositTx(any());
        verify(blockingStub(), never()).publishDepositTx(any());
        var failed = protocol.getTrade();
        protocol.handle(depositSetupMessage());
        assertSame(failed, protocol.getTrade());
        assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
    }

    @Test
    void anAccountMismatchDoesNotStartDepositObservation() {
        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());
        protocol.handle(depositSetupMessage());
        AccountPayload<?> invalid = mock(AccountPayload.class);
        when(invalid.serializeForHash()).thenReturn(new byte[]{99});

        assertProtocolFailure(() -> protocol.handle(accountPayloadMessage(invalid)));

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertTrue(protocol.getTrade().getExecution().data().getPeerAccountPayload().isEmpty());
        assertTrue(effects.stream().noneMatch(ObserveDepositTxConfirmation.class::isInstance));
    }

    @Test
    void earlyPaymentInitiationRequiresANewCommandWhenTheDepositIsConfirmed() {
        var initial = protocol.getTrade();

        assertRejected(() -> protocol.handle(new PaymentInitiatedEvent()));

        assertSame(initial, protocol.getTrade());
        runSetup(protocol::handle);
        protocol.resume();
        assertInstanceOf(DepositTxConfirmed.class, protocol.getExecutionState());
        verify(blockingStub(), never()).getPartialSignatures(argThat(request -> request.getBuyerReadyToRelease()));
        assertFalse(logs.contains(PAYMENT_INITIATED_LOG));

        protocol.handle(new PaymentInitiatedEvent());
        assertInstanceOf(PaymentInitiated.class, protocol.getExecutionState());
    }

    @Test
    void earlySellerConfirmationsWaitForPaymentAndDuplicateTypesCloseOnlyOnce() {
        protocol.handle(paymentReceivedMessage());
        protocol.handle(paymentReceivedMessage());
        runSetup(protocol::handle);
        assertInstanceOf(DepositTxConfirmed.class, protocol.getExecutionState());
        verify(blockingStub(), never()).closeTrade(any());

        protocol.handle(new PaymentInitiatedEvent());

        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        var closed = protocol.getTrade();
        protocol.handle(paymentReceivedMessage());
        protocol.handle(new PaymentInitiatedEvent());
        assertSame(closed, protocol.getTrade());
        assertEquals(1, logs.stream().filter(PAYMENT_INITIATED_LOG::equals).count());
        verify(blockingStub(), times(1)).closeTrade(any());
        verify(blockingStub(), times(1)).getPartialSignatures(argThat(request -> request.getBuyerReadyToRelease()));
    }

    @ParameterizedTest
    @CsvSource({
            "trade, true", "trade, false",
            "sender, true", "sender, false",
            "receiver, true", "receiver, false",
            "version, true", "version, false"
    })
    void invalidSellerConfirmationsAreRejectedBeforeHandlingRetentionOrDuplicateFiltering(String problem,
                                                                                          boolean early) {
        if (!early) {
            runSetup(protocol::handle);
            protocol.handle(new PaymentInitiatedEvent());
        }
        var before = protocol.getTrade();
        var effectsBefore = List.copyOf(effects);
        var logsBefore = List.copyOf(logs);
        var valid = paymentReceivedMessage();
        var invalid = new PaymentReceivedMessage_F(uid(),
                problem.equals("trade") ? uid() : TRADE_ID,
                problem.equals("version") ? "2.0.0" : "1.0.0",
                problem.equals("sender") ? myNetworkId() : peerNetworkId(),
                problem.equals("receiver") ? peerNetworkId() : myNetworkId(),
                valid.getSwapTxSignature());

        assertRejected(() -> protocol.handle(invalid));

        assertSame(before, protocol.getTrade());
        assertEquals(effectsBefore, effects);
        assertEquals(logsBefore, logs);
        verify(blockingStub(), never()).closeTrade(any());
        if (early) {
            runSetup(protocol::handle);
            protocol.handle(new PaymentInitiatedEvent());
            protocol.resume();
            assertInstanceOf(PaymentInitiated.class, protocol.getExecutionState());
            verify(blockingStub(), never()).closeTrade(any());
        }

        protocol.handle(valid);
        var closed = protocol.getTrade();
        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        assertRejected(() -> protocol.handle(invalid));
        protocol.handle(paymentReceivedMessage());
        assertSame(closed, protocol.getTrade());
        verify(blockingStub(), times(1)).closeTrade(any());
    }

    @Test
    void failedPaymentInitiationTerminatesExecution() {
        runSetup(protocol::handle);
        var stub = blockingStub();
        doThrow(new IllegalStateException("Backend unavailable"))
                .when(stub).getPartialSignatures(argThat(request -> request.getBuyerReadyToRelease()));

        assertOperationFailure(() -> protocol.handle(new PaymentInitiatedEvent()));

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertTrue(protocol.getTrade().getExecution().data().getMyFailure().isPresent());
        assertFalse(logs.contains(PAYMENT_INITIATED_LOG));
        var failed = protocol.getTrade();
        protocol.resume();
        assertRejected(() -> protocol.handle(new PaymentInitiatedEvent()));
        assertSame(failed, protocol.getTrade());
        verify(stub, times(1)).getPartialSignatures(argThat(request -> request.getBuyerReadyToRelease()));
    }

    @Test
    void failedClosureDoesNotRecordTheSignatureOrSendTheClosingMessage() {
        runSetup(protocol::handle);
        protocol.handle(new PaymentInitiatedEvent());
        when(blockingStub().closeTrade(any())).thenThrow(new IllegalStateException("Backend unavailable"));

        assertOperationFailure(() -> protocol.handle(paymentReceivedMessage()));

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertTrue(protocol.getTrade().getExecution().data().getPeerSwapTxSignature().isEmpty());
        assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
        var failed = protocol.getTrade();
        protocol.handle(paymentReceivedMessage());
        assertSame(failed, protocol.getTrade());
        verify(blockingStub(), times(1)).closeTrade(any());
    }

    @Test
    void theServiceRunsTheBuyerFlowOnItsWorkerAndKeepsTheTradeOpenAfterTimeout() throws Exception {
        var worker = new AtomicReference<Thread>();
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            worker.set(Thread.currentThread());
            effects.add(new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        var timers = new CopyOnWriteArrayList<Runnable>();
        var service = serviceWithCapturedTimers(services, grpcClient, () -> TAKE_OFFER_DATE, timers);
        try {
            await(service.initialize());
            await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
            assertTrue(worker.get() != Thread.currentThread());
            assertEquals(TradeRole.BUYER_AS_TAKER, savedRecord(service, TRADE_ID).trade().getMyRole());
            assertInstanceOf(MuSigProtocolException.class, assertThrows(ExecutionException.class,
                    () -> await(service.paymentInitiated(TRADE_ID))).getCause());
            await(submitMessage(service, setupMessage()));
            await(submitMessage(service, depositSetupMessage()));
            await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
            await(service.onDepositTxConfirmed(TRADE_ID));
            await(resumeLane(service, TRADE_ID));
            var confirmed = savedRecord(service, TRADE_ID).trade();
            assertInstanceOf(DepositTxConfirmed.class, confirmed.getExecution().state());
            await(service.paymentInitiated(TRADE_ID));
            assertEquals(1, timers.size());
            timers.getFirst().run();
            await(resumeLane(service, TRADE_ID));
            assertInstanceOf(PaymentInitiated.class,
                    savedRecord(service, TRADE_ID).trade().getExecution().state());
            assertTrue(savedRecord(service, TRADE_ID).scheduledEvents().isEmpty());
            verify(blockingStub(), never()).closeTrade(any());
            await(submitMessage(service, paymentReceivedMessage()));
            assertInstanceOf(TradeClosed.class,
                    savedRecord(service, TRADE_ID).trade().getExecution().state());
        } finally {
            service.shutdown().join();
        }
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
}
