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

import bisq.common.data.ByteArray;
import bisq.common.observable.map.HashMapObserver;
import bisq.common.processing.Effect;
import bisq.common.util.ExceptionUtil;
import bisq.i18n.Res;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.persistence.Persistence;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig_v2.MuSigTradeReadModel;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.protobuf.PubKeySharesRequest;
import bisq.trade.protobuf.PubKeySharesResponse;
import bisq.trade.protobuf.SubscribeTxConfirmationStatusRequest;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.profile.UserProfile;
import io.grpc.Context;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithCapturedTimers;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenReadModel;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuSigTradeServiceTest extends SellerAsTakerFixture {
    private final ConcurrentLinkedQueue<Effect> serviceEffects = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> serviceLogs = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> effectThreads = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> logThreads = new ConcurrentLinkedQueue<>();
    private BiConsumer<String, Effect> onEffect = (tradeId, effect) -> {};
    private final List<Runnable> timers = new CopyOnWriteArrayList<>();
    private final MuSigTradeService service = serviceWithCapturedTimers(services, grpcClient, serviceClock, timers);

    @BeforeEach
    void initialize() throws Exception {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            var effect = new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1));
            serviceEffects.add(effect);
            effectThreads.add(Thread.currentThread().getName());
            onEffect.accept(TRADE_ID, effect);
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        doAnswer(invocation -> {
            assertSame(channel, invocation.getArgument(1));
            serviceLogs.add(invocation.getArgument(0));
            logThreads.add(Thread.currentThread().getName());
            return CompletableFuture.completedFuture(null);
        }).when(channels).sendTradeLogMessage(anyString(), any());
        service.initialize().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void shutdown() {
        service.shutdown().join();
    }

    @Test
    void initializationPublishesAReadModelAndRunsTheFirstStep() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(blockingStub().initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return PubKeySharesResponse.getDefaultInstance();
        });
        var command = initializeTrade(muSigContract, myIdentity, myAccountPayload);
        takeOfferDate.set(TAKE_OFFER_DATE + 1_000);
        var preparation = initializeTrade(service, command);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var readModel = await(whenReadModel(service, TRADE_ID));
            assertInstanceOf(Init.class, readModel.getExecutionState());
            assertFalse(preparation.isDone());
            release.countDown();
            var created = await(preparation);
            assertEquals(TAKE_OFFER_DATE, created.getContract().getTakeOfferDate());
            assertEquals(TRADE_ID, created.getId());
            assertInstanceOf(TradeInitialized.class, created.getExecution().state());
            assertInstanceOf(TradeInitialized.class, readModel.getExecutionState());
            assertSame(readModel, service.getTradeReadModel(TRADE_ID));
            assertSame(created, savedRecord(service, TRADE_ID).trade());
            verify(blockingStub(), times(1)).initTrade(any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void readModelObserversCannotOvertakeTheInitialSave() throws Exception {
        var initialSave = new CompletableFuture<Void>();
        var saveStarted = new CountDownLatch(1);
        Persistence<?> persistence = service.getPersistence();
        when(persistence.persistAsync(any()))
                .thenAnswer(invocation -> {
                    saveStarted.countDown();
                    return initialSave;
                })
                .thenReturn(CompletableFuture.completedFuture(null));
        var models = new ConcurrentLinkedQueue<MuSigTradeReadModel>();
        var started = new AtomicReference<CompletableFuture<Void>>();
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String tradeId, MuSigTradeReadModel readModel) {
                models.add(readModel);
                started.set(resumeLane(service, tradeId));
            }
        });
        try {
            var preparation = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
            var readModel = await(whenReadModel(service, TRADE_ID));
            assertEquals(List.of(readModel), List.copyOf(models));
            assertTrue(saveStarted.await(10, TimeUnit.SECONDS));
            assertFalse(started.get().isDone());
            verify(blockingStub(), never()).initTrade(any());

            initialSave.complete(null);
            await(started.get());
            assertInstanceOf(TradeInitialized.class, readModel.getExecutionState());
            assertEquals(List.of(readModel), List.copyOf(models));
            assertSame(readModel, service.getTradeReadModels().get(TRADE_ID));

            var existing = new ConcurrentLinkedQueue<MuSigTradeReadModel>();
            var latePin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
                @Override
                public void put(String tradeId, MuSigTradeReadModel model) {
                    existing.add(model);
                }
            });
            try {
                assertEquals(List.of(readModel), List.copyOf(existing));
            } finally {
                latePin.unbind();
            }
        } finally {
            initialSave.complete(null);
            pin.unbind();
        }
    }

    @Test
    void chatSetupFailureIsClearedAfterASuccessfulRetry() throws Exception {
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        var failure = new IllegalStateException("Chat write failed");
        when(channels.getPersistence().persistAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(failure))
                .thenReturn(CompletableFuture.completedFuture(null));

        var preparation = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
        var thrown = assertThrows(ExecutionException.class, () -> await(preparation));
        assertSame(failure, ExceptionUtil.getRootCause(thrown));
        var readModel = service.getTradeReadModel(TRADE_ID);
        assertEquals(Optional.of("Chat write failed"), readModel.getPreparationError());
        assertTrue(readModel.getPersistenceError().isEmpty());
        assertInstanceOf(Init.class, readModel.getExecutionState());
        verify(blockingStub(), never()).initTrade(any());

        await(resumeLane(service, TRADE_ID));

        assertTrue(readModel.getPreparationError().isEmpty());
        assertInstanceOf(TradeInitialized.class, readModel.getExecutionState());
        verify(blockingStub(), times(1)).initTrade(any());
    }

    @Test
    void initialCheckpointFailureIsReportedOnlyAsPersistenceError() throws Exception {
        var failure = new IllegalStateException("Trade write failed");
        Persistence<?> persistence = service.getPersistence();
        when(persistence.persistAsync(any()))
                .thenReturn(CompletableFuture.failedFuture(failure))
                .thenReturn(CompletableFuture.completedFuture(null));

        var preparation = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
        var thrown = assertThrows(ExecutionException.class, () -> await(preparation));
        assertSame(failure, ExceptionUtil.getRootCause(thrown));
        var readModel = service.getTradeReadModel(TRADE_ID);
        assertEquals(Optional.of("Trade write failed"), readModel.getPersistenceError());
        assertTrue(readModel.getPreparationError().isEmpty());
        verify(blockingStub(), never()).initTrade(any());

        await(resumeLane(service, TRADE_ID));

        assertTrue(readModel.getPersistenceError().isEmpty());
        assertTrue(readModel.getPreparationError().isEmpty());
        assertInstanceOf(TradeInitialized.class, readModel.getExecutionState());
    }

    @Test
    void initialSendingFailureDoesNotBecomeAPreparationError() throws Exception {
        var failure = new IllegalStateException("Send failed");
        onEffect = (tradeId, effect) -> {
            throw failure;
        };

        var preparation = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
        var thrown = assertThrows(ExecutionException.class, () -> await(preparation));
        assertSame(failure, ExceptionUtil.getRootCause(thrown));
        var readModel = service.getTradeReadModel(TRADE_ID);
        assertInstanceOf(TradeInitialized.class, readModel.getExecutionState());
        assertTrue(readModel.getPreparationError().isEmpty());
        assertTrue(readModel.getPersistenceError().isEmpty());
        assertEquals(1, savedRecord(service, TRADE_ID).pendingEffects().size());
    }

    @Test
    void aReadModelObserverFailureDoesNotFailTradePreparation() throws Exception {
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String tradeId, MuSigTradeReadModel readModel) {
                throw new IllegalStateException("Observer failed");
            }
        });
        try {
            var preparation = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
            var created = await(preparation);

            assertInstanceOf(TradeInitialized.class, created.getExecution().state());
            assertSame(service.getTradeReadModel(TRADE_ID), service.getTradeReadModels().get(created.getId()));
            assertTrue(service.getTradeReadModel(TRADE_ID).getPreparationError().isEmpty());
            assertSame(created, savedRecord(service, created.getId()).trade());
        } finally {
            pin.unbind();
        }
    }

    @ParameterizedTest
    @CsvSource({"BUY, BUYER_AS_TAKER", "SELL, SELLER_AS_TAKER"})
    void takerRoleComesFromTheOffer(Direction direction, TradeRole role) throws Exception {
        when(muSigContract.getOffer().getTakersDirection()).thenReturn(direction);
        var created = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        assertEquals(role, created.getMyRole());

        await(resumeLane(service, created.getId()));
        var started = savedRecord(service, created.getId()).trade();
        assertEquals(role, started.getMyRole());
        assertInstanceOf(TradeInitialized.class, started.getExecution().state());
        verify(blockingStub()).initTrade(argThat(request -> request.getMyRole().name().equals(role.name())));
    }

    @Test
    void takingAnOfferCreatesAndStartsTheTradeOnTheWorkerPool() throws Exception {
        var trade = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));

        assertEquals(TRADE_ID, trade.getId());
        assertEquals(TRADE_ID.substring(0, 8), trade.getShortId());
        assertInstanceOf(TradeInitialized.class, trade.getExecution().state());
        assertTrue(trade.getExecution().data().getMyContractSignature().isPresent());
        var send = assertInstanceOf(Effect.SendMessage.class, serviceEffects.element());
        assertInstanceOf(SetupTradeMessage_A.class, send.message());
        assertSame(peerNetworkId, send.receiver());
        assertEquals(List.of(Res.encode("muSig.trade.protocol.logMessage.takeOffer", "someone", "someone", "short"),
                "Taker (as seller) created his pubKeyShares.\nTaker (as seller) sent his pubKeyShares to maker."),
                List.copyOf(serviceLogs));
        verify(services.getChatService().getMuSigOpenTradeChannelService(), times(3)).findChannelByTradeId(TRADE_ID);
        assertTrue(effectThreads.stream().allMatch(name -> name.startsWith("MuSigTradeServiceV2")));
        assertSame(trade, savedRecord(service, TRADE_ID).trade());

        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)))).getCause());
        verify(blockingStub(), times(1)).initTrade(any());
    }

    @Test
    void aMissingChatChannelSkipsLogsWithoutFailingTheTrade() throws Exception {
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(TRADE_ID)).thenReturn(Optional.of(channel), Optional.empty());
        var trade = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        assertInstanceOf(TradeInitialized.class, trade.getExecution().state());
        verify(channels, never()).sendTradeLogMessage(anyString(), any());
    }

    @Test
    void aChatLogExceptionDoesNotFailTheTrade() throws Exception {
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        doThrow(new IllegalStateException("Chat unavailable"))
                .when(channels).sendTradeLogMessage(anyString(), eq(channel));

        var trade = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));

        assertInstanceOf(TradeInitialized.class, trade.getExecution().state());
        assertTrue(trade.getExecution().data().getMyFailure().isEmpty());
        assertEquals(1, serviceEffects.size());
        verify(channels, times(2)).sendTradeLogMessage(anyString(), eq(channel));
    }

    @Test
    void chatDeliveryDoesNotHoldUpTradeProcessing() throws Exception {
        var delivery = new CompletableFuture<SendMessageResult>();
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        doReturn(delivery).when(channels).sendTradeLogMessage(anyString(), eq(channel));

        var trade = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));

        assertInstanceOf(TradeInitialized.class, trade.getExecution().state());
        assertFalse(delivery.isDone());
        verify(channels, times(2)).sendTradeLogMessage(anyString(), eq(channel));
    }

    @Test
    void aSlowTradeDoesNotBlockItsCallerOrAnotherTrade() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(blockingStub().initTrade(any())).thenAnswer(invocation -> {
            PubKeySharesRequest request = invocation.getArgument(0);
            if (request.getTradeId().equals(TRADE_ID)) {
                entered.countDown();
                assertTrue(release.await(10, TimeUnit.SECONDS));
            }
            return PubKeySharesResponse.getDefaultInstance();
        });
        var slow = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var sameTradeMessage = submitMessage(service, setupMessage());
            assertFalse(slow.isDone());
            assertFalse(sameTradeMessage.isDone());

            var laterContract = muSigContract(peerAccountPayload);
            when(laterContract.getTakeOfferDate()).thenReturn(TAKE_OFFER_DATE + 1);
            takeOfferDate.set(TAKE_OFFER_DATE + 1);
            var other = initializeTrade(service, initializeTrade(laterContract, myIdentity, myAccountPayload));
            assertInstanceOf(TradeInitialized.class, await(other).getExecution().state());
            assertFalse(slow.isDone());

            release.countDown();
            assertInstanceOf(TradeInitialized.class, await(slow).getExecution().state());
            await(sameTradeMessage);
            assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                    service.getTradeReadModel(TRADE_ID).getExecutionState());
        } finally {
            release.countDown();
        }
    }

    @Test
    void anEarlyPaymentConfirmationFailsAndNeedsANewCommandAfterThePeerMessage() throws Exception {
        completeSetup();
        var before = savedRecord(service, TRADE_ID).trade();

        assertFailure(MuSigProtocolException.class, service.paymentReceiptConfirmed(TRADE_ID));

        assertSame(before, savedRecord(service, TRADE_ID).trade());
        await(submitMessage(service, paymentInitiatedMessage()));
        var paymentInitiated = savedRecord(service, TRADE_ID).trade();
        assertInstanceOf(PaymentInitiatedMessageReceived.class, paymentInitiated.getExecution().state());
        await(resumeLane(service, TRADE_ID));
        assertSame(paymentInitiated, savedRecord(service, TRADE_ID).trade());
        verify(blockingStub(), never()).signSwapTx(argThat(request -> request.getSellerReadyToRelease()));
        assertFalse(serviceEffects.stream().anyMatch(effect -> effect instanceof Effect.SendMessage<?, ?> send &&
                send.message() instanceof PaymentReceivedMessage_F));

        await(service.paymentReceiptConfirmed(TRADE_ID));
        assertInstanceOf(PaymentReceiptConfirmed.class,
                savedRecord(service, TRADE_ID).trade().getExecution().state());
        verify(blockingStub(), times(1)).signSwapTx(argThat(request -> request.getSellerReadyToRelease()));
    }

    @Test
    void earlyMessagesReleaseTheWorkerAndRetryWhenALaterMessageAdvancesTheTrade() throws Exception {
        var initialized = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(service.onDepositTxConfirmed(TRADE_ID));
        assertSame(initialized, savedRecord(service, TRADE_ID).trade());
        await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
        assertSame(initialized, savedRecord(service, TRADE_ID).trade());
        await(submitMessage(service, depositSetupMessage()));
        assertSame(initialized, savedRecord(service, TRADE_ID).trade());

        await(submitMessage(service, setupMessage()));
        var confirmed = savedRecord(service, TRADE_ID).trade();
        assertInstanceOf(DepositTxConfirmed.class, confirmed.getExecution().state());
        await(submitMessage(service, setupMessage()));
        assertSame(confirmed, savedRecord(service, TRADE_ID).trade());
        verify(blockingStub(), times(1)).getNonceShares(any());
        verify(blockingStub(), times(1)).signDepositTx(any());
    }

    @Test
    void immediateBackendConfirmationIsQueuedAfterTheCommittedAccountPayloadStep() throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        await(submitMessage(service, depositSetupMessage()));
        var stub = grpcClient.getAsyncStub();
        verify(stub, never()).subscribeTxConfirmationStatus(any(), any());
        var beforeConfirmation = new AtomicReference<MuSigV2Trade<?>>();
        var afterEnqueuingConfirmation = new AtomicReference<MuSigV2Trade<?>>();
        var afterConfirmation = new AtomicReference<CompletableFuture<MuSigTradeExecutionState>>();
        doAnswer(invocation -> {
            SubscribeTxConfirmationStatusRequest request = invocation.getArgument(0);
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            assertEquals(TRADE_ID, request.getTradeId());
            beforeConfirmation.set(savedRecord(service, TRADE_ID).trade());
            responses.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            afterEnqueuingConfirmation.set(savedRecord(service, TRADE_ID).trade());
            afterConfirmation.set(whenExecutionState(service, TRADE_ID, DepositTxConfirmed.class));
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
        var accountReceived = beforeConfirmation.get();
        assertInstanceOf(AccountPayloadReceived.class, accountReceived.getExecution().state());
        assertSame(accountReceived, afterEnqueuingConfirmation.get());
        assertInstanceOf(DepositTxConfirmed.class, await(afterConfirmation.get()));
        var confirmed = savedRecord(service, TRADE_ID).trade();
        await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
        assertSame(confirmed, savedRecord(service, TRADE_ID).trade());
        verify(stub, times(1)).subscribeTxConfirmationStatus(any(), any());
    }

    @Test
    void aBackendThreadOnlyEnqueuesConfirmationAndTheTradeWorkerHandlesIt() throws Exception {
        var responses = new AtomicReference<StreamObserver<TxConfirmationStatus>>();
        var subscription = new AtomicReference<Context>();
        var stub = grpcClient.getAsyncStub();
        doAnswer(invocation -> {
            responses.set(invocation.getArgument(1));
            subscription.set(Context.current());
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        await(submitMessage(service, depositSetupMessage()));
        await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
        int logsBeforeConfirmation = serviceLogs.size();

        // This thread stands in for gRPC's callback thread, outside the trade's worker pool.
        responses.get().onNext(TxConfirmationStatus.getDefaultInstance());
        assertInstanceOf(AccountPayloadReceived.class,
                await(whenExecutionState(service, TRADE_ID, AccountPayloadReceived.class)));
        responses.get().onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
        responses.get().onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(2).build());

        assertInstanceOf(DepositTxConfirmed.class,

                await(whenExecutionState(service, TRADE_ID, DepositTxConfirmed.class)));
        assertEquals(logsBeforeConfirmation + 1, serviceLogs.size());
        assertTrue(logThreads.stream().allMatch(name -> name.startsWith("MuSigTradeServiceV2")));
        assertTrue(subscription.get().isCancelled());
    }

    @Test
    void subscriptionStartupFailureRetainsTheEffectAndPausesProcessingUntilRetrySucceeds() throws Exception {
        var stub = grpcClient.getAsyncStub();
        var failure = new IllegalStateException("backend unavailable");
        doThrow(failure).when(stub).subscribeTxConfirmationStatus(any(), any());
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        await(submitMessage(service, depositSetupMessage()));

        assertSame(failure, assertThrows(ExecutionException.class,
                () -> await(submitMessage(service, accountPayloadMessage(peerAccountPayload)))).getCause());
        var saved = savedRecord(service, TRADE_ID);
        var accountReceived = saved.trade();
        assertInstanceOf(AccountPayloadReceived.class, accountReceived.getExecution().state());
        assertTrue(saved.pendingEffects().stream().anyMatch(ObserveDepositTxConfirmation.class::isInstance));

        assertSame(failure, assertThrows(ExecutionException.class,
                () -> await(service.requestMediation(TRADE_ID))).getCause());
        assertTrue(savedRecord(service, TRADE_ID).pendingInputs().isEmpty());
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> await(service.onDepositTxConfirmed(TRADE_ID))).getCause());
        assertSame(accountReceived, savedRecord(service, TRADE_ID).trade());
        assertEquals(saved.processedEventTypes(), savedRecord(service, TRADE_ID).processedEventTypes());
        assertTrue(savedRecord(service, TRADE_ID).pendingInputs().stream()
                .anyMatch(input -> input.event() instanceof DepositTxConfirmedEvent));

        doAnswer(invocation -> null).when(stub).subscribeTxConfirmationStatus(any(), any());

        await(resumeLane(service, TRADE_ID));
        assertInstanceOf(DepositTxConfirmed.class, savedRecord(service, TRADE_ID).trade().getExecution().state());
        assertTrue(savedRecord(service, TRADE_ID).pendingInputs().isEmpty());
        assertTrue(savedRecord(service, TRADE_ID).pendingEffects().isEmpty());
        verify(blockingStub(), times(1)).signDepositTx(any());
    }

    @Test
    void automaticPeerRepliesAreQueuedAfterTheSendingStep() throws Exception {
        var deliveries = new ConcurrentLinkedQueue<CompletableFuture<Void>>();
        var paymentReady = new CompletableFuture<Void>();
        var closed = new CompletableFuture<Void>();
        onEffect = (tradeId, effect) -> {
            if (effect instanceof Effect.SendMessage<?, ?> send) {
                switch (send.message()) {
                    case SetupTradeMessage_A ignored -> deliveries.add(submitMessage(service, setupMessage()));
                    case SetupTradeMessage_C ignored -> deliveries.add(submitMessage(service, depositSetupMessage()));
                    case SendAccountPayloadAndDepositTxMessage ignored -> {
                        deliveries.add(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
                        deliveries.add(service.onDepositTxConfirmed(tradeId));
                        var payment = submitMessage(service, paymentInitiatedMessage());
                        deliveries.add(payment);
                        payment.whenComplete((unused, failure) -> complete(paymentReady, failure));
                    }
                    case PaymentReceivedMessage_F ignored -> {
                        // The outgoing step must finish its logs before its immediate reply runs.
                        assertEquals(7, serviceLogs.size());
                        var closure = submitMessage(service, new CooperativeClosureMessage_G(uid(),
                                TRADE_ID,
                                "1.0.0",
                                peerNetworkId(),
                                myNetworkId(),
                                new ByteArray(new byte[]{3})));
                        deliveries.add(closure);
                        closure.whenComplete((unused, failure) -> complete(closed, failure));
                    }
                    default -> { }
                }
            }
        };

        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(paymentReady);
        await(service.paymentReceiptConfirmed(TRADE_ID));
        await(closed);
        assertInstanceOf(TradeClosed.class, service.getTradeReadModel(TRADE_ID).getExecutionState());
        for (var delivery : deliveries) {
            await(delivery);
        }
        assertEquals(10, serviceLogs.size());
        verify(blockingStub(), times(1)).closeTrade(any());
    }

    @Test
    void anUnavailableCommandSubmittedByACallbackOnlyFailsItsOwnFuture() throws Exception {
        var confirmation = new AtomicReference<CompletableFuture<Void>>();
        onEffect = (tradeId, effect) -> {
            if (effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof SetupTradeMessage_A) {
                var submitted = service.paymentReceiptConfirmed(tradeId);
                confirmation.set(submitted);
                assertFalse(submitted.isDone());
            }
        };

        var initialized = await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));

        assertInstanceOf(TradeInitialized.class, initialized.getExecution().state());
        var rejection = assertInstanceOf(MuSigProtocolException.class,
                assertThrows(ExecutionException.class, () -> await(confirmation.get())).getCause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, rejection.getCategory());
        assertSame(initialized, savedRecord(service, TRADE_ID).trade());
        verify(blockingStub(), times(1)).initTrade(any());
        verify(blockingStub(), never()).signSwapTx(argThat(request -> request.getSellerReadyToRelease()));

        await(submitMessage(service, setupMessage()));
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                savedRecord(service, TRADE_ID).trade().getExecution().state());
    }

    @Test
    void executionAndMediationUseTheSameQueue() throws Exception {
        NetworkId mediatorNetworkId = networkId("mediator-profile");
        UserProfile mediator = mock(UserProfile.class);
        when(mediator.getNetworkId()).thenReturn(mediatorNetworkId);
        when(mediator.toProto(anyBoolean())).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        onEffect = (tradeId, effect) -> {
            if (effect instanceof Effect.SendMessage<?, ?> send && send.message() instanceof SendAccountPayloadAndDepositTxMessage) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };
        var execution = submitMessage(service, depositSetupMessage())
                .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var requested = service.requestMediation(TRADE_ID)
                    .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
            var opened = submitMessage(service, new MuSigMediationStateChangeMessage(uid(),
                    TRADE_ID,
                    mediatorNetworkId,
                    MediationCaseState.OPEN,
                    Optional.empty(),
                    Optional.empty()))
                    .thenApply(ignored -> savedRecord(service, TRADE_ID).trade());
            assertFalse(requested.isDone());
            assertFalse(opened.isDone());
            release.countDown();

            assertInstanceOf(MuSigMediationState.Init.class, await(execution).getMediation().orElseThrow().state());
            assertInstanceOf(MuSigMediationState.Requested.class, await(requested).getMediation().orElseThrow().state());
            assertInstanceOf(MuSigMediationState.Open.class, await(opened).getMediation().orElseThrow().state());
            assertSame(await(execution).getExecution(), await(opened).getExecution());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedCommandsDoNotPoisonTheQueue() throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        assertFailure(MuSigProtocolException.class, service.requestMediation(TRADE_ID));
        assertFailure(MuSigProtocolException.class, service.acceptMediationResult(TRADE_ID));
        assertFailure(MuSigProtocolException.class, service.rejectMediationResult(TRADE_ID));
        await(submitMessage(service, setupMessage()));
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                savedRecord(service, TRADE_ID).trade().getExecution().state());
    }

    @Test
    void aFailedStartRemainsInspectableAndDoesNotAllowASecondOwner() throws Exception {
        when(blockingStub().initTrade(any())).thenThrow(new IllegalStateException("backend unavailable"));
        assertFailure(MuSigProtocolException.class,
                initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        assertInstanceOf(Failed.class,
                await(whenExecutionState(service, TRADE_ID, Failed.class)));
        assertInstanceOf(Failed.class, service.getTradeReadModel(TRADE_ID).getExecutionState());
        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)))).getCause());
    }

    @Test
    void theScheduledTimeoutRunsThroughTheQueue() throws Exception {
        completeSetup();
        await(submitMessage(service, paymentInitiatedMessage()));
        await(service.paymentReceiptConfirmed(TRADE_ID));
        assertEquals(1, timers.size());

        timers.getFirst().run();

        await(resumeLane(service, TRADE_ID));
        assertInstanceOf(SellerForceClosedTrade.class,
                savedRecord(service, TRADE_ID).trade().getExecution().state());
        assertTrue(savedRecord(service, TRADE_ID).scheduledEvents().isEmpty());
        verify(blockingStub(), times(1)).closeTrade(any());
    }

    @Test
    void shutdownDrainsQueuedWorkAndRejectsNewInputs() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(blockingStub().initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return PubKeySharesResponse.getDefaultInstance();
        });
        var started = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var queued = submitMessage(service, setupMessage());
            var stopped = service.shutdown();
            assertFalse(stopped.isDone());
            assertFailure(RejectedExecutionException.class, service.onDepositTxConfirmed(TRADE_ID));
            assertInstanceOf(RejectedExecutionException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)))).getCause());

            release.countDown();
            await(started);
            await(queued);
            assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                    service.getTradeReadModel(TRADE_ID).getExecutionState());
            assertTrue(stopped.get(10, TimeUnit.SECONDS));
            assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));
            assertFailure(RejectedExecutionException.class, service.initialize());
        } finally {
            release.countDown();
        }
    }

    @Test
    void shutdownCancelsObservationStartedByAlreadyAcceptedWork() throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        var subscription = new AtomicReference<Context>();
        var stub = grpcClient.getAsyncStub();
        doAnswer(invocation -> {
            subscription.set(Context.current());
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        onEffect = (tradeId, effect) -> {
            if (effect instanceof Effect.SendMessage<?, ?> send &&
                    send.message() instanceof SendAccountPayloadAndDepositTxMessage) {
                entered.countDown();
                try {
                    assertTrue(release.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            }
        };
        var deposit = submitMessage(service, depositSetupMessage());
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var accountReceived = submitMessage(service, accountPayloadMessage(peerAccountPayload));
            var stopped = service.shutdown();
            assertFalse(stopped.isDone());
            release.countDown();

            await(deposit);
            await(accountReceived);
            assertInstanceOf(AccountPayloadReceived.class, service.getTradeReadModel(TRADE_ID).getExecutionState());
            assertTrue(stopped.get(10, TimeUnit.SECONDS));
            assertTrue(subscription.get().isCancelled());
        } finally {
            release.countDown();
        }
    }

    @Test
    void commandsAndQueriesForMissingTradesAndUninitializedServicesFailExplicitly() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service.getTradeReadModel(TRADE_ID));
        assertFailure(IllegalStateException.class, service.requestMediation(TRADE_ID));
        assertFailure(IllegalStateException.class, service.onDepositTxConfirmed(TRADE_ID));
        var uninitialized = serviceWithClock(services, grpcClient, serviceClock);
        try {
            assertFailure(IllegalStateException.class, submitMessage(uninitialized, setupMessage()));
            assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(uninitialized, initializeTrade(muSigContract, myIdentity, myAccountPayload)))).getCause());
        } finally {
            uninitialized.shutdown().join();
        }
        assertInstanceOf(TradeInitialized.class,
                await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)))
                        .getExecution().state());
    }

    private void completeSetup() throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)));
        await(submitMessage(service, setupMessage()));
        await(submitMessage(service, depositSetupMessage()));
        await(submitMessage(service, accountPayloadMessage(peerAccountPayload)));
        await(service.onDepositTxConfirmed(TRADE_ID));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void assertFailure(Class<? extends Throwable> type, CompletableFuture<?> future) {
        assertInstanceOf(type,
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause());
    }

    private static void complete(CompletableFuture<Void> future, Throwable failure) {
        if (failure == null) {
            future.complete(null);
        } else {
            future.completeExceptionally(failure);
        }
    }
}
