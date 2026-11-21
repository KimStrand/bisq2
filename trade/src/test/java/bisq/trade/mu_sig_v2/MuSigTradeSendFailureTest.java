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
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.common.network.TransportType;
import bisq.common.processing.Effect;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.SendMessageResult;
import bisq.network.p2p.services.confidential.SendConfidentialMessageResult;
import bisq.network.p2p.services.confidential.ack.MessageDeliveryStatus;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.CollateralOption;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.PersistenceService;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.awaitSavedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.runNextRetry;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.takeNextRetry;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Outgoing messages of a saved trade: the handoff to the network, failed sends and their replay.
 */
class MuSigTradeSendFailureTest extends MuSigTradePersistenceFixture {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkHandoffUsesTheSavedMessageWithoutWaitingForItsDeliveryFuture(boolean futureAlreadyFailed) throws Exception {
        var delivery = new CompletableFuture<SendMessageResult>();
        var failure = new IllegalStateException("Peer is offline");
        if (futureAlreadyFailed) {
            delivery.completeExceptionally(failure);
        }
        var network = services.getNetworkService();
        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            doAnswer(invocation -> {
                var message = assertInstanceOf(PaymentReceivedMessage_F.class, invocation.getArgument(0));
                assertEquals(contract.getMaker().getNetworkId(), invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                var checkpoint = saved(service);
                assertInstanceOf(PaymentReceiptConfirmed.class, checkpoint.trade().getExecution().state());
                assertEquals(new SendMessage<>(message, message.getReceiver()).toProto(false),
                        checkpoint.pendingEffects().getFirst().toProto(false));
                return delivery;
            }).when(network).confidentialSend(any(), any(), any());

            await(service.paymentReceiptConfirmed(tradeId));
            var committed = savedRecord(service, tradeId).trade();
            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(saved(service).pendingEffects().isEmpty());
            if (!futureAlreadyFailed) {
                assertFalse(delivery.isDone());
                delivery.completeExceptionally(failure);
            }
            await(service.shutdown());
            assertTrue(retries.isEmpty(), "Completed handoffs must not schedule a retry");
            assertSame(committed, savedRecord(service, tradeId).trade());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            verify(network, times(1)).confidentialSend(any(), any(), any());
        } finally {
            service.shutdown().join();
        }
        clearInvocations(network);
        var restoredService = startAndAwaitRecovery();
        try {
            assertInstanceOf(PaymentReceiptConfirmed.class,
                    await(whenExecutionState(restoredService, tradeId, PaymentReceiptConfirmed.class)));
            verify(network, never()).confidentialSend(any(), any(), any());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void aFailedNetworkDeliveryStatusDoesNotCreateAnotherProtocolRetry() throws Exception {
        var delivery = new SendMessageResult();
        delivery.put(TransportType.CLEAR, new SendConfidentialMessageResult(MessageDeliveryStatus.FAILED)
                .setErrorMsg("Mailbox unavailable"));
        var network = services.getNetworkService();
        when(network.confidentialSend(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(delivery));

        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            await(service.paymentReceiptConfirmed(tradeId));
            var committed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(saved(service).pendingEffects().isEmpty());
            await(service.shutdown());
            assertTrue(retries.isEmpty(), "Completed handoffs must not schedule a retry");
            assertSame(committed, savedRecord(service, tradeId).trade());
            verify(network, times(1)).confidentialSend(any(), any(), any());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aBannedTraderPreventsSendingWithoutFailingTheTradeOrRetainingTheMessage(boolean localTraderBanned) throws Exception {
        var bans = services.getUserService().getBannedUserService();
        var bannedTrader = localTraderBanned ? myIdentity.getNetworkId() : contract.getMaker().getNetworkId();
        when(bans.isUserProfileBanned(bannedTrader)).thenReturn(true);
        var network = services.getNetworkService();

        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            await(service.paymentReceiptConfirmed(tradeId));
            var committed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(network, never()).confidentialSend(any(), any(), any());

            when(bans.isUserProfileBanned(bannedTrader)).thenReturn(false);
            await(service.shutdown());
            assertTrue(retries.isEmpty(), "Completed handoffs must not schedule a retry");
            assertSame(committed, savedRecord(service, tradeId).trade());
            verify(network, never()).confidentialSend(any(), any(), any());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery();
        try {
            assertInstanceOf(PaymentReceiptConfirmed.class,
                    await(whenExecutionState(restoredService, tradeId, PaymentReceiptConfirmed.class)));
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verify(network, never()).confidentialSend(any(), any(), any());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restoredService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anImmediateNetworkExceptionReplaysTheOriginalMessageWithoutRepeatingTheBackend(boolean retryBeforeRestart) throws Exception {
        var network = services.getNetworkService();
        var failure = new IllegalStateException("Sender node is not available");
        var firstMessage = new AtomicReference<PaymentReceivedMessage_F>();
        doAnswer(invocation -> {
            firstMessage.compareAndSet(null, invocation.getArgument(0));
            throw failure;
        }).when(network).confidentialSend(any(), any(), any());
        MuSigV2Trade<?> committed;
        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> await(service.paymentReceiptConfirmed(tradeId))).getCause());
            committed = savedRecord(service, tradeId).trade();
            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            assertEquals(new SendMessage<>(firstMessage.get(), contract.getMaker().getNetworkId()).toProto(false),
                    saved(service).pendingEffects().getFirst().toProto(false));
            runNextRetry(retries);
            var retryAfterFailure = takeNextRetry(retries);
            verify(network, times(2)).confidentialSend(any(), any(), any());
            assertEquals(1, saved(service).pendingEffects().size());

            doAnswer(invocation -> {
                var replayed = assertInstanceOf(PaymentReceivedMessage_F.class, invocation.getArgument(0));
                assertEquals(firstMessage.get().getId(), replayed.getId());
                assertEquals(firstMessage.get().toProto(false), replayed.toProto(false));
                assertEquals(contract.getMaker().getNetworkId(), invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(network).confidentialSend(any(), any(), any());
            if (retryBeforeRestart) {
                retryAfterFailure.run();
                awaitSavedRecord(service, tradeId, record -> record.pendingEffects().isEmpty());
                assertSame(committed, savedRecord(service, tradeId).trade());
                assertTrue(saved(service).pendingEffects().isEmpty());
            }
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery();
        try {
            assertEquals(committed.toProto(false), savedRecord(restoredService, tradeId).trade().toProto(false));
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verify(network, times(3)).confidentialSend(any(), any(), any());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restoredService.shutdown().join();
        }
        clearInvocations(network);
        var restartedService = startAndAwaitRecovery();
        try {
            assertEquals(committed.toProto(false), savedRecord(restartedService, tradeId).trade().toProto(false));
            verify(network, never()).confidentialSend(any(), any(), any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void aMediationRequestUsesTheSavedMediatorAndSenderAfterRestart() throws Exception {
        var requester = userProfile("requester", myIdentity.getNetworkId());
        var peer = userProfile("peer", contract.getMaker().getNetworkId());
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var request = new MuSigMediationRequest(tradeId, contract, requester, peer, List.of(), mediator);
        var original = tradeAt(new DepositTxConfirmed()).withMediation(
                FlowSnapshot.of(new MuSigMediationState.Requested(),
                        MuSigV2MediationData.initial()));
        seed(new MuSigV2TradeRecord(original,
                Map.of(Flow.MEDIATION, Set.of(MediationRequestedEvent.class)),
                List.of(),
                List.of(),
                List.of(new SendMessage<>(request, mediator))));
        var network = services.getNetworkService();
        var delivery = new CompletableFuture<SendMessageResult>();
        doAnswer(invocation -> {
            var restored = assertInstanceOf(MuSigMediationRequest.class, invocation.getArgument(0));
            assertEquals(request.getAckRequestingMessageId(), restored.getAckRequestingMessageId());
            assertEquals(request.toProto(false), restored.toProto(false));
            assertEquals(mediator, invocation.getArgument(1));
            assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
            return delivery;
        }).when(network).confidentialSend(any(), any(), any());

        var service = startAndAwaitRecovery();
        try {
            assertEquals(original.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertTrue(saved(service).pendingEffects().isEmpty());
            assertFalse(delivery.isDone());
            verify(network, times(1)).confidentialSend(any(), any(), any());
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void anEarlyPaymentDetailsRequestSurvivesRestartAndIsAnsweredOnlyOnce() throws Exception {
        var original = tradeWithPaymentDetails(new MuSigMediationState.Init());
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var request = new MuSigDisputeCasePaymentDetailsRequest(tradeId, mediator);
        seed(initialRecord(original));
        var service = startAndAwaitRecovery(effect -> fail("Mediation has not started"));
        try {
            var pending = submitMessage(service, request);

            await(pending);
            assertEquals(original.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertEquals(List.of(request), saved(service).pendingInputs());
        } finally {
            service.shutdown().join();
        }

        var network = services.getNetworkService();
        var delivery = new CompletableFuture<SendMessageResult>();
        doReturn(delivery).when(network).confidentialSend(any(), any(), any());
        bisq.trade.protobuf.MuSigV2Trade answered;
        var restoredService = startAndAwaitRecovery();
        try {
            assertEquals(1, saved(restoredService).pendingInputs().size());
            verify(network, never()).confidentialSend(any(), any(), any());
            var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                    tradeId,
                    mediator);
            await(submitMessage(restoredService, opened));
            var trade = savedRecord(restoredService, opened.getTradeId()).trade();
            answered = trade.toProto(false);
            assertInstanceOf(MuSigMediationState.Open.class, trade.getMediation().orElseThrow().state());
            assertEquals(original.toProto(false).getExecution(), answered.getExecution());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                    .contains(MuSigDisputeCasePaymentDetailsRequest.class));
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            assertFalse(delivery.isDone());
            await(submitMessage(restoredService, request));
            verify(network, times(1)).confidentialSend(any(MuSigDisputeCasePaymentDetailsResponse.class),
                    any(),
                    any());
        } finally {
            restoredService.shutdown().join();
        }

        clearInvocations(network);
        var restartedService = startAndAwaitRecovery();
        try {
            await(submitMessage(restartedService, request));
            assertEquals(answered, savedRecord(restartedService, request.getTradeId()).trade().toProto(false));
            assertTrue(saved(restartedService).pendingInputs().isEmpty());
            assertTrue(saved(restartedService).pendingEffects().isEmpty());
            verify(network, never()).confidentialSend(any(), any(), any());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aPaymentDetailsResponseIsSavedBeforeSendingAndReplayedAfterFailure(boolean retryBeforeRestart) throws Exception {
        var original = tradeWithPaymentDetails(new MuSigMediationState.Open());
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var request = new MuSigDisputeCasePaymentDetailsRequest(tradeId, mediator);
        var network = services.getNetworkService();
        var sendFailure = new IllegalStateException("Sender unavailable");
        var firstResponse = new AtomicReference<MuSigDisputeCasePaymentDetailsResponse>();
        bisq.trade.protobuf.MuSigV2Trade committed;
        seed(initialRecord(original));
        var service = startAndAwaitRecovery();
        try {

            doAnswer(invocation -> {
                var response = assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, invocation.getArgument(0));
                firstResponse.compareAndSet(null, response);
                assertSame(firstResponse.get(), response);
                assertEquals(myAccountPayload, response.getTakerAccountPayload());
                assertEquals(original.getExecution().data().getPeerAccountPayload().orElseThrow(),
                        response.getMakerAccountPayload());
                var checkpoint = saved(service);
                assertTrue(checkpoint.processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                        .contains(MuSigDisputeCasePaymentDetailsRequest.class));
                assertEquals(new SendMessage<>(response, mediator).toProto(false),
                        checkpoint.pendingEffects().getFirst().toProto(false));
                throw sendFailure;
            }).when(network).confidentialSend(any(), any(), any());

            assertSame(sendFailure, assertThrows(ExecutionException.class,
                    () -> await(submitMessage(service, request))).getCause());
            var answered = savedRecord(service, tradeId).trade();
            committed = answered.toProto(false);
            assertEquals(original.toProto(false).getExecution(), answered.toProto(false).getExecution());
            assertInstanceOf(MuSigMediationState.Open.class, answered.getMediation().orElseThrow().state());
            assertTrue(answered.getMediation().orElseThrow().data().failure().isEmpty());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertSame(sendFailure, assertThrows(ExecutionException.class,
                    () -> await(submitMessage(service, request))).getCause());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertEquals(committed, savedRecord(service, tradeId).trade().toProto(false));
            verify(network, times(2)).confidentialSend(any(), any(), any());

            doAnswer(invocation -> {
                var response = assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, invocation.getArgument(0));
                assertEquals(firstResponse.get().toProto(false), response.toProto(false));
                assertEquals(mediator, invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(network).confidentialSend(any(), any(), any());
            if (retryBeforeRestart) {
                runNextRetry(retries);
                awaitSavedRecord(service, tradeId, record -> record.pendingEffects().isEmpty());
                assertEquals(committed, savedRecord(service, tradeId).trade().toProto(false));
                assertTrue(saved(service).pendingEffects().isEmpty());
            }
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery();
        try {
            await(submitMessage(restoredService, request));
            assertEquals(committed, savedRecord(restoredService, request.getTradeId()).trade().toProto(false));
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verify(network, times(3)).confidentialSend(any(), any(), any());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
        clearInvocations(network);
        var restartedService = startAndAwaitRecovery();
        try {
            await(submitMessage(restartedService, request));
            verify(network, never()).confidentialSend(any(), any(), any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"failedTrade", "historicalTrade", "failedMediation"})
    void stoppedFlowsDoNotAnswerNewPaymentDetailsRequests(String stopped) throws Exception {
        var original = tradeWithPaymentDetails(new MuSigMediationState.Open());
        original = switch (stopped) {
            case "failedTrade" -> withTradeState(original, new MuSigTradeState.Failed());
            case "historicalTrade" -> withTradeState(original, new MuSigTradeState.Historical());
            default -> original.withMediation(FlowSnapshot.of(new MuSigMediationState.Failed(),
                    MuSigV2MediationData.initial()));
        };
        seed(initialRecord(original));
        var request = new MuSigDisputeCasePaymentDetailsRequest(tradeId,
                contract.getMediator().orElseThrow().getNetworkId());
        var service = startAndAwaitRecovery(effect -> fail("Stopped flows must not answer requests"));
        try {
            await(submitMessage(service, request));
            assertEquals(original.toProto(false), savedRecord(service, request.getTradeId()).trade().toProto(false));
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertTrue(saved(service).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anImmediateSendFailureRetainsThePaymentMessageAndDeadline(boolean retryBeforeRestart) throws Exception {
        var attempts = new ArrayList<PaymentReceivedMessage_F>();
        var failSend = new AtomicBoolean(true);
        var sendFailure = new IllegalStateException("Sender unavailable");
        Instant now = Instant.parse("2030-01-01T12:00:00.123Z");
        Clock clock = now::toEpochMilli;
        var deadline = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                now.plus(Duration.ofHours(24)),
                new SellersCloseTradeTimeoutEvent());
        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery(effect -> {
            attempts.add(assertInstanceOf(PaymentReceivedMessage_F.class,
                    ((SendMessage<?, ?>) effect).message()));
            if (failSend.get()) {
                throw sendFailure;
            }
        }, clock);
        try {

            assertSame(sendFailure, assertThrows(ExecutionException.class,
                    () -> await(service.paymentReceiptConfirmed(tradeId))).getCause());
            var committed = savedRecord(service, tradeId).trade();
            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(committed.getExecution().data().getMySwapTxSignatureResponse().isPresent());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            assertEquals(Set.of(PaymentReceiptConfirmedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.EXECUTION,
                            Set.of()));
            assertEquals(committed.toProto(false), saved(service).trade().toProto(false));
            assertEquals(List.of(deadline), saved(service).scheduledEvents());
            assertEquals(List.of(new SendMessage<>(attempts.getFirst(), contract.getMaker().getNetworkId())),
                    saved(service).pendingEffects());

            runNextRetry(retries);
            var retryAfterFailure = takeNextRetry(retries);
            assertSame(committed, savedRecord(service, tradeId).trade());
            assertEquals(attempts.getFirst(), attempts.getLast());
            assertEquals(2, attempts.size());
            assertEquals(1, saved(service).pendingEffects().size());
            assertEquals(List.of(deadline), saved(service).scheduledEvents());
            if (retryBeforeRestart) {
                failSend.set(false);
                retryAfterFailure.run();
                awaitSavedRecord(service, tradeId, record -> record.pendingEffects().isEmpty());
                assertSame(committed, savedRecord(service, tradeId).trade());
                assertEquals(attempts.getFirst(), attempts.getLast());
                assertEquals(3, attempts.size());
                assertTrue(saved(service).pendingEffects().isEmpty());
            }
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            service.shutdown().join();
        }
        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect), clock);
        try {
            assertInstanceOf(PaymentReceiptConfirmed.class,
                    await(whenExecutionState(restoredService, tradeId, PaymentReceiptConfirmed.class)));
            assertEquals(retryBeforeRestart ? List.of() :
                    List.of(new SendMessage<>(attempts.getFirst(), contract.getMaker().getNetworkId())), sent);
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            assertEquals(List.of(deadline), saved(restoredService).scheduledEvents());
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            restoredService.shutdown().join();
        }
        var restartedService = startAndAwaitRecovery(effect -> fail("Completed sends must not replay"), clock);
        try {
            assertInstanceOf(PaymentReceiptConfirmed.class,
                    savedRecord(restartedService, tradeId).trade().getExecution().state());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void anImmediateSendFailureDuringRecoveryKeepsTheMessageAndDeadline() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed());
        var outgoing = new PaymentReceivedMessage_F(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})));
        Instant now = Instant.parse("2030-01-01T00:00:00Z");
        Clock clock = now::toEpochMilli;
        var scheduled = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                now.plus(Duration.ofHours(24)),
                new SellersCloseTradeTimeoutEvent());
        var pending = new SendMessage<>(outgoing, contract.getMaker().getNetworkId());
        seed(new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(scheduled), List.of(pending)));
        var failure = new IllegalStateException("Sender unavailable during recovery");

        for (int restart = 0; restart < 2; restart++) {
            var persistenceService = new PersistenceService(directory);
            when(services.getPersistenceService()).thenReturn(persistenceService);
            var attempts = new ArrayList<Effect>();
            mockNetworkSend(effect -> {
                attempts.add(effect);
                throw failure;
            });
            var service = serviceWithClock(services, grpcClient, clock);
            try {
                assertTrue(await(persistenceService.readAllPersisted()));
                assertTrue(await(service.initialize()));
                assertSame(failure,
                        assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture())).getCause());
                assertEquals(List.of(pending), attempts);
                assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
                assertEquals(List.of(pending), saved(service).pendingEffects());
                assertEquals(List.of(scheduled), saved(service).scheduledEvents());
            } finally {
                service.shutdown().join();
            }
        }
        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect), clock);
        try {
            assertEquals(trade.toProto(false), savedRecord(restoredService, tradeId).trade().toProto(false));
            assertEquals(List.of(pending), sent);
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            assertEquals(List.of(scheduled), saved(restoredService).scheduledEvents());
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFinalMessageSendFailureRetainsBuyerClosureAndReplaysOnlyTheMessage(boolean buyerAsMaker) throws Exception {
        MuSigV2Trade<?> trade = buyerTradeAt(new PaymentInitiated());
        if (buyerAsMaker) {
            var paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
            var priceSpec = new MarketPriceSpec();
            var offer = new MuSigOffer(contract.getOffer().getId(),
                    myIdentity.getNetworkId(),
                    Direction.BUY,
                    contract.getOffer().getMarket(),
                    new BaseSideFixedAmountSpec(100_000),
                    priceSpec,
                    List.of(paymentMethod),
                    List.of(new CollateralOption(0.25, 0.25)),
                    "1.0.0");
            var makerContract = new MuSigContract(contract.getTakeOfferDate(),
                    offer,
                    contract.getMaker().getNetworkId(),
                    100_000,
                    3_500_000,
                    PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR"),
                    new byte[20],
                    contract.getMediator(),
                    Optional.empty(),
                    priceSpec,
                    0);
            trade = MuSigV2TradeTestFactory.tradeAt(makerContract,
                    myIdentity,
                    TradeRole.BUYER_AS_MAKER,
                    "1.0.0",
                    myAccountPayload,
                    new PaymentInitiated());
        }
        String buyerTradeId = trade.getId();
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var peer = buyerAsMaker ? trade.getContract().getTaker() : trade.getContract().getMaker();
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                buyerTradeId,
                "1.0.0",
                peer.getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        var attempts = new ArrayList<Effect>();
        var failure = new IllegalStateException("Final-message sender unavailable");
        MuSigV2Trade<?> closed;
        seed(initialRecord(trade));
        var service = startAndAwaitRecovery(effect -> {
            assertInstanceOf(CooperativeClosureMessage_G.class, ((SendMessage<?, ?>) effect).message());
            attempts.add(effect);
            throw failure;
        });
        try {

            assertSame(failure,
                    assertThrows(ExecutionException.class, () -> await(submitMessage(service, confirmation))).getCause());
            closed = savedRecord(service, buyerTradeId).trade();
            assertInstanceOf(TradeClosed.class, closed.getExecution().state());
            assertEquals(Optional.of(signature), closed.getExecution().data().getPeerSwapTxSignature());
            assertTrue(closed.getExecution().data().getMyCloseTradeResponse().isPresent());
            assertTrue(closed.getExecution().data().getMyFailure().isEmpty());
            assertEquals(Set.of(PaymentReceivedMessage_F.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.EXECUTION,
                            Set.of()));
            assertEquals(closed.toProto(false), saved(service).trade().toProto(false));
            assertEquals(List.of(attempts.getFirst()), saved(service).pendingEffects());

            runNextRetry(retries);
            takeNextRetry(retries); // The same send remains pending after the automatic retry.
            assertSame(closed, savedRecord(service, buyerTradeId).trade());
            assertEquals(2, attempts.size());
            assertEquals(attempts.getFirst(), attempts.getLast());
            assertEquals(List.of(attempts.getFirst()), saved(service).pendingEffects());
            verify(backend, times(1)).closeTrade(any());
        } finally {
            service.shutdown().join();
        }
        assertSame(failure, assertThrows(ExecutionException.class, () -> startAndAwaitRecovery(effect -> {
            assertEquals(attempts.getFirst(), effect);
            throw failure;
        })).getCause());
        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect));
        try {
            assertEquals(closed.toProto(false), savedRecord(restoredService, buyerTradeId).trade().toProto(false));
            assertEquals(List.of(attempts.getFirst()), sent);
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verify(backend, times(1)).closeTrade(any());
        } finally {
            restoredService.shutdown().join();
        }
        var restartedService = startAndAwaitRecovery(effect -> fail("Completed sends must not replay"));
        try {
            assertEquals(closed.toProto(false), savedRecord(restartedService, buyerTradeId).trade().toProto(false));
            verify(backend, times(1)).closeTrade(any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void failedErrorReportDeliveryIsReplayedWithoutRepeatingTheFailedOperation() throws Exception {
        var failedReport = new AtomicReference<MuSigReportErrorMessage>();
        var backendFailure = new IllegalStateException("Signing failed");
        when(backend.signSwapTx(any())).thenThrow(backendFailure);
        seed(initialRecord(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery(effect -> {
            failedReport.set((MuSigReportErrorMessage) ((SendMessage<?, ?>) effect).message());
            throw new IllegalStateException("Transport unavailable");
        });
        try {

            var completion = assertThrows(ExecutionException.class,
                    () -> await(service.paymentReceiptConfirmed(tradeId)));
            assertEquals("Transport unavailable", completion.getCause().getMessage());
            assertTrue(service.getTradeReadModel(tradeId).getMyFailure().orElseThrow().errorMessage()
                    .contains("Signing failed"));
            assertInstanceOf(Failed.class, saved(service).trade().getExecution().state());
            assertEquals(1, saved(service).pendingEffects().size());
            assertTrue(saved(service).scheduledEvents().isEmpty());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect));
        try {
            assertInstanceOf(Failed.class,
                    await(whenExecutionState(restoredService, tradeId, Failed.class)));
            var replayed = assertInstanceOf(MuSigReportErrorMessage.class,
                    ((SendMessage<?, ?>) sent.getFirst()).message());
            assertEquals(failedReport.get().getId(), replayed.getId());
            assertEquals(failedReport.get().getValueBuilder(false).build(), replayed.getValueBuilder(false).build());
            assertEquals(1, sent.size());
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restoredService.shutdown().join();
        }
        var restartedService = startAndAwaitRecovery(effect -> fail("Acknowledged error reports must not be replayed"));
        try {
            assertInstanceOf(Failed.class, savedRecord(restartedService, tradeId).trade().getExecution().state());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    private MuSigV2Trade<MuSigSellerAsTakerState> tradeWithPaymentDetails(MuSigMediationState state) {
        var trade = tradeAt(new DepositTxConfirmed());
        var execution = trade.getExecution();
        var peerPayload = new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR"));
        return trade.withExecution(execution.to(execution.state(),
                        execution.data().toBuilder().peerAccountPayload(Optional.of(peerPayload)).build()))
                .withMediation(FlowSnapshot.of(state, MuSigV2MediationData.initial()));
    }
}
