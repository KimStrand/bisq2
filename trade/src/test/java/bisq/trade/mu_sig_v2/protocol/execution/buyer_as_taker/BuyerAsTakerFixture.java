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
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelStore;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeMessage;
import bisq.common.market.Market;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.timer.Clock;
import bisq.contract.ContractService;
import bisq.contract.ContractSignatureData;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.offer.Direction;
import bisq.offer.OfferService;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.offer.options.AccountOption;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.protobuf.MusigGrpc;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.AfterEach;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.copyContract;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/** Shared backend and contract fixture; no test methods live here. */
class BuyerAsTakerFixture {
    static final String OFFER_ID = UUID.randomUUID().toString();
    static final String TAKER_ID = "taker-profile";
    static final String MAKER_ID = "maker-profile";
    static final long TAKE_OFFER_DATE = 1_750_000_000_000L;
    static final String TRADE_ID = TradeV2.createId(OFFER_ID, TAKER_ID, TAKE_OFFER_DATE);
    static final byte[] CONTRACT_HASH = new byte[20];

    final Clock clock = () -> TAKE_OFFER_DATE;
    final List<Effect> effects = new ArrayList<>();
    final List<LogEntry> logs = new ArrayList<>();
    final NetworkId myNetworkId = networkId(TAKER_ID);
    final NetworkId peerNetworkId = networkId(MAKER_ID);
    final UserProfile myUserProfile = userProfile(myNetworkId);
    final UserProfile peerUserProfile = userProfile(peerNetworkId);
    final ObservableSet<MuSigOpenTradeMessage> chatMessages = new ObservableSet<>();
    final MuSigOpenTradeChannel channel = tradeChannel();
    final AccountPayload<?> peerAccountPayload = accountPayload();
    final AccountPayload<?> myAccountPayload = accountPayload();
    final MuSigContract muSigContract = muSigContract(peerAccountPayload);
    final Identity myIdentity = identity();
    final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class);
    final ServiceProvider services = serviceProvider(grpcClient);
    final MuSigTradeLaneFixture<BuyerAsTakerState> protocol =
            new MuSigTradeLaneFixture<>(services, grpcClient,
                    tradeAt(new Init()),
                    BuyerAsTakerState.class,
                    new BuyerAsTakerProtocol(),
                    effects::add,
                    logs::add,
                    clock);

    @AfterEach
    void closeProtocolLane() {
        protocol.close();
    }

    void runSetup(Consumer<Event> handle) {
        handle.accept(new MuSigTakeOfferEvent());
        completeSetup(handle);
    }

    void completeSetup(Consumer<Event> handle) {
        handle.accept(setupMessage());
        handle.accept(depositSetupMessage());
        handle.accept(accountPayloadMessage(peerAccountPayload));
        handle.accept(new DepositTxConfirmedEvent());
    }

    void runSettlement(Consumer<Event> handle) {
        handle.accept(new PaymentInitiatedEvent());
        handle.accept(paymentReceivedMessage());
    }

    PaymentReceivedMessage_F paymentReceivedMessage() {
        return new PaymentReceivedMessage_F(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})));
    }

    List<Object> sentMessageTypes() {
        return effects.stream()
                .filter(Effect.SendMessage.class::isInstance)
                .<Object>map(effect -> ((Effect.SendMessage<?, ?>) effect).message().getClass())
                .toList();
    }

    List<Object> nonMessageEffectTypes() {
        return effects.stream()
                .filter(effect -> !(effect instanceof Effect.SendMessage<?, ?>))
                .<Object>map(Object::getClass)
                .toList();
    }


    /* --------------------------------------------------------------------- */
    // Fixture
    /* --------------------------------------------------------------------- */

    MuSigV2Trade<BuyerAsTakerState> tradeAt(BuyerAsTakerState state) {
        return MuSigV2TradeTestFactory.tradeAt(muSigContract,
                myIdentity,
                TradeRole.BUYER_AS_TAKER,
                "1.0.0",
                myAccountPayload,
                state);
    }

    MusigGrpc.MusigBlockingStub blockingStub() {
        return grpcClient.getBlockingStub();
    }

    SetupTradeMessage_B setupMessage() {
        return new SetupTradeMessage_B(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                copyContract(muSigContract),
                contractSignatureData(),
                pubKeyShares(),
                nonceShares());
    }

    SetupTradeMessage_D depositSetupMessage() {
        return new SetupTradeMessage_D(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                PartialSignatures.toUnRedacted(redactedPartialSignatures(), new byte[]{9}),
                new byte[]{1});
    }

    SendAccountPayloadMessage accountPayloadMessage(AccountPayload<?> payload) {
        return new SendAccountPayloadMessage(uid(), TRADE_ID, "1.0.0", peerNetworkId(), myNetworkId(), payload);
    }

    PaymentInitiatedMessage_E paymentInitiatedMessage() {
        return new PaymentInitiatedMessage_E(uid(), TRADE_ID, "1.0.0", peerNetworkId(), myNetworkId(), new byte[]{2});
    }

    static String uid() {
        return UUID.randomUUID().toString();
    }

    NetworkId myNetworkId() {
        return myNetworkId;
    }

    NetworkId peerNetworkId() {
        return peerNetworkId;
    }

    static ContractSignatureData contractSignatureData() {
        return new ContractSignatureData(CONTRACT_HASH,
                new byte[70],
                KeyGeneration.generateDefaultEcKeyPair().getPublic());
    }

    static PubKeyShares pubKeyShares() {
        return assertDoesNotThrow(() -> PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance()));
    }

    static NonceShares nonceShares() {
        return assertDoesNotThrow(() -> NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance()));
    }

    static PartialSignatures redactedPartialSignatures() {
        return assertDoesNotThrow(() -> PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance()));
    }

    ServiceProvider serviceProvider(MusigGrpcClient grpcClient) {
        MusigGrpc.MusigBlockingStub stub = mock(MusigGrpc.MusigBlockingStub.class);
        when(stub.initTrade(any())).thenReturn(bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance());
        // A non-zero redirection amount, or the burning-men receiver computation rejects it.
        when(stub.getNonceShares(any())).thenReturn(bisq.trade.protobuf.NonceSharesMessage.newBuilder()
                .setRedirectionAmountMsat(1_000_000_000L)
                .build());
        when(stub.getPartialSignatures(any())).thenAnswer(invocation -> {
            bisq.trade.protobuf.PartialSignaturesRequest request = invocation.getArgument(0);
            byte[] signature = request.getBuyerReadyToRelease() ? new byte[]{6} : new byte[]{2};
            return bisq.trade.protobuf.PartialSignaturesMessage.newBuilder()
                    .setSwapTxInputPartialSignature(ByteString.copyFrom(signature)).build();
        });
        when(stub.signDepositTx(any())).thenReturn(bisq.trade.protobuf.DepositPsbt.newBuilder()
                .setDepositPsbt(ByteString.copyFrom(new byte[]{4})).build());
        // publishDepositTx is a server-streaming call; the handler ignores the confirmation stream.
        when(stub.publishDepositTx(any()))
                .thenReturn(List.<bisq.trade.protobuf.TxConfirmationStatus>of().iterator());
        when(stub.signSwapTx(any())).thenReturn(bisq.trade.protobuf.SwapTxSignatureResponse.getDefaultInstance());
        when(stub.closeTrade(any())).thenReturn(bisq.trade.protobuf.CloseTradeResponse.newBuilder()
                .setPeerOutputPrvKeyShare(ByteString.copyFrom(new byte[]{3})).build());

        when(grpcClient.getBlockingStub()).thenReturn(stub);
        when(grpcClient.getAsyncStub()).thenReturn(mock(MusigGrpc.MusigStub.class));

        ContractService contractService = mock(ContractService.class);
        try {
            when(contractService.signContract(any(), any())).thenReturn(contractSignatureData());
            when(contractService.verifyContractSignature(any(), any())).thenReturn(true);
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }

        UserProfileService userProfileService = mock(UserProfileService.class);
        when(userProfileService.findUserProfile(TAKER_ID)).thenReturn(Optional.of(myUserProfile));
        when(userProfileService.findUserProfile(MAKER_ID)).thenReturn(Optional.of(peerUserProfile));
        UserService userService = mock(UserService.class);
        when(userService.getUserProfileService()).thenReturn(userProfileService);
        when(userService.getBannedUserService()).thenReturn(mock(BannedUserService.class));
        var userIdentityService = mock(UserIdentityService.class);
        var userIdentity = channel.getMyUserIdentity();
        when(userIdentityService.findUserIdentity(anyString())).thenReturn(Optional.of(userIdentity));
        when(userService.getUserIdentityService()).thenReturn(userIdentityService);

        MuSigOpenTradeChannelService channels = mock(MuSigOpenTradeChannelService.class);
        when(channels.findChannelByTradeId(TRADE_ID)).thenReturn(Optional.of(channel));
        ChatService chatService = mock(ChatService.class);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(channels);

        Persistence<?> persistence = mock(Persistence.class);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        var chatStore = mock(MuSigOpenTradeChannelStore.class);
        when(chatStore.getClone()).thenReturn(chatStore);
        when(channels.getPersistableStore()).thenReturn(chatStore);
        Persistence<?> chatPersistence = mock(Persistence.class);
        when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        doReturn(chatPersistence).when(channels).getPersistence();
        PersistenceService persistenceService = mock(PersistenceService.class);
        doReturn(persistence).when(persistenceService).getOrCreatePersistence(
                any(),
                any(DbSubDirectory.class),
                anyString(),
                any());
        ServiceProvider serviceProvider = mock(ServiceProvider.class);
        when(serviceProvider.getPersistenceService()).thenReturn(persistenceService);

        var networkService = mock(NetworkService.class);
        when(networkService.confidentialSend(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResult()));
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        var offerService = mock(OfferService.class);
        when(offerService.getMuSigOfferService()).thenReturn(mock(MuSigOfferService.class));
        when(serviceProvider.getOfferService()).thenReturn(offerService);
        when(serviceProvider.getContractService()).thenReturn(contractService);
        when(serviceProvider.getUserService()).thenReturn(userService);
        when(serviceProvider.getChatService()).thenReturn(chatService);
        var alerts = mock(AlertService.class);
        when(alerts.getAuthorizedAlertDataSet()).thenReturn(new ObservableSet<>());
        var bondedRoles = mock(BondedRolesService.class);
        when(bondedRoles.getAlertService()).thenReturn(alerts);
        when(serviceProvider.getBondedRolesService()).thenReturn(bondedRoles);

        return serviceProvider;
    }

    static NetworkId networkId(String id) {
        NetworkId networkId = mock(NetworkId.class);
        when(networkId.getId()).thenReturn(id);
        when(networkId.toProto(anyBoolean())).thenReturn(bisq.network.identity.protobuf.NetworkId.getDefaultInstance());
        return networkId;
    }

    private static UserProfile userProfile(NetworkId networkId) {
        String id = networkId.getId();
        UserProfile profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getNetworkId()).thenReturn(networkId);
        when(profile.getUserName()).thenReturn("someone");
        when(profile.toProto(anyBoolean())).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        return profile;
    }

    private MuSigOpenTradeChannel tradeChannel() {
        UserIdentity userIdentity = mock(UserIdentity.class);
        when(userIdentity.getUserName()).thenReturn("local-buyer");
        MuSigOpenTradeChannel channel = mock(MuSigOpenTradeChannel.class);
        when(channel.getMyUserIdentity()).thenReturn(userIdentity);
        when(channel.getChatMessages()).thenReturn(chatMessages);
        return channel;
    }

    Identity identity() {
        Identity identity = mock(Identity.class);
        when(identity.getId()).thenReturn(TAKER_ID);
        when(identity.getNetworkId()).thenReturn(myNetworkId());
        when(identity.getKeyBundle()).thenReturn(mock(KeyBundle.class));
        return identity;
    }

    static AccountPayload<?> accountPayload() {
        AccountPayload<?> accountPayload = mock(AccountPayload.class);
        when(accountPayload.serializeForHash()).thenReturn(new byte[]{5});
        return accountPayload;
    }

    MuSigContract muSigContract(AccountPayload<?> peerAccountPayload) {
        var paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
        var paymentMethodSpec = PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "USD");
        var accountOption = mock(AccountOption.class);
        doReturn(paymentMethod).when(accountOption).getPaymentMethod();
        byte[] peerAccountPayloadHash = createSaltedAccountPayloadHash(peerAccountPayload, OFFER_ID);
        when(accountOption.getSaltedAccountPayloadHash()).thenReturn(peerAccountPayloadHash);
        var priceSpec = new MarketPriceSpec();
        MuSigOffer offer = mock(MuSigOffer.class);
        Market market = mock(Market.class);
        when(market.getBaseCurrencyCode()).thenReturn("BTC");
        when(market.getQuoteCurrencyCode()).thenReturn("USD");
        when(market.isBaseCurrencyBitcoin()).thenReturn(true);
        when(offer.getMarket()).thenReturn(market);
        when(offer.getId()).thenReturn(OFFER_ID);
        when(offer.getShortId()).thenReturn("short");
        when(offer.getMakersUserProfileId()).thenReturn(MAKER_ID);
        when(offer.getMakerNetworkId()).thenReturn(peerNetworkId());
        when(offer.getTakersDirection()).thenReturn(Direction.BUY);
        doReturn(PaymentMethodSpecUtil.createBitcoinMainChainPaymentMethodSpec()).when(offer).getBaseSidePaymentMethodSpecs();
        when(offer.getOfferOptions()).thenReturn(List.of(accountOption));
        when(offer.getPriceSpec()).thenReturn(priceSpec);
        when(offer.toProto(anyBoolean())).thenReturn(bisq.offer.protobuf.Offer.getDefaultInstance());

        var contract = spy(new MuSigContract(TAKE_OFFER_DATE,
                offer,
                myNetworkId(),
                100_000,
                2_500_000,
                paymentMethodSpec,
                createSaltedAccountPayloadHash(myAccountPayload, OFFER_ID),
                Optional.empty(),
                Optional.empty(),
                priceSpec,
                0));
        // Hash the current terms, including mediator or amount overrides made by a test.
        doAnswer(invocation -> copyContract(contract).serializeForHash()).when(contract).serializeForHash();
        return contract;
    }
}
