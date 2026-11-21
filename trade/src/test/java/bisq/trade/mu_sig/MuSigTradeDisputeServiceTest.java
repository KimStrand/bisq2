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

package bisq.trade.mu_sig;

import bisq.account.accounts.AccountPayload;
import bisq.account.accounts.fiat.NationalBankAccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.account.payment_method.PaymentMethodSpecUtil;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.common.market.Market;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.TransportType;
import bisq.common.network.clear_net_address_types.LocalHostAddressTypeFacade;
import bisq.contract.ContractService;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.CollateralOption;
import bisq.offer.options.OfferOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.offer.price.spec.PriceSpec;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.support.arbitration.ArbitrationPayoutDistributionType;
import bisq.support.arbitration.ArbitrationResultReason;
import bisq.support.arbitration.mu_sig.MuSigArbitrationClosedMessage;
import bisq.support.arbitration.mu_sig.MuSigArbitrationOpenedMessage;
import bisq.support.arbitration.mu_sig.MuSigArbitrationResult;
import bisq.support.arbitration.mu_sig.MuSigArbitrationResultService;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.MuSigDisputeState;
import bisq.trade.mu_sig.arbitration.MuSigTraderArbitrationService;
import bisq.trade.mu_sig.mediation.MuSigTraderMediationService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import java.security.GeneralSecurityException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.security.KeyPair;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigTradeDisputeServiceTest {
    private static final AtomicInteger FIXTURE_SEQUENCE = new AtomicInteger();

    private BannedUserService bannedUserService;
    private MuSigOpenTradeChannelService muSigOpenTradeChannelService;
    private MuSigTraderMediationService muSigTraderMediationService;
    private MuSigTraderArbitrationService muSigTraderArbitrationService;
    private AtomicInteger persistCalls;
    private Map<String, MuSigTrade> tradeById;
    private MuSigTradeDisputeService service;

    @BeforeEach
    void setUp() {
        bannedUserService = mock(BannedUserService.class);
        muSigOpenTradeChannelService = mock(MuSigOpenTradeChannelService.class);
        muSigTraderMediationService = mock(MuSigTraderMediationService.class);
        muSigTraderArbitrationService = mock(MuSigTraderArbitrationService.class);
        persistCalls = new AtomicInteger();
        tradeById = new HashMap<>();

        service = new MuSigTradeDisputeService(
                bannedUserService,
                muSigOpenTradeChannelService,
                muSigTraderMediationService,
                muSigTraderArbitrationService,
                tradeId -> Optional.ofNullable(tradeById.get(tradeId)),
                persistCalls::incrementAndGet
        );
    }

    @Test
    void givenTradeWithMediatorAndNoDispute_whenRequestMediation_thenSetsRequestedPersistsAndDelegates() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.NO_DISPUTE, true, false);
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);

        service.requestMediation(fixture.trade());

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_REQUESTED);
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderMediationService).requestMediation(
                fixture.tradeId(),
                fixture.identity(),
                fixture.peer(),
                fixture.mediator().orElseThrow(),
                fixture.contract(),
                channel);
    }

    @Test
    void givenTradeWithoutMediator_whenRequestMediation_thenKeepsStateAndDoesNotDelegate() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.NO_DISPUTE, false, false);
        stubOpenTradeChannel(fixture);

        service.requestMediation(fixture.trade());

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.NO_DISPUTE);
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @Test
    void givenTradeAlreadyInDispute_whenRequestMediation_thenKeepsStateAndDoesNotDelegate() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_OPEN, true, false);
        stubOpenTradeChannel(fixture);

        service.requestMediation(fixture.trade());

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_OPEN);
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @Test
    void givenTradeWithArbitratorAndClosedMediation_whenRequestArbitration_thenSetsRequestedPersistsAndDelegates() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_CLOSED, true, true);
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);
        fixture.tradeDispute().setMuSigMediationResult(createMediationResult());
        byte[] mediationResultSignature = signatureBytes();
        fixture.tradeDispute().setMediationResultSignature(mediationResultSignature);

        service.requestArbitration(fixture.trade());

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.ARBITRATION_REQUESTED);
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderArbitrationService).requestArbitration(
                fixture.tradeId(),
                fixture.identity(),
                fixture.peer(),
                fixture.arbitrator().orElseThrow(),
                fixture.contract(),
                fixture.tradeDispute().getMuSigMediationResult().orElseThrow(),
                mediationResultSignature,
                channel);
    }

    @Test
    void givenTradeNotMediationClosed_whenRequestArbitration_thenKeepsStateAndDoesNotDelegate() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_OPEN, true, true);
        stubOpenTradeChannel(fixture);

        service.requestArbitration(fixture.trade());

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_OPEN);
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderArbitrationService);
    }

    @Test
    void givenAuthorizedOpenMediationMessage_whenOnDisputeMessage_thenTransitionsToMediationOpenAndPersists() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_REQUESTED, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);

        MuSigMediationOpenedMessage message = new MuSigMediationOpenedMessage(
                "msg-8",
                fixture.tradeId(),
                fixture.mediator().orElseThrow().getNetworkId()
        );

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_OPEN);
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderMediationService).applyMediationStateToChannel(
                fixture.tradeId(),
                MuSigDisputeState.MEDIATION_OPEN,
                MuSigDisputeState.MEDIATION_REQUESTED,
                channel);
    }

    @Test
    void givenClosedMediation_whenOpenMessageArrives_thenStaysClosedAndDoesNotPersist() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_CLOSED, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);

        MuSigMediationOpenedMessage message = new MuSigMediationOpenedMessage(
                "msg-after-close",
                fixture.tradeId(),
                fixture.mediator().orElseThrow().getNetworkId()
        );

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_CLOSED);
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @Test
    void givenClosedMediationMessageWithInvalidSignature_whenOnDisputeMessage_thenStaysOpenAndStoresNoResult() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_OPEN, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);

        MuSigMediationClosedMessage message = new MuSigMediationClosedMessage(
                "msg-invalid-close",
                fixture.tradeId(),
                fixture.mediator().orElseThrow().getNetworkId(),
                createMediationResult(),
                signatureBytes()
        );

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_OPEN);
        assertThat(fixture.tradeDispute().getMuSigMediationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @Test
    void givenAuthorizedClosedMediationMessage_whenOnDisputeMessage_thenTransitionsToMediationClosedAndStoresResult() throws Exception {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_OPEN, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);
        MuSigMediationClosedMessage message = createSignedMediationClosedMessage(fixture,
                fixture.mediator().orElseThrow().getNetworkId());

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.MEDIATION_CLOSED);
        assertThat(fixture.tradeDispute().getMuSigMediationResult()).contains(message.getMuSigMediationResult());
        assertThat(fixture.tradeDispute().getMediationResultSignature().orElseThrow())
                .isEqualTo(message.getMediationResultSignature());
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderMediationService).applyMediationStateToChannel(
                fixture.tradeId(),
                MuSigDisputeState.MEDIATION_CLOSED,
                MuSigDisputeState.MEDIATION_OPEN,
                channel);
    }

    @ParameterizedTest(name = "closed={0}")
    @ValueSource(booleans = {false, true})
    void givenMediationMessageFromAnotherSender_whenOnDisputeMessage_thenKeepsStateAndDoesNotPersist(boolean closed) throws Exception {
        MuSigDisputeState state = closed ? MuSigDisputeState.MEDIATION_OPEN : MuSigDisputeState.MEDIATION_REQUESTED;
        TradeFixture fixture = createTradeFixture(state, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);
        // The peer knows the trade ID. After the case is closed it also has the result which the mediator signed.
        NetworkId sender = fixture.takerProfile().getNetworkId();
        EnvelopePayloadMessage message = closed
                ? createSignedMediationClosedMessage(fixture, sender)
                : new MuSigMediationOpenedMessage("msg-other-sender", fixture.tradeId(), sender);

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(state);
        assertThat(fixture.tradeDispute().getMuSigMediationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @ParameterizedTest(name = "closed={0}")
    @ValueSource(booleans = {false, true})
    void givenMediationMessageFromBannedMediator_whenOnDisputeMessage_thenKeepsStateAndDoesNotPersist(boolean closed) throws Exception {
        MuSigDisputeState state = closed ? MuSigDisputeState.MEDIATION_OPEN : MuSigDisputeState.MEDIATION_REQUESTED;
        TradeFixture fixture = createTradeFixture(state, true, false);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);
        NetworkId mediator = fixture.mediator().orElseThrow().getNetworkId();
        when(bannedUserService.isUserProfileBanned(mediator)).thenReturn(true);
        EnvelopePayloadMessage message = closed
                ? createSignedMediationClosedMessage(fixture, mediator)
                : new MuSigMediationOpenedMessage("msg-banned-mediator", fixture.tradeId(), mediator);

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(state);
        assertThat(fixture.tradeDispute().getMuSigMediationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderMediationService);
    }

    @Test
    void givenQueuedPaymentDetailsRequestWithoutChannel_whenMaybeProcessPendingDisputeMessages_thenReplaysAndSendsResponse() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_OPEN, true, false);
        fixture.taker().setAccountPayload(createNationalBankPayload("taker-9", "DE901"));
        fixture.maker().setAccountPayload(createNationalBankPayload("maker-9", "DE902"));
        tradeById.put(fixture.tradeId(), fixture.trade());
        when(muSigOpenTradeChannelService.findChannelByTradeId(fixture.tradeId()))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(createOpenTradeChannel(fixture)));

        MuSigDisputeCasePaymentDetailsRequest message =
                new MuSigDisputeCasePaymentDetailsRequest(fixture.tradeId(), fixture.mediator().orElseThrow().getNetworkId());

        service.onDisputeMessage(message);
        assertThat(persistCalls.get()).isZero();

        service.maybeProcessPendingDisputeMessages(fixture.tradeId());

        verify(muSigTraderMediationService).sendDisputeCasePaymentDetailsResponse(
                fixture.tradeId(),
                fixture.identity(),
                fixture.mediator().orElseThrow().getNetworkId(),
                fixture.taker().getAccountPayload().orElseThrow(),
                fixture.maker().getAccountPayload().orElseThrow());
    }

    @Test
    void givenAuthorizedOpenArbitrationMessage_whenOnDisputeMessage_thenTransitionsToArbitrationOpenAndPersists() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.MEDIATION_CLOSED, true, true);
        tradeById.put(fixture.tradeId(), fixture.trade());
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);

        MuSigArbitrationOpenedMessage message = new MuSigArbitrationOpenedMessage(
                "msg-11",
                fixture.tradeId(),
                fixture.arbitrator().orElseThrow().getNetworkId()
        );

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.ARBITRATION_OPEN);
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderArbitrationService).applyArbitrationStateToChannel(
                fixture.tradeId(),
                MuSigDisputeState.ARBITRATION_OPEN,
                MuSigDisputeState.MEDIATION_CLOSED,
                channel);
    }

    @Test
    void givenClosedArbitrationMessageWithInvalidSignature_whenOnDisputeMessage_thenStaysOpenAndStoresNoResult() {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.ARBITRATION_OPEN, true, true);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);

        MuSigArbitrationClosedMessage message = new MuSigArbitrationClosedMessage(
                "msg-invalid-close",
                fixture.tradeId(),
                fixture.arbitrator().orElseThrow().getNetworkId(),
                new MuSigArbitrationResult(new byte[20],
                        ArbitrationResultReason.OTHER,
                        ArbitrationPayoutDistributionType.CUSTOM_PAYOUT,
                        60_000L,
                        40_000L,
                        Optional.empty()),
                signatureBytes()
        );

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.ARBITRATION_OPEN);
        assertThat(fixture.tradeDispute().getMuSigArbitrationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderArbitrationService);
    }

    @Test
    void givenAuthorizedClosedArbitrationMessage_whenOnDisputeMessage_thenTransitionsToArbitrationClosedAndStoresResult() throws Exception {
        TradeFixture fixture = createTradeFixture(MuSigDisputeState.ARBITRATION_OPEN, true, true);
        tradeById.put(fixture.tradeId(), fixture.trade());
        MuSigOpenTradeChannel channel = stubOpenTradeChannel(fixture);
        MuSigArbitrationClosedMessage message = createSignedArbitrationClosedMessage(fixture,
                fixture.arbitrator().orElseThrow().getNetworkId());

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(MuSigDisputeState.ARBITRATION_CLOSED);
        assertThat(fixture.tradeDispute().getMuSigArbitrationResult()).contains(message.getMuSigArbitrationResult());
        assertThat(fixture.tradeDispute().getArbitrationResultSignature().orElseThrow())
                .isEqualTo(message.getArbitrationResultSignature());
        assertThat(persistCalls.get()).isEqualTo(1);
        verify(muSigTraderArbitrationService).applyArbitrationStateToChannel(
                fixture.tradeId(),
                MuSigDisputeState.ARBITRATION_CLOSED,
                MuSigDisputeState.ARBITRATION_OPEN,
                channel);
    }

    @ParameterizedTest(name = "closed={0}")
    @ValueSource(booleans = {false, true})
    void givenArbitrationMessageFromAnotherSender_whenOnDisputeMessage_thenKeepsStateAndDoesNotPersist(boolean closed) throws Exception {
        MuSigDisputeState state = closed ? MuSigDisputeState.ARBITRATION_OPEN : MuSigDisputeState.ARBITRATION_REQUESTED;
        TradeFixture fixture = createTradeFixture(state, true, true);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);
        // The peer knows the trade ID. After the case is closed it also has the result which the arbitrator signed.
        NetworkId sender = fixture.takerProfile().getNetworkId();
        EnvelopePayloadMessage message = closed
                ? createSignedArbitrationClosedMessage(fixture, sender)
                : new MuSigArbitrationOpenedMessage("msg-other-sender", fixture.tradeId(), sender);

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(state);
        assertThat(fixture.tradeDispute().getMuSigArbitrationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderArbitrationService);
    }

    @ParameterizedTest(name = "closed={0}")
    @ValueSource(booleans = {false, true})
    void givenArbitrationMessageFromBannedArbitrator_whenOnDisputeMessage_thenKeepsStateAndDoesNotPersist(boolean closed) throws Exception {
        MuSigDisputeState state = closed ? MuSigDisputeState.ARBITRATION_OPEN : MuSigDisputeState.ARBITRATION_REQUESTED;
        TradeFixture fixture = createTradeFixture(state, true, true);
        tradeById.put(fixture.tradeId(), fixture.trade());
        stubOpenTradeChannel(fixture);
        NetworkId arbitrator = fixture.arbitrator().orElseThrow().getNetworkId();
        when(bannedUserService.isUserProfileBanned(arbitrator)).thenReturn(true);
        EnvelopePayloadMessage message = closed
                ? createSignedArbitrationClosedMessage(fixture, arbitrator)
                : new MuSigArbitrationOpenedMessage("msg-banned-arbitrator", fixture.tradeId(), arbitrator);

        service.onDisputeMessage(message);

        assertThat(fixture.tradeDispute().getDisputeState()).isEqualTo(state);
        assertThat(fixture.tradeDispute().getMuSigArbitrationResult()).isEmpty();
        assertThat(persistCalls.get()).isZero();
        verifyNoInteractions(muSigTraderArbitrationService);
    }

    private TradeFixture createTradeFixture(MuSigDisputeState disputeState,
                                            boolean includeMediator,
                                            boolean includeArbitrator) {
        int index = FIXTURE_SEQUENCE.incrementAndGet();
        String tradeId = UUID.randomUUID().toString();
        int offset = 100 + index;
        UserProfile maker = createUserProfile(20_000 + offset);
        UserProfile taker = createUserProfile(21_000 + offset);
        KeyPair mediatorKeyPair = KeyGeneration.generateDefaultEcKeyPair();
        KeyPair arbitratorKeyPair = KeyGeneration.generateDefaultEcKeyPair();
        Optional<UserProfile> mediator = includeMediator ? Optional.of(createUserProfile(22_000 + offset, mediatorKeyPair)) : Optional.empty();
        Optional<UserProfile> arbitrator = includeArbitrator ? Optional.of(createUserProfile(23_000 + offset, arbitratorKeyPair)) : Optional.empty();

        AccountPayload<?> takerPayload = createNationalBankPayload("taker-" + index, "DE" + offset + "01");
        AccountPayload<?> makerPayload = createNationalBankPayload("maker-" + index, "DE" + offset + "02");
        MuSigContract contract = createContract(maker, taker, mediator, arbitrator, "offer-" + tradeId, takerPayload, makerPayload);

        Identity identity = createIdentity(maker.getNetworkId(), "identity-" + tradeId);
        MuSigTrade trade = new MuSigTrade(
                contract,
                true,
                false,
                identity,
                contract.getOffer(),
                taker.getNetworkId(),
                maker.getNetworkId()
        );
        MuSigTradeDispute tradeDispute = trade.getTradeDispute();
        tradeDispute.setDisputeState(disputeState);
        MuSigTradeParty myself = trade.getMyself();
        MuSigTradeParty peer = trade.getPeer();
        MuSigTradeParty takerParty = trade.getTaker();
        MuSigTradeParty makerParty = trade.getMaker();

        return new TradeFixture(trade.getId(), trade, tradeDispute, contract, identity, myself, peer, takerParty, makerParty, maker, taker,
                mediator, arbitrator, mediatorKeyPair, arbitratorKeyPair);
    }

    private MuSigContract createContract(UserProfile maker,
                                         UserProfile taker,
                                         Optional<UserProfile> mediator,
                                         Optional<UserProfile> arbitrator,
                                         String offerId,
                                         AccountPayload<?> takerPayloadForHash,
                                         AccountPayload<?> makerPayloadForHash) {
        Market market = new Market("BTC", "EUR", "Bitcoin", "Euro");
        PaymentMethod<?> paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK);
        List<OfferOption> accountOptions = List.of(
                new CollateralOption(0.25, 0.25),
                new AccountOption(
                        paymentMethod,
                        "0123456789abcdef0123456789abcdef01234567",
                        Optional.empty(),
                        List.of(),
                        Optional.empty(),
                        List.of(),
                        OfferOptionUtil.createSaltedAccountPayloadHash(makerPayloadForHash, offerId)
                ));
        MuSigOffer offer = new MuSigOffer(
                offerId,
                maker.getNetworkId(),
                Direction.BUY,
                market,
                new BaseSideFixedAmountSpec(100_000L),
                new MarketPriceSpec(),
                List.of(paymentMethod),
                accountOptions,
                "1.0.0"
        );
        PaymentMethodSpec<?> quoteSidePaymentMethodSpec = PaymentMethodSpecUtil.createPaymentMethodSpec(paymentMethod, "EUR");
        return new MuSigContract(
                System.currentTimeMillis(),
                offer,
                taker.getNetworkId(),
                100_000L,
                3_500_000L,
                quoteSidePaymentMethodSpec,
                OfferOptionUtil.createSaltedAccountPayloadHash(takerPayloadForHash, offerId),
                mediator,
                arbitrator,
                createPriceSpec(),
                0
        );
    }

    private PriceSpec createPriceSpec() {
        return new MarketPriceSpec();
    }

    private UserProfile createUserProfile(int port) {
        return createUserProfile(port, KeyGeneration.generateDefaultEcKeyPair());
    }

    private UserProfile createUserProfile(int port, KeyPair keyPair) {
        PubKey pubKey = new PubKey(keyPair.getPublic(), "key-" + port);
        AddressByTransportTypeMap addresses = new AddressByTransportTypeMap(
                Map.of(TransportType.CLEAR, LocalHostAddressTypeFacade.toLocalHostAddress(port))
        );
        NetworkId networkId = new NetworkId(addresses, pubKey);
        ProofOfWork proofOfWork = new ProofOfWork(pubKey.getHash(), 0, null, 1.0, new byte[72], 0);
        return new UserProfile(1, "nick-" + port, proofOfWork, 0, networkId, "", "", "1.0.0");
    }

    private Identity createIdentity(NetworkId networkId, String tag) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        KeyBundle keyBundle = new KeyBundle(
                tag,
                keyPair,
                TorKeyGeneration.generateKeyPair(),
                I2PKeyGeneration.generateKeyPair()
        );
        return new Identity(tag, networkId, keyBundle);
    }

    private MuSigOpenTradeChannel createOpenTradeChannel(TradeFixture fixture) {
        return MuSigOpenTradeChannel.create(
                fixture.tradeId(),
                new UserIdentity(fixture.identity(), fixture.makerProfile()),
                java.util.Set.of(fixture.makerProfile(), fixture.takerProfile()),
                fixture.mediator(),
                fixture.arbitrator(),
                MuSigDisputeAgentType.NONE
        );
    }

    private MuSigOpenTradeChannel stubOpenTradeChannel(TradeFixture fixture) {
        MuSigOpenTradeChannel channel = createOpenTradeChannel(fixture);
        when(muSigOpenTradeChannelService.findChannelByTradeId(fixture.tradeId())).thenReturn(Optional.of(channel));
        return channel;
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

    private MuSigMediationResult createMediationResult() {
        return new MuSigMediationResult(
                new byte[20],
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(60_000L),
                Optional.of(40_000L),
                Optional.empty(),
                Optional.empty()
        );
    }

    private byte[] signatureBytes() {
        return new byte[70];
    }

    /** A closed message with a result which the trade's mediator signed. The sender can be another node. */
    private MuSigMediationClosedMessage createSignedMediationClosedMessage(TradeFixture fixture,
                                                                           NetworkId sender) throws GeneralSecurityException {
        MuSigMediationResult result = new MuSigMediationResult(
                ContractService.getContractHash(fixture.contract()),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(90_000L),
                Optional.of(60_000L),
                Optional.empty(),
                Optional.empty());
        return new MuSigMediationClosedMessage("msg-close", fixture.tradeId(), sender, result,
                MuSigMediationResultService.signMediationResult(result, fixture.mediatorKeyPair()));
    }

    /** A closed message with a result which the trade's arbitrator signed. The sender can be another node. */
    private MuSigArbitrationClosedMessage createSignedArbitrationClosedMessage(TradeFixture fixture,
                                                                               NetworkId sender) throws GeneralSecurityException {
        MuSigArbitrationResult result = new MuSigArbitrationResult(
                ContractService.getContractHash(fixture.contract()),
                ArbitrationResultReason.OTHER,
                ArbitrationPayoutDistributionType.CUSTOM_PAYOUT,
                90_000L,
                60_000L,
                Optional.empty());
        return new MuSigArbitrationClosedMessage("msg-close", fixture.tradeId(), sender, result,
                MuSigArbitrationResultService.signArbitrationResult(result, fixture.arbitratorKeyPair()));
    }

    private record TradeFixture(String tradeId,
                                MuSigTrade trade,
                                MuSigTradeDispute tradeDispute,
                                MuSigContract contract,
                                Identity identity,
                                MuSigTradeParty myself,
                                MuSigTradeParty peer,
                                MuSigTradeParty taker,
                                MuSigTradeParty maker,
                                UserProfile makerProfile,
                                UserProfile takerProfile,
                                Optional<UserProfile> mediator,
                                Optional<UserProfile> arbitrator,
                                KeyPair mediatorKeyPair,
                                KeyPair arbitratorKeyPair) {
    }
}
