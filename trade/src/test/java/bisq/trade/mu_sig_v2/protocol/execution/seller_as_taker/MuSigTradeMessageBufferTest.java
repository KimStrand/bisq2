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

import bisq.common.processing.Effect;
import bisq.common.threading.ExecutorFactory;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.trade.mu_sig_v2.MuSigV2TradeRecord;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuSigTradeMessageBufferTest extends SellerAsTakerFixture {
    private final ConcurrentLinkedQueue<Effect> serviceEffects = new ConcurrentLinkedQueue<>();
    private MuSigTradeService service = serviceWithClock(services, grpcClient, serviceClock);
    private final NetworkId mediatorId = networkId("mediator");

    @BeforeEach
    void initialize() throws Exception {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            serviceEffects.add(new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        var mediator = mock(UserProfile.class);
        when(mediator.getNetworkId()).thenReturn(mediatorId);
        when(mediator.toProto(true)).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        await(service.initialize());
    }

    @AfterEach
    void shutdown() {
        service.shutdown().join();
    }

    @Test
    void aMessageWaitsForRegistrationAndThenForTheRequiredTradeState() throws Exception {
        var pending = submitMessage(service, setupMessage());

        assertFalse(pending.isDone());
        verify(blockingStub(), never()).initTrade(any());
        verify(blockingStub(), never()).getNonceShares(any());

        var created = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        assertInstanceOf(TradeInitialized.class, created.getExecution().state());
        await(pending);
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                service.getTradeReadModel(TRADE_ID).getExecutionState());

        await(resumeLane(service, TRADE_ID));
        var started = savedRecord(service, TRADE_ID).trade();
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class, started.getExecution().state());
        verify(blockingStub(), times(1)).initTrade(any());
        verify(blockingStub(), times(1)).getNonceShares(any());
    }

    @Test
    void registrationOnlyReplaysMessagesForThatTrade() throws Exception {
        var pending = submitMessage(service, setupMessage());
        var otherContract = muSigContract(peerAccountPayload);
        when(otherContract.getTakeOfferDate()).thenReturn(TAKE_OFFER_DATE + 1);
        takeOfferDate.set(TAKE_OFFER_DATE + 1);

        await(initializeTrade(service, initializeTrade(otherContract, myIdentity, myAccountPayload)));
        assertFalse(pending.isDone());

        takeOfferDate.set(TAKE_OFFER_DATE);
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(pending);
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                service.getTradeReadModel(TRADE_ID).getExecutionState());
    }

    @Test
    void recoveryPrecedesBufferedMessagesAndLaterInputsCannotOvertakeThem() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var asyncStub = grpcClient.getAsyncStub();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(asyncStub).subscribeTxConfirmationStatus(any(), any());

        var restored = restoreTrade(MuSigV2TradeRecord.initial(tradeWithPaymentData(new AccountPayloadReceived())));
        var payment = submitMessage(service, paymentInitiatedMessage())
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
        var opened = submitMessage(service, openedMessage())
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var confirmed = service.onDepositTxConfirmed(TRADE_ID)
                    .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
            assertFalse(restored.isDone());
            assertFalse(payment.isDone());
            assertFalse(opened.isDone());
            assertFalse(confirmed.isDone());

            release.countDown();
            assertInstanceOf(MuSigMediationState.Init.class, await(restored).getMediation().orElseThrow().state());
            assertInstanceOf(AccountPayloadReceived.class, await(payment).getExecution().state());
            assertInstanceOf(MuSigMediationState.Init.class, await(payment).getMediation().orElseThrow().state());
            assertInstanceOf(MuSigMediationState.Open.class, await(opened).getMediation().orElseThrow().state());
            assertInstanceOf(AccountPayloadReceived.class, await(opened).getExecution().state());
            assertInstanceOf(PaymentInitiatedMessageReceived.class, await(confirmed).getExecution().state());
            assertInstanceOf(MuSigMediationState.Open.class,
                    service.getTradeReadModel(TRADE_ID).getMediationState().orElseThrow());
            verify(blockingStub(), times(1)).signSwapTx(any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void invalidMessagesDoNotBlockValidMessagesAndOnlyIdenticalDuplicatesShareReplay() throws Exception {
        await(restoreTrade(MuSigV2TradeRecord.initial(tradeWithPaymentData(new DepositTxConfirmed()))));

        var invalidPeer = submitMessage(service, new PaymentInitiatedMessage_E(uid(),
                TRADE_ID,
                "1.0.0",
                myNetworkId,
                myNetworkId,
                new byte[]{2}));
        var paymentMessage = paymentInitiatedMessage();
        var payment = submitMessage(service, paymentMessage)
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
        var invalidMediator = submitMessage(service, new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                peerNetworkId,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()));
        var opened = submitMessage(service, openedMessage())
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
        var duplicate = submitMessage(service, new PaymentInitiatedMessage_E(paymentMessage.getId(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId,
                myNetworkId,
                new byte[]{2}))
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());


        for (var invalid : List.of(invalidPeer, invalidMediator)) {
            var failure = assertInstanceOf(MuSigProtocolException.class,
                    assertThrows(ExecutionException.class, () -> await(invalid)).getCause());
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        }
        assertInstanceOf(PaymentInitiatedMessageReceived.class, await(payment).getExecution().state());
        assertInstanceOf(MuSigMediationState.Init.class, await(payment).getMediation().orElseThrow().state());
        assertEquals(await(payment).getExecution(), await(duplicate).getExecution());
        var result = await(opened);
        assertInstanceOf(MuSigMediationState.Open.class, result.getMediation().orElseThrow().state());
        assertTrue(result.getExecution().data().getMyFailure().isEmpty());
        verify(blockingStub(), times(1)).signSwapTx(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completedReplayCallbacksDoNotHoldTheServiceLock(boolean invalidMessage) throws Exception {
        try (var worker = ExecutorFactory.newSingleThreadExecutor("buffer-replay-test");
             var caller = ExecutorFactory.newSingleThreadExecutor("buffer-caller-test");
             var timer = ExecutorFactory.newSingleThreadScheduledExecutor("buffer-timer-test");
             var factory = mockStatic(ExecutorFactory.class)) {
            var executor = mock(ExecutorService.class);
            // Finish on a worker before execute returns, forcing an already-completed replay future.
            doAnswer(invocation -> {
                worker.submit(invocation.<Runnable>getArgument(0)).get(10, TimeUnit.SECONDS);
                return null;
            }).when(executor).execute(any(Runnable.class));
            factory.when(() -> ExecutorFactory.boundedCachedPool(anyString(), anyInt(), anyInt(), anyLong(), anyInt(), any()))
                    .thenReturn(executor);
            factory.when(() -> ExecutorFactory.newSingleThreadScheduledExecutor(anyString())).thenReturn(timer);

            var fastService = serviceWithClock(services, grpcClient, serviceClock);
            try {
                await(fastService.initialize());
                var waiting = invalidMessage
                        ? submitMessage(fastService, new PaymentInitiatedMessage_E(uid(),
                                TRADE_ID,
                                "1.0.0",
                                myNetworkId,
                                myNetworkId,
                                new byte[]{2}))
                        : submitMessage(fastService, setupMessage());
                var callback = waiting.handle((ignored, failure) -> {
                    try {
                        // Another thread must be able to enter the service while this callback runs.
                        return caller.submit(() -> fastService.getTradeReadModel(TRADE_ID)).get(10, TimeUnit.SECONDS);
                    } catch (Exception exception) {
                        throw new CompletionException(exception);
                    }
                });

                var created = await(initializeTrade(fastService, initializeTrade(muSigContract, myIdentity, myAccountPayload)));

                assertSame(fastService.getTradeReadModel(created.getId()), await(callback));
                if (invalidMessage) {
                    assertInstanceOf(MuSigProtocolException.class,
                            assertThrows(ExecutionException.class, () -> await(waiting)).getCause());
                } else {
                    await(waiting);
                    assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                            fastService.getTradeReadModel(TRADE_ID).getExecutionState());
                }
            } finally {
                fastService.shutdown().join();
            }
        }
    }

    @Test
    void paymentDetailsRequestsWaitForRegistrationAndThenMediationOpening() throws Exception {
        await(restoreTrade(MuSigV2TradeRecord.initial(tradeWithPaymentData(new DepositTxConfirmed()))));

        var request = new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorId);
        var pending = submitMessage(service, request);

        await(pending);
        assertInstanceOf(MuSigMediationState.Init.class,
                service.getTradeReadModel(TRADE_ID).getMediationState().orElseThrow());
        assertTrue(serviceEffects.isEmpty());

        await(submitMessage(service, openedMessage()));
        await(submitMessage(service, request));
        var responses = serviceEffects.stream()
                .filter(effect -> effect instanceof Effect.SendMessage<?, ?> send &&
                        send.message() instanceof MuSigDisputeCasePaymentDetailsResponse)
                .toList();
        assertEquals(1, responses.size());
        assertEquals(mediatorId, ((Effect.SendMessage<?, ?>) responses.getFirst()).receiver());
    }

    @Test
    void cancellingOneObserverDoesNotCancelTheBufferedMessageOrOtherObservers() throws Exception {
        var message = setupMessage();
        var cancelled = submitMessage(service, message);
        var waiting = submitMessage(service, message);
        assertTrue(cancelled.cancel(false));
        assertFalse(waiting.isDone());

        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(waiting);
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                service.getTradeReadModel(TRADE_ID).getExecutionState());
        await(resumeLane(service, TRADE_ID));
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                savedRecord(service, TRADE_ID).trade().getExecution().state());
        verify(blockingStub(), times(1)).getNonceShares(any());
    }

    @Test
    void shutdownRejectsAllMessagesStillWaitingForRegistration() throws Exception {
        var pending = List.of(submitMessage(service, setupMessage()),
                submitMessage(service, openedMessage()),
                submitMessage(service, new MuSigMediationStateChangeMessage(uid(),
                        TRADE_ID,
                        mediatorId,
                        MediationCaseState.CLOSED,
                        Optional.empty(),
                        Optional.empty())),
                submitMessage(service, new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorId)));
        assertTrue(pending.stream().noneMatch(CompletableFuture::isDone));

        assertTrue(await(service.shutdown()));

        for (var result : pending) {
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> await(result)).getCause());
        }
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, setupMessage()))).getCause());
        verify(blockingStub(), never()).initTrade(any());
    }

    private MuSigV2Trade<SellerAsTakerState> tradeWithPaymentData(SellerAsTakerState state) {
        var trade = tradeAt(state);
        var execution = trade.getExecution();
        return trade.withExecution(execution.to(state, execution.data().toBuilder()
                .peerRedactedPartialSignatures(Optional.of(redactedPartialSignatures()))
                .peerAccountPayload(Optional.of(peerAccountPayload))
                .build()));
    }

    private MuSigMediationStateChangeMessage openedMessage() {
        return new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                mediatorId,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }
    private CompletableFuture<MuSigV2Trade<?>> restoreTrade(MuSigV2TradeRecord record) {
        service.shutdown().join();
        service = serviceWithClock(services, grpcClient, serviceClock);
        return bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.restoreTrade(service, record);
    }

}
