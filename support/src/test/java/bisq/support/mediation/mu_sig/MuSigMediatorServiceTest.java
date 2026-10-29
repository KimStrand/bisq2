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

package bisq.support.mediation.mu_sig;

import bisq.account.accounts.AccountPayload;
import bisq.account.accounts.fiat.NationalBankAccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.BondedRoleType;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRole;
import bisq.bonded_roles.bonded_role.AuthorizedBondedRolesService;
import bisq.chat.ChatMessageType;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelStore;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeMessage;
import bisq.common.market.Market;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.TransportType;
import bisq.common.network.clear_net_address_types.LocalHostAddressTypeFacade;
import bisq.contract.mu_sig.MuSigContract;
import bisq.i18n.Res;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.identity.NetworkIdWithKeyPair;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.offer.price.spec.PriceSpec;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.PersistableStore;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceClient;
import bisq.persistence.PersistenceService;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.pow.ProofOfWork;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.MediationCaseState;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MuSigMediatorServiceTest {
    private BannedUserService bannedUserService;
    private PersistenceService persistenceService;
    private ChatService chatService;
    private UserService userService;
    private UserIdentityService userIdentityService;
    private BondedRolesService bondedRolesService;
    private AuthorizedBondedRolesService authorizedBondedRolesService;

    @BeforeAll
    static void setupRes() {
        Res.setAndApplyLanguageTag("en");
    }

    @BeforeEach
    void setUp() {
        persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigMediatorStore> persistence = mock(Persistence.class);
        when(persistenceService.getOrCreatePersistence(any(PersistenceClient.class), any(DbSubDirectory.class), any(PersistableStore.class)))
                .thenReturn(persistence);
        when(persistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        when(persistence.getStorePath()).thenReturn(Path.of("build/test/musig-mediator-service"));

        chatService = mock(ChatService.class);
        MuSigOpenTradeChannelService openTradeChannelService = mock(MuSigOpenTradeChannelService.class);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(openTradeChannelService);

        userService = mock(UserService.class);
        userIdentityService = mock(UserIdentityService.class);
        when(userService.getUserIdentityService()).thenReturn(userIdentityService);
        bannedUserService = mock(BannedUserService.class);
        when(userService.getBannedUserService()).thenReturn(bannedUserService);
        when(bannedUserService.isUserProfileBanned(any(String.class))).thenReturn(false);
        when(bannedUserService.isUserProfileBanned(any(UserProfile.class))).thenReturn(false);
        when(bannedUserService.isUserProfileBanned(any(NetworkId.class))).thenReturn(false);

        bondedRolesService = mock(BondedRolesService.class);
        authorizedBondedRolesService = mock(AuthorizedBondedRolesService.class);
        when(bondedRolesService.getAuthorizedBondedRolesService()).thenReturn(authorizedBondedRolesService);
    }

    @ParameterizedTest(name = "logArrivesFirst={0}")
    @ValueSource(booleans = {false, true})
    void mediationRequestAndSeparateLogCanArriveInEitherOrderWithoutDuplicates(boolean logArrivesFirst) throws Exception {
        NetworkService networkService = mock(NetworkService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigOpenTradeChannelStore> chatPersistence = mock(Persistence.class);
        when(persistenceService.getOrCreatePersistence(any(MuSigOpenTradeChannelService.class),
                any(DbSubDirectory.class),
                any(MuSigOpenTradeChannelStore.class))).thenReturn(chatPersistence);
        when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
        MuSigOpenTradeChannelService channels = new MuSigOpenTradeChannelService(
                persistenceService,
                networkService,
                userService);
        when(chatService.getMuSigOpenTradeChannelService()).thenReturn(channels);

        UserProfile requester = createUserProfile(1061);
        UserProfile peer = createUserProfile(1062);
        UserProfile mediator = createUserProfile(1063);
        UserIdentity mediatorIdentity = mock(UserIdentity.class);
        when(mediatorIdentity.getId()).thenReturn(mediator.getId());
        when(mediatorIdentity.getUserProfile()).thenReturn(mediator);
        NetworkIdWithKeyPair networkIdentity = mock(NetworkIdWithKeyPair.class);
        when(networkIdentity.getNetworkId()).thenReturn(mediator.getNetworkId());
        when(mediatorIdentity.getNetworkIdWithKeyPair()).thenReturn(networkIdentity);
        when(userIdentityService.findUserIdentity(mediator.getId())).thenReturn(Optional.of(mediatorIdentity));
        when(userIdentityService.isUserIdentityPresent(mediator.getId())).thenReturn(true);
        AuthorizedBondedRole role = mock(AuthorizedBondedRole.class);
        when(role.getBondedRoleType()).thenReturn(BondedRoleType.MEDIATOR);
        when(role.getProfileId()).thenReturn(mediator.getId());
        when(authorizedBondedRolesService.getAuthorizedBondedRoleStream(true)).thenAnswer(ignored -> Stream.of(role));
        when(networkService.confidentialSend(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        MuSigMediatorService service = new MuSigMediatorService(
                persistenceService,
                networkService,
                chatService,
                userService,
                bondedRolesService);

        String tradeId = "trade-61";
        String channelId = MuSigOpenTradeChannel.createId(tradeId);
        MuSigOpenTradeMessage history = createChatMessage(tradeId, channelId, requester, peer);
        MuSigMediationRequest request = createMediationRequest(tradeId, requester, peer, mediator, List.of(history));
        MuSigOpenTradeMessage logMessage = new MuSigOpenTradeMessage(
                tradeId,
                "request-log-" + tradeId,
                channelId,
                requester,
                mediator.getId(),
                mediator.getNetworkId(),
                Res.encode("muSig.mediation.requester.tradeLogMessage", "requester"),
                Optional.empty(),
                history.getDate() + 1,
                false,
                Optional.empty(),
                Optional.empty(),
                ChatMessageType.PROTOCOL_LOG_MESSAGE,
                Optional.empty(),
                Set.of());

        if (logArrivesFirst) {
            channels.onMessage(MuSigOpenTradeMessage.fromProto(logMessage.toValueProto(false)));
            channels.onMessage(MuSigOpenTradeMessage.fromProto(logMessage.toValueProto(false)));
            assertThat(channels.getChannels()).isEmpty();
            assertThat(service.getMediationCases()).isEmpty();
            verifyNoInteractions(networkService);
        }

        service.onMessage(MuSigMediationRequest.fromProto(request.toValueProto(false)));

        MuSigOpenTradeChannel channel = channels.findChannelByTradeId(tradeId).orElseThrow();
        if (!logArrivesFirst) {
            assertThat(channel.getChatMessages()).doesNotContain(logMessage);
            channels.onMessage(MuSigOpenTradeMessage.fromProto(logMessage.toValueProto(false)));
        }
        assertThat(channels.getChannels()).containsExactly(channel);
        assertThat(channel.getDisputeAgentType()).isEqualTo(MuSigDisputeAgentType.MEDIATOR);
        assertThat(channel.getChatMessages()).contains(history);
        assertThat(channel.getChatMessages())
                .filteredOn(message -> message.getId().equals(logMessage.getId()))
                .containsExactly(logMessage);
        Set<MuSigOpenTradeMessage> messagesBeforeDuplicates = Set.copyOf(channel.getChatMessages());

        channels.onMessage(MuSigOpenTradeMessage.fromProto(logMessage.toValueProto(false)));
        service.onMessage(MuSigMediationRequest.fromProto(request.toValueProto(false)));

        assertThat(channel.getChatMessages()).containsExactlyInAnyOrderElementsOf(messagesBeforeDuplicates);
        assertThat(service.getMediationCases()).singleElement().satisfies(mediationCase -> {
            assertThat(mediationCase.getMediationCaseState()).isEqualTo(MediationCaseState.OPEN);
            assertThat(mediationCase.getMuSigMediationRequest().getTradeId()).isEqualTo(tradeId);
            assertThat(mediationCase.getMuSigMediationRequest().getChatMessages()).containsExactly(history);
        });
        for (UserProfile trader : List.of(requester, peer)) {
            verify(networkService).confidentialSend(argThat(message ->
                            message instanceof MuSigMediationStateChangeMessage change &&
                                    change.getTradeId().equals(tradeId) &&
                                    change.getMediationCaseState() == MediationCaseState.OPEN),
                    eq(trader.getNetworkId()),
                    same(networkIdentity));
        }
        verifyNoMoreInteractions(networkService);
    }

    @Test
    void verifyMediationRequestMatchesAndPartiesAndMediatorAreConsistent() {
        UserProfile requester = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile mediator = createUserProfile(1003);
        MuSigContract contract = createContract(requester, peer, mediator, "offer-10",
                createNationalBankPayload("taker-account-10", "DE101"),
                createNationalBankPayload("maker-account-10", "DE102"));
        MuSigMediationRequest request = new MuSigMediationRequest(
                "trade-10",
                contract,
                requester,
                peer,
                List.of(),
                mediator.getNetworkId()
        );

        Optional<UserProfile> verifiedRequester = MuSigMediatorService.verifyMediationRequest(
                request,
                bannedUserService
        );

        assertThat(verifiedRequester).containsSame(requester);
    }

    @Test
    void muSigMediationRequestProvidesMediatorReceiverPublicKey() {
        UserProfile requester = createUserProfile(1051);
        UserProfile peer = createUserProfile(1052);
        UserProfile mediator = createUserProfile(1053);
        MuSigMediationRequest request = createMediationRequest(
                "trade-51",
                requester,
                peer,
                mediator,
                List.of());

        assertThat(request.getReceiverPublicKey().getEncoded())
                .isEqualTo(mediator.getPublicKey().getEncoded());
    }

    @Test
    void verifyMediationRequestAcceptsValidEmbeddedChatMessages() {
        UserProfile requester = createUserProfile(1011);
        UserProfile peer = createUserProfile(1012);
        UserProfile mediator = createUserProfile(1013);
        String tradeId = "trade-11";
        MuSigOpenTradeMessage chatMessage = createChatMessage(
                tradeId,
                MuSigOpenTradeChannel.createId(tradeId),
                requester,
                peer);
        MuSigMediationRequest request = createMediationRequest(
                tradeId,
                requester,
                peer,
                mediator,
                List.of(chatMessage));

        Optional<UserProfile> verifiedRequester = MuSigMediatorService.verifyMediationRequest(
                request,
                bannedUserService
        );

        assertThat(verifiedRequester).containsSame(requester);
    }

    @Test
    void verifyMediationRequestRejectsEmbeddedChatMessageWithWrongTradeId() {
        UserProfile requester = createUserProfile(1021);
        UserProfile peer = createUserProfile(1022);
        UserProfile mediator = createUserProfile(1023);
        String tradeId = "trade-21";
        MuSigOpenTradeMessage chatMessage = createChatMessage(
                "trade-22",
                MuSigOpenTradeChannel.createId(tradeId),
                requester,
                peer);
        MuSigMediationRequest request = createMediationRequest(
                tradeId,
                requester,
                peer,
                mediator,
                List.of(chatMessage));

        Optional<UserProfile> verifiedRequester = MuSigMediatorService.verifyMediationRequest(
                request,
                bannedUserService
        );

        assertThat(verifiedRequester).isEmpty();
    }

    @Test
    void verifyMediationRequestRejectsEmbeddedChatMessageWithWrongChannelId() {
        UserProfile requester = createUserProfile(1031);
        UserProfile peer = createUserProfile(1032);
        UserProfile mediator = createUserProfile(1033);
        String tradeId = "trade-31";
        MuSigOpenTradeMessage chatMessage = createChatMessage(
                tradeId,
                MuSigOpenTradeChannel.createId("trade-32"),
                requester,
                peer);
        MuSigMediationRequest request = createMediationRequest(
                tradeId,
                requester,
                peer,
                mediator,
                List.of(chatMessage));

        Optional<UserProfile> verifiedRequester = MuSigMediatorService.verifyMediationRequest(
                request,
                bannedUserService
        );

        assertThat(verifiedRequester).isEmpty();
    }

    @Test
    void verifyMediationRequestRejectsEmbeddedChatMessageWithUnexpectedSender() {
        UserProfile requester = createUserProfile(1041);
        UserProfile peer = createUserProfile(1042);
        UserProfile mediator = createUserProfile(1043);
        UserProfile stranger = createUserProfile(1044);
        String tradeId = "trade-41";
        MuSigOpenTradeMessage chatMessage = createChatMessage(
                tradeId,
                MuSigOpenTradeChannel.createId(tradeId),
                stranger,
                peer);
        MuSigMediationRequest request = createMediationRequest(
                tradeId,
                requester,
                peer,
                mediator,
                List.of(chatMessage));

        Optional<UserProfile> verifiedRequester = MuSigMediatorService.verifyMediationRequest(
                request,
                bannedUserService
        );

        assertThat(verifiedRequester).isEmpty();
    }

    @Test
    void verifyDisputeCasePaymentDetailsResponse_returnsCase_whenKnownSenderIsNotBanned() {
        UserProfile requester = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        MuSigMediationRequest request = createMediationRequest("trade-12", requester, peer);
        MuSigMediationCase mediationCase = new MuSigMediationCase(request);
        MuSigDisputeCasePaymentDetailsResponse response = new MuSigDisputeCasePaymentDetailsResponse(
                "trade-12",
                requester.getNetworkId(),
                createNationalBankPayload("taker-account-12", "DE121"),
                createNationalBankPayload("maker-account-12", "DE122")
        );

        Optional<MuSigMediationCase> verifiedCase = MuSigMediatorService.verifyDisputeCasePaymentDetailsResponse(
                response,
                tradeId -> tradeId.equals(mediationCase.getMuSigMediationRequest().getTradeId()) ? Optional.of(mediationCase) : Optional.empty(),
                bannedUserService
        );

        assertThat(verifiedCase).containsSame(mediationCase);
    }

    @Test
    void verifyDisputeCasePaymentDetailsResponse_returnsEmpty_whenSenderNetworkIdIsUnknown() {
        UserProfile requester = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        UserProfile stranger = createUserProfile(1003);
        MuSigMediationRequest request = createMediationRequest("trade-13", requester, peer);
        MuSigMediationCase mediationCase = new MuSigMediationCase(request);
        MuSigDisputeCasePaymentDetailsResponse response = new MuSigDisputeCasePaymentDetailsResponse(
                "trade-13",
                stranger.getNetworkId(),
                createNationalBankPayload("taker-account-13", "DE131"),
                createNationalBankPayload("maker-account-13", "DE132")
        );

        Optional<MuSigMediationCase> verifiedCase = MuSigMediatorService.verifyDisputeCasePaymentDetailsResponse(
                response,
                tradeId -> tradeId.equals(mediationCase.getMuSigMediationRequest().getTradeId()) ? Optional.of(mediationCase) : Optional.empty(),
                bannedUserService
        );

        assertThat(verifiedCase).isEmpty();
    }

    @Test
    void verifyDisputeCaseDataMessage_returnsCase_whenPeerIsNotBanned() {
        UserProfile requester = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        MuSigMediationRequest request = createMediationRequest("trade-14", requester, peer);
        MuSigMediationCase mediationCase = new MuSigMediationCase(request);
        MuSigDisputeCaseDataMessage message = new MuSigDisputeCaseDataMessage(
                "trade-14",
                peer.getNetworkId(),
                new byte[20],
                List.of()
        );

        Optional<MuSigMediationCase> verifiedCase = MuSigMediatorService.verifyDisputeCaseDataMessage(
                message,
                tradeId -> tradeId.equals(mediationCase.getMuSigMediationRequest().getTradeId()) ? Optional.of(mediationCase) : Optional.empty(),
                bannedUserService
        );

        assertThat(verifiedCase).containsSame(mediationCase);
    }

    @Test
    void verifyDisputeCaseDataMessage_returnsEmpty_whenPeerIsBanned() {
        UserProfile requester = createUserProfile(1001);
        UserProfile peer = createUserProfile(1002);
        when(bannedUserService.isUserProfileBanned(peer.getNetworkId())).thenReturn(true);
        MuSigMediationRequest request = createMediationRequest("trade-15", requester, peer);
        MuSigMediationCase mediationCase = new MuSigMediationCase(request);
        MuSigDisputeCaseDataMessage message = new MuSigDisputeCaseDataMessage(
                "trade-15",
                peer.getNetworkId(),
                new byte[20],
                List.of()
        );

        Optional<MuSigMediationCase> verifiedCase = MuSigMediatorService.verifyDisputeCaseDataMessage(
                message,
                tradeId -> tradeId.equals(mediationCase.getMuSigMediationRequest().getTradeId()) ? Optional.of(mediationCase) : Optional.empty(),
                bannedUserService
        );

        assertThat(verifiedCase).isEmpty();
    }

    private MuSigMediationRequest createMediationRequest(String tradeId,
                                                         UserProfile requester,
                                                         UserProfile peer) {
        return createMediationRequest(tradeId, requester, peer, createUserProfile(19000), List.of());
    }

    private MuSigMediationRequest createMediationRequest(String tradeId,
                                                         UserProfile requester,
                                                         UserProfile peer,
                                                         UserProfile mediator,
                                                         List<MuSigOpenTradeMessage> chatMessages) {
        return new MuSigMediationRequest(
                tradeId,
                createContract(requester, peer, mediator, "offer-" + tradeId,
                        createNationalBankPayload("taker-" + tradeId, "DE" + tradeId.substring(tradeId.length() - 2) + "1"),
                        createNationalBankPayload("maker-" + tradeId, "DE" + tradeId.substring(tradeId.length() - 2) + "2")),
                requester,
                peer,
                chatMessages,
                mediator.getNetworkId()
        );
    }

    private MuSigOpenTradeMessage createChatMessage(String tradeId,
                                                    String channelId,
                                                    UserProfile sender,
                                                    UserProfile receiver) {
        return new MuSigOpenTradeMessage(
                tradeId,
                "message-" + tradeId + "-" + sender.getId().substring(0, 6),
                channelId,
                sender,
                receiver.getId(),
                receiver.getNetworkId(),
                "message",
                Optional.empty(),
                System.currentTimeMillis(),
                false,
                Optional.empty(),
                Optional.empty(),
                ChatMessageType.TEXT,
                Optional.empty(),
                Set.of());
    }

    private MuSigContract createContract(UserProfile maker,
                                         UserProfile taker,
                                         UserProfile mediator,
                                         String offerId,
                                         AccountPayload<?> takerPayloadForHash,
                                         AccountPayload<?> makerPayloadForHash) {
        return createContractWithMediator(maker, taker, Optional.of(mediator), offerId, takerPayloadForHash, List.of(makerPayloadForHash));
    }

    private MuSigContract createContractWithMediator(UserProfile maker,
                                                     UserProfile taker,
                                                     Optional<UserProfile> mediator,
                                                     String offerId,
                                                     AccountPayload<?> takerPayloadForHash,
                                                     List<AccountPayload<?>> makerPayloadsForOfferOptions) {
        Market market = new Market("BTC", "EUR", "Bitcoin", "Euro");
        PaymentMethod<?> paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK);
        List<AccountOption> accountOptions = makerPayloadsForOfferOptions.stream()
                .map(payload -> new AccountOption(
                        paymentMethod,
                        "0123456789abcdef0123456789abcdef01234567",
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        OfferOptionUtil.createSaltedAccountPayloadHash(payload, offerId)
                ))
                .toList();
        MuSigOffer offer = new MuSigOffer(
                offerId,
                maker.getNetworkId(),
                Direction.BUY,
                market,
                new BaseSideFixedAmountSpec(100_000L),
                new MarketPriceSpec(),
                List.of(paymentMethod),
                withCollateral(accountOptions),
                "1.0.0"
        );
        PaymentMethodSpec<?> quoteSidePaymentMethodSpec = PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR");
        byte[] takerSaltedAccountPayloadHash = OfferOptionUtil.createSaltedAccountPayloadHash(takerPayloadForHash, offerId);
        return new MuSigContract(
                System.currentTimeMillis(),
                offer,
                taker.getNetworkId(),
                100_000L,
                3_500_000L,
                quoteSidePaymentMethodSpec,
                takerSaltedAccountPayloadHash,
                mediator,
                Optional.empty(),
                createPriceSpec(),
                0
        );
    }

    private PriceSpec createPriceSpec() {
        return new MarketPriceSpec();
    }

    private UserProfile createUserProfile(int port) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        PubKey pubKey = new PubKey(keyPair.getPublic(), "key-" + port);
        AddressByTransportTypeMap addresses = new AddressByTransportTypeMap(
                Map.of(TransportType.CLEAR, LocalHostAddressTypeFacade.toLocalHostAddress(port))
        );
        NetworkId networkId = new NetworkId(addresses, pubKey);
        ProofOfWork proofOfWork = new ProofOfWork(pubKey.getHash(), 0, null, 1.0, new byte[72], 0);
        UserProfile userProfile = new UserProfile(1, "nick-" + port, proofOfWork, 0, networkId, "", "", "1.0.0");
        return userProfile;
    }

    private AccountPayload<?> createNationalBankPayload(String id, String accountNr) {
        return new NationalBankAccountPayload(
                id,
                "DE",
                "EUR",
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                accountNr,
                Optional.empty(),
                Optional.empty()
        );
    }

    private static java.util.List<bisq.offer.options.OfferOption> withCollateral(java.util.List<? extends bisq.offer.options.OfferOption> options) {
        java.util.List<bisq.offer.options.OfferOption> result = new java.util.ArrayList<>(options.size() + 1);
        result.add(new bisq.offer.options.CollateralOption(0.25, 0.25));
        result.addAll(options);
        return result;
    }

}
