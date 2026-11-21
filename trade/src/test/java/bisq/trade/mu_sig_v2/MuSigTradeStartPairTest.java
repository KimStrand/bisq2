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

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.ClearnetAddress;
import bisq.common.network.TransportType;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.observable.map.HashMapObserver;
import bisq.common.util.ExceptionUtil;
import bisq.common.util.StringUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.offer.Direction;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.protobuf.Role;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.awaitSavedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.runNextRetry;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenValue;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The start of a trade between two services: the maker is created, the chat is set up before the backend work,
 * duplicate requests, and a shutdown during the start.
 */
class MuSigTradeStartPairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerSetupPrecedesMessagesBufferedBeforeItsRegistration(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        var channel = mock(MuSigOpenTradeChannel.class);
        when(channel.getChatMessages()).thenReturn(new ObservableSet<>());
        doAnswer(invocation -> {
            when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(channel));
            return channel;
        }).when(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        var signatures = bisq.trade.protobuf.PartialSignatures.newBuilder();
        if (buyerAsMaker) {
            signatures.setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5}));
        }
        var early = new SetupTradeMessage_C(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker().identity.getNetworkId(),
                maker.identity.getNetworkId(),
                NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance()),
                PartialSignatures.fromProto(signatures.build()));
        var pending = submitMessage(maker.service, early)
                .thenApply(ignored -> savedRecord(maker.service, tradeId).trade());
        var mediation = submitMessage(maker.service, mediationOpenedMessage())
                .thenApply(ignored -> savedRecord(maker.service, tradeId).trade());
        assertFalse(pending.isDone());
        assertFalse(mediation.isDone());
        verifyNoInteractions(maker.backend);

        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(maker.backend.initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance();
        });
        try {
            var created = submitMessage(maker.service, request())
                    .thenApply(ignored -> savedRecord(maker.service, tradeId).trade());
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
            assertFalse(mediation.isDone());
            release.countDown();

            assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, await(created).getExecution().state());
            assertInstanceOf(PartialSignaturesCreatedAndDepositTxSigned.class, await(pending).getExecution().state());
            assertInstanceOf(PartialSignaturesCreatedAndDepositTxSigned.class, await(mediation).getExecution().state());
            assertEquals(List.of(SetupTradeMessage_B.class, SetupTradeMessage_D.class),
                    messages.stream().map(Object::getClass).toList());
            var order = inOrder(maker.backend);
            order.verify(maker.backend).initTrade(any());
            order.verify(maker.backend).getNonceShares(any());
            order.verify(maker.backend).getPartialSignatures(any());
            order.verify(maker.backend).signDepositTx(any());
        } finally {
            release.countDown();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theRegisteredListenerCreatesAMakerWithoutBlockingOrRepeatingADuplicateRequest(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        automaticDelivery = false;
        var maker = maker();
        var available = new CompletableFuture<MuSigTradeReadModel>();
        var models = new ConcurrentLinkedQueue<MuSigTradeReadModel>();
        var pin = maker.service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String id, MuSigTradeReadModel readModel) {
                models.add(readModel);
                available.complete(readModel);
            }
        });
        when(maker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(new ObservableSet<>());

        var listener = ArgumentCaptor.forClass(ConfidentialMessageService.Listener.class);
        verify(maker.services.getNetworkService()).subscribeToConfidentialMessages(listener.capture());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(maker.backend.initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance();
        });
        try {
            var request = request();
            listener.getValue().onMessage(request);
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            var readModel = await(available);
            assertSame(readModel, maker.service.getTradeReadModel(tradeId));
            var duplicate = submitMessage(maker.service, request);
            assertFalse(duplicate.isDone());

            release.countDown();
            await(duplicate);
            assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, readModel.getExecutionState());
            assertEquals(List.of(readModel), List.copyOf(models));
            assertEquals(1, messages.stream().filter(SetupTradeMessage_B.class::isInstance).count());
            verify(maker.backend, times(1)).initTrade(any());
        } finally {
            release.countDown();
            pin.unbind();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void eitherMakerRoleCompletesATradeWithAutomaticDelivery(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        startTrade();
        var buyerTrade = savedRecord(buyer.service, tradeId).trade();
        var sellerTrade = savedRecord(seller.service, tradeId).trade();
        assertEquals(buyerAsMaker ? TradeRole.BUYER_AS_MAKER : TradeRole.BUYER_AS_TAKER, buyerTrade.getMyRole());
        assertEquals(buyerAsMaker ? TradeRole.SELLER_AS_TAKER : TradeRole.SELLER_AS_MAKER, sellerTrade.getMyRole());
        assertInstanceOf(AccountPayloadReceived.class, buyerTrade.getExecution().state());
        assertInstanceOf(AccountPayloadReceived.class, sellerTrade.getExecution().state());
        assertEquals(seller.account.getAccountPayload(),
                buyerTrade.getExecution().data().getPeerAccountPayload().orElseThrow());
        assertEquals(buyer.account.getAccountPayload(),
                sellerTrade.getExecution().data().getPeerAccountPayload().orElseThrow());
        var makerTrade = buyerAsMaker ? buyerTrade : sellerTrade;
        assertArrayEquals(new byte[]{(byte) (buyerAsMaker ? 2 : 1)},
                makerTrade.getExecution().data().getPeerDepositTx().orElseThrow().getBytes());
        assertTrue(contractService.verifyContractSignature(contract,
                buyerTrade.getExecution().data().getPeerContractSignature().orElseThrow()));
        assertTrue(contractService.verifyContractSignature(contract,
                sellerTrade.getExecution().data().getPeerContractSignature().orElseThrow()));
        var messageC = sent(SetupTradeMessage_C.class);
        var messageD = sent(SetupTradeMessage_D.class);
        assertEquals(buyerAsMaker, messageC.getPartialSignatures().getSwapTxInputPartialSignature().isPresent());
        assertEquals(buyerAsMaker, messageD.getPartialSignatures().getSwapTxInputPartialSignature().isEmpty());
        assertTrue(sellerTrade.getExecution().data().getPeerRedactedPartialSignatures().isPresent());
        assertTrue(sellerTrade.getExecution().data().getPeerPartialSignatures().isEmpty());
        assertTrue(buyerTrade.getExecution().data().getPeerPartialSignatures().isPresent());
        verify(buyer.backend).initTrade(argThat(request ->
                request.getMyRole() == (buyerAsMaker ? Role.BUYER_AS_MAKER : Role.BUYER_AS_TAKER)));
        verify(seller.backend).initTrade(argThat(request ->
                request.getMyRole() == (buyerAsMaker ? Role.SELLER_AS_TAKER : Role.SELLER_AS_MAKER)));
        verify(maker().backend, never()).publishDepositTx(any());
        verify(taker().backend).publishDepositTx(any());

        buyer.confirmDeposit();
        seller.confirmDeposit();
        assertInstanceOf(DepositTxConfirmed.class,
                await(whenExecutionState(buyer.service, tradeId, DepositTxConfirmed.class)));
        assertInstanceOf(DepositTxConfirmed.class,
                await(whenExecutionState(seller.service, tradeId, DepositTxConfirmed.class)));
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        assertArrayEquals(new byte[]{5}, savedRecord(seller.service, tradeId).trade().getExecution().data()
                .getPeerPartialSignatures().orElseThrow().getSwapTxInputPartialSignature().orElseThrow());
        verify(seller.backend).signSwapTx(argThat(request -> !request.getSellerReadyToRelease()
                && request.getSwapTxInputPeersPartialSignature().equals(ByteString.copyFrom(new byte[]{5}))));
        await(seller.service.paymentReceiptConfirmed(tradeId));
        awaitDelivery(seller, CooperativeClosureMessage_G.class);
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(buyer.service, tradeId, TradeClosed.class)));
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(seller.service, tradeId, TradeClosed.class)));
        verify(buyer.backend).closeTrade(argThat(request ->
                request.getMyOutputPeersPrvKeyShare().equals(ByteString.copyFrom(new byte[]{7}))));
        verify(seller.backend).closeTrade(argThat(request ->
                request.getMyOutputPeersPrvKeyShare().equals(ByteString.copyFrom(new byte[]{8}))));
        assertEquals(List.of(SetupTradeMessage_A.class,
                SetupTradeMessage_B.class,
                SetupTradeMessage_C.class,
                SetupTradeMessage_D.class,
                SendAccountPayloadAndDepositTxMessage.class,
                SendAccountPayloadMessage.class,
                PaymentInitiatedMessage_E.class,
                PaymentReceivedMessage_F.class,
                CooperativeClosureMessage_G.class), messages.stream().map(Object::getClass).toList());
        assertTrue(messageThreads.stream().allMatch(name -> name.startsWith("MuSigTradeServiceV2")));
        assertTrue(buyer.saved().pendingInputs().isEmpty());
        assertTrue(seller.saved().pendingInputs().isEmpty());
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
    }

    @Test
    void aMakerWhoseAddressesChangedSinceItPublishedTheOfferCompletesSetup() throws Exception {
        // The offer carries the maker's key with the addresses it had before it added a transport.
        var current = buyer.identity.getNetworkId();
        configureOffer(Direction.BUY, new NetworkId(new AddressByTransportTypeMap(Map.of(TransportType.CLEAR,
                new ClearnetAddress("127.0.0.1", 19921))), current.getPubKey()));
        assertNotEquals(current, offer.getMakerNetworkId());

        startTrade();

        assertInstanceOf(AccountPayloadReceived.class, savedRecord(buyer.service, tradeId).trade().getExecution().state());
        assertInstanceOf(AccountPayloadReceived.class, savedRecord(seller.service, tradeId).trade().getExecution().state());
        // The maker sends under the network ID which the taker stored for it: the one of the offer.
        var sendersOfTheMaker = messages.stream()
                .map(MuSigTradeMessage::getSender)
                .filter(sender -> sender.getId().equals(current.getId()))
                .toList();
        assertFalse(sendersOfTheMaker.isEmpty());
        sendersOfTheMaker.forEach(sender -> assertEquals(offer.getMakerNetworkId(), sender));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerCreatesChatAfterCheckpointingAndBeforeBackendPreparation(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        var channel = mock(MuSigOpenTradeChannel.class);
        doAnswer(invocation -> {
            var saved = maker.saved();
            assertInstanceOf(Init.class, saved.trade().getExecution().state());
            assertTrue(saved.processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).isEmpty());
            assertEquals(1, saved.pendingInputs().size());
            assertInstanceOf(SetupTradeMessage_A.class, saved.pendingInputs().getFirst());
            assertTrue(saved.pendingEffects().isEmpty());
            verify(maker.backend, never()).initTrade(any());
            verify(maker.backend, never()).getNonceShares(any());
            assertTrue(messages.isEmpty());
            assertTrue(maker.logs.isEmpty());
            assertEquals(tradeId, invocation.getArgument(0));
            assertEquals(maker.identity, invocation.<UserIdentity>getArgument(1).getIdentity());
            assertEquals(taker().profile.getId(), invocation.<UserProfile>getArgument(2).getId());
            assertEquals(contract.getMediator(), invocation.getArgument(3));
            assertEquals(contract.getArbitrator(), invocation.getArgument(4));
            when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(channel));
            return channel;
        }).when(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());

        var request = request();
        await(submitMessage(maker.service, request));

        var order = inOrder(channels, channels.getPersistence(), maker.backend);
        // The check before the trade is created, then the lookup of the chat setup.
        order.verify(channels, times(2)).findChannelByTradeId(tradeId);
        order.verify(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        order.verify(channels.getPersistence()).persistAsync(any());
        order.verify(maker.backend).initTrade(any());
        order.verify(maker.backend).getNonceShares(any());
        assertEquals(List.of(SetupTradeMessage_B.class), messages.stream().map(Object::getClass).toList());
        assertEquals(1, maker.logs.size());
        assertTrue(maker.saved().pendingEffects().isEmpty());

        await(submitMessage(maker.service, request));
        verify(channels, times(1)).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        assertEquals(1, messages.size());
        assertEquals(1, maker.logs.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void takerWaitsForChatPersistenceBeforeBackendPreparation(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var taker = taker();
        var channels = taker.services.getChatService().getMuSigOpenTradeChannelService();
        var chatPersistence = channels.getPersistence();
        var saving = new CountDownLatch(1);
        var chatSaved = new CompletableFuture<Void>();
        doAnswer(invocation -> {
            var saved = taker.saved();
            assertInstanceOf(Init.class, saved.trade().getExecution().state());
            assertTrue(saved.processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).isEmpty());
            assertEquals(1, saved.pendingInputs().size());
            assertInstanceOf(MuSigTakeOfferEvent.class, saved.pendingInputs().getFirst());
            assertTrue((saved.toCheckpoint().pendingInputs().getFirst() instanceof bisq.common.processing.Command));
            assertTrue(saved.pendingEffects().isEmpty());
            saving.countDown();
            return chatSaved;
        }).when(chatPersistence).persistAsync(any());

        var preparation = initializeTrade(taker.service, initializeTrade(contract,
                taker.identity,
                taker.account.getAccountPayload()));
        try {
            assertTrue(saving.await(15, TimeUnit.SECONDS));
            assertFalse(preparation.isDone());
            verify(taker.backend, never()).initTrade(any());

            chatSaved.complete(null);
            var prepared = await(preparation);

            var order = inOrder(channels, channels.getPersistence(), taker.backend);
            order.verify(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
            order.verify(channels.getPersistence()).persistAsync(any());
            order.verify(taker.backend).initTrade(any());
            assertInstanceOf(SetupTradeMessage_A.class, messages.peek());
        } finally {
            chatSaved.complete(null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aMakerRejectsARequestIfAChatChannelWithItsTradeIdExists(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        // A new trade creates its own channel. On a node which is also a mediator or an arbitrator, a request can
        // create a channel for any trade ID before the trade exists.
        when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(mock(MuSigOpenTradeChannel.class)));

        var failure = assertFailure(TradeProtocolException.class, submitMessage(maker.service, request()));

        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE, failure.getTradeProtocolFailure());
        verify(channels, never()).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        verifyNoInteractions(maker.backend);
        // The taker gets the usual reason, which does not mention the chat.
        assertEquals(List.of(MuSigReportErrorMessage.class), messages.stream().map(Object::getClass).toList());
        assertEquals(failure.getMessage(), sent(MuSigReportErrorMessage.class).getErrorMessage());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "false,false", "true,true", "false,true"})
    void makerChatFailureRetainsTheInitialMessageBeforeBackendWork(boolean buyerAsMaker,
                                                                   boolean restart) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        var failure = new IllegalStateException("Chat creation failed");
        var chatReady = new AtomicBoolean();
        doAnswer(invocation -> {
            if (!chatReady.get()) {
                throw failure;
            }
            return mock(MuSigOpenTradeChannel.class);
        }).when(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        var request = request();

        var thrown = assertThrows(ExecutionException.class, () -> await(submitMessage(maker.service, request)));
        assertSame(failure, thrown.getCause());
        var readModel = maker.service.getTradeReadModel(tradeId);
        assertEquals(Optional.of("Chat creation failed"), readModel.getPreparationError());
        assertTrue(readModel.getPersistenceError().isEmpty());
        var saved = maker.saved();
        assertInstanceOf(MuSigTradeState.Active.class, saved.trade().getState());
        assertInstanceOf(Init.class, saved.trade().getExecution().state());
        assertTrue(saved.processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).isEmpty());
        assertEquals(1, saved.pendingInputs().size());
        assertEquals(request, saved.pendingInputs().getFirst());
        assertTrue(saved.pendingEffects().isEmpty());
        verify(maker.backend, never()).initTrade(any());
        verify(maker.backend, never()).getNonceShares(any());
        assertTrue(messages.isEmpty());
        assertTrue(maker.logs.isEmpty());

        if (restart) {
            await(maker.service.shutdown());
            var restoredFailure = assertThrows(ExecutionException.class, () -> maker.start(taker()));
            assertSame(failure, ExceptionUtil.getRootCause(restoredFailure));
            var restoredReadModel = maker.service.getTradeReadModel(tradeId);
            assertEquals(Optional.of("Chat creation failed"), restoredReadModel.getPreparationError());
            assertTrue(restoredReadModel.getPersistenceError().isEmpty());
        }
        chatReady.set(true);
        runNextRetry(maker.retries);
        awaitSavedRecord(maker.service, tradeId, record -> record.pendingInputs().isEmpty() && record.pendingEffects().isEmpty());
        await(whenValue(maker.service.getTradeReadModel(tradeId).preparationErrorObservable(), Optional::isEmpty));

        assertTrue(maker.service.getTradeReadModel(tradeId).getPreparationError().isEmpty());
        assertEquals(1, messages.size());
        assertInstanceOf(SetupTradeMessage_B.class, messages.peek());
        assertTrue(maker.saved().pendingEffects().isEmpty());
        verify(maker.backend, times(1)).initTrade(any());
        verify(maker.backend, times(1)).getNonceShares(any());
        verify(channels, times(restart ? 3 : 2)).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());

        await(submitMessage(maker.service, request));
        assertEquals(1, messages.size());
        verify(channels, times(restart ? 3 : 2)).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void makerRetriesAFailedChatWriteBeforeStartingBackendWork(boolean restart) throws Exception {
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        var chatPersistence = channels.getPersistence();
        var channel = mock(MuSigOpenTradeChannel.class);
        doAnswer(invocation -> {
            when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(channel));
            return channel;
        }).when(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        var saving = new CountDownLatch(1);
        var chatSaved = new CompletableFuture<Void>();
        doAnswer(invocation -> {
            saving.countDown();
            return chatSaved;
        }).when(chatPersistence).persistAsync(any());

        var submitted = submitMessage(maker.service, request());
        try {
            assertTrue(saving.await(15, TimeUnit.SECONDS));
            assertFalse(submitted.isDone());
            assertTrue(messages.isEmpty());

            var failure = new IllegalStateException("Chat write failed");
            chatSaved.completeExceptionally(failure);
            var thrown = assertThrows(ExecutionException.class, () -> await(submitted));
            assertSame(failure, ExceptionUtil.getRootCause(thrown));
            var readModel = maker.service.getTradeReadModel(tradeId);
            assertEquals(Optional.of("Chat write failed"), readModel.getPreparationError());
            assertTrue(readModel.getPersistenceError().isEmpty());
            var saved = maker.saved();
            assertInstanceOf(Init.class, saved.trade().getExecution().state());
            assertTrue(saved.pendingEffects().isEmpty());
            assertEquals(1, saved.pendingInputs().size());
            assertInstanceOf(SetupTradeMessage_A.class, saved.pendingInputs().getFirst());
            verify(maker.backend, never()).initTrade(any());
            verify(maker.backend, never()).getNonceShares(any());
            assertTrue(messages.isEmpty());

            when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
            if (restart) {
                await(maker.service.shutdown());
                maker.start(taker());
            } else {
                runNextRetry(maker.retries);
                awaitSavedRecord(maker.service, tradeId, record -> record.pendingInputs().isEmpty() && record.pendingEffects().isEmpty());
                await(whenValue(maker.service.getTradeReadModel(tradeId).preparationErrorObservable(), Optional::isEmpty));
            }

            assertTrue(maker.service.getTradeReadModel(tradeId).getPreparationError().isEmpty());
            assertEquals(1, messages.size());
            assertInstanceOf(SetupTradeMessage_B.class, messages.peek());
            assertTrue(maker.saved().pendingEffects().isEmpty());
            verify(channels, times(1)).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
            verify(chatPersistence, times(2)).persistAsync(any());
            verify(maker.backend, times(1)).initTrade(any());
            verify(maker.backend, times(1)).getNonceShares(any());
        } finally {
            chatSaved.complete(null);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerBackendFailureKeepsThePreparedChat(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        when(maker.backend.getNonceShares(any())).thenThrow(new IllegalStateException("Backend unavailable"));

        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, request()));

        assertInstanceOf(Failed.class, maker.saved().trade().getExecution().state());
        var readModel = maker.service.getTradeReadModel(tradeId);
        assertTrue(readModel.getMyFailure().isPresent());
        assertTrue(readModel.getPreparationError().isEmpty());
        verify(channels).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        verify(channels.getPersistence()).persistAsync(any());
        assertTrue(messages.stream().noneMatch(SetupTradeMessage_B.class::isInstance));
    }

    @Test
    void concurrentDuplicateRequestsCreateOnlyOneMakerOwner() throws Exception {
        automaticDelivery = false;
        var first = request();
        var second = new SetupTradeMessage_A(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                first.getSender(),
                first.getReceiver(),
                contract,
                first.getContractSignatureData(),
                first.getPubKeyShares());
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseNextRequestValidation(validating, release);
        var one = submitMessage(buyer.service, first);
        try (var callers = Executors.newSingleThreadExecutor()) {
            MuSigV2Trade<?> created;
            try {
                assertTrue(validating.await(15, TimeUnit.SECONDS));
                var submission = CompletableFuture.supplyAsync(() -> submitMessage(buyer.service, second), callers);
                var duplicate = await(submission);
                assertFalse(duplicate.isDone());
                assertFalse(one.isDone());
                release.countDown();
                await(duplicate);
                created = savedRecord(buyer.service, tradeId).trade();
            } finally {
                release.countDown();
            }
            await(one);
            assertSame(created, savedRecord(buyer.service, tradeId).trade());
        }
        verify(buyer.backend, times(1)).initTrade(any());
        verify(buyer.backend, times(1)).getNonceShares(any());
        verify(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), times(1))
                .claimActivatedOffer(offer.getId());
        assertEquals(1, messages.size());
        assertEquals(1, buyer.service.getPersistableStore().getTrades().size());
        assertEquals(Set.of(SetupTradeMessage_A.class),
                buyer.saved().processedEventTypes().getOrDefault(Flow.EXECUTION,
                        Set.of()));
        verify(buyer.services.getChatService().getMuSigOpenTradeChannelService(), times(1))
                .traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
    }

    @Test
    void makerValidationDoesNotBlockInputsForAnExistingTrade() throws Exception {
        await(initializeTrade(seller.service, initializeTrade(contract, seller.identity, seller.account.getAccountPayload())));
        awaitDelivery(seller, SendAccountPayloadMessage.class);
        automaticDelivery = false;
        var nextContract = new MuSigContract(contract.getTakeOfferDate() + 1,
                offer,
                seller.identity.getNetworkId(),
                contract.getBaseSideAmount(),
                contract.getQuoteSideAmount(),
                contract.getNonBtcSidePaymentMethodSpec(),
                contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                contract.getMediator(),
                contract.getArbitrator(),
                contract.getPriceSpec(),
                contract.getMarketPrice());
        var nextRequest = request(nextContract);
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseNextRequestValidation(validating, release);
        var pending = submitMessage(buyer.service, nextRequest);
        try (var callers = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(validating.await(15, TimeUnit.SECONDS));
                var submission = CompletableFuture.supplyAsync(() -> buyer.service.onDepositTxConfirmed(tradeId), callers);
                await(await(submission));
                var trade = savedRecord(buyer.service, tradeId).trade();
                assertInstanceOf(DepositTxConfirmed.class, trade.getExecution().state());
                assertFalse(pending.isDone());
            } finally {
                release.countDown();
            }
            await(pending);
            assertInstanceOf(TradeInitializedAndNonceSharesCreated.class,
                    buyer.service.getTradeReadModel(nextRequest.getTradeId()).getExecutionState());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownDuringMakerValidationDrainsAcceptedInitialization(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseNextRequestValidation(validating, release);
        var pending = submitMessage(maker.service, request());
        try (var callers = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(validating.await(15, TimeUnit.SECONDS));
                var submission = CompletableFuture.supplyAsync(maker.service::shutdown, callers);
                var stopped = await(submission);
                assertFalse(stopped.isDone());
                assertFalse(pending.isDone());
                assertFailure(RejectedExecutionException.class, submitMessage(maker.service, request()));
                release.countDown();
                await(pending);
                assertTrue(await(stopped));
            } finally {
                release.countDown();
            }
        }
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, maker.saved().trade().getExecution().state());
        verify(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), times(1))
                .claimActivatedOffer(offer.getId());
        verify(maker.backend, times(1)).initTrade(any());
        assertEquals(List.of(SetupTradeMessage_B.class), messages.stream().map(Object::getClass).toList());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shutdownDuringTakerPreparationDrainsAcceptedInitialization(boolean buyerAsTaker) throws Exception {
        configureOffer(buyerAsTaker ? Direction.SELL : Direction.BUY);
        automaticDelivery = false;
        var taker = taker();
        var preparing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // Allow router admission, then pause the lane's trading-allowed recheck.
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenAnswer(invocation -> {
                    preparing.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return taker.alerts;
                });
        var command = initializeTrade(contract, taker.identity, taker.account.getAccountPayload());
        var pending = taker.service.initializeTrade(command);
        try (var callers = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(preparing.await(15, TimeUnit.SECONDS));
                var submission = CompletableFuture.supplyAsync(taker.service::shutdown, callers);
                var stopped = await(submission);
                assertFalse(stopped.isDone());
                assertFalse(pending.isDone());
                assertFailure(RejectedExecutionException.class, taker.service.initializeTrade(command));
                release.countDown();
                await(pending);
                assertTrue(await(stopped));
            } finally {
                release.countDown();
            }
        }
        assertInstanceOf(TradeInitialized.class, taker.saved().trade().getExecution().state());
        verify(taker.backend, times(1)).initTrade(any());
        assertEquals(List.of(SetupTradeMessage_A.class), messages.stream().map(Object::getClass).toList());
    }
}
