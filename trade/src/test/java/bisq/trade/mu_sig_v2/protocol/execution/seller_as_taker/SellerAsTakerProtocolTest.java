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

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.RuleTable;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.MuSigTradeFormatter;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertOperationFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertProtocolFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Runs the seller path with a stubbed MuSig backend, including document retention, failures and
 * the ordering of outgoing work.
 */
class SellerAsTakerProtocolTest extends SellerAsTakerFixture {
    @Test
    void theCooperativePathRunsFromInitToTradeClosed() {
        runSetup(protocol::handle);
        runToPaymentReceiptConfirmed(protocol::handle);
        var scheduled = protocol.getSavedRecord().scheduledEvents().getFirst();
        assertEquals("closeTrade", scheduled.id());
        assertInstanceOf(SellersCloseTradeTimeoutEvent.class, scheduled.event());
        assertEquals(NOW.plus(Duration.ofHours(24)), scheduled.triggerAt());
        closeCooperatively(protocol::handle);

        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        assertTrue(protocol.getSavedRecord().scheduledEvents().isEmpty());
        assertEquals(List.<Object>of(SetupTradeMessage_A.class,
                        SetupTradeMessage_C.class,
                        SendAccountPayloadAndDepositTxMessage.class,
                        PaymentReceivedMessage_F.class),
                sentMessageTypes());
        assertEquals(List.<Object>of(ObserveDepositTxConfirmation.class),
                nonMessageEffectTypes());
        assertEquals(10, logs.size(), logs.toString());
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Localized("muSig.trade.state.phase3a.logMessage",
                List.of("local-seller", MuSigTradeFormatter.formatQuoteSideAmountWithCode(muSigContract))))));
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text("Taker (as seller) created his pubKeyShares.\n"
                + "Taker (as seller) sent his pubKeyShares to maker."))));
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text(
                "Taker (as seller) received peers pubKeyShares and nonceShares.\n"
                        + "Taker (as seller) created his nonceShares and partialSignatures.\n"
                        + "Taker (as seller) sent his nonceShares and his partialSignatures to buyer."))));
        assertTrue(logs.contains(new LogEntry.Shared(new LogEntry.Text("Seller saw deposit transaction confirmed"))));
        verify(blockingStub()).initTrade(argThat(request ->
                request.getMyRole() == bisq.trade.protobuf.Role.SELLER_AS_TAKER));
    }

    @Test
    void setupSendsTheSellersSwapPartialSignature() {
        when(blockingStub().getPartialSignatures(any()))
                .thenReturn(bisq.trade.protobuf.PartialSignaturesMessage.newBuilder()
                        .setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{6}))
                        .build());

        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());

        var outgoing = (SetupTradeMessage_C) ((Effect.SendMessage<?, ?>) effects.getLast()).message();
        assertArrayEquals(new byte[]{6},
                outgoing.getPartialSignatures().getSwapTxInputPartialSignature().orElseThrow());
        assertArrayEquals(new byte[]{6}, protocol.getTrade().getExecution().data().getMyPartialSignatures()
                .orElseThrow().getSwapTxInputPartialSignature().orElseThrow());
    }

    @Test
    void anUnredactedBuyerSignatureIsRejectedBeforeSigningOrPublishingTheDeposit() {
        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());
        var invalid = new SetupTradeMessage_D(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                PartialSignatures.toUnRedacted(redactedPartialSignatures(), new byte[]{2}),
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

    /** Restores a saved record into another lane without file I/O. */
    @Test
    void aTradeResumesFromItsRecord() {
        runSetup(protocol::handle);
        MuSigV2Trade<SellerAsTakerState> snapshot = protocol.getTrade();
        assertInstanceOf(DepositTxConfirmed.class, snapshot.getExecution().state());

        var resumedClient = mock(MusigGrpcClient.class);
        try (var resumed = new MuSigTradeLaneFixture<>(serviceProvider(resumedClient), resumedClient,
                protocol.getSavedRecord(),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock)) {
            assertSame(snapshot, resumed.getTrade());
            assertEquals(protocol.getProcessedEventTypes(), resumed.getProcessedEventTypes());
            runSettlement(resumed::handle);

            assertInstanceOf(TradeClosed.class, resumed.getExecutionState());
            assertInstanceOf(DepositTxConfirmed.class, snapshot.getExecution().state());
            assertTrue(snapshot.getExecution().data().getMyCloseTradeResponse().isEmpty());
            assertRetainsSetupData(snapshot, resumed.getTrade());
            assertEquals(List.<Object>of(SetupTradeMessage_A.class,
                            SetupTradeMessage_C.class,
                            SendAccountPayloadAndDepositTxMessage.class,
                            PaymentReceivedMessage_F.class),
                    sentMessageTypes());
        }
    }

    /** Cooperative closure and the timeout both leave PaymentReceiptConfirmed, so the first one wins. */
    @Test
    void theTimeoutForceClosesWhenTheBuyersKeyShareNeverArrives() {
        runSetup(protocol::handle);
        runToPaymentReceiptConfirmed(protocol::handle);
        MuSigV2Trade<SellerAsTakerState> beforeClosure = protocol.getTrade();

        protocol.handle(new SellersCloseTradeTimeoutEvent());

        assertInstanceOf(SellerForceClosedTrade.class, protocol.getExecutionState());
        assertRetainsSetupData(beforeClosure, protocol.getTrade());
        assertEquals(beforeClosure.getExecution().data().getPeerPartialSignatures(),
                protocol.getTrade().getExecution().data().getPeerPartialSignatures());
        assertEquals(beforeClosure.getExecution().data().getMySwapTxSignatureResponse(),
                protocol.getTrade().getExecution().data().getMySwapTxSignatureResponse());
        assertTrue(protocol.getTrade().getExecution().data().getMyCloseTradeResponse().isPresent());
        assertTrue(protocol.getTrade().getExecution().data().getPeersOutputPrvKeyShare().isEmpty());
    }

    @Test
    void aTimeoutArrivingAfterCooperativeClosureIsDiscardedAndChangesNothing() {
        runSetup(protocol::handle);
        runSettlement(protocol::handle);
        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        int effectsBefore = effects.size();
        MuSigV2Trade<SellerAsTakerState> before = protocol.getTrade();

        protocol.handle(new SellersCloseTradeTimeoutEvent());

        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        assertSame(before, protocol.getTrade());
        assertEquals(effectsBefore, effects.size(), "a discarded input must not run effects");
        verify(blockingStub(), times(1)).closeTrade(any());
    }

    @Test
    void eventTypesAbsentFromTheTableAreDiscarded() {
        MuSigV2Trade<SellerAsTakerState> before = protocol.getTrade();

        protocol.handle(new Event() {
            @Override
            public bisq.common.protobuf.Event.Builder getBuilder(boolean serializeForHash) {
                throw new AssertionError("A discarded event must not be persisted");
            }
        });

        assertSame(before, protocol.getTrade());
        assertTrue(protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).isEmpty());
        assertTrue(effects.isEmpty());
        assertTrue(logs.isEmpty());
        verifyNoInteractions(blockingStub());
    }

    @Test
    void aNewMessageOfAnAlreadyProcessedTypeIsDiscarded() {
        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());
        MuSigV2Trade<SellerAsTakerState> before = protocol.getTrade();
        int effectsBefore = effects.size();
        int logsBefore = logs.size();

        protocol.handle(setupMessage());
        protocol.handle(new MuSigTakeOfferEvent());

        assertSame(before, protocol.getTrade());
        assertEquals(Set.of(MuSigTakeOfferEvent.class, SetupTradeMessage_B.class),
                protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()));
        assertEquals(effectsBefore, effects.size());
        assertEquals(logsBefore, logs.size());
        verify(blockingStub(), times(1)).initTrade(any());
        verify(blockingStub(), times(1)).getNonceShares(any());
        verify(blockingStub(), times(1)).getPartialSignatures(any());
    }

    @Test
    void anEarlyPaymentConfirmationIsRejectedAndNeverAppliedLater() {
        runSetup(protocol::handle);
        var before = protocol.getTrade();
        int effectsBefore = effects.size();

        assertRejected(() -> protocol.handle(new PaymentReceiptConfirmedEvent()));

        assertSame(before, protocol.getTrade());
        assertEquals(effectsBefore, effects.size());
        assertFalse(protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(PaymentReceiptConfirmedEvent.class));
        protocol.handle(paymentInitiatedMessage());
        protocol.resume();
        assertInstanceOf(PaymentInitiatedMessageReceived.class, protocol.getExecutionState());
        verify(blockingStub(), never()).signSwapTx(argThat(request -> request.getSellerReadyToRelease()));
        assertFalse(sentMessageTypes().contains(PaymentReceivedMessage_F.class));
        var receiptLog = new LogEntry.Shared(new LogEntry.Localized("muSig.trade.state.phase3a.logMessage",
                List.of("local-seller", MuSigTradeFormatter.formatQuoteSideAmountWithCode(muSigContract))));
        assertFalse(logs.contains(receiptLog));

        protocol.handle(new PaymentReceiptConfirmedEvent());
        var confirmed = protocol.getTrade();
        assertInstanceOf(PaymentReceiptConfirmed.class, protocol.getExecutionState());
        assertTrue(sentMessageTypes().contains(PaymentReceivedMessage_F.class));
        protocol.handle(new PaymentReceiptConfirmedEvent());
        assertSame(confirmed, protocol.getTrade());
        assertEquals(1, logs.stream().filter(receiptLog::equals).count());
        verify(blockingStub(), times(1)).signSwapTx(argThat(request -> request.getSellerReadyToRelease()));
    }

    @Test
    void anUnavailableTakeOfferCommandIsRejectedEvenWhenItsStageCannotBeReached() {
        try (var later = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new DepositTxConfirmed()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock)) {
            var before = later.getTrade();

            assertRejected(() -> later.handle(new MuSigTakeOfferEvent()));

            assertSame(before, later.getTrade());
            verifyNoInteractions(blockingStub());
        }
    }

    @Test
    void earlyInputsWaitForTheirStageAndQueuedDuplicatesAreDiscardedAfterSuccess() {
        MuSigV2Trade<SellerAsTakerState> initial = protocol.getTrade();
        protocol.handle(new DepositTxConfirmedEvent());
        protocol.handle(accountPayloadMessage(peerAccountPayload));
        protocol.handle(depositSetupMessage());
        protocol.handle(setupMessage());
        protocol.handle(setupMessage());
        assertSame(initial, protocol.getTrade());
        verifyNoInteractions(blockingStub());

        protocol.handle(new MuSigTakeOfferEvent());

        assertInstanceOf(DepositTxConfirmed.class, protocol.getExecutionState());
        assertEquals(Set.of(MuSigTakeOfferEvent.class,
                SetupTradeMessage_B.class,
                SetupTradeMessage_D.class,
                SendAccountPayloadMessage.class,
                DepositTxConfirmedEvent.class),
                protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                        Set.of()));
        assertSame(peerAccountPayload, protocol.getTrade().getExecution().data().getPeerAccountPayload().orElseThrow());
        verify(blockingStub(), times(1)).getNonceShares(any());
        verify(blockingStub(), times(1)).signDepositTx(any());
        assertEquals(List.of(SetupTradeMessage_A.class,
                SetupTradeMessage_C.class,
                SendAccountPayloadAndDepositTxMessage.class), sentMessageTypes());
    }

    @Test
    void pendingClosureInputsKeepTheirArrivalOrder() {
        closeCooperatively(protocol::handle);
        protocol.handle(new SellersCloseTradeTimeoutEvent());

        runSetup(protocol::handle);
        runToPaymentReceiptConfirmed(protocol::handle);

        assertInstanceOf(TradeClosed.class, protocol.getExecutionState());
        verify(blockingStub(), times(1)).closeTrade(any());
        assertFalse(protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(SellersCloseTradeTimeoutEvent.class));
    }

    @Test
    void aFailedQueuedInputTerminatesExecutionAndDiscardsLaterExecutionInputs() {
        AccountPayload<?> invalidPayload = mock(AccountPayload.class);
        when(invalidPayload.serializeForHash()).thenReturn(new byte[]{99});
        protocol.handle(accountPayloadMessage(invalidPayload));
        protocol.handle(accountPayloadMessage(peerAccountPayload));
        protocol.handle(new MuSigTakeOfferEvent());
        protocol.handle(setupMessage());

        // The deposit setup applies; the older account-payload input fails when that makes it ready.
        assertInstanceOf(ProcessingResult.Applied.class, protocol.handle(depositSetupMessage()));

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertEquals("Peer account payload does not match the salted account payload hash from the contract",
                protocol.getTrade().getExecution().data().getMyFailure().orElseThrow().errorMessage());
        assertSame(protocol.getTrade(), protocol.getSavedRecord().trade());
        assertTrue(protocol.getSavedRecord().pendingInputs().isEmpty());
        assertFalse(protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(SendAccountPayloadMessage.class));
        assertTrue(protocol.getTrade().getExecution().data().getPeerAccountPayload().isEmpty());
        var failed = protocol.getTrade();
        protocol.handle(new DepositTxConfirmedEvent());
        protocol.resume();
        assertSame(failed, protocol.getTrade());
    }

    @Test
    void processedTypesSurviveAHandoffEvenIfAnotherRuleWouldAcceptTheSameType() {
        protocol.handle(new MuSigTakeOfferEvent());
        MuSigV2Trade<SellerAsTakerState> snapshot = protocol.getTrade();
        RuleTable<SellerAsTakerState, MuSigV2TradeExecutionData, Event,
                MuSigProtocolContext> repeatedTypeRules =
                RuleTable.<SellerAsTakerState, MuSigV2TradeExecutionData, Event, MuSigProtocolContext>builder()
                        .rule(TradeInitialized.class,
                                MuSigTakeOfferEvent.class,
                                (context, flow) -> new MuSigTransitionHandler<TradeInitialized, MuSigTakeOfferEvent, TradeInitialized, MuSigV2TradeExecutionData>(context,
                                        flow) {
                                    @Override
                                    protected Transition<TradeInitialized, MuSigV2TradeExecutionData, Event> process(
                                            MuSigTakeOfferEvent event) {
                                        throw new AssertionError("This event type has already been processed");
                                    }
                                },
                                TradeInitialized.class)
                        .build();
        var execution = new MuSigExecutionProtocol<SellerAsTakerState>() {
            @Override
            protected RuleTable<SellerAsTakerState, MuSigV2TradeExecutionData, Event,
                    MuSigProtocolContext> rules() {
                return repeatedTypeRules;
            }
        };
        try (var resumed = new MuSigTradeLaneFixture<>(services, grpcClient,
                protocol.getSavedRecord(),
                SellerAsTakerState.class,
                execution,
                effects::add,
                logs::add,
                clock)) {
            assertTrue(repeatedTypeRules.canHandle(snapshot.getExecution().state(), new MuSigTakeOfferEvent()));

            assertInstanceOf(ProcessingResult.Ignored.class, resumed.handle(new MuSigTakeOfferEvent()));

            assertSame(snapshot, resumed.getTrade());
            assertEquals(Set.of(MuSigTakeOfferEvent.class),
                    resumed.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                            Set.of()));
            assertThrows(UnsupportedOperationException.class,
                    () -> resumed.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                            Set.of()).clear());
            verify(blockingStub(), times(1)).initTrade(any());
        }
    }

    @Test
    void aSendFailurePreservesTheTradeWithoutRunningTheCommittedEventTypeAgain() {
        RuntimeException failure = new IllegalStateException("Sending failed");
        var sendingFails = new AtomicBoolean(true);
        try (var failingProtocol = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effect -> {
                    effects.add(effect);
                    if (effect instanceof Effect.SendMessage<?, ?> send &&
                            send.message() instanceof SetupTradeMessage_A && sendingFails.get()) {
                        throw failure;
                    }
                },
                logs::add,
                clock)) {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> failingProtocol.handle(new MuSigTakeOfferEvent())));
            MuSigV2Trade<SellerAsTakerState> committed = failingProtocol.getTrade();
            var saved = failingProtocol.getSavedRecord();

            assertInstanceOf(TradeInitialized.class, committed.getExecution().state());
            assertSame(committed, saved.trade());
            assertEquals(Set.of(MuSigTakeOfferEvent.class), saved.processedEventTypes().get(Flow.EXECUTION));
            assertTrue(saved.pendingInputs().isEmpty());
            assertEquals(1, saved.pendingEffects().size());
            var savedSend = assertInstanceOf(Effect.SendMessage.class, saved.pendingEffects().getFirst());
            assertInstanceOf(SetupTradeMessage_A.class, savedSend.message());
            assertEquals(List.of(savedSend), effects);
            int logsAfterFailure = logs.size();

            sendingFails.set(false);
            failingProtocol.resume();

            assertSame(committed, failingProtocol.getTrade());
            assertSame(committed, failingProtocol.getSavedRecord().trade());
            assertTrue(failingProtocol.getSavedRecord().pendingEffects().isEmpty());
            assertEquals(List.of(savedSend, savedSend), effects);
            assertSame(savedSend, effects.getLast(), "retry must reuse the saved message");

            assertInstanceOf(ProcessingResult.Ignored.class, failingProtocol.handle(new MuSigTakeOfferEvent()));

            assertSame(committed, failingProtocol.getTrade());
            assertEquals(Set.of(MuSigTakeOfferEvent.class),
                    failingProtocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()));
            assertEquals(List.of(savedSend, savedSend), effects);
            assertEquals(logsAfterFailure, logs.size());
            verify(blockingStub(), times(1)).initTrade(any());
        }
    }

    @Test
    void dataAccumulatesThroughCooperativeClosureWithoutChangingEarlierDocuments() {
        MuSigV2Trade<SellerAsTakerState> initial = protocol.getTrade();
        protocol.handle(new MuSigTakeOfferEvent());
        MuSigV2Trade<SellerAsTakerState> initialized = protocol.getTrade();
        completeSetup(protocol::handle);
        MuSigV2Trade<SellerAsTakerState> depositConfirmed = protocol.getTrade();

        runSettlement(protocol::handle);

        MuSigV2Trade<SellerAsTakerState> closed = protocol.getTrade();
        assertInstanceOf(Init.class, initial.getExecution().state());
        assertTrue(initial.getExecution().data().getMyContractSignature().isEmpty());
        assertTrue(initialized.getExecution().data().getPeerContractSignature().isEmpty());
        assertTrue(initialized.getExecution().data().getMyDepositPsbt().isEmpty());
        assertEquals(initialized.getExecution().data().getMyPubKeyShares(),
                closed.getExecution().data().getMyPubKeyShares());
        assertEquals(initialized.getExecution().data().getMyContractSignature(),
                closed.getExecution().data().getMyContractSignature());
        assertRetainsSetupData(depositConfirmed, closed);
        assertTrue(depositConfirmed.getExecution().data().getPeerPartialSignatures().isEmpty());
        assertTrue(depositConfirmed.getExecution().data().getMyCloseTradeResponse().isEmpty());
        assertArrayEquals(new byte[]{2}, closed.getExecution().data().getPeerPartialSignatures().orElseThrow()
                .getSwapTxInputPartialSignature().orElseThrow());
        assertArrayEquals(new byte[]{3},
                closed.getExecution().data().getPeersOutputPrvKeyShare().orElseThrow().getBytes());
        assertTrue(closed.getExecution().data().getMySwapTxSignatureResponse().isPresent());
        assertTrue(closed.getExecution().data().getMyCloseTradeResponse().isPresent());
    }

    @Test
    void missingContractSignatureRejectsSetupBeforeCallingTheBackend() {
        MuSigV2Trade<SellerAsTakerState> incomplete = tradeAt(new TradeInitialized());
        try (var restored = new MuSigTradeLaneFixture<>(services, grpcClient,
                incomplete,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock)) {
            var error = assertProtocolFailure(() -> restored.handle(setupMessage()));

            assertTrue(error.getMessage().contains("contract signature"));
            assertInstanceOf(Failed.class, restored.getExecutionState());
            assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
            verifyNoInteractions(blockingStub());
        }
    }

    @Test
    void missingRedactedSignaturesRejectPaymentBeforeCallingTheBackend() {
        MuSigV2Trade<SellerAsTakerState> incomplete = tradeAt(new DepositTxConfirmed());
        try (var restored = new MuSigTradeLaneFixture<>(services, grpcClient,
                incomplete,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock)) {
            var error = assertProtocolFailure(() -> restored.handle(paymentInitiatedMessage()));

            assertTrue(error.getMessage().contains("redacted partial signatures"));
            assertInstanceOf(Failed.class, restored.getExecutionState());
            assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
            verifyNoInteractions(blockingStub());
        }
    }

    @Test
    void aHandlerFailureRecordsAnErrorAndCannotRunAgain() {
        protocol.handle(new MuSigTakeOfferEvent());
        var before = protocol.getTrade();
        RuntimeException failure = new IllegalStateException("Cannot create partial signatures");
        when(blockingStub().getPartialSignatures(any())).thenThrow(failure);
        SetupTradeMessage_B message = setupMessage();

        assertSame(failure, assertOperationFailure(() -> protocol.handle(message)).getCause());

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertFalse(protocol.getProcessedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(SetupTradeMessage_B.class));
        var data = protocol.getTrade().getExecution().data();
        assertSame(before.getExecution().data().getMyContractSignature(), data.getMyContractSignature());
        assertTrue(data.getPeerContractSignature().isEmpty());
        assertTrue(data.getMyNonceShares().isEmpty());
        assertEquals("Cannot create partial signatures", data.getMyFailure().orElseThrow().errorMessage());
        assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
        var failed = protocol.getTrade();
        protocol.handle(message);
        protocol.resume();
        assertSame(failed, protocol.getTrade());
        verify(blockingStub(), times(1)).getPartialSignatures(any());
    }

    @Test
    void effectsSeeTheCommittedDocumentAndReentrantInputWaitsForAllEffects() {
        var owner = new AtomicReference<MuSigTradeLaneFixture<SellerAsTakerState>>();
        var callbackSubmission = new AtomicReference<CompletableFuture<?>>();
        when(blockingStub().closeTrade(any())).thenAnswer(invocation -> {
            var saved = owner.get().getSavedRecord();
            assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
            assertTrue(saved.pendingEffects().isEmpty(), "the previous transition's effects must finish before closure");
            assertEquals(1, saved.scheduledEvents().size());
            assertEquals("closeTrade", saved.scheduledEvents().getFirst().id());
            assertFalse(callbackSubmission.get().isDone());
            return bisq.trade.protobuf.CloseTradeResponse.getDefaultInstance();
        });
        try (var callbackProtocol = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effect -> {
                    effects.add(effect);
                    if (effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof PaymentReceivedMessage_F) {
                        var saved = owner.get().getSavedRecord();
                        var committed = saved.trade();
                        assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
                        assertTrue(committed.getExecution().data().getMySwapTxSignatureResponse().isPresent());
                        assertTrue(saved.pendingEffects().contains(effect), "the send must already be checkpointed");
                        closeCooperatively(event -> {
                            var submission = owner.get().submit(event);
                            callbackSubmission.set(submission);
                            assertFalse(submission.isDone(), "callback input must wait for the current step");
                        });
                        assertSame(committed,
                                owner.get().getSavedRecord().trade(),
                                "callback input must wait for this transition's effects");
                    }
                },
                logs::add,
                clock)) {
            owner.set(callbackProtocol);

            runSetup(callbackProtocol::handle);
            runToPaymentReceiptConfirmed(callbackProtocol::handle);

            assertTrue(callbackSubmission.get().isDone());
            var completed = assertInstanceOf(SubmissionResult.Completed.class, callbackSubmission.get().join());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, completed.processingResult());
            var result = assertInstanceOf(StateMachineSnapshot.class, applied.snapshot());
            assertSame(callbackProtocol.getTrade(), result.document());
            assertInstanceOf(TradeClosed.class, callbackProtocol.getExecutionState());
            assertTrue(callbackProtocol.getSavedRecord().pendingEffects().isEmpty());
            assertTrue(callbackProtocol.getSavedRecord().scheduledEvents().isEmpty());
            assertEquals(List.of(ObserveDepositTxConfirmation.class), nonMessageEffectTypes());
            verify(blockingStub(), times(1)).closeTrade(any());
        }
    }

    private void assertRetainsSetupData(MuSigV2Trade<?> before, MuSigV2Trade<?> after) {
        assertEquals(before.getId(), after.getId());
        assertSame(before.getContract(), after.getContract());
        assertTrue(before.getExecution().data().getMyPubKeyShares().isPresent());
        assertTrue(before.getExecution().data().getMyContractSignature().isPresent());
        assertTrue(before.getExecution().data().getPeerContractSignature().isPresent());
        assertTrue(before.getExecution().data().getPeerPubKeyShares().isPresent());
        assertTrue(before.getExecution().data().getPeerNonceShares().isPresent());
        assertTrue(before.getExecution().data().getMyNonceShares().isPresent());
        assertTrue(before.getExecution().data().getMyPartialSignatures().isPresent());
        assertTrue(before.getExecution().data().getPeerRedactedPartialSignatures().isPresent());
        assertTrue(before.getExecution().data().getMyDepositPsbt().isPresent());
        assertTrue(before.getExecution().data().getPeerDepositPsbt().isPresent());
        assertEquals(before.getExecution().data().getMyPubKeyShares(), after.getExecution().data().getMyPubKeyShares());
        assertEquals(before.getExecution().data().getMyContractSignature(),
                after.getExecution().data().getMyContractSignature());
        assertEquals(before.getExecution().data().getPeerContractSignature(),
                after.getExecution().data().getPeerContractSignature());
        assertEquals(before.getExecution().data().getPeerPubKeyShares(),
                after.getExecution().data().getPeerPubKeyShares());
        assertEquals(before.getExecution().data().getPeerNonceShares(),
                after.getExecution().data().getPeerNonceShares());
        assertEquals(before.getExecution().data().getMyNonceShares(), after.getExecution().data().getMyNonceShares());
        assertEquals(before.getExecution().data().getMyPartialSignatures(),
                after.getExecution().data().getMyPartialSignatures());
        assertEquals(before.getExecution().data().getPeerRedactedPartialSignatures(),
                after.getExecution().data().getPeerRedactedPartialSignatures());
        assertEquals(before.getExecution().data().getMyDepositPsbt(), after.getExecution().data().getMyDepositPsbt());
        assertEquals(before.getExecution().data().getPeerDepositPsbt(),
                after.getExecution().data().getPeerDepositPsbt());
        assertSame(peerAccountPayload, after.getExecution().data().getPeerAccountPayload().orElseThrow());
    }

}
