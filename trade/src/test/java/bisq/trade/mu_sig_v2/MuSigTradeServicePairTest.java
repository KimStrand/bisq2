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
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelStore;
import bisq.common.data.ByteArray;
import bisq.common.market.Market;
import bisq.common.monetary.PriceQuote;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.network.ClearnetAddress;
import bisq.common.network.TransportType;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.observable.map.HashMapObserver;
import bisq.common.processing.Effect;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.util.ExceptionUtil;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.contract.mu_sig.MuSigContract;
import bisq.i18n.Res;
import bisq.identity.Identity;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
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
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
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
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.protobuf.CustomPayoutPsbt;
import bisq.trade.protobuf.MusigGrpc;
import bisq.trade.protobuf.Role;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static bisq.offer.options.OfferOptionUtil.createdSaltedAccountId;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithCapturedTimers;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Two independent services exchange serialized messages and persist trades, with simulated local dependencies. */
class MuSigTradeServicePairTest {
    private static final String PAYOUT_TX_ID = "12".repeat(32);
    @TempDir
    Path directory;
    private final ContractService contractService = new ContractService(null);
    private final FiatPaymentMethod paymentMethod = FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.REVOLUT);
    private final KeyPair mediatorKeys = KeyGeneration.generateDefaultEcKeyPair();
    private final Map<Peer, Map<Class<?>, CompletableFuture<MuSigV2Trade<?>>>> deliveries = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<MuSigTradeMessage> messages = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<MuSigMediationRequest> mediationRequests = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<MuSigDisputeCaseDataMessage> mediationCaseData = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<String> messageThreads = new ConcurrentLinkedQueue<>();
    private final CompletableFuture<Void> deliveryFailure = new CompletableFuture<>();
    private Peer buyer;
    private Peer seller;
    private MuSigOffer offer;
    private MuSigContract contract;
    private MuSigMediationResult mediationResult;
    private String tradeId;
    private volatile boolean automaticDelivery = true;

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

    private void configureOffer(Direction direction) {
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
                maker.identity.getNetworkId(),
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
        tradeId = TradeV2.createId(offerId, taker.identity.getNetworkId().getId(), contract.getTakeOfferDate());
        mediationResult = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(2_400_000L),
                Optional.of(600_000L),
                Optional.empty(),
                Optional.empty());
        when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offerId)).thenReturn(Optional.of(offer));
        when(maker.services.getIdentityService().findAnyIdentityByNetworkId(maker.identity.getNetworkId()))
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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedPreparationDoesNotRegisterOrPersistATrade(boolean haltTrading) throws Exception {
        var taker = taker();
        taker.alerts.add(restriction(haltTrading));

        var failure = assertInstanceOf(TradeRestrictedException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(taker.service,
                        initializeTrade(contract, taker.identity, taker.account.getAccountPayload())))).getCause());
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertTrue(taker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(taker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend, taker.asyncBackend);
        verifyNoInteractions(taker.services.getChatService().getMuSigOpenTradeChannelService());
        assertTrue(messages.isEmpty());
        assertTrue(taker.logs.isEmpty());

        taker.alerts.clear();
        assertFailure(IllegalArgumentException.class, resumeLane(taker.service, tradeId));
        var prepared = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        assertInstanceOf(TradeInitialized.class, prepared.getExecution().state());
        assertSame(prepared, savedRecord(taker.service, tradeId).trade());
        assertEquals(1, taker.service.getPersistableStore().getTrades().size());
        verify(taker.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void takerAdmissionRechecksRestrictionsAfterSubmission(boolean haltTrading) throws Exception {
        automaticDelivery = false;
        var taker = taker();
        var admitting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenAnswer(invocation -> {
                    admitting.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return taker.alerts;
                });
        var command = initializeTrade(contract, taker.identity, taker.account.getAccountPayload());
        var pending = taker.service.initializeTrade(tradeId, command);
        try {
            assertTrue(admitting.await(15, TimeUnit.SECONDS));
            taker.alerts.add(restriction(haltTrading));
        } finally {
            release.countDown();
        }

        var failure = assertFailure(TradeRestrictedException.class, pending);
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertTrue(taker.service.getPersistableStore().getTrades().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend, taker.asyncBackend);
        verifyNoInteractions(taker.services.getChatService().getMuSigOpenTradeChannelService());
        assertTrue(messages.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void takerAdmissionKeepsTheSubmittedTermsWhileTheRequestWaits(boolean buyerAsTaker) throws Exception {
        configureOffer(buyerAsTaker ? Direction.SELL : Direction.BUY);
        automaticDelivery = false;
        var taker = taker();
        var admitting = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(taker.alerts)
                .thenAnswer(invocation -> {
                    admitting.countDown();
                    assertTrue(release.await(30, TimeUnit.SECONDS));
                    return taker.alerts;
                });
        var command = initializeTrade(contract, taker.identity, taker.account.getAccountPayload());
        var pending = taker.service.initializeTrade(tradeId, command);
        try {
            assertTrue(admitting.await(15, TimeUnit.SECONDS));
            when(taker.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenReturn(Optional.empty());
            when(taker.services.getMuSigTradeService().getMuSigTraderArbitrationService()
                    .selectArbitrator(anyString(), anyString(), anyString(), any())).thenReturn(Optional.empty());
            when(taker.services.getBondedRolesService().getMarketPriceService().findMarketPrice(any()))
                    .thenReturn(Optional.empty());
        } finally {
            release.countDown();
        }
        await(pending);

        assertEquals(contract, taker.saved().trade().getContract());
        assertEquals(contract, sent(SetupTradeMessage_A.class).getContract());
        verify(taker.services.getMuSigTradeService().getMuSigTraderMediationService(), never())
                .selectMediator(anyString(), anyString(), anyString());
        verify(taker.services.getMuSigTradeService().getMuSigTraderArbitrationService(), never())
                .selectArbitrator(anyString(), anyString(), anyString(), any());
        verify(taker.services.getBondedRolesService().getMarketPriceService(), never()).findMarketPrice(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aRestrictedInitializerIsNotRetainedOrReplayed(boolean haltTrading) throws Exception {
        var taker = taker();
        taker.alerts.add(restriction(haltTrading));
        assertFailure(TradeRestrictedException.class,
                initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        taker.alerts.clear();
        assertThrows(IllegalArgumentException.class, () -> taker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(taker.backend);
        await(initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        verify(taker.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"banned-sender", "trading-halted", "minimum-version"})
    void aRefusedMakerRequestDoesNotCreateATradeOrSendARejection(String reason) throws Exception {
        automaticDelivery = false;
        var maker = maker();
        var request = request();
        var bans = maker.services.getUserService().getBannedUserService();
        boolean bannedSender = reason.equals("banned-sender");
        if (bannedSender) {
            when(bans.isUserProfileBanned(request.getSender())).thenReturn(true);
        } else {
            maker.alerts.add(restriction(reason.equals("trading-halted")));
        }

        if (bannedSender) {
            var failure = assertFailure(MuSigProtocolException.class, submitMessage(maker.service, request));
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        } else {
            var failure = assertFailure(TradeRestrictedException.class, submitMessage(maker.service, request));
            assertEquals(reason.equals("trading-halted") ? TradeRestrictedException.Restriction.HALT_TRADING
                    : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        }
        maker.service.onMessage(request);
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        assertTrue(messages.isEmpty());
        verifyNoInteractions(maker.backend, maker.asyncBackend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());

        when(bans.isUserProfileBanned(request.getSender())).thenReturn(false);
        maker.alerts.clear();
        assertFailure(IllegalArgumentException.class, resumeLane(maker.service, tradeId));
        await(submitMessage(maker.service, request));
        var trade = savedRecord(maker.service, request.getTradeId()).trade();
        assertEquals(tradeId, trade.getId());
        verify(maker.backend, times(1)).initTrade(any());
        assertTrue(messages.stream().noneMatch(MuSigReportErrorMessage.class::isInstance));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedPaymentCommandsDoNotChangeTheTradeOrRunLater(boolean haltTrading) throws Exception {
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();

        assertTradingCommandRejected(buyer, haltTrading, () -> buyer.service.paymentInitiated(tradeId));
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);

        assertTradingCommandRejected(seller, haltTrading, () -> seller.service.paymentReceiptConfirmed(tradeId));
        await(seller.service.paymentReceiptConfirmed(tradeId));
        awaitDelivery(seller, CooperativeClosureMessage_G.class);
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(buyer.service, tradeId, TradeClosed.class)));
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(seller.service, tradeId, TradeClosed.class)));
        verify(seller.backend, times(1)).signSwapTx(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restrictedMediationAcceptanceDoesNotCommitOrSign(boolean haltTrading) throws Exception {
        prepareMediation(false);

        assertTradingCommandRejected(buyer, haltTrading, () -> buyer.service.acceptMediationResult(tradeId));
        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);

        assertTradingCommandRejected(seller, haltTrading, () -> seller.service.acceptMediationResult(tradeId));
        await(seller.service.acceptMediationResult(tradeId));
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertBothClosedWithMediation();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void mediationRequestAndRejectionRemainAvailableDuringTradingRestrictions(boolean haltTrading) throws Exception {
        startTrade();
        automaticDelivery = false;
        prepareTradeChat(buyer);
        buyer.alerts.add(restriction(haltTrading));
        seller.alerts.add(restriction(haltTrading));

        await(buyer.service.requestMediation(tradeId));
        var requested = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(1, mediationRequests.size());

        publishMediationResult();
        await(buyer.service.rejectMediationResult(tradeId));
        var rejected = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationDecisionState.Rejected.class,
                rejected.getMediation().orElseThrow().myDecision().state());
        assertInstanceOf(MuSigTradeState.Active.class, rejected.getState());
        assertEquals(1, messages.stream().filter(MuSigMediationResultRejectionMessage.class::isInstance).count());
        verify(buyer.backend, never()).signCustomPayoutTx(any());
    }

    @Test
    void aLocalBanRejectsMediationWithoutRetainingTheRequestButAPeerBanDoesNot() throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var before = savedRecord(buyer.service, tradeId).trade();
        var saved = buyer.saved().toProto(false);
        var bans = buyer.services.getUserService().getBannedUserService();
        when(bans.isUserProfileBanned(buyer.identity.getNetworkId())).thenReturn(true);
        clearInvocations(buyer.backend, buyer.asyncBackend);

        assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                assertFailure(MuSigProtocolException.class, buyer.service.requestMediation(tradeId)).getCategory());

        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        assertEquals(saved, buyer.saved().toProto(false));
        assertEquals(MuSigDisputeAgentType.NONE, channel.getDisputeAgentType());
        assertTrue(mediationRequests.isEmpty());
        verifyNoInteractions(buyer.backend, buyer.asyncBackend);

        when(bans.isUserProfileBanned(buyer.identity.getNetworkId())).thenReturn(false);
        await(resumeLane(buyer.service, tradeId));
        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        await(buyer.service.shutdown());
        buyer.confirmation = new CompletableFuture<>();
        buyer.start(seller);
        assertEquals(saved, buyer.saved().toProto(false));
        assertTrue(mediationRequests.isEmpty());
        verifyNoInteractions(buyer.backend);

        when(bans.isUserProfileBanned(seller.identity.getNetworkId())).thenReturn(true);
        await(buyer.service.requestMediation(tradeId));
        var requested = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(1, mediationRequests.size());
    }

    @Test
    void aRestrictedCommandDoesNotWaitForTheTradeWorker() throws Exception {
        var taker = taker();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(taker.backend.initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return bisq.trade.protobuf.PubKeySharesResponse.getDefaultInstance();
        });
        var starting = initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload()));
        try {
            assertTrue(entered.await(15, TimeUnit.SECONDS));
            taker.alerts.add(restriction(true));

            var rejected = taker.service.paymentReceiptConfirmed(tradeId);
            assertTrue(rejected.isCompletedExceptionally());
            assertFailure(TradeRestrictedException.class, rejected);
            assertFalse(starting.isDone());
        } finally {
            taker.alerts.clear();
            release.countDown();
        }
        await(starting);
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        verify(taker.backend, times(1)).initTrade(any());
    }

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
        verify(maker.services.getNetworkService()).addConfidentialMessageListener(listener.capture());
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
            assertInstanceOf(SetupTradeMessage_A.class, saved.pendingInputs().getFirst().event());
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
        order.verify(channels).findChannelByTradeId(tradeId);
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
            assertEquals(Flow.EXECUTION, saved.pendingInputs().getFirst().flow());
            assertInstanceOf(MuSigTakeOfferEvent.class, saved.pendingInputs().getFirst().event());
            assertTrue(saved.toCheckpoint().pendingWork().getFirst().command());
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
            await(resumeLane(taker.service, prepared.getId()));

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
    void makerReusesAnExistingChat(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        automaticDelivery = false;
        var maker = maker();
        var channels = maker.services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.of(mock(MuSigOpenTradeChannel.class)));

        await(submitMessage(maker.service, request()));

        verify(channels, never()).traderFindOrCreatesChannel(anyString(), any(), any(), any(), any());
        assertEquals(List.of(SetupTradeMessage_B.class), messages.stream().map(Object::getClass).toList());
        assertTrue(maker.saved().pendingEffects().isEmpty());
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
        assertEquals(request, saved.pendingInputs().getFirst().event());
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
        await(resumeLane(maker.service, tradeId));

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
            assertInstanceOf(SetupTradeMessage_A.class, saved.pendingInputs().getFirst().event());
            verify(maker.backend, never()).initTrade(any());
            verify(maker.backend, never()).getNonceShares(any());
            assertTrue(messages.isEmpty());

            when(chatPersistence.persistAsync(any())).thenReturn(CompletableFuture.completedFuture(null));
            if (restart) {
                await(maker.service.shutdown());
                maker.start(taker());
            } else {
                await(resumeLane(maker.service, tradeId));
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buyerTimeoutKeepsTradeOpenAcrossRestartAndAllowsLaterClosure(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        var waiting = savedRecord(buyer.service, tradeId).trade();
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        var timeout = buyer.saved().scheduledEvents().getFirst();
        assertInstanceOf(BuyersCloseTradeTimeoutEvent.class, timeout.event());

        buyer.service.shutdown().join();
        buyer.start(seller);
        assertEquals(List.of(timeout), buyer.saved().scheduledEvents());
        assertEquals(1, buyer.timers.size());

        var timeoutCallback = buyer.timers.getFirst();
        timeoutCallback.run();
        await(resumeLane(buyer.service, tradeId));
        var afterTimeout = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(PaymentInitiated.class, afterTimeout.getExecution().state());
        assertEquals(waiting.toProto(false), afterTimeout.toProto(false));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(buyer.saved().processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of())
                .contains(BuyersCloseTradeTimeoutEvent.class));
        verify(buyer.backend, never()).closeTrade(any());

        timeoutCallback.run();
        await(resumeLane(buyer.service, tradeId));
        assertSame(afterTimeout, savedRecord(buyer.service, tradeId).trade());

        buyer.service.shutdown().join();
        buyer.start(seller);
        var restored = savedRecord(buyer.service, tradeId).trade();
        assertEquals(afterTimeout.toProto(false), restored.toProto(false));
        assertTrue(buyer.timers.isEmpty());
        timeoutCallback.run();
        await(resumeLane(buyer.service, tradeId));
        assertSame(restored, savedRecord(buyer.service, tradeId).trade());
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        verify(buyer.backend, never()).closeTrade(any());

        await(seller.service.paymentReceiptConfirmed(tradeId));
        awaitDelivery(buyer, PaymentReceivedMessage_F.class);
        awaitDelivery(seller, CooperativeClosureMessage_G.class);
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(buyer.service, tradeId, TradeClosed.class)));
        assertInstanceOf(TradeClosed.class,
                await(whenExecutionState(seller.service, tradeId, TradeClosed.class)));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
    }

    @Test
    void sellerAsMakerCanForceCloseAfterPaymentReceiptConfirmation() throws Exception {
        configureOffer(Direction.SELL);
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        await(buyer.service.paymentInitiated(tradeId));
        awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        automaticDelivery = false;
        await(seller.service.paymentReceiptConfirmed(tradeId));
        assertInstanceOf(PaymentReceiptConfirmed.class,
                await(whenExecutionState(seller.service, tradeId, PaymentReceiptConfirmed.class)));
        assertEquals(1, seller.saved().scheduledEvents().size());

        assertEquals(1, seller.timers.size());
        seller.timers.getFirst().run();
        await(resumeLane(seller.service, tradeId));

        assertInstanceOf(SellerForceClosedTrade.class,
                await(whenExecutionState(seller.service, tradeId, SellerForceClosedTrade.class)));
        verify(seller.backend).closeTrade(argThat(request -> request.getMyOutputPeersPrvKeyShare().isEmpty()));
        verify(buyer.backend, never()).closeTrade(any());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        assertInstanceOf(SellerForceClosedTrade.class, seller.saved().trade().getExecution().state());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void sellerAsMakerDoesNotScheduleCloseTimeoutAfterCustomSigningAndRestart(boolean paymentInitiated) throws Exception {
        configureOffer(Direction.SELL);
        prepareMediation(paymentInitiated);
        await(seller.service.acceptMediationResult(tradeId));
        var signed = savedRecord(seller.service, tradeId).trade();
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertInstanceOf(CustomPayoutSigned.class, signed.getExecution().state());

        assertTrue(seller.timers.isEmpty());
        await(resumeLane(seller.service, tradeId));
        assertSame(signed, savedRecord(seller.service, tradeId).trade());
        assertEquals(signed.toProto(false), seller.saved().trade().toProto(false));
        assertTrue(seller.saved().pendingInputs().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        verify(seller.backend, never()).closeTrade(any());
        verify(seller.backend, never()).customCloseTrade(any());

        seller.service.shutdown().join();
        seller.start(buyer);
        var restored = savedRecord(seller.service, tradeId).trade();
        assertEquals(signed.toProto(false), restored.toProto(false));
        assertTrue(seller.timers.isEmpty());
        await(resumeLane(seller.service, tradeId));
        assertSame(restored, savedRecord(seller.service, tradeId).trade());
        assertTrue(seller.saved().pendingInputs().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        verify(seller.backend, never()).closeTrade(any());
        verify(seller.backend, never()).customCloseTrade(any());

        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
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
        var pending = taker.service.initializeTrade(tradeId, command);
        try (var callers = Executors.newSingleThreadExecutor()) {
            try {
                assertTrue(preparing.await(15, TimeUnit.SECONDS));
                var submission = CompletableFuture.supplyAsync(taker.service::shutdown, callers);
                var stopped = await(submission);
                assertFalse(stopped.isDone());
                assertFalse(pending.isDone());
                assertFailure(RejectedExecutionException.class, taker.service.initializeTrade(tradeId, command));
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

    @ParameterizedTest
    @ValueSource(strings = {"offer-withdrawn", "identity-retired", "peer-ignored"})
    void makerAvailabilityIsCheckedAfterValidation(String change) throws Exception {
        automaticDelivery = false;
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseNextRequestValidation(validating, release);
        var pending = submitMessage(buyer.service, request());
        try {
            assertTrue(validating.await(15, TimeUnit.SECONDS));
            switch (change) {
                case "offer-withdrawn" -> when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                        .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());
                case "identity-retired" -> when(buyer.services.getUserService().getUserIdentityService()
                        .findUserIdentity(buyer.identity.getId())).thenReturn(Optional.empty());
                case "peer-ignored" -> when(buyer.services.getUserService().getUserProfileService()
                        .isChatUserIgnored(seller.profile.getId())).thenReturn(true);
                default -> throw new AssertionError(change);
            }
        } finally {
            release.countDown();
        }
        assertFailure(TradeProtocolException.class, pending);
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        if (change.equals("peer-ignored")) {
            assertTrue(messages.isEmpty());
        } else {
            assertEquals(1, messages.size());
            assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                    sent(MuSigReportErrorMessage.class).getTradeProtocolFailure());
        }
    }

    @Test
    void unsupportedRequestVersionsDoNotClaimTheOfferOrCreateATrade() throws Exception {
        automaticDelivery = false;
        var valid = request();
        var invalid = new SetupTradeMessage_A(StringUtils.createUid(),
                tradeId,
                "2.0.0",
                valid.getSender(),
                valid.getReceiver(),
                contract,
                valid.getContractSignatureData(),
                valid.getPubKeyShares());

        assertFailure(IllegalArgumentException.class, submitMessage(buyer.service, invalid));

        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verify(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), never())
                .claimActivatedOffer(offer.getId());
        verifyNoInteractions(buyer.backend);
        assertTrue(messages.isEmpty());

        await(submitMessage(buyer.service, valid));
        var trade = savedRecord(buyer.service, valid.getTradeId()).trade();
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ignoredPeersCanResendTheSameRequestAfterBeingUnignored(boolean ignoredDuringValidation) throws Exception {
        automaticDelivery = false;
        var profiles = buyer.services.getUserService().getUserProfileService();
        var message = request();
        CompletableFuture<Void> pending;
        if (ignoredDuringValidation) {
            var validating = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            pauseNextRequestValidation(validating, release);
            pending = submitMessage(buyer.service, message);
            try {
                assertTrue(validating.await(15, TimeUnit.SECONDS));
                when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(true);
            } finally {
                release.countDown();
            }
        } else {
            when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(true);
            pending = submitMessage(buyer.service, message);
        }

        assertFailure(TradeProtocolException.class, pending);
        assertTrue(messages.isEmpty());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        verifyNoInteractions(buyer.backend);
        verify(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), never())
                .claimActivatedOffer(offer.getId());

        when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(false);
        await(submitMessage(buyer.service, message));
        var trade = savedRecord(buyer.service, message.getTradeId()).trade();

        assertEquals(tradeId, trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
        assertEquals(0, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-signature", "wrong-sender", "wrong-receiver", "wrong-trade-id"})
    void unverifiedRequestsDoNotBlockAValidRequestForTheSameTradeId(String problem) throws Exception {
        automaticDelivery = false;
        var valid = request();
        var invalid = switch (problem) {
            case "foreign-signature" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    valid.getSender(),
                    valid.getReceiver(),
                    contract,
                    contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                    valid.getPubKeyShares());
            case "wrong-sender" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    buyer.identity.getNetworkId(),
                    valid.getReceiver(),
                    contract,
                    valid.getContractSignatureData(),
                    valid.getPubKeyShares());
            case "wrong-receiver" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    valid.getSender(),
                    valid.getSender(),
                    contract,
                    valid.getContractSignatureData(),
                    valid.getPubKeyShares());
            case "wrong-trade-id" -> {
                var differentAttempt = request(contractWithTakeOfferDate(contract.getTakeOfferDate() + 1));
                yield new SetupTradeMessage_A(StringUtils.createUid(),
                        tradeId,
                        valid.getProtocolVersion(),
                        valid.getSender(),
                        valid.getReceiver(),
                        differentAttempt.getContract(),
                        differentAttempt.getContractSignatureData(),
                        valid.getPubKeyShares());
            }
            default -> throw new AssertionError(problem);
        };

        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, invalid));
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        verifyNoInteractions(buyer.backend);

        await(submitMessage(buyer.service, valid));
        var trade = savedRecord(buyer.service, valid.getTradeId()).trade();
        assertEquals(tradeId, trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @Test
    void rejectedAttemptStaysBlockedWhenMarketPriceReturnsButAFreshAttemptCanStart() throws Exception {
        automaticDelivery = false;
        var prices = buyer.services.getBondedRolesService().getMarketPriceService();
        var freshPrice = prices.findMarketPrice(offer.getMarket());
        when(prices.findMarketPrice(any())).thenReturn(Optional.empty());
        var rejectedRequest = request();

        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, rejectedRequest));
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());

        when(prices.findMarketPrice(any())).thenReturn(freshPrice);
        clearInvocations(prices);
        for (var replay : List.of(rejectedRequest, request())) {
            var failure = assertFailure(IllegalStateException.class, submitMessage(buyer.service, replay));
            assertEquals("Initialization was already rejected for this entity ID", failure.getMessage());
        }
        verifyNoInteractions(prices, buyer.backend);
        assertEquals(1, messages.size());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());

        var freshRequest = request(contractWithTakeOfferDate(contract.getTakeOfferDate() + 1));
        await(submitMessage(buyer.service, freshRequest));
        var trade = savedRecord(buyer.service, freshRequest.getTradeId()).trade();
        assertNotEquals(tradeId, trade.getId());
        assertEquals(freshRequest.getTradeId(), trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-signature", "wrong-trade-id", "unavailable-offer", "ignored-peer", "missing-identity",
            "retired-identity", "missing-account", "unknown-peer", "invalid-price", "wrong-mediator", "wrong-arbitrator"})
    void rejectedRequestsCreateNoTradeOrBackendWork(String problem) throws Exception {
        automaticDelivery = false;
        var request = request();
        switch (problem) {
            case "foreign-signature" -> request = new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    "1.0.0",
                    seller.identity.getNetworkId(),
                    buyer.identity.getNetworkId(),
                    contract,
                    contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                    request.getPubKeyShares());
            case "wrong-trade-id" -> request = new SetupTradeMessage_A(StringUtils.createUid(),
                    "wrong-trade",
                    "1.0.0",
                    seller.identity.getNetworkId(),
                    buyer.identity.getNetworkId(),
                    contract,
                    request.getContractSignatureData(),
                    request.getPubKeyShares());
            case "unavailable-offer" -> when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                    .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());
            case "ignored-peer" -> when(buyer.services.getUserService().getUserProfileService().isChatUserIgnored(seller.profile.getId()))
                    // The rejected request stays silent even if the peer is subsequently unignored.
                    .thenReturn(true, false);
            case "missing-identity" -> when(buyer.services.getIdentityService().findAnyIdentityByNetworkId(buyer.identity.getNetworkId()))
                    .thenReturn(Optional.empty());
            case "retired-identity" -> when(buyer.services.getUserService().getUserIdentityService().findUserIdentity(buyer.identity.getId()))
                    .thenReturn(Optional.empty());
            case "missing-account" -> when(buyer.services.getAccountService().getAccounts(paymentMethod)).thenReturn(Set.of());
            case "unknown-peer" -> when(buyer.services.getUserService().getUserProfileService().findUserProfile(seller.profile.getId()))
                    .thenReturn(Optional.empty());
            case "invalid-price" -> {
                var invalid = new MuSigContract(contract.getTakeOfferDate(),
                        offer,
                        seller.identity.getNetworkId(),
                        2_000_000,
                        20_000_000,
                        contract.getNonBtcSidePaymentMethodSpec(),
                        contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                        contract.getMediator(),
                        contract.getArbitrator(),
                        offer.getPriceSpec(),
                        0);
                request = new SetupTradeMessage_A(StringUtils.createUid(),
                        tradeId,
                        "1.0.0",
                        seller.identity.getNetworkId(),
                        buyer.identity.getNetworkId(),
                        invalid,
                        contractService.signContract(invalid, seller.identity.getKeyBundle().getKeyPair()),
                        request.getPubKeyShares());
            }
            case "wrong-mediator" -> when(buyer.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenReturn(contract.getArbitrator());
            case "wrong-arbitrator" -> when(buyer.services.getMuSigTradeService().getMuSigTraderArbitrationService()
                    .selectArbitrator(anyString(), anyString(), anyString(), any())).thenReturn(contract.getMediator());
            default -> throw new AssertionError(problem);
        }
        var failure = assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request));
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        verifyNoInteractions(buyer.services.getChatService().getMuSigOpenTradeChannelService());
        if (problem.equals("ignored-peer") || problem.equals("missing-identity")) {
            assertTrue(messages.isEmpty());
        } else {
            assertEquals(1, messages.size());
            var report = sent(MuSigReportErrorMessage.class);
            assertEquals(request.getTradeId(), report.getTradeId());
            assertEquals(buyer.identity.getNetworkId(), report.getSender());
            assertEquals(seller.identity.getNetworkId(), report.getReceiver());
            assertEquals("1.0.0", report.getProtocolVersion());
            assertEquals(failure.getTradeProtocolFailure(), report.getTradeProtocolFailure());
            assertEquals(failure.getMessage(), report.getErrorMessage());
            assertTrue(report.getStackTrace().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerRejectionNotifiesTheTakerWithoutCreatingAMakerTrade(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        var maker = maker();
        var taker = taker();
        when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());

        var initial = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        await(resumeLane(taker.service, initial.getId()));
        var failed = await(delivery(taker, MuSigReportErrorMessage.class));

        assertFailure(TradeProtocolException.class, delivery(maker, SetupTradeMessage_A.class));
        assertInstanceOf(FailedAtPeer.class, failed.getExecution().state());
        assertInstanceOf(MuSigTradeState.Failed.class, failed.getState());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getPeerFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(failed.toProto(false), taker.saved().trade().toProto(false));
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(maker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(maker.backend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());
        assertEquals(2, messages.size());
        assertInstanceOf(SetupTradeMessage_A.class, messages.element());
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectionSendFailurePreservesTheAdmissionFailure(boolean immediate) throws Exception {
        automaticDelivery = false;
        var rejection = new TradeProtocolException("Offer unavailable", TradeProtocolFailure.OFFER_NOT_AVAILABLE);
        when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenThrow(rejection);
        var networkService = buyer.services.getNetworkService();
        var sendFailure = new IllegalStateException("Could not send rejection");
        if (immediate) {
            doThrow(sendFailure).when(networkService).confidentialSend(any(), any(), any());
        } else {
            doReturn(CompletableFuture.failedFuture(sendFailure))
                    .when(networkService).confidentialSend(any(), any(), any());
        }

        assertSame(rejection, assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request())));
        var replayFailure = assertFailure(IllegalStateException.class, submitMessage(buyer.service, request()));
        assertEquals("Initialization was already rejected for this entity ID", replayFailure.getMessage());

        verify(networkService).confidentialSend(any(), any(), any());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        assertTrue(messages.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "profile", "economics", "offer"})
    void unexpectedMakerValidationFailuresNotifyTheTakerWithoutCreatingATrade(String validation) throws Exception {
        var maker = maker();
        var taker = taker();
        var unexpected = new IllegalStateException("Internal validation detail");
        switch (validation) {
            case "identity" -> when(maker.services.getContractService()).thenThrow(unexpected);
            case "profile" -> when(maker.services.getUserService().getUserProfileService()
                    .findUserProfile(taker.profile.getId())).thenThrow(unexpected);
            case "economics" -> when(maker.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenThrow(unexpected);
            case "offer" -> when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                    .claimActivatedOffer(offer.getId())).thenThrow(unexpected);
            default -> throw new AssertionError(validation);
        }

        var initial = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        await(resumeLane(taker.service, initial.getId()));

        var rejection = assertFailure(TradeProtocolException.class, delivery(maker, SetupTradeMessage_A.class));
        assertSame(unexpected, rejection.getCause());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE, rejection.getTradeProtocolFailure());
        assertEquals("The maker has rejected the take offer request because the offer is not available anymore.",
                rejection.getMessage());
        var failed = await(delivery(taker, MuSigReportErrorMessage.class));
        assertInstanceOf(FailedAtPeer.class, failed.getExecution().state());
        assertInstanceOf(MuSigTradeState.Failed.class, failed.getState());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getPeerFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(failed.toProto(false), taker.saved().trade().toProto(false));
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(maker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(maker.backend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());
        assertEquals(2, messages.size());
        var report = sent(MuSigReportErrorMessage.class);
        assertEquals(rejection.getMessage(), report.getErrorMessage());
        assertTrue(report.getStackTrace().isEmpty());
    }

    @Test
    void makerSetupFailuresAreNotReportedAsValidationRejections() throws Exception {
        automaticDelivery = false;
        var failure = new IllegalStateException("Identity service unavailable");
        when(buyer.services.getUserService().getUserIdentityService().findUserIdentity(buyer.identity.getId()))
                .thenThrow(failure);

        assertSame(failure, assertFailure(IllegalStateException.class, submitMessage(buyer.service, request())));

        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        verifyNoInteractions(buyer.services.getChatService().getMuSigOpenTradeChannelService());
        var network = buyer.services.getNetworkService();
        verify(network).addConfidentialMessageListener(buyer.service);
        verify(network).getConfidentialMessageServices();
        verifyNoMoreInteractions(network);
        assertTrue(messages.isEmpty());
    }

    @Test
    void rejectionReportLimitsItsTextAndPreservesItsReason() throws Exception {
        automaticDelivery = false;
        String errorMessage = "x".repeat(MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE + 100);
        var rejection = new TradeProtocolException(errorMessage, TradeProtocolFailure.PRICE_DEVIATION);
        when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenThrow(rejection);

        assertSame(rejection, assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request())));

        assertEquals(1, messages.size());
        var report = sent(MuSigReportErrorMessage.class);
        assertEquals(MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE, report.getErrorMessage().length());
        assertEquals(TradeProtocolFailure.PRICE_DEVIATION, report.getTradeProtocolFailure());
        assertTrue(report.getStackTrace().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(TradeRole.class)
    void startupRejectsAnExecutionStateFromAnotherRoleBeforeRegistration(TradeRole role) throws Exception {
        boolean buyerAsMaker = role == TradeRole.BUYER_AS_MAKER || role == TradeRole.SELLER_AS_TAKER;
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        var peer = role.isBuyer() ? buyer : seller;
        MuSigTradeExecutionState wrongState = role.isMaker()
                ? new TradeInitialized() : new TradeInitializedAndNonceSharesCreated();
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                peer.identity,
                role,
                "1.0.0",
                peer.account.getAccountPayload(),
                wrongState);

        var failure = assertFailure(IllegalArgumentException.class,
                peer.restoreTrade(MuSigV2TradeRecord.initial(trade)));

        assertEquals("Execution state does not match the trade role", failure.getMessage());
        assertThrows(IllegalArgumentException.class, () -> peer.service.getTradeReadModel(tradeId));
        assertEquals(List.of(MuSigV2TradeRecord.initial(trade)), peer.service.getPersistableStore().getTrades());
        assertFalse(Files.exists(peer.service.getPersistence().getStorePath()));
        verifyNoInteractions(peer.backend, peer.asyncBackend);
        assertTrue(messages.isEmpty());
    }

    @Test
    void restoredInitialTradeStillRejectsAContractSignedByAForeignKey() throws Exception {
        automaticDelivery = false;
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                buyer.identity,
                TradeRole.BUYER_AS_MAKER,
                "1.0.0",
                buyer.account.getAccountPayload(),
                new Init());
        await(buyer.restoreTrade(MuSigV2TradeRecord.initial(trade)));
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        // Recovery already ensured the chat; now inspect only handling of the invalid message.
        clearInvocations(channels);
        var invalid = new SetupTradeMessage_A(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkId(),
                contract,
                contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                request().getPubKeyShares());
        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, invalid));
        assertInstanceOf(Failed.class,
                await(whenExecutionState(buyer.service, tradeId, Failed.class)));
        verifyNoInteractions(buyer.backend);
        verify(channels).findChannelByTradeId(tradeId);
        verifyNoMoreInteractions(channels);
        assertInstanceOf(MuSigReportErrorMessage.class, messages.element());
        assertEquals(1, messages.size());
        var network = buyer.services.getNetworkService();
        verify(network).addConfidentialMessageListener(buyer.service);
        verify(network).getConfidentialMessageServices();
        verify(network).confidentialSend(messages.element(),
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkIdWithKeyPair());
        verifyNoMoreInteractions(network);
    }

    @Test
    void aRestoredMakerKeepsTheExistingFailureReasonWhenItsHandlerRejectsThePeer() throws Exception {
        automaticDelivery = false;
        var initial = MuSigV2TradeTestFactory.tradeAt(contract,
                buyer.identity,
                TradeRole.BUYER_AS_MAKER,
                "1.0.0",
                buyer.account.getAccountPayload(),
                new Init());
        await(buyer.restoreTrade(MuSigV2TradeRecord.initial(initial)));
        when(buyer.services.getUserService().getUserProfileService().isChatUserIgnored(seller.profile.getId()))
                .thenReturn(true);

        var completion = assertThrows(ExecutionException.class, () -> await(submitMessage(buyer.service, request())));

        var failure = assertInstanceOf(MuSigProtocolException.class, completion.getCause());
        assertEquals(MuSigFailureCategory.PROTOCOL_FAILED, failure.getCategory());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE, failure.getTradeProtocolFailure());
        var failed = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(Failed.class, failed.getExecution().state());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getMyFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                sent(MuSigReportErrorMessage.class).getTradeProtocolFailure());
        verifyNoInteractions(buyer.backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void wrongSignatureRedactionIsRejectedBeforeMakerSignsTheDeposit(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        automaticDelivery = false;
        var maker = maker();
        var taker = taker();
        await(submitMessage(maker.service, request()));
        var messageB = sent(SetupTradeMessage_B.class);
        clearInvocations(maker.backend);
        var signatures = bisq.trade.protobuf.PartialSignatures.newBuilder();
        if (!buyerAsMaker) {
            signatures.setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5}));
        }
        var invalid = new SetupTradeMessage_C(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                messageB.getNonceShares(),
                PartialSignatures.fromProto(signatures.build()));
        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, invalid));
        assertInstanceOf(Failed.class,
                await(whenExecutionState(maker.service, tradeId, Failed.class)));
        verifyNoInteractions(maker.backend);
        assertEquals(2, messages.size());

        if (buyerAsMaker) {
            signatures.setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5}));
        } else {
            signatures.clearSwapTxInputPartialSignature();
        }
        var valid = new SetupTradeMessage_C(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                messageB.getNonceShares(),
                PartialSignatures.fromProto(signatures.build()));
        await(submitMessage(maker.service, valid));
        assertInstanceOf(Failed.class, savedRecord(maker.service, valid.getTradeId()).trade().getExecution().state());
        verify(maker.backend, never()).signDepositTx(any());
        assertEquals(MuSigReportErrorMessage.class,
                messages.stream().reduce((first, last) -> last).orElseThrow().getClass());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void accountMismatchDoesNotRecordTheDepositOrStartObservation(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        automaticDelivery = false;
        var maker = maker();
        var taker = taker();
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                maker.identity,
                buyerAsMaker ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER,
                "1.0.0",
                maker.account.getAccountPayload(),
                new PartialSignaturesCreatedAndDepositTxSigned());
        await(maker.restoreTrade(MuSigV2TradeRecord.initial(trade)));
        var invalid = new SendAccountPayloadAndDepositTxMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                new ByteArray(new byte[]{4}),
                maker.account.getAccountPayload());
        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, invalid));
        var unchanged = savedRecord(maker.service, tradeId).trade();
        assertInstanceOf(Failed.class, unchanged.getExecution().state());
        assertTrue(unchanged.getExecution().data().getPeerAccountPayload().isEmpty());
        assertTrue(unchanged.getExecution().data().getPeerDepositTx().isEmpty());
        assertFalse(maker.confirmation.isDone());
        assertInstanceOf(MuSigReportErrorMessage.class, messages.element());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerRestartRestoresDepositObservationAndDoesNotRepeatSetup(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        startTrade();
        var maker = maker();
        var before = savedRecord(maker.service, tradeId).trade().toProto(false);
        maker.service.shutdown().join();
        clearInvocations(maker.backend, maker.asyncBackend);
        maker.confirmation = new CompletableFuture<>();
        maker.start(taker());
        assertEquals(before, savedRecord(maker.service, tradeId).trade().toProto(false));
        verify(maker.asyncBackend).subscribeTxConfirmationStatus(any(), any());
        verifyNoInteractions(maker.backend);
        maker.confirmDeposit();
        assertInstanceOf(DepositTxConfirmed.class,
                await(whenExecutionState(maker.service, tradeId, DepositTxConfirmed.class)));
        await(submitMessage(maker.service, sent(SetupTradeMessage_A.class)));
        verifyNoInteractions(maker.backend);
        assertEquals(6, messages.size());
        assertArrayEquals(new byte[]{(byte) (buyerAsMaker ? 2 : 1)},
                maker.saved().trade().getExecution().data().getPeerDepositTx().orElseThrow().getBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyMakerStateAndReceivedDepositSurviveSerialization(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        List<? extends MuSigTradeExecutionState> states = buyerAsMaker
                ? List.of(new Init(),
                        new TradeInitializedAndNonceSharesCreated(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived(),
                        new DepositTxConfirmed(),
                        new PaymentInitiated(),
                        new CustomPayoutSigned(),
                        new CustomPayoutClosedTrade(),
                        new TradeClosed(),
                        new Failed(),
                        new FailedAtPeer())
                : List.of(new Init(),
                        new TradeInitializedAndNonceSharesCreated(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived(),
                        new DepositTxConfirmed(),
                        new PaymentInitiatedMessageReceived(),
                        new PaymentReceiptConfirmed(),
                        new CustomPayoutSigned(),
                        new CustomPayoutClosedTrade(),
                        new SellerForceClosedTrade(),
                        new TradeClosed(),
                        new Failed(),
                        new FailedAtPeer());
        var maker = maker();
        var role = buyerAsMaker ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER;
        for (var state : states) {
            var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                    maker.identity,
                    role,
                    "1.0.0",
                    maker.account.getAccountPayload(),
                    state);
            trade = trade.withExecution(FlowSnapshot.of(state, trade.getExecution().data().toBuilder()
                    .peerDepositTx(Optional.of(new ByteArray(new byte[]{3, 4}))).build()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(role, restored.getMyRole());
            assertEquals(state, restored.getExecution().state());
            assertArrayEquals(new byte[]{3, 4},
                    restored.getExecution().data().getPeerDepositTx().orElseThrow().getBytes());
            assertEquals(proto, restored.toProto(false));
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void mediationActivatesChatOnRequestAndPeerOpeningAndKeepsItActiveAfterClosure(boolean buyerAsMaker,
                                                                                   boolean buyerRequests) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        startTrade();
        var requester = buyerRequests ? buyer : seller;
        var peer = buyerRequests ? seller : buyer;
        var requesterChannel = prepareTradeChat(requester);
        var peerChannel = prepareTradeChat(peer);
        assertEquals(MuSigDisputeAgentType.NONE, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.NONE, peerChannel.getDisputeAgentType());

        await(requester.service.requestMediation(tradeId));
        var requested = savedRecord(requester.service, tradeId).trade();

        assertInstanceOf(MuSigMediationState.Requested.class, requested.getMediation().orElseThrow().state());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.NONE, peerChannel.getDisputeAgentType());
        assertEquals(1, mediationRequests.size());
        var sharedLogsBeforeOpening = List.copyOf(requester.logs);
        var opened = mediationOpenedMessage();
        await(submitMessage(requester.service, opened));
        assertTrue(mediationCaseData.isEmpty());
        await(submitMessage(peer.service, opened));
        assertEquals(1, mediationCaseData.size());
        var report = mediationCaseData.element();
        assertEquals(tradeId, report.getTradeId());
        assertEquals(peer.identity.getNetworkId(), report.getSenderNetworkId());
        assertArrayEquals(ContractService.getContractHash(contract), report.getContractHash());
        assertEquals(List.copyOf(peerChannel.getChatMessages()), report.getChatMessages());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, peerChannel.getDisputeAgentType());
        assertEquals(sharedLogsBeforeOpening, List.copyOf(requester.logs));
        assertTrue(peer.logs.isEmpty());

        var closed = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId(),
                MediationCaseState.CLOSED,
                Optional.of(mediationResult),
                Optional.of(MuSigMediationResultService.signMediationResult(mediationResult, mediatorKeys)));
        for (var trader : List.of(requester, peer)) {
            await(submitMessage(trader.service, closed));
            var completed = savedRecord(trader.service, closed.getTradeId()).trade();
            assertInstanceOf(MuSigMediationState.Closed.class, completed.getMediation().orElseThrow().state());
            await(submitMessage(trader.service, opened));
            assertSame(completed, savedRecord(trader.service, opened.getTradeId()).trade());
            await(submitMessage(trader.service, closed));
            assertSame(completed, savedRecord(trader.service, closed.getTradeId()).trade());
            assertTrue(trader.saved().pendingEffects().isEmpty());
        }
        assertEquals(MuSigDisputeAgentType.MEDIATOR, requesterChannel.getDisputeAgentType());
        assertEquals(MuSigDisputeAgentType.MEDIATOR, peerChannel.getDisputeAgentType());
        verify(requester.services.getChatService().getMuSigOpenTradeChannelService(), times(2))
                .setDisputeAgentType(requesterChannel, MuSigDisputeAgentType.MEDIATOR);
        verify(peer.services.getChatService().getMuSigOpenTradeChannelService())
                .setDisputeAgentType(peerChannel, MuSigDisputeAgentType.MEDIATOR);
        String mediatorId = contract.getMediator().orElseThrow().getId();
        verify(requester.services.getChatService().getMuSigOpenTradeChannelService())
                .addLocalTradeLogMessage(Res.encode("authorizedRole.mediator.message.toRequester"),
                        mediatorId,
                        requesterChannel);
        verify(peer.services.getChatService().getMuSigOpenTradeChannelService())
                .addLocalTradeLogMessage(Res.encode("authorizedRole.mediator.message.toNonRequester"),
                        mediatorId,
                        peerChannel);
        assertEquals(1, mediationCaseData.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void openingNoticesAreBestEffortAndAreNotReplayedAfterRestart(boolean postingFails) throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        String notice = Res.encode("authorizedRole.mediator.message.toNonRequester");
        String mediatorId = contract.getMediator().orElseThrow().getId();
        if (postingFails) {
            doThrow(new IllegalStateException("Could not add opening notice"))
                    .when(channels).addLocalTradeLogMessage(notice, mediatorId, channel);
        }
        var before = savedRecord(buyer.service, tradeId).trade();
        var opened = mediationOpenedMessage();

        await(submitMessage(buyer.service, opened));
        var trade = savedRecord(buyer.service, opened.getTradeId()).trade();

        assertInstanceOf(MuSigMediationState.Open.class, trade.getMediation().orElseThrow().state());
        assertInstanceOf(MuSigTradeState.Active.class, trade.getState());
        assertSame(before.getExecution(), trade.getExecution());
        assertTrue(buyer.saved().pendingEffects().isEmpty());
        await(resumeLane(buyer.service, tradeId));
        assertSame(trade, savedRecord(buyer.service, tradeId).trade());
        await(buyer.service.shutdown());
        buyer.start(seller);
        await(submitMessage(buyer.service, opened));

        assertEquals(trade.toProto(false), buyer.saved().trade().toProto(false));
        verify(channels).addLocalTradeLogMessage(notice, mediatorId, channel);
        verify(channels, never()).sendTradeLogMessage(anyString(), eq(channel));
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void mediationChatActivationFailureIsRetriedWithoutRepeatingTheTransition(boolean localRequest,
                                                                              boolean restart) throws Exception {
        startTrade();
        var channel = prepareTradeChat(buyer);
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        var failure = new IllegalStateException("Chat activation failed");
        doAnswer(invocation -> {
            var checkpoint = buyer.saved();
            assertInstanceOf(ActivateMediationChat.class, checkpoint.pendingEffects().getFirst());
            assertEquals(localRequest ? new MuSigMediationState.Requested() : new MuSigMediationState.Open(),
                    checkpoint.trade().getMediation().orElseThrow().state());
            throw failure;
        }).when(channels).setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
        var opened = mediationOpenedMessage();

        assertSame(failure, assertFailure(IllegalStateException.class, localRequest
                ? buyer.service.requestMediation(tradeId) : submitMessage(buyer.service, opened)));

        var pending = buyer.saved();
        assertInstanceOf(MuSigTradeState.Active.class, pending.trade().getState());
        assertEquals(2, pending.pendingEffects().size());
        assertTrue(mediationRequests.isEmpty());
        assertTrue(mediationCaseData.isEmpty());
        assertEquals(MuSigDisputeAgentType.NONE, channel.getDisputeAgentType());
        doAnswer(invocation -> {
            channel.setDisputeAgentType(MuSigDisputeAgentType.MEDIATOR);
            return null;
        }).when(channels).setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);

        if (restart) {
            await(buyer.service.shutdown());
            buyer.start(seller);
        } else {
            await(resumeLane(buyer.service, tradeId));
        }

        assertEquals(MuSigDisputeAgentType.MEDIATOR, channel.getDisputeAgentType());
        assertEquals(pending.trade().toProto(false), buyer.saved().trade().toProto(false));
        assertTrue(buyer.saved().pendingEffects().isEmpty());
        assertEquals(localRequest ? 1 : 0, mediationRequests.size());
        if (localRequest) {
            var request = assertInstanceOf(Effect.SendMessage.class, pending.pendingEffects().getLast());
            assertEquals(request.message().toProto(false), mediationRequests.element().toProto(false));
            await(buyer.service.requestMediation(tradeId));
        } else {
            var report = assertInstanceOf(Effect.SendMessage.class, pending.pendingEffects().getLast());
            assertEquals(report.message().toProto(false), mediationCaseData.element().toProto(false));
            await(submitMessage(buyer.service, opened));
        }
        assertEquals(localRequest ? 1 : 0, mediationRequests.size());
        assertEquals(localRequest ? 0 : 1, mediationCaseData.size());
        verify(channels, times(2)).setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
        verify(channels, times(localRequest ? 0 : 1)).addLocalTradeLogMessage(anyString(), anyString(), eq(channel));
    }

    @Test
    void missingChatKeepsMediationActivationPendingUntilTheChannelIsAvailable() throws Exception {
        startTrade();
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        when(channels.findChannelByTradeId(tradeId)).thenReturn(Optional.empty());

        assertFailure(IllegalStateException.class, submitMessage(buyer.service, mediationOpenedMessage()));

        var pending = buyer.saved();
        assertInstanceOf(MuSigMediationState.Open.class, pending.trade().getMediation().orElseThrow().state());
        assertEquals(2, pending.pendingEffects().size());
        assertInstanceOf(ActivateMediationChat.class, pending.pendingEffects().getFirst());
        var send = assertInstanceOf(Effect.SendMessage.class, pending.pendingEffects().getLast());
        var report = assertInstanceOf(MuSigDisputeCaseDataMessage.class, send.message());
        assertTrue(report.getChatMessages().isEmpty());
        var channel = prepareTradeChat(buyer);
        await(resumeLane(buyer.service, tradeId));

        assertEquals(MuSigDisputeAgentType.MEDIATOR, channel.getDisputeAgentType());
        assertTrue(buyer.saved().pendingEffects().isEmpty());
        assertEquals(pending.trade().toProto(false), buyer.saved().trade().toProto(false));
        assertEquals(report.toProto(false), mediationCaseData.element().toProto(false));
    }

    @ParameterizedTest
    @CsvSource({"true, false, true", "false, false, true", "true, true, true", "false, true, true",
            "true, false, false", "false, false, false", "true, true, false", "false, true, false"})
    void eitherTraderCanAcceptFirstAndBothCloseWithTheMediatedPayout(boolean buyerFirst,
                                                                     boolean paymentInitiated,
                                                                     boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        prepareMediation(paymentInitiated);
        var first = buyerFirst ? buyer : seller;
        var second = buyerFirst ? seller : buyer;

        await(first.service.acceptMediationResult(tradeId));
        awaitDelivery(second, MuSigCustomPayoutPsbtMessage.class);

        var committed = savedRecord(first.service, tradeId).trade();
        var waiting = savedRecord(second.service, tradeId).trade();
        assertInstanceOf(CustomPayoutSigned.class, committed.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                committed.getMediation().orElseThrow().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                committed.getMediation().orElseThrow().peerDecision().state());
        var expectedWaitingState = !paymentInitiated ? DepositTxConfirmed.class
                : buyerFirst ? PaymentInitiatedMessageReceived.class : PaymentInitiated.class;
        assertInstanceOf(expectedWaitingState, waiting.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                waiting.getMediation().orElseThrow().myDecision().state());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                waiting.getMediation().orElseThrow().peerDecision().state());
        assertEquals(1, second.saved().pendingInputs().size());
        assertInstanceOf(MediationResultByPeerAcceptedEvent.class, second.saved().pendingInputs().getFirst().event());
        verify(first.backend).signCustomPayoutTx(argThat(request ->
                request.getTradeId().equals(tradeId) && request.getSellersPayoutAmountExcludingFee() == 600_000));
        verify(second.backend, never()).signCustomPayoutTx(any());
        verify(first.backend, never()).customCloseTrade(any());
        verify(second.backend, never()).customCloseTrade(any());

        await(second.service.acceptMediationResult(tradeId));
        awaitDelivery(first, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
        for (var peer : List.of(buyer, seller)) {
            var closed = savedRecord(peer.service, tradeId).trade();
            await(peer.service.acceptMediationResult(tradeId));
            var peerAcceptance = messages.stream().filter(MuSigCustomPayoutPsbtMessage.class::isInstance)
                    .filter(message -> message.getReceiver().equals(peer.identity.getNetworkId())).findFirst().orElseThrow();
            await(submitMessage(peer.service, peerAcceptance));
            assertSame(closed, savedRecord(peer.service, peerAcceptance.getTradeId()).trade());
            verify(peer.backend, times(1)).signCustomPayoutTx(any());
            verify(peer.backend, times(1)).customCloseTrade(any());
        }
    }

    @ParameterizedTest
    @CsvSource({"true,true,false", "true,true,true", "true,false,false", "true,false,true",
            "false,true,false", "false,true,true", "false,false,false", "false,false,true"})
    void signingFailureIsNotifiedAndBothFailuresSurviveRestart(boolean buyerAsMaker,
                                                               boolean buyerFails,
                                                               boolean peerFirst) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        prepareMediation(false);
        var local = buyerFails ? buyer : seller;
        var peer = buyerFails ? seller : buyer;
        if (peerFirst) {
            await(peer.service.acceptMediationResult(tradeId));
            awaitDelivery(local, MuSigCustomPayoutPsbtMessage.class);
        }
        var before = savedRecord(local.service, tradeId).trade();
        RuntimeException backendFailure = new IllegalStateException("Signing unavailable");
        when(local.backend.signCustomPayoutTx(any())).thenThrow(backendFailure);

        var completion = assertThrows(ExecutionException.class,
                () -> await(local.service.acceptMediationResult(tradeId)));
        var failure = assertInstanceOf(MuSigProtocolException.class, completion.getCause());
        assertEquals(MuSigFailureCategory.OPERATION_FAILED, failure.getCategory());
        assertSame(backendFailure, failure.getCause());
        awaitDelivery(peer, MuSigReportErrorMessage.class);

        var failed = savedRecord(local.service, tradeId).trade();
        var failedAtPeer = savedRecord(peer.service, tradeId).trade();
        assertInstanceOf(Failed.class, failed.getExecution().state());
        assertInstanceOf(FailedAtPeer.class, failedAtPeer.getExecution().state());
        assertSame(before.getMediation().orElseThrow().peerDecision(),
                failed.getMediation().orElseThrow().peerDecision());
        assertEquals(new MuSigMediationDecisionState.Accepted(),
                failed.getMediation().orElseThrow().myDecision().state());
        assertTrue(local.saved().processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION, Set.of())
                .contains(MediationResultAcceptedEvent.class));
        assertEquals(peerFirst ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Init(),
                failed.getMediation().orElseThrow().peerDecision().state());
        var data = failed.getExecution().data();
        var myFailure = data.getMyFailure().orElseThrow();
        assertEquals(TradeProtocolFailure.UNKNOWN, myFailure.tradeProtocolFailure());
        assertEquals("Signing unavailable", myFailure.errorMessage());
        assertTrue(myFailure.errorStackTrace().startsWith(IllegalStateException.class.getName()));
        assertTrue(data.getMyCustomPayoutPsbt().isEmpty());
        var report = sent(MuSigReportErrorMessage.class);
        var peerData = failedAtPeer.getExecution().data();
        var peerFailure = peerData.getPeerFailure().orElseThrow();
        assertEquals(report.getTradeProtocolFailure(), peerFailure.tradeProtocolFailure());
        assertEquals(report.getErrorMessage(), peerFailure.errorMessage());
        assertEquals(report.getStackTrace(), peerFailure.errorStackTrace());
        assertTrue(peerData.getMyFailure().isEmpty());
        assertTrue(local.saved().pendingInputs().isEmpty());
        assertTrue(peer.saved().pendingInputs().isEmpty());
        assertTrue(local.saved().scheduledEvents().isEmpty());
        assertTrue(peer.saved().scheduledEvents().isEmpty());
        await(submitMessage(peer.service, report));
        assertSame(failedAtPeer, savedRecord(peer.service, report.getTradeId()).trade());
        assertFailure(MuSigProtocolException.class, local.service.acceptMediationResult(tradeId));
        await(resumeLane(local.service, tradeId));
        assertSame(failed, savedRecord(local.service, tradeId).trade());
        verify(local.backend, times(1)).signCustomPayoutTx(any());
        verify(local.backend, never()).customCloseTrade(any());
        int messageCount = messages.size();
        buyer.service.shutdown().join();
        seller.service.shutdown().join();
        clearInvocations(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        buyer.start(seller);
        seller.start(buyer);

        assertEquals(failed.toProto(false), savedRecord(local.service, tradeId).trade().toProto(false));
        assertEquals(failedAtPeer.toProto(false), savedRecord(peer.service, tradeId).trade().toProto(false));
        assertEquals(messageCount, messages.size());
        verifyNoInteractions(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothServicesResumeMediatedSettlementAfterRestartBetweenAcceptances(boolean buyerFirst) throws Exception {
        prepareMediation(false);
        var first = buyerFirst ? buyer : seller;
        var second = buyerFirst ? seller : buyer;
        await(first.service.acceptMediationResult(tradeId));
        awaitDelivery(second, MuSigCustomPayoutPsbtMessage.class);
        var beforeBuyer = savedRecord(buyer.service, tradeId).trade().toProto(false);
        var beforeSeller = savedRecord(seller.service, tradeId).trade().toProto(false);
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        int messageCount = messages.size();
        buyer.service.shutdown().join();
        seller.service.shutdown().join();
        clearInvocations(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        buyer.start(seller);
        seller.start(buyer);

        assertEquals(beforeBuyer, savedRecord(buyer.service, tradeId).trade().toProto(false));
        assertEquals(beforeSeller, savedRecord(seller.service, tradeId).trade().toProto(false));
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        assertTrue(seller.saved().scheduledEvents().isEmpty());
        assertEquals(1, second.saved().pendingInputs().size());
        assertInstanceOf(MediationResultByPeerAcceptedEvent.class, second.saved().pendingInputs().getFirst().event());
        assertEquals(messageCount, messages.size());
        verifyNoInteractions(buyer.backend, seller.backend, buyer.asyncBackend, seller.asyncBackend);

        await(second.service.acceptMediationResult(tradeId));
        awaitDelivery(first, MuSigCustomPayoutPsbtMessage.class);

        assertBothClosedWithMediation();
        verify(first.backend, never()).signCustomPayoutTx(any());
        verify(second.backend, times(1)).signCustomPayoutTx(any());
        verify(first.backend, times(1)).customCloseTrade(any());
        verify(second.backend, times(1)).customCloseTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void makerCannotCloseNormallyAfterSigningTheCustomPayout(boolean paymentInitiated) throws Exception {
        prepareMediation(paymentInitiated);
        assertEquals(paymentInitiated ? 1 : 0, buyer.saved().scheduledEvents().size());
        await(buyer.service.acceptMediationResult(tradeId));
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);
        var signed = savedRecord(buyer.service, tradeId).trade();
        assertTrue(buyer.saved().scheduledEvents().isEmpty());
        if (paymentInitiated) {
            assertEquals(1, buyer.timers.size());
            // A callback already released by the timer must be ignored after custom signing cancels its deadline.
            buyer.timers.getFirst().run();
            await(resumeLane(buyer.service, tradeId));
            assertSame(signed, savedRecord(buyer.service, tradeId).trade());
        }
        int messageCount = messages.size();
        var lateConfirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{6}, new byte[]{7})));

        await(submitMessage(buyer.service, lateConfirmation));
        assertSame(signed, savedRecord(buyer.service, lateConfirmation.getTradeId()).trade());
        assertEquals(messageCount, messages.size());
        verify(buyer.backend, never()).closeTrade(any());
        if (!paymentInitiated) {
            assertFailure(MuSigProtocolException.class, buyer.service.paymentInitiated(tradeId));
        }

        await(seller.service.acceptMediationResult(tradeId));
        awaitDelivery(buyer, MuSigCustomPayoutPsbtMessage.class);
        assertBothClosedWithMediation();
    }

    @Test
    void makerAcceptanceBeforeDepositConfirmationIsRejectedAndNotReplayed() throws Exception {
        await(initializeTrade(seller.service, initializeTrade(contract, seller.identity, seller.account.getAccountPayload())));
        awaitDelivery(seller, SendAccountPayloadMessage.class);
        publishMediationResult();
        var before = savedRecord(buyer.service, tradeId).trade();

        assertFailure(MuSigProtocolException.class, buyer.service.acceptMediationResult(tradeId));
        assertSame(before, savedRecord(buyer.service, tradeId).trade());
        buyer.confirmDeposit();
        seller.confirmDeposit();
        var confirmed = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(DepositTxConfirmed.class, confirmed.getExecution().state());
        assertEquals(new MuSigMediationDecisionState.Init(),
                confirmed.getMediation().orElseThrow().myDecision().state());
        verify(buyer.backend, never()).signCustomPayoutTx(any());

        await(buyer.service.acceptMediationResult(tradeId));
        assertInstanceOf(CustomPayoutSigned.class,
                savedRecord(buyer.service, tradeId).trade().getExecution().state());
        awaitDelivery(seller, MuSigCustomPayoutPsbtMessage.class);
    }

    private void prepareMediation(boolean paymentInitiated) throws Exception {
        startTrade();
        buyer.confirmDeposit();
        seller.confirmDeposit();
        if (paymentInitiated) {
            await(buyer.service.paymentInitiated(tradeId));
            awaitDelivery(seller, PaymentInitiatedMessage_E.class);
        }
        publishMediationResult();
    }

    private void assertTradingCommandRejected(Peer peer,
                                              boolean haltTrading,
                                              Supplier<CompletableFuture<Void>> command) throws Exception {
        var before = savedRecord(peer.service, tradeId).trade();
        var saved = peer.saved().toProto(false);
        var sentMessages = List.copyOf(messages);
        int logCount = peer.logs.size();
        clearInvocations(peer.backend, peer.asyncBackend);
        peer.alerts.add(restriction(haltTrading));

        var rejected = command.get();
        assertTrue(rejected.isCompletedExceptionally());
        var failure = assertFailure(TradeRestrictedException.class, rejected);
        assertEquals(haltTrading ? TradeRestrictedException.Restriction.HALT_TRADING
                : TradeRestrictedException.Restriction.MIN_VERSION_REQUIRED, failure.getRestriction());
        assertEquals(haltTrading ? Optional.empty() : Optional.of("999.0.0"), failure.findMinRequiredVersion());
        assertSame(before, savedRecord(peer.service, tradeId).trade());
        assertEquals(saved, peer.saved().toProto(false));
        verifyNoInteractions(peer.backend, peer.asyncBackend);

        peer.alerts.clear();
        await(resumeLane(peer.service, tradeId));
        assertSame(before, savedRecord(peer.service, tradeId).trade());
        verifyNoInteractions(peer.backend, peer.asyncBackend);
        await(peer.service.shutdown());
        peer.confirmation = new CompletableFuture<>();
        peer.start(peer == buyer ? seller : buyer);
        assertEquals(saved, peer.saved().toProto(false));
        assertEquals(sentMessages, List.copyOf(messages));
        assertEquals(logCount, peer.logs.size());
        verifyNoInteractions(peer.backend);
    }

    private static AuthorizedAlertData restriction(boolean haltTrading) {
        var alert = mock(AuthorizedAlertData.class);
        when(alert.getAlertType()).thenReturn(AlertType.EMERGENCY);
        when(alert.getAppType()).thenReturn(AppType.DESKTOP);
        when(alert.isHaltTrading()).thenReturn(haltTrading);
        when(alert.isRequireVersionForTrading()).thenReturn(!haltTrading);
        when(alert.getMinVersion()).thenReturn(Optional.of("999.0.0"));
        return alert;
    }

    private void publishMediationResult() throws Exception {
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var opened = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());
        var closed = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                MediationCaseState.CLOSED,
                Optional.of(mediationResult),
                Optional.of(MuSigMediationResultService.signMediationResult(mediationResult, mediatorKeys)));
        for (var peer : List.of(buyer, seller)) {
            prepareTradeChat(peer);
            await(submitMessage(peer.service, opened));
            await(submitMessage(peer.service, closed));
        }
    }

    private void assertBothClosedWithMediation() throws Exception {
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

    private MuSigOpenTradeChannel prepareTradeChat(Peer peer) throws Exception {
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

    private MuSigMediationStateChangeMessage mediationOpenedMessage() {
        return new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId(),
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());
    }

    private SetupTradeMessage_A request() throws Exception {
        return request(contract);
    }

    private MuSigContract contractWithTakeOfferDate(long takeOfferDate) {
        return new MuSigContract(takeOfferDate,
                offer,
                taker().identity.getNetworkId(),
                contract.getBaseSideAmount(),
                contract.getQuoteSideAmount(),
                contract.getNonBtcSidePaymentMethodSpec(),
                contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                contract.getMediator(),
                contract.getArbitrator(),
                contract.getPriceSpec(),
                contract.getMarketPrice());
    }

    private SetupTradeMessage_A request(MuSigContract requestedContract) throws Exception {
        var taker = taker();
        var requestedTradeId = TradeV2.createId(requestedContract.getOffer().getId(),
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

    private Peer maker() {
        return offer.getDirection().isBuy() ? buyer : seller;
    }

    private Peer taker() {
        return offer.getDirection().isBuy() ? seller : buyer;
    }

    private void startTrade() throws Exception {
        var taker = taker();
        await(initializeTrade(taker.service, initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        awaitDelivery(taker, SendAccountPayloadMessage.class);
        awaitDelivery(maker(), SendAccountPayloadAndDepositTxMessage.class);
    }

    private void pauseNextRequestValidation(CountDownLatch validating, CountDownLatch release) throws GeneralSecurityException {
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

    private <T extends MuSigTradeMessage> T sent(Class<T> type) {
        return messages.stream().filter(type::isInstance).map(type::cast).findFirst().orElseThrow();
    }

    private void awaitDelivery(Peer receiver, Class<? extends MuSigTradeMessage> type) throws Exception {
        await(CompletableFuture.anyOf(delivery(receiver, type), deliveryFailure));
    }

    private CompletableFuture<MuSigV2Trade<?>> delivery(Peer receiver, Class<?> type) {
        return deliveries.computeIfAbsent(receiver, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(type, ignored -> new CompletableFuture<>());
    }

    private void send(Peer peer, Effect effect) {
        var send = assertInstanceOf(Effect.SendMessage.class, effect);
        if (send.message() instanceof MuSigMediationRequest request) {
            var sender = peer == buyer ? seller : buyer;
            var channel = sender.services.getChatService().getMuSigOpenTradeChannelService()
                    .findChannelByTradeId(tradeId).orElseThrow();
            assertEquals(MuSigDisputeAgentType.MEDIATOR, channel.getDisputeAgentType());
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

    private final class Peer {
        private final String name;
        private final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
        private final List<Runnable> timers = new CopyOnWriteArrayList<>();
        private final ObservableSet<AuthorizedAlertData> alerts = new ObservableSet<>();
        private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class, RETURNS_DEEP_STUBS);
        private final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        private final MusigGrpc.MusigBlockingStub backend = mock(MusigGrpc.MusigBlockingStub.class);
        private final MusigGrpc.MusigStub asyncBackend = mock(MusigGrpc.MusigStub.class);
        private final Identity identity;
        private final UserProfile profile;
        private final RevolutAccount account;
        private CompletableFuture<StreamObserver<TxConfirmationStatus>> confirmation = new CompletableFuture<>();
        private MuSigTradeService service;

        private CompletableFuture<MuSigV2Trade<?>> restoreTrade(MuSigV2TradeRecord record) {
            service.shutdown().join();
            clearInvocations(services.getNetworkService());
            timers.clear();
            service = serviceWithCapturedTimers(services, grpcClient, () -> contract.getTakeOfferDate(), timers);
            return MuSigTradeServiceTestSupport.restoreTrade(service, record);
        }


        private Peer(String name, int port) {
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

        private void start(Peer peer) throws Exception {
            var persistence = new PersistenceService(directory.resolve(name));
            when(services.getPersistenceService()).thenReturn(persistence);
            var networkService = services.getNetworkService();
            doAnswer(invocation -> {
                var message = assertInstanceOf(EnvelopePayloadMessage.class, invocation.getArgument(0));
                var receiver = assertInstanceOf(NetworkId.class, invocation.getArgument(1));
                assertEquals(identity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                send(peer, new Effect.SendMessage<>(message, receiver));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(networkService).confidentialSend(any(), any(), any());
            timers.clear();
            service = serviceWithCapturedTimers(services, grpcClient, () -> contract.getTakeOfferDate(), timers);
            assertTrue(await(persistence.readAllPersisted()));
            assertTrue(await(service.initialize()));
            await(service.getRecoveryFuture());
        }

        private void confirmDeposit() throws Exception {
            var confirmed = whenExecutionState(service, tradeId, DepositTxConfirmed.class);
            await(confirmation).onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            await(confirmed);
        }

        private MuSigV2TradeRecord saved() {
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

    private static <T extends Throwable> T assertFailure(Class<T> type, CompletableFuture<?> future) {
        var failure = assertThrows(ExecutionException.class, () -> await(future));
        return assertInstanceOf(type, failure.getCause());
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
}
