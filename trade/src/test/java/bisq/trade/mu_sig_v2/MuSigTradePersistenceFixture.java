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

import bisq.account.accounts.AccountPayload;
import bisq.account.accounts.fiat.RevolutAccountPayload;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelStore;
import bisq.common.data.ByteArray;
import bisq.common.market.Market;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.ClearnetAddress;
import bisq.common.network.TransportType;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.processing.Effect;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.OfferService;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.offer.options.CollateralOption;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigBuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecision;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;
import bisq.trade.protobuf.MusigGrpc;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared setup of the tests which save and restore MuSig V2 trades: a seller as taker with its contract, the
 * mocked services, and helpers which seed the store and start the trade service.
 */
class MuSigTradePersistenceFixture {
    final MuSigTradeInputValidator inputValidator =
            new MuSigTradeInputValidator(mock(BannedUserService.class), mock(MuSigOpenTradeChannelService.class));
    static final String TX_ID = "12".repeat(32);
    @TempDir
    Path directory;
    final BlockingQueue<Runnable> retries = new LinkedBlockingQueue<>();
    final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class);
    final ServiceProvider services = mock(ServiceProvider.class);
    final MusigGrpc.MusigBlockingStub backend = mock(MusigGrpc.MusigBlockingStub.class);
    final MusigGrpc.MusigStub asyncBackend = mock(MusigGrpc.MusigStub.class);
    MuSigContract contract;
    Identity myIdentity;
    AccountPayload<?> myAccountPayload;
    MuSigMediationResult result;
    KeyPair mediatorKeys;
    String tradeId;

    @BeforeAll
    static void registerResolvers() {
        MuSigPersistenceTestSupport.registerResolvers();
    }

    @BeforeEach
    void setUp() {
        KeyPair myKeys = KeyGeneration.generateDefaultEcKeyPair();
        NetworkId mine = networkId("taker", 9901, myKeys);
        myIdentity = new Identity("test-identity", mine, new KeyBundle("test-key-bundle",
                myKeys,
                TorKeyGeneration.generateKeyPair(),
                I2PKeyGeneration.generateKeyPair()));
        NetworkId peer = networkId("maker", 9902, KeyGeneration.generateDefaultEcKeyPair());
        mediatorKeys = KeyGeneration.generateDefaultEcKeyPair();
        NetworkId mediatorNetworkId = networkId("mediator", 9903, mediatorKeys);
        var proofOfWork = new ProofOfWork(mediatorNetworkId.getPubKey().getHash(), 0, null, 1.0, new byte[72], 0);
        var mediator = new UserProfile(1, "mediator", proofOfWork, 0, mediatorNetworkId, "", "", "1.0.0");
        var market = new Market("BTC", "EUR", "Bitcoin", "Euro");
        var paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
        var priceSpec = new MarketPriceSpec();
        var offer = new MuSigOffer(StringUtils.createUid(),
                peer,
                Direction.BUY,
                market,
                new BaseSideFixedAmountSpec(100_000),
                priceSpec,
                List.of(paymentMethod),
                List.of(new CollateralOption(0.25, 0.25)),
                "1.0.0");
        myAccountPayload = new RevolutAccountPayload(StringUtils.createUid(), "test-user", List.of("EUR"));
        contract = new MuSigContract(1_750_000_000_000L,
                offer,
                mine,
                100_000,
                3_500_000,
                PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR"),
                createSaltedAccountPayloadHash(myAccountPayload, offer.getId()),
                Optional.of(mediator),
                Optional.empty(),
                priceSpec,
                0);
        tradeId = tradeAt(new AccountPayloadReceived()).getId();
        result = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(90_000L),
                Optional.of(60_000L),
                Optional.empty(),
                Optional.of("test proposal"));

        when(grpcClient.getBlockingStub()).thenReturn(backend);
        when(grpcClient.getAsyncStub()).thenReturn(asyncBackend);
        var networkService = mock(NetworkService.class);
        when(networkService.confidentialSend(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResult()));
        when(services.getNetworkService()).thenReturn(networkService);
        var offerService = mock(OfferService.class);
        when(offerService.getMuSigOfferService()).thenReturn(mock(MuSigOfferService.class));
        when(services.getOfferService()).thenReturn(offerService);
        var users = mock(UserService.class);
        when(services.getUserService()).thenReturn(users);
        when(users.getBannedUserService()).thenReturn(mock(BannedUserService.class));
        var alerts = mock(AlertService.class);
        when(alerts.getAuthorizedAlertDataSet()).thenReturn(new ObservableSet<>());
        var bondedRoles = mock(BondedRolesService.class);
        when(bondedRoles.getAlertService()).thenReturn(alerts);
        when(services.getBondedRolesService()).thenReturn(bondedRoles);

        var chatService = mock(ChatService.class);
        var channels = mock(MuSigOpenTradeChannelService.class);
        var channel = mock(MuSigOpenTradeChannel.class);
        var chatIdentity = mock(UserIdentity.class);
        when(channel.getMyUserIdentity()).thenReturn(chatIdentity);
        when(channel.getChatMessages()).thenReturn(new ObservableSet<>());
        when(chatIdentity.getUserName()).thenReturn("test-user");
        when(services.getChatService()).thenReturn(chatService);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(channels);
        when(channels.findChannelByTradeId(anyString())).thenReturn(Optional.of(channel));
        var chatStore = mock(MuSigOpenTradeChannelStore.class);
        when(chatStore.getClone()).thenReturn(chatStore);
        when(channels.getPersistableStore()).thenReturn(chatStore);
        Persistence<?> chatPersistence = mock(Persistence.class);
        when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        doReturn(chatPersistence).when(channels).getPersistence();
        when(backend.signSwapTx(any())).thenReturn(bisq.trade.protobuf.SwapTxSignatureResponse.newBuilder()
                .setSwapTx(ByteString.copyFrom(new byte[]{8})).setPeerOutputPrvKeyShare(ByteString.copyFrom(new byte[]{9})).build());
        when(backend.closeTrade(any())).thenReturn(bisq.trade.protobuf.CloseTradeResponse.newBuilder()
                .setPeerOutputPrvKeyShare(ByteString.copyFrom(new byte[]{10})).build());
        when(backend.customCloseTrade(any())).thenReturn(bisq.trade.protobuf.CustomCloseTradeResponse.newBuilder()
                .setCustomPayoutTx(ByteString.copyFrom(new byte[]{11})).build());
    }

    MuSigV2MediationDecision<MuSigMediationDecisionState> decisionSnapshot(
            MuSigMediationDecisionState state) {
        return new MuSigV2MediationDecision<>(state, MuSigV2MediationDecisionData.initial());
    }

    MuSigTradeMessage peerDecision(boolean accepted, byte[] resultHash) {
        return accepted
                ? new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                        tradeId,
                        "1.0.0",
                        contract.getMaker().getNetworkId(),
                        myIdentity.getNetworkId(),
                        resultHash,
                        TX_ID,
                        new byte[]{7})
                : new MuSigMediationResultRejectionMessage(StringUtils.createUid(),
                        tradeId,
                        "1.0.0",
                        contract.getMaker().getNetworkId(),
                        myIdentity.getNetworkId(),
                        resultHash);
    }

    MuSigV2Trade<MuSigSellerAsTakerState> signedCustomPayoutTrade() {
        return tradeAt(new CustomPayoutSigned()).withExecution(FlowSnapshot.of(new CustomPayoutSigned(),
                MuSigV2TradeExecutionData.builder().myCustomPayoutPsbt(Optional.of(
                        new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000))).build()))
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                                new ByteArray(new byte[]{1, 2}))), Optional.empty())))
                .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()))
                .withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
    }

    MuSigV2TradeRecord pendingCustomSettlement(String peerTxId) {
        var peer = new PeerCustomPayoutPsbt(peerTxId, new byte[]{7});
        return new MuSigV2TradeRecord(signedCustomPayoutTrade(),
                Map.of(Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class), Flow.PEER_MEDIATION_DECISION, Set.of(MuSigCustomPayoutPsbtMessage.class)),
                List.of(new MediationResultByPeerAcceptedEvent(peer)),
                List.of(),
                List.of());
    }

    MuSigV2Trade<MuSigSellerAsTakerState> tradeAt(MuSigSellerAsTakerState state) {
        return MuSigV2TradeTestFactory.tradeAt(contract,
                myIdentity,
                TradeRole.SELLER_AS_TAKER,
                "1.0.0",
                myAccountPayload,
                state);
    }

    /** A copy of the trade with another state of the TRADE flow. */
    static MuSigV2Trade<MuSigSellerAsTakerState> withTradeState(MuSigV2Trade<MuSigSellerAsTakerState> trade,
                                                                        MuSigTradeState state) {
        return trade.withTradeFlow(FlowSnapshot.of(state, trade.getData()));
    }

    MuSigV2Trade<MuSigBuyerAsTakerState> buyerTradeAt(MuSigBuyerAsTakerState state) {
        var paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
        var priceSpec = new MarketPriceSpec();
        var offer = new MuSigOffer(contract.getOffer().getId(),
                contract.getMaker().getNetworkId(),
                Direction.SELL,
                contract.getOffer().getMarket(),
                new BaseSideFixedAmountSpec(100_000),
                priceSpec,
                List.of(paymentMethod),
                List.of(new CollateralOption(0.25, 0.25)),
                "1.0.0");
        var buyerContract = new MuSigContract(contract.getTakeOfferDate() + 1,
                offer,
                myIdentity.getNetworkId(),
                100_000,
                3_500_000,
                PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR"),
                new byte[20],
                contract.getMediator(),
                Optional.empty(),
                priceSpec,
                0);
        return MuSigV2TradeTestFactory.tradeAt(buyerContract,
                myIdentity,
                TradeRole.BUYER_AS_TAKER,
                "1.0.0",
                myAccountPayload,
                state);
    }

    static UserProfile userProfile(String name, NetworkId networkId) {
        var proofOfWork = new ProofOfWork(networkId.getPubKey().getHash(), 0, null, 1.0, new byte[72], 0);
        return new UserProfile(1, name, proofOfWork, 0, networkId, "", "", "1.0.0");
    }

    void mockNetworkSend(Consumer<Effect> onSend) {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            onSend.accept(new SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
    }

    MuSigTradeService startAndAwaitRecovery() throws Exception {
        retries.clear();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        return initializeAndAwaitRecovery(
                serviceWithClock(services, grpcClient, contract::getTakeOfferDate, retries),
                persistenceService);
    }

    MuSigTradeService startAndAwaitRecovery(Consumer<Effect> effects) throws Exception {
        return startAndAwaitRecovery(effects, contract::getTakeOfferDate);
    }

    MuSigTradeService startAndAwaitRecovery(Consumer<Effect> effects, Clock clock) throws Exception {
        retries.clear();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effects);
        var service = serviceWithClock(services, grpcClient, clock, retries);
        return initializeAndAwaitRecovery(service, persistenceService);
    }

    private MuSigTradeService initializeAndAwaitRecovery(MuSigTradeService service,
                                                         PersistenceService persistenceService) throws Exception {
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertTrue(await(service.initialize()));
            await(service.getRecoveryFuture());
            return service;
        } catch (Throwable failure) {
            service.shutdown().join();
            throw failure;
        }
    }

    void seed(MuSigV2TradeRecord... records) throws Exception {
        var service = startAndAwaitRecovery();
        try {
            for (var record : records) {
                service.getPersistableStore().put(record);
            }
            assertTrue(await(service.persist()));
        } finally {
            service.shutdown().join();
        }
    }

    MuSigV2TradeRecord saved(MuSigTradeService service) {
        return service.getPersistence().read().orElseThrow().getTrades().getFirst();
    }

    private static NetworkId networkId(String name, int port, KeyPair keys) {
        return new NetworkId(new AddressByTransportTypeMap(Map.of(TransportType.CLEAR,
                new ClearnetAddress("127.0.0.1", port))), new PubKey(keys.getPublic(), name));
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
}
