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

import bisq.account.accounts.AccountOrigin;
import bisq.account.accounts.fiat.RevolutAccount;
import bisq.account.accounts.fiat.RevolutAccountPayload;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.account.timestamp.KeyType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelStore;
import bisq.common.market.Market;
import bisq.common.monetary.PriceQuote;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.ClearnetAddress;
import bisq.common.network.TransportType;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.processing.Effect;
import bisq.common.processing.SendMessage;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.contract.TradeId;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.CollateralOption;
import bisq.offer.price.spec.FixPriceSpec;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.protobuf.CustomPayoutPsbt;
import bisq.trade.protobuf.MusigGrpc;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static bisq.offer.options.OfferOptionUtil.createdSaltedAccountId;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithCapturedTimers;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared setup of the tests which run a buyer and a seller service against each other: both peers with their
 * mocked services, the offer and the contract, and the delivery of messages between them.
 */
class MuSigTradeServicePairFixture {
    private static final String PAYOUT_TX_ID = "12".repeat(32);
    @TempDir
    Path directory;
    final ContractService contractService = new ContractService(null);
    final FiatPaymentMethod paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
    final KeyPair mediatorKeys = KeyGeneration.generateDefaultEcKeyPair();
    private final Map<Peer, Map<Class<?>, CompletableFuture<MuSigV2Trade<?>>>> deliveries = new ConcurrentHashMap<>();
    final ConcurrentLinkedQueue<MuSigTradeMessage> messages = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<MuSigMediationRequest> mediationRequests = new ConcurrentLinkedQueue<>();
    volatile boolean chatActivationFails;
    final ConcurrentLinkedQueue<MuSigDisputeCaseDataMessage> mediationCaseData = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<String> messageThreads = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Void> deliveryFailure = new CompletableFuture<>();
    Peer buyer;
    Peer seller;
    MuSigOffer offer;
    MuSigContract contract;
    MuSigMediationResult mediationResult;
    String tradeId;
    volatile boolean automaticDelivery = true;

    @BeforeAll
    static void registerResolvers() {
        MuSigPersistenceTestSupport.registerResolvers();
    }

    @BeforeEach
    void setUp() throws Exception {
        buyer = new Peer("buyer", 9921);
        seller = new Peer("seller", 9922);
        for (var peer : List.of(buyer, seller)) {
            for (var trader : List.of(buyer, seller)) {
                // Display-name lookup normally uses the application's UserProfileService singleton.
                var profile = spy(trader.profile);
                doReturn(trader.name).when(profile).getUserName();
                when(peer.services.getUserService().getUserProfileService().findUserProfile(trader.profile.getId()))
                        .thenReturn(Optional.of(profile));
            }
        }
        configureOffer(Direction.BUY);
        buyer.start(seller);
        seller.start(buyer);
    }

    void configureOffer(Direction direction) {
        configureOffer(direction, (direction.isBuy() ? buyer : seller).identity.getNetworkId());
    }

    void configureOffer(Direction direction, NetworkId makerNetworkIdInOffer) {
        var maker = direction.isBuy() ? buyer : seller;
        var taker = direction.isBuy() ? seller : buyer;
        var mediator = profile("mediator", networkId("mediator", 9923, mediatorKeys));
        var arbitrator = profile("arbitrator", networkId("arbitrator", 9924, KeyGeneration.generateDefaultEcKeyPair()));
        String offerId = StringUtils.createUid();
        var accountOption = new AccountOption(paymentMethod,
                createdSaltedAccountId(maker.account.getId(), offerId),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                createSaltedAccountPayloadHash(maker.account.getAccountPayload(), offerId));
        var price = PriceQuote.fromFiatPrice(50_000, "USD");
        offer = new MuSigOffer(offerId,
                makerNetworkIdInOffer,
                direction,
                new Market("BTC", "USD", "Bitcoin", "US Dollar"),
                new BaseSideFixedAmountSpec(2_000_000),
                new FixPriceSpec(price),
                List.of(paymentMethod),
                List.of(accountOption, new CollateralOption(0.25, 0.25)),
                "1.0.0");
        contract = new MuSigContract(offer.getDate(),
                offer,
                taker.identity.getNetworkId(),
                2_000_000,
                10_000_000,
                PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "USD"),
                createSaltedAccountPayloadHash(taker.account.getAccountPayload(), offerId),
                Optional.of(mediator),
                Optional.of(arbitrator),
                offer.getPriceSpec(),
                0);
        tradeId = TradeId.create(offerId, taker.identity.getNetworkId().getId(), contract.getTakeOfferDate());
        mediationResult = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(2_400_000L),
                Optional.of(600_000L),
                Optional.empty(),
                Optional.empty());
        when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offerId)).thenReturn(Optional.of(offer));
        when(maker.services.getIdentityService().findAnyIdentityById(maker.identity.getId()))
                .thenReturn(Optional.of(maker.identity));
        when(maker.services.getUserService().getUserIdentityService().findUserIdentity(maker.identity.getId()))
                .thenReturn(Optional.of(new UserIdentity(maker.identity, maker.profile)));
        when(maker.services.getAccountService().getAccounts(paymentMethod)).thenReturn(Set.of(maker.account));
        when(maker.services.getSettingsService().getMaxTradePriceDeviation()).thenReturn(new Observable<>(0.05));
        when(maker.services.getSettingsService().getCloseMyOfferWhenTaken()).thenReturn(new Observable<>(false));
        var freshPrice = mock(bisq.bonded_roles.market_price.MarketPrice.class);
        when(freshPrice.isValidDate()).thenReturn(true);
        when(freshPrice.getPriceQuote()).thenReturn(price);
        when(maker.services.getBondedRolesService().getMarketPriceService().findMarketPrice(any()))
                .thenReturn(Optional.of(freshPrice));
        when(maker.services.getMuSigTradeService().getMuSigTraderMediationService()
                .selectMediator(anyString(), anyString(), anyString())).thenReturn(Optional.of(mediator));
        when(maker.services.getMuSigTradeService().getMuSigTraderArbitrationService()
                .selectArbitrator(anyString(), anyString(), anyString(), any())).thenReturn(Optional.of(arbitrator));
    }

    @AfterEach
    void shutdown() {
        if (buyer != null && buyer.service != null) {
            buyer.service.shutdown().join();
        }
        if (seller != null && seller.service != null) {
            seller.service.shutdown().join();
        }
    }

    void prepareMediation(boolean paymentInitiated) throws Exception {
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        if (paymentInitiated) {
            await(buyer.service.paymentInitiated(tradeId));
            awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        }
        publishMediationResult();
    }

    void publishMediationResult() throws Exception {
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                tradeId,
                mediator);
        var closed = new MuSigMediationClosedMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                mediationResult,
                MuSigMediationResultService.signMediationResult(mediationResult, mediatorKeys));
        for (var peer : List.of(buyer, seller)) {
            prepareTradeChat(peer);
            await(submitMessage(peer.service, opened));
            await(submitMessage(peer.service, closed));
        }
    }

    void assertBothClosedWithMediation() throws Exception {
        for (var peer : List.of(buyer, seller)) {
            await(whenExecutionState(peer.service, tradeId, CustomPayoutClosedTrade.class));
            var trade = savedRecord(peer.service, tradeId).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, trade.getExecution().state());
            var mediation = trade.getMediation().orElseThrow();
            assertEquals(new MuSigMediationDecisionState.Accepted(), mediation.myDecision().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(), mediation.peerDecision().state());
            var execution = trade.getExecution().data();
            var other = peer == buyer ? seller : buyer;
            assertEquals(PAYOUT_TX_ID, execution.getMyCustomPayoutPsbt().orElseThrow().getTxId());
            assertEquals(PAYOUT_TX_ID, execution.getPeerCustomPayoutPsbt().orElseThrow().getTxId());
            assertArrayEquals(other.signedPayout().getPsbt().toByteArray(),
                    execution.getPeerCustomPayoutPsbt().orElseThrow().getPsbt());
            assertArrayEquals(new byte[]{9}, execution.getCustomCloseTradeResponse().orElseThrow().getCustomPayoutTx());
            assertTrue(peer.saved().pendingInputs().isEmpty());
            assertTrue(peer.saved().pendingEffects().isEmpty());
            assertTrue(peer.saved().scheduledEvents().isEmpty());
            assertEquals(trade.toProto(false), peer.saved().trade().toProto(false));
            verify(peer.backend, never()).closeTrade(any());
        }
    }

    MuSigOpenTradeChannel prepareTradeChat(Peer peer) throws Exception {
        var profile = peer.services.getUserService().getUserProfileService()
                .findUserProfile(peer.identity.getId()).orElseThrow();
        var channel = MuSigOpenTradeChannel.create(tradeId,
                new UserIdentity(peer.identity, profile),
                Set.of(buyer.profile, seller.profile),
                contract.getMediator(),
                contract.getArbitrator(),
                MuSigDisputeAgentType.NONE);
        var channels = peer.services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(channel));
        doAnswer(invocation -> {
            channel.setDisputeAgentType(invocation.getArgument(1));
            return null;
        }).when(channels).setDisputeAgentType(any(), any());
        return channel;
    }

    MuSigMediationOpenedMessage mediationOpenedMessage() {
        return new MuSigMediationOpenedMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId());
    }

    SetupTradeMessage_A request() throws Exception {
        return request(contract);
    }

    SetupTradeMessage_A request(MuSigContract requestedContract) throws Exception {
        var taker = taker();
        var requestedTradeId = TradeId.create(requestedContract.getOffer().getId(),
                taker.identity.getNetworkId().getId(),
                requestedContract.getTakeOfferDate());
        return new SetupTradeMessage_A(StringUtils.createUid(),
                requestedTradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker().identity.getNetworkId(),
                requestedContract,
                contractService.signContract(requestedContract, taker.identity.getKeyBundle().getKeyPair()),
                PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance()));
    }

    Peer maker() {
        return offer.getDirection().isBuy() ? buyer : seller;
    }

    Peer taker() {
        return offer.getDirection().isBuy() ? seller : buyer;
    }

    void startTrade() throws Exception {
        var taker = taker();
        await(initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        awaitDelivery(maker(), SendAccountPayloadAndDepositTxMessage.class);
    }

    void pauseNextRequestValidation(CountDownLatch validating, CountDownLatch release) throws GeneralSecurityException {
        var validator = spy(contractService);
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            if (first.getAndSet(false)) {
                validating.countDown();
                assertTrue(release.await(30, TimeUnit.SECONDS));
            }
            return invocation.callRealMethod();
        }).when(validator).verifyContractSignature(any(), any());
        when(maker().services.getContractService()).thenReturn(validator);
    }

    <T extends MuSigTradeMessage> T sent(Class<T> type) {
        return messages.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    void awaitDelivery(Peer receiver, Class<? extends MuSigTradeMessage> type) throws Exception {
        await(CompletableFuture.anyOf(delivery(receiver, type), deliveryFailure));
    }

    CompletableFuture<MuSigV2Trade<?>> delivery(Peer receiver, Class<?> type) {
        return deliveries.computeIfAbsent(receiver, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, ignored -> new CompletableFuture<>());
    }

    private void send(Peer peer, Effect effect) {
        var send = assertInstanceOf(SendMessage.class, effect);
        if (send.message() instanceof MuSigMediationRequest request) {
            var sender = peer == buyer ? seller : buyer;
            var channel = sender.services.getChatService().getMuSigOpenTradeChannelService()
                    .findChannelByTradeId(tradeId).orElseThrow();
            // Chat activation runs before the request, so chat lines of the same step also reach the mediator.
            if (!chatActivationFails) {
                assertEquals(MuSigDisputeAgentType.MEDIATOR, channel.getDisputeAgentType());
            }
            assertEquals(request.getMediatorNetworkId(), send.receiver());
            mediationRequests.add(request);
            return;
        }
        if (send.message() instanceof MuSigDisputeCaseDataMessage report) {
            assertEquals(contract.getMediator().orElseThrow().getNetworkId(), send.receiver());
            mediationCaseData.add(report);
            return;
        }
        var message = assertInstanceOf(MuSigTradeMessage.class, send.message());
        assertEquals(message.getReceiver(), send.receiver());
        send(peer, message);
    }

    private void send(Peer peer, MuSigTradeMessage message) {
        messages.add(message);
        messageThreads.add(Thread.currentThread().getName());
        if (automaticDelivery) {
            // Exercise the actual wire representation as well as routing; never wait on either service's worker.
            MuSigTradeMessage decoded;
            try {
                decoded = MuSigTradeMessage.fromProto(bisq.trade.protobuf.TradeMessage.parseFrom(
                        message.getValueBuilder(false).build().toByteArray()));
            } catch (InvalidProtocolBufferException | UnresolvableProtobufException exception) {
                throw new AssertionError(exception);
            }
            var delivered = delivery(peer, message.getClass());
            submitMessage(peer.service, decoded).whenComplete((ignored, failure) -> {
                if (failure == null) {
                    delivered.complete(savedRecord(peer.service, message.getTradeId()).trade());
                } else {
                    delivered.completeExceptionally(failure);
                    deliveryFailure.completeExceptionally(failure);
                }
            });
        }
    }

    final class Peer {
        final String name;
        final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        final List<Runnable> timers = new CopyOnWriteArrayList<>();
        final BlockingQueue<Runnable> retries = new LinkedBlockingQueue<>();
        final ObservableSet<AuthorizedAlertData> alerts = new ObservableSet<>();
        private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class, RETURNS_DEEP_STUBS);
        final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        final MusigGrpc.MusigBlockingStub backend = mock(MusigGrpc.MusigBlockingStub.class);
        final MusigGrpc.MusigStub asyncBackend = mock(MusigGrpc.MusigStub.class);
        final Identity identity;
        final UserProfile profile;
        final RevolutAccount account;
        CompletableFuture<StreamObserver<TxConfirmationStatus>> confirmation = new CompletableFuture<>();
        MuSigTradeService service;

        CompletableFuture<MuSigV2Trade<?>> restoreTrade(MuSigV2TradeRecord record) {
            service.shutdown().join();
            clearInvocations(services.getNetworkService());
            timers.clear();
            retries.clear();
            service = serviceWithCapturedTimers(services, grpcClient, () -> contract.getTakeOfferDate(), timers, retries);
            return MuSigTradeServiceTestSupport.restoreTrade(service, record);
        }


        Peer(String name, int port) {
            this.name = name;
            var keys = KeyGeneration.generateDefaultEcKeyPair();
            var networkId = networkId(name, port, keys);
            profile = profile(name, networkId);
            identity = new Identity(profile.getId(), networkId, new KeyBundle(name,
                    keys,
                    TorKeyGeneration.generateKeyPair(),
                    I2PKeyGeneration.generateKeyPair()));
            var payload = new RevolutAccountPayload(StringUtils.createUid(), name, List.of("USD"));
            account = new RevolutAccount(payload.getId(),
                    System.currentTimeMillis(),
                    name,
                    payload,
                    keys,
                    KeyType.EC,
                    AccountOrigin.BISQ2_NEW);
            when(services.getUserService().getUserIdentityService().findUserIdentity(identity.getId()))
                    .thenReturn(Optional.of(new UserIdentity(identity, profile)));
            when(services.getContractService()).thenReturn(contractService);
            when(services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet()).thenReturn(alerts);

            when(grpcClient.getBlockingStub()).thenReturn(backend);
            when(grpcClient.getAsyncStub()).thenReturn(asyncBackend);
            var channels = services.getChatService().getMuSigOpenTradeChannelService();
            @SuppressWarnings("unchecked")
            Persistence<MuSigOpenTradeChannelStore> chatPersistence = mock(Persistence.class);
            var chatStore = mock(MuSigOpenTradeChannelStore.class);
            when(chatStore.getClone()).thenReturn(chatStore);
            when(channels.getPersistence()).thenReturn(chatPersistence);
            when(channels.getPersistableStore()).thenReturn(chatStore);
            when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
            doAnswer(invocation -> {
                logs.add(invocation.getArgument(0));
                return CompletableFuture.completedFuture(null);
            }).when(channels).sendTradeLogMessage(anyString(), any());
            when(backend.initTrade(any())).thenReturn(bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance());
            when(backend.getNonceShares(any())).thenReturn(bisq.trade.protobuf.NonceSharesMessage.newBuilder()
                    .setRedirectionAmountMsat(1_000_000_000L).build());
            when(backend.getPartialSignatures(any())).thenReturn(bisq.trade.protobuf.PartialSignaturesMessage.newBuilder()
                    .setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5})).build());
            when(backend.signDepositTx(any())).thenReturn(bisq.trade.protobuf.DepositPsbt.newBuilder()
                    .setDepositPsbt(ByteString.copyFrom(new byte[]{(byte) (name.equals("buyer") ? 1 : 2)})).build());
            when(backend.publishDepositTx(any())).thenReturn(List.<TxConfirmationStatus>of().iterator());
            when(backend.signSwapTx(any())).thenReturn(bisq.trade.protobuf.SwapTxSignatureResponse.newBuilder()
                    .setSwapTx(ByteString.copyFrom(new byte[]{6})).setPeerOutputPrvKeyShare(ByteString.copyFrom(new byte[]{7})).build());
            when(backend.closeTrade(any())).thenReturn(bisq.trade.protobuf.CloseTradeResponse.newBuilder()
                    .setPeerOutputPrvKeyShare(ByteString.copyFrom(new byte[]{8})).build());
            when(backend.signCustomPayoutTx(any())).thenReturn(signedPayout());
            when(backend.customCloseTrade(any())).thenReturn(bisq.trade.protobuf.CustomCloseTradeResponse.newBuilder()
                    .setCustomPayoutTx(ByteString.copyFrom(new byte[]{9})).build());
            doAnswer(invocation -> {
                confirmation.complete(invocation.getArgument(1));
                return null;
            }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());
        }

        void start(Peer peer) throws Exception {
            var persistence = new PersistenceService(directory.resolve(name));
            when(services.getPersistenceService()).thenReturn(persistence);
            var networkService = services.getNetworkService();
            doAnswer(invocation -> {
                var message = assertInstanceOf(EnvelopePayloadMessage.class, invocation.getArgument(0));
                var receiver = assertInstanceOf(NetworkId.class, invocation.getArgument(1));
                assertEquals(identity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                send(peer, new SendMessage<>(message, receiver));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(networkService).confidentialSend(any(), any(), any());
            timers.clear();
            retries.clear();
            service = serviceWithCapturedTimers(services, grpcClient, () -> contract.getTakeOfferDate(), timers, retries);
            assertTrue(await(persistence.readAllPersisted()));
            assertTrue(await(service.initialize()));
            await(service.getRecoveryFuture());
        }

        void confirmDeposit() throws Exception {
            var confirmed = whenExecutionState(service, tradeId, DepositTxConfirmed.class);
            await(confirmation).onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            await(confirmed);
        }

        MuSigV2TradeRecord saved() {
            return service.getPersistence().read().orElseThrow().getTrades().getFirst();
        }

        private CustomPayoutPsbt signedPayout() {
            return CustomPayoutPsbt.newBuilder().setTxId(PAYOUT_TX_ID)
                    .setPsbt(ByteString.copyFrom(new byte[]{(byte) (name.equals("buyer") ? 21 : 22)}))
                    .setBuyersPayoutAmountIncludingFee(2_400_000).setSellersPayoutAmountIncludingFee(600_000).build();
        }
    }

    private static NetworkId networkId(String name, int port, KeyPair keys) {
        return new NetworkId(new AddressByTransportTypeMap(Map.of(TransportType.CLEAR,
                new ClearnetAddress("127.0.0.1", port))), new PubKey(keys.getPublic(), name));
    }

    private static UserProfile profile(String name, NetworkId networkId) {
        var proofOfWork = new ProofOfWork(networkId.getPubKey().getHash(), 0, null, 1.0, new byte[72], 0);
        return new UserProfile(1, name, proofOfWork, 0, networkId, "", "", "1.0.0");
    }

    static <T extends Throwable> T assertFailure(Class<T> type, CompletableFuture<?> future) {
        var failure = assertThrows(ExecutionException.class, () -> await(future));
        return assertInstanceOf(type, failure.getCause());
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
}
