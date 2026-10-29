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
import bisq.bonded_roles.release.AppType;
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
import bisq.common.observable.map.HashMapObserver;
import bisq.common.processing.Checkpoint;
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.processing.PendingWork;
import bisq.common.processing.ProcessingLane;
import bisq.common.processing.ProcessingLaneDefinition;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.SubmissionResult.CoordinationPhase;
import bisq.common.processing.SubmissionResult;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Delivery;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable.Rule;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.StateMachineResolver;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.contract.ContractService;
import bisq.contract.ContractSignatureData;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.network.p2p.services.confidential.SendConfidentialMessageResult;
import bisq.network.p2p.services.confidential.ack.MessageDeliveryStatus;
import bisq.offer.Direction;
import bisq.offer.OfferService;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.offer.options.CollateralOption;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.security.pow.ProofOfWork;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeRequest;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
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
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigTradeEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigV2EventType;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.RemoveTakenOffer;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.BuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecision;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;
import bisq.trade.protobuf.MusigGrpc;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.UnknownFieldSet;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenValue;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigTradePersistenceTest {
    private static final String TX_ID = "12".repeat(32);
    @TempDir
    Path directory;
    private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class);
    private final ServiceProvider services = mock(ServiceProvider.class);
    private final MusigGrpc.MusigBlockingStub backend = mock(MusigGrpc.MusigBlockingStub.class);
    private final MusigGrpc.MusigStub asyncBackend = mock(MusigGrpc.MusigStub.class);
    private MuSigContract contract;
    private Identity myIdentity;
    private AccountPayload<?> myAccountPayload;
    private MuSigMediationResult result;
    private KeyPair mediatorKeys;
    private String tradeId;

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

    @Test
    void anInitialRecordWithoutPendingInputDoesNotInventAStartupCommand() throws Exception {
        bisq.trade.protobuf.MuSigV2Trade saved;
        seed(MuSigV2TradeRecord.initial(tradeAt(new Init())));
        var service = startAndAwaitRecovery(effect -> fail("Recovery must not invent a startup command"));
        try {
            var trade = savedRecord(service, tradeId).trade();
            assertInstanceOf(Init.class, trade.getExecution().state());
            saved = trade.toProto(false);
            assertEquals(saved, service.getPersistableStore().getTrades().getFirst().trade().toProto(false));
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("An unstarted trade must stay unstarted"));
        try {
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(saved, restored.toProto(false));
            assertInstanceOf(Init.class, restored.getExecution().state());
            assertInstanceOf(Init.class, restoredService.getTradeReadModel(tradeId).getExecutionState());
            assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(restoredService, initializeTrade(contract, myIdentity, myAccountPayload)))).getCause());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void recoveryAndOutgoingMessagesPreserveTheSavedProtocolVersion() throws Exception {
        String savedVersion = "0.9.0";
        assertNotEquals(MuSigProtocol.VERSION, savedVersion);
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                myIdentity,
                TradeRole.SELLER_AS_TAKER,
                savedVersion,
                myAccountPayload,
                new PaymentInitiatedMessageReceived());
        seed(MuSigV2TradeRecord.initial(trade));
        var effects = new ArrayList<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(savedVersion, restored.getProtocolVersion());

            await(service.paymentReceiptConfirmed(tradeId));
            var confirmed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, confirmed.getExecution().state());
            assertEquals(savedVersion, confirmed.getProtocolVersion());
            var outgoing = assertInstanceOf(Effect.SendMessage.class, effects.getFirst());
            var message = assertInstanceOf(PaymentReceivedMessage_F.class, outgoing.message());
            assertEquals(savedVersion, message.getProtocolVersion());
            assertEquals(savedVersion, saved(service).trade().getProtocolVersion());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unrecoverableStoreBlocksInitializationAndSavingAcrossRestarts(boolean invalidRecord) throws Exception {
        byte[] unreadable;
        if (invalidRecord) {
            var record = MuSigV2TradeRecord.initial(tradeAt(new Init())).toProto(false);
            var proto = bisq.trade.protobuf.MuSigV2TradeStore.newBuilder()
                    .putTradeById(tradeId, record)
                    .putTradeById("wrong-trade-id", record).build();
            var output = new ByteArrayOutputStream();
            Any.pack(proto).writeDelimitedTo(output);
            unreadable = output.toByteArray();
        } else {
            unreadable = new byte[]{1, 2, 3};
        }

        for (int restart = 0; restart < 2; restart++) {
            var persistenceService = new PersistenceService(directory);
            when(services.getPersistenceService()).thenReturn(persistenceService);
            mockNetworkSend(effect -> fail("Unrestored trades must not send messages"));
            var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
            try {
                Path storePath = service.getPersistence().getStorePath();
                if (restart == 0) {
                    Files.createDirectories(storePath.getParent());
                    Files.write(storePath, unreadable);
                }

                assertFalse(await(persistenceService.readAllPersisted()));
                var initializationFailure = assertThrows(ExecutionException.class, () -> await(service.initialize()));
                var cause = assertInstanceOf(IllegalStateException.class, initializationFailure.getCause());
                assertTrue(cause.getMessage().contains(storePath.toString()));
                assertEquals(1, cause.getSuppressed().length);
                var persistFailure = assertThrows(ExecutionException.class, () -> await(service.persist()));
                assertSame(cause, persistFailure.getCause());
                assertSame(cause, assertThrows(ExecutionException.class, () -> await(service.initialize())).getCause());
                assertInstanceOf(IllegalStateException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(service, initializeTrade(contract, myIdentity, myAccountPayload)))).getCause());
                assertTrue(service.getPersistableStore().getTrades().isEmpty());
                assertArrayEquals(unreadable, Files.readAllBytes(storePath));
                assertTrue(service.getPersistence().getBackups().isEmpty());
                verifyNoInteractions(backend, asyncBackend);
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void cachedAndLiveMediationMessagesWaitForRestoredRecoveryAndAreNotProcessedTwice() throws Exception {
        seed(MuSigV2TradeRecord.initial(tradeAt(new AccountPayloadReceived())));
        var network = services.getNetworkService();
        clearInvocations(network);
        var bans = mock(BannedUserService.class);
        var users = mock(UserService.class);
        when(users.getBannedUserService()).thenReturn(bans);
        when(services.getUserService()).thenReturn(users);
        var cached = mock(ConfidentialMessageService.class);
        when(network.getConfidentialMessageServices()).thenReturn(Set.of(cached));
        var opened = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId(),
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());
        var listener = new AtomicReference<ConfidentialMessageService.Listener>();
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            return null;
        }).when(network).addConfidentialMessageListener(any());
        when(cached.getProcessedEnvelopePayloadMessages()).thenAnswer(invocation -> {
            assertNotNull(listener.get());
            listener.get().onMessage(opened);
            return Set.of(opened);
        });
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);

        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            try {
                assertTrue(await(persistenceService.readAllPersisted()));
                assertTrue(await(service.initialize()));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                var afterReplay = whenValue(service.getTradeReadModel(tradeId).mediationStateObservable(),
                        state -> state.orElseThrow() instanceof MuSigMediationState.Open);
                assertFalse(afterReplay.isDone());

                release.countDown();
                assertInstanceOf(MuSigMediationState.Open.class,
                        await(afterReplay).orElseThrow());
                var channels = services.getChatService().getMuSigOpenTradeChannelService();
                verify(channels, times(1)).addLocalTradeLogMessage(anyString(), anyString(), any());
            } finally {
                release.countDown();
            }
        } finally {
            service.shutdown().join();
        }
        verify(network).removeConfidentialMessageListener(listener.get());
    }

    @Test
    void initializationReturnsBeforeRecoveryFinishesAndNewInputsWaitBehindIt() throws Exception {
        var other = buyerTradeAt(new DepositTxConfirmed());
        seed(MuSigV2TradeRecord.initial(tradeAt(new AccountPayloadReceived())), MuSigV2TradeRecord.initial(other));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effect -> fail("No peer send is expected"));
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        var models = new ConcurrentLinkedQueue<MuSigTradeReadModel>();
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String id, MuSigTradeReadModel readModel) {
                models.add(readModel);
            }
        });
        try {
            try {
                assertTrue(await(persistenceService.readAllPersisted()));
                var initialization = service.initialize();
                assertTrue(initialization.isDone());
                assertTrue(await(initialization));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                assertEquals(Set.of(tradeId, other.getId()), models.stream()
                        .map(MuSigTradeReadModel::getId)
                        .collect(Collectors.toSet()));
                assertSame(service.getTradeReadModel(tradeId), service.getTradeReadModels().get(tradeId));

                var recovery = service.getRecoveryFuture();
                assertFalse(recovery.isDone());
                // Repeated initialization and cancellation of an observer do not restart or cancel recovery.
                assertTrue(await(service.initialize()));
                assertEquals(2, models.size());
                assertTrue(service.getRecoveryFuture().cancel(false));
                assertFalse(recovery.isDone());

                var confirmation = service.onDepositTxConfirmed(tradeId);
                assertFalse(confirmation.isDone());
                assertEquals(other.toProto(false), savedRecord(service, other.getId()).trade().toProto(false));

                release.countDown();
                await(recovery);
                await(confirmation);
                assertInstanceOf(DepositTxConfirmed.class, service.getTradeReadModel(tradeId).getExecutionState());
                verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
            } finally {
                release.countDown();
            }
        } finally {
            pin.unbind();
            service.shutdown().join();
        }
    }

    @Test
    void failedOutgoingRecoveryAllowsMediationAndOtherTradesToProgress() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed());
        var expectedTrade = trade.withMediation(FlowSnapshot.of(new MuSigMediationState.Open(),
                trade.getMediation().orElseThrow().data()));
        var other = buyerTradeAt(new DepositTxConfirmed());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var message = new PaymentReceivedMessage_F(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                signature);
        var pendingSend = new Effect.SendMessage<>(message, message.getReceiver());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(),
                List.of(),
                List.of(pendingSend)),
                MuSigV2TradeRecord.initial(other));
        var failure = new IllegalStateException("Transport unavailable during recovery");
        var attempted = new ConcurrentLinkedQueue<Effect>();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effect -> {
            attempted.add(effect);
            if (((Effect.SendMessage<?, ?>) effect).message() instanceof MuSigTradeMessage messageSent &&
                    messageSent.getTradeId().equals(tradeId)) {
                throw failure;
            }
        });
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        MuSigV2TradeRecord savedBeforeRestart;
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertTrue(await(service.initialize()));
            assertSame(failure,
                    assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture())).getCause());
            assertTrue(await(service.initialize()));

            var opened = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId(),
                    MediationCaseState.OPEN,
                    Optional.empty(),
                    Optional.empty());
            for (var input : List.of(submitMessage(service, opened),
                    resumeLane(service, tradeId))) {
                var reportedFailure = assertThrows(ExecutionException.class, () -> await(input)).getCause();
                assertSame(failure, reportedFailure);
            }
            var committed = savedRecord(service, tradeId);
            assertEquals(expectedTrade.toProto(false), committed.trade().toProto(false));
            assertTrue(committed.pendingInputs().isEmpty());
            assertEquals(Set.of(MediationOpenedEvent.class), committed.processedEventTypes().get(Flow.MEDIATION));
            assertEquals(List.of(pendingSend, pendingSend, pendingSend), List.copyOf(attempted));
            assertEquals(2, committed.pendingEffects().size());
            assertEquals(pendingSend, committed.pendingEffects().getFirst());
            var reportSend = assertInstanceOf(Effect.SendMessage.class, committed.pendingEffects().getLast());
            var report = assertInstanceOf(MuSigDisputeCaseDataMessage.class, reportSend.message());
            assertEquals(tradeId, report.getTradeId());
            assertEquals(contract.getMediator().orElseThrow().getNetworkId(), reportSend.receiver());
            verifyNoInteractions(backend);

            var otherOpened = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                    other.getId(),
                    contract.getMediator().orElseThrow().getNetworkId(),
                    MediationCaseState.OPEN,
                    Optional.empty(),
                    Optional.empty());
            await(submitMessage(service, otherOpened));
            assertInstanceOf(MuSigMediationState.Open.class,
                    savedRecord(service, otherOpened.getTradeId()).trade().getMediation().orElseThrow().state());
            savedBeforeRestart = service.getPersistence().readOrThrow().orElseThrow().getTrades().stream()
                    .filter(record -> record.trade().getId().equals(tradeId)).findFirst().orElseThrow();
            assertEquals(committed.toProto(false), savedBeforeRestart.toProto(false));
        } finally {
            service.shutdown().join();
        }

        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect));
        try {
            assertEquals(savedBeforeRestart.pendingEffects(), sent);
            await(resumeLane(restoredService, tradeId));
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(expectedTrade.toProto(false), restored.toProto(false));
            assertTrue(savedRecord(restoredService, tradeId).pendingEffects().isEmpty());
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void aReadableBackupAllowsInitializationAfterStoreCorruption() throws Exception {
        var original = tradeAt(new DepositTxConfirmed());
        Path storePath;
        seed(MuSigV2TradeRecord.initial(original));
        var service = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {

            assertTrue(await(service.persist()));
            assertEquals(1, service.getPersistence().getBackups().size());
            storePath = service.getPersistence().getStorePath();
        } finally {
            service.shutdown().join();
        }
        Files.write(storePath, new byte[]{1, 2, 3});

        var restoredService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            assertEquals(original.toProto(false), savedRecord(restoredService, tradeId).trade().toProto(false));
            assertEquals(original.toProto(false), saved(restoredService).trade().toProto(false));
            assertEquals(1, services.getPersistenceService().getRestoreService().getRestoredBackupFileInfos().size());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void diskRestartResumesDepositObservationOnceAndDoesNotRestartItAfterConfirmation() throws Exception {
        var original = tradeAt(new AccountPayloadReceived());
        seed(MuSigV2TradeRecord.initial(original));
        var service = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {

            assertTrue(Files.isRegularFile(service.getPersistence().getStorePath()));
        } finally {
            service.shutdown().join();
        }
        clearInvocations(asyncBackend, backend);
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> observer = invocation.getArgument(1);
            observer.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var restoredService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            // Recovery queued the callback; await its published result separately.
            await(whenExecutionState(restoredService, tradeId, DepositTxConfirmed.class));
            var restored = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
            assertEquals(contract.toProto(false), restored.getContract().toProto(false));
            assertEquals(myIdentity.toProto(false), restored.getMyIdentity().toProto(false));
            assertEquals(TradeRole.SELLER_AS_TAKER, restored.getMyRole());
            assertEquals("1.0.0", restored.getProtocolVersion());
            assertEquals(myAccountPayload.toProto(false), restored.getMyAccountPayload().toProto(false));
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(DepositTxConfirmedEvent.class));
            verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
        clearInvocations(asyncBackend);
        var restartedService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            assertInstanceOf(DepositTxConfirmed.class, savedRecord(restartedService, tradeId).trade().getExecution().state());
            verifyNoInteractions(asyncBackend);
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void unacknowledgedObservationIsRecoveredOnceEvenWithAnImmediateConfirmation() throws Exception {
        seed(new MuSigV2TradeRecord(tradeAt(new AccountPayloadReceived()),
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ObserveDepositTxConfirmation())));
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> observer = invocation.getArgument(1);
            observer.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var service = startAndAwaitRecovery(effect -> {});
        try {
            assertInstanceOf(DepositTxConfirmed.class,
                    await(whenExecutionState(service, tradeId, DepositTxConfirmed.class)));
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void aSavedObservationRequestIsDiscardedWhenConfirmationIsAlreadyRecorded() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ObserveDepositTxConfirmation())));

        var service = startAndAwaitRecovery(effect -> fail("No outgoing work is needed"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertTrue(saved(service).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void earlyPeerMessageSurvivesRestartAndRunsAfterDepositConfirmation() throws Exception {
        var early = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new byte[]{7});
        var initial = tradeAt(new AccountPayloadReceived()).withExecution(FlowSnapshot.of(new AccountPayloadReceived(),
                MuSigV2TradeExecutionData.builder().peerRedactedPartialSignatures(Optional.of(
                        PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance()))).build()));
        seed(MuSigV2TradeRecord.initial(initial));
        var service = startAndAwaitRecovery(effect -> {});
        try {

            await(submitMessage(service, early));
            assertInstanceOf(AccountPayloadReceived.class, savedRecord(service, early.getTradeId()).trade().getExecution().state());
            assertEquals(1, saved(service).pendingInputs().size());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> {});
        try {
            assertInstanceOf(AccountPayloadReceived.class,
                    await(whenExecutionState(restoredService, tradeId, AccountPayloadReceived.class)));
            assertEquals(early.getId(),
                    ((PaymentInitiatedMessage_E) saved(restoredService).pendingInputs().getFirst().event()).getId());
            await(restoredService.onDepositTxConfirmed(tradeId));
            var advanced = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(PaymentInitiatedMessageReceived.class, advanced.getExecution().state());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(PaymentInitiatedMessage_E.class));
            await(submitMessage(restoredService, early));
            assertSame(advanced, savedRecord(restoredService, early.getTradeId()).trade());
        } finally {
            restoredService.shutdown().join();
        }
        var restartedService = startAndAwaitRecovery(effect -> {});
        try {
            var restored = savedRecord(restartedService, tradeId).trade();
            assertTrue(saved(restartedService).processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).containsAll(
                    Set.of(DepositTxConfirmedEvent.class, PaymentInitiatedMessage_E.class)));
            var duplicate = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                    tradeId,
                    "1.0.0",
                    contract.getMaker().getNetworkId(),
                    myIdentity.getNetworkId(),
                    new byte[]{7});
            await(submitMessage(restartedService, duplicate));
            assertSame(restored, savedRecord(restartedService, duplicate.getTradeId()).trade());
            await(restartedService.onDepositTxConfirmed(tradeId));
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void networkHandoffUsesTheSavedMessageWithoutWaitingForItsDeliveryFuture(boolean futureAlreadyFailed) throws Exception {
        var delivery = new CompletableFuture<SendMessageResult>();
        var failure = new IllegalStateException("Peer is offline");
        if (futureAlreadyFailed) {
            delivery.completeExceptionally(failure);
        }
        var network = services.getNetworkService();
        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            doAnswer(invocation -> {
                var message = assertInstanceOf(PaymentReceivedMessage_F.class, invocation.getArgument(0));
                assertEquals(contract.getMaker().getNetworkId(), invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                var checkpoint = saved(service);
                assertInstanceOf(PaymentReceiptConfirmed.class, checkpoint.trade().getExecution().state());
                assertEquals(new Effect.SendMessage<>(message, message.getReceiver()).toProto(false),
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
            await(resumeLane(service, tradeId));
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

        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            await(service.paymentReceiptConfirmed(tradeId));
            var committed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(saved(service).pendingEffects().isEmpty());
            await(resumeLane(service, tradeId));
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

        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            await(service.paymentReceiptConfirmed(tradeId));
            var committed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(network, never()).confidentialSend(any(), any(), any());

            when(bans.isUserProfileBanned(bannedTrader)).thenReturn(false);
            await(resumeLane(service, tradeId));
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
        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery();
        try {

            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> await(service.paymentReceiptConfirmed(tradeId))).getCause());
            committed = savedRecord(service, tradeId).trade();
            assertInstanceOf(PaymentReceiptConfirmed.class, committed.getExecution().state());
            assertTrue(committed.getExecution().data().getMyFailure().isEmpty());
            assertEquals(new Effect.SendMessage<>(firstMessage.get(), contract.getMaker().getNetworkId()).toProto(false),
                    saved(service).pendingEffects().getFirst().toProto(false));
            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> await(resumeLane(service, tradeId))).getCause());

            doAnswer(invocation -> {
                var replayed = assertInstanceOf(PaymentReceivedMessage_F.class, invocation.getArgument(0));
                assertEquals(firstMessage.get().getId(), replayed.getId());
                assertEquals(firstMessage.get().toProto(false), replayed.toProto(false));
                assertEquals(contract.getMaker().getNetworkId(), invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(network).confidentialSend(any(), any(), any());
            if (retryBeforeRestart) {
                await(resumeLane(service, tradeId));
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
                List.of(new Effect.SendMessage<>(request, mediator))));
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
        seed(MuSigV2TradeRecord.initial(original));
        var service = startAndAwaitRecovery(effect -> fail("Mediation has not started"));
        try {
            var pending = submitMessage(service, request);

            await(pending);
            assertEquals(original.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertEquals(List.of(new Delivery<>(MuSigProtocol.Flow.MEDIATION,
                    new MediationPaymentDetailsRequestedEvent(request))), saved(service).pendingInputs());
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
            var opened = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                    tradeId,
                    mediator,
                    MediationCaseState.OPEN,
                    Optional.empty(),
                    Optional.empty());
            await(submitMessage(restoredService, opened));
            var trade = savedRecord(restoredService, opened.getTradeId()).trade();
            answered = trade.toProto(false);
            assertInstanceOf(MuSigMediationState.Open.class, trade.getMediation().orElseThrow().state());
            assertEquals(original.toProto(false).getExecution(), answered.getExecution());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                    .contains(MediationPaymentDetailsRequestedEvent.class));
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
        seed(MuSigV2TradeRecord.initial(original));
        var service = startAndAwaitRecovery();
        try {

            doAnswer(invocation -> {
                var response = assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, invocation.getArgument(0));
                firstResponse.set(response);
                assertEquals(myAccountPayload, response.getTakerAccountPayload());
                assertEquals(original.getExecution().data().getPeerAccountPayload().orElseThrow(),
                        response.getMakerAccountPayload());
                var checkpoint = saved(service);
                assertTrue(checkpoint.processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                        .contains(MediationPaymentDetailsRequestedEvent.class));
                assertEquals(new Effect.SendMessage<>(response, mediator).toProto(false),
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
            await(submitMessage(service, request));
            verify(network, times(1)).confidentialSend(any(), any(), any());

            doAnswer(invocation -> {
                var response = assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, invocation.getArgument(0));
                assertEquals(firstResponse.get().toProto(false), response.toProto(false));
                assertEquals(mediator, invocation.getArgument(1));
                assertEquals(myIdentity.getNetworkIdWithKeyPair(), invocation.getArgument(2));
                return CompletableFuture.completedFuture(new SendMessageResult());
            }).when(network).confidentialSend(any(), any(), any());
            if (retryBeforeRestart) {
                await(resumeLane(service, tradeId));
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
            verify(network, times(2)).confidentialSend(any(), any(), any());
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
            case "failedTrade" -> original.withState(new MuSigTradeState.Failed());
            case "historicalTrade" -> original.withState(new MuSigTradeState.Historical());
            default -> original.withMediation(FlowSnapshot.of(new MuSigMediationState.Failed(),
                    MuSigV2MediationData.initial()));
        };
        seed(MuSigV2TradeRecord.initial(original));
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
        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery(effect -> {
            attempts.add(assertInstanceOf(PaymentReceivedMessage_F.class,
                    ((Effect.SendMessage<?, ?>) effect).message()));
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
            assertEquals(List.of(new Effect.SendMessage<>(attempts.getFirst(), contract.getMaker().getNetworkId())),
                    saved(service).pendingEffects());

            assertSame(sendFailure, assertThrows(ExecutionException.class,
                    () -> await(resumeLane(service, tradeId))).getCause());
            assertSame(committed, savedRecord(service, tradeId).trade());
            assertEquals(attempts.getFirst(), attempts.getLast());
            assertEquals(2, attempts.size());
            assertEquals(1, saved(service).pendingEffects().size());
            assertEquals(List.of(deadline), saved(service).scheduledEvents());
            if (retryBeforeRestart) {
                failSend.set(false);
                await(resumeLane(service, tradeId));
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
                    List.of(new Effect.SendMessage<>(attempts.getFirst(), contract.getMaker().getNetworkId())), sent);
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
        var pending = new Effect.SendMessage<>(outgoing, contract.getMaker().getNetworkId());
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
        seed(MuSigV2TradeRecord.initial(trade));
        var service = startAndAwaitRecovery(effect -> {
            assertInstanceOf(CooperativeClosureMessage_G.class, ((Effect.SendMessage<?, ?>) effect).message());
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

            assertSame(failure, assertThrows(ExecutionException.class,
                    () -> await(resumeLane(service, buyerTradeId))).getCause());
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
        seed(MuSigV2TradeRecord.initial(tradeAt(new PaymentInitiatedMessageReceived())));
        var service = startAndAwaitRecovery(effect -> {
            failedReport.set((MuSigReportErrorMessage) ((Effect.SendMessage<?, ?>) effect).message());
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
                    ((Effect.SendMessage<?, ?>) sent.getFirst()).message());
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

    @Test
    void failedCheckpointPreventsEffectsAndRetryDoesNotRerunSigning() {
        var failWrites = new AtomicBoolean(false);
        var saveFailure = new IllegalStateException("Disk unavailable");
        var effects = new ArrayList<Effect>();
        var logs = new ArrayList<LogEntry>();
        var writes = new ArrayList<MuSigV2TradeRecord>();
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        Clock clock = contract::getTakeOfferDate;
        var timerExecutor = mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS);
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var checkpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(checkpoint.snapshot());
        writes.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(checkpoint),
                Runnable::run,
                timerExecutor,
                clock,
                definition,
                saved -> {
                    var record = MuSigV2TradeRecord.fromCheckpoint(saved);
                    if (failWrites.get() && record.trade().getExecution().state() instanceof PaymentReceiptConfirmed) {
                        throw saveFailure;
                    }
                    writes.add(record);
                },
                (current, effect) -> {
                    var saved = writes.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    assertEquals(1, saved.scheduledEvents().size());
                    effects.add(effect);
                },
                logs::add,
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            failWrites.set(true);
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
            assertSame(published.get(), applied.snapshot());
            var calculatedTrade = published.get().document();
            assertInstanceOf(PaymentReceiptConfirmed.class, calculatedTrade.getExecution().state());
            assertTrue(calculatedTrade.getExecution().data().getMySwapTxSignatureResponse().isPresent());
            assertSame(trade, writes.getLast().trade());
            assertTrue(effects.isEmpty());
            assertTrue(logs.isEmpty());
            verifyNoInteractions(timerExecutor);

            var blocked = assertInstanceOf(SubmissionResult.NotProcessed.class,
                    lane.submit(new MediationRequestedEvent()).join());
            assertSame(saveFailure, blocked.cause());
            assertSame(trade, writes.getLast().trade());
            assertTrue(effects.isEmpty());
            assertTrue(logs.isEmpty());
            verifyNoInteractions(timerExecutor);

            failWrites.set(false);
            lane.resume().join();
            var saved = writes.getLast();
            assertSame(calculatedTrade, saved.trade());
            assertEquals(1, effects.size());
            assertInstanceOf(PaymentReceivedMessage_F.class, ((Effect.SendMessage<?, ?>) effects.getFirst()).message());
            assertTrue(saved.pendingEffects().isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertInstanceOf(MuSigMediationState.Init.class, saved.trade().getMediation().orElseThrow().state());
            assertEquals(1, saved.scheduledEvents().size());
            var scheduled = saved.scheduledEvents().getFirst();
            assertEquals(CLOSE_TRADE_TIMEOUT_ID, scheduled.id());
            assertEquals(Instant.ofEpochMilli(clock.now()).plus(CLOSE_TRADE_TIMEOUT), scheduled.triggerAt());
            assertInstanceOf(SellersCloseTradeTimeoutEvent.class, scheduled.event());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            assertFalse(logs.isEmpty());
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aCheckpointFailureAfterSendingDoesNotFailTheTradeOrRepeatTheSend() {
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable after sending");
        var effects = new ArrayList<Effect>();
        var writes = new ArrayList<MuSigV2TradeRecord>();
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        Clock clock = contract::getTakeOfferDate;
        var timerExecutor = mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS);
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var checkpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(checkpoint.snapshot());
        writes.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(checkpoint),
                Runnable::run,
                timerExecutor,
                clock,
                definition,
                saved -> {
                    var record = MuSigV2TradeRecord.fromCheckpoint(saved);
                    if (failWrites.get() && !effects.isEmpty()) {
                        assertEquals(1, record.pendingEffects().size());
                        assertInstanceOf(ScheduledEvent.class, record.pendingEffects().getFirst());
                        throw saveFailure;
                    }
                    writes.add(record);
                },
                (current, effect) -> {
                    var saved = writes.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(PaymentReceiptConfirmed.class, saved.trade().getExecution().state());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    effects.add(effect);
                },
                ignored -> {},
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, pending.processingResult());
            assertSame(published.get(), applied.snapshot());
            var confirmedTrade = published.get().document();
            assertInstanceOf(PaymentReceiptConfirmed.class, confirmedTrade.getExecution().state());
            assertInstanceOf(MuSigTradeState.Active.class, confirmedTrade.state());
            assertEquals(1, effects.size());
            assertInstanceOf(PaymentReceivedMessage_F.class, ((Effect.SendMessage<?, ?>) effects.getFirst()).message());
            var savedBeforeRemoval = writes.getLast();
            assertSame(confirmedTrade, savedBeforeRemoval.trade());
            assertEquals(2, savedBeforeRemoval.pendingEffects().size());
            assertEquals(effects.getFirst(), savedBeforeRemoval.pendingEffects().getFirst());
            verifyNoInteractions(timerExecutor);

            var blocked = assertInstanceOf(SubmissionResult.NotProcessed.class,
                    lane.submit(new MediationRequestedEvent()).join());
            assertSame(saveFailure, blocked.cause());
            assertSame(savedBeforeRemoval, writes.getLast());
            assertEquals(1, effects.size());
            verifyNoInteractions(timerExecutor);

            failWrites.set(false);
            lane.resume().join();
            var saved = writes.getLast();
            assertSame(confirmedTrade, saved.trade());
            assertEquals(1, effects.size());
            assertTrue(saved.pendingEffects().isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertInstanceOf(MuSigMediationState.Init.class, saved.trade().getMediation().orElseThrow().state());
            assertEquals(1, saved.scheduledEvents().size());
            var scheduled = saved.scheduledEvents().getFirst();
            assertEquals(CLOSE_TRADE_TIMEOUT_ID, scheduled.id());
            assertEquals(Instant.ofEpochMilli(clock.now()).plus(CLOSE_TRADE_TIMEOUT), scheduled.triggerAt());
            assertInstanceOf(SellersCloseTradeTimeoutEvent.class, scheduled.event());
            verify(timerExecutor).schedule(any(Runnable.class), eq(CLOSE_TRADE_TIMEOUT.toNanos()), eq(TimeUnit.NANOSECONDS));
            verify(backend, times(1)).signSwapTx(any());
            verify(backend, never()).closeTrade(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aResumedTradeFailureDoesNotPreventOtherTradesFromStarting(boolean backendFails) throws Exception {
        var record = pendingCustomSettlement(TX_ID);
        var other = buyerTradeAt(new PaymentInitiated());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(record, new MuSigV2TradeRecord(other,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, confirmation)),
                List.of(),
                List.of()));
        if (backendFails) {
            when(backend.customCloseTrade(any())).thenThrow(new IllegalStateException("Backend unavailable"));
        } else {
            when(backend.customCloseTrade(any())).thenReturn(bisq.trade.protobuf.CustomCloseTradeResponse.getDefaultInstance());
        }
        String expectedError = backendFails ? "Backend unavailable" : "Custom payout transaction must not be empty";

        for (int restart = 0; restart < 3; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var failed = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, failed.getExecution().state());
                await(resumeLane(service, tradeId));
                assertSame(failed, savedRecord(service, tradeId).trade());
                assertEquals(record.trade().toProto(false).getMediation(),
                        failed.toProto(false).getMediation());
                assertEquals(record.trade().getExecution().data().getMyCustomPayoutPsbt(),
                        failed.getExecution().data().getMyCustomPayoutPsbt());
                assertTrue(failed.getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
                assertFalse(savedRecord(service,
                        tradeId).processedEventTypes().getOrDefault(Flow.EXECUTION,
                        Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
                assertEquals(expectedError, failed.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                var closed = savedRecord(service, other.getId()).trade();
                assertInstanceOf(TradeClosed.class, closed.getExecution().state());
                assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
                var saved = service.getPersistence().read().orElseThrow().getTrades();
                assertEquals(2, saved.size());
                for (var savedRecord : saved) {
                    assertTrue(savedRecord.pendingInputs().isEmpty());
                    assertTrue(savedRecord.scheduledEvents().isEmpty());
                    assertTrue(savedRecord.pendingEffects().isEmpty());
                    assertEquals(savedRecord(service, savedRecord.trade().getId()).trade().toProto(false),
                            savedRecord.trade().toProto(false));
                }
                var messageTypes = effects.stream().map(effect -> ((Effect.SendMessage<?, ?>) effect).message().getClass())
                        .collect(Collectors.toSet());
                assertEquals(restart == 0 ? Set.of(MuSigReportErrorMessage.class, CooperativeClosureMessage_G.class) : Set.of(),
                        messageTypes);
                assertEquals(restart == 0 ? 2 : 0, effects.size());
                verify(backend, times(1)).customCloseTrade(any());
                verify(backend, times(1)).closeTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void signingWaitsForTheLocalAcceptanceCheckpointToSucceed() throws GeneralSecurityException {
        var trade = tradeWithMediationResult();
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class, MediationClosedEvent.class)),
                List.of(),
                List.of(),
                List.of());
        var saved = new AtomicReference<>(record);
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable");
        var effects = new ArrayList<Effect>();
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, record.processedEventTypes()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                checkpoint -> {
                    var next = MuSigV2TradeRecord.fromCheckpoint(checkpoint);
                    if (failWrites.get() && next.pendingInputs().stream()
                            .anyMatch(input -> input.event() instanceof MediationResultByMyselfAcceptedEvent)) {
                        throw saveFailure;
                    }
                    saved.set(next);
                },
                (current, effect) -> {
                    assertSame(current.document(), saved.get().trade());
                    assertInstanceOf(CustomPayoutSigned.class, saved.get().trade().getExecution().state());
                    assertEquals(effect, saved.get().pendingEffects().getFirst());
                    effects.add(effect);
                },
                ignored -> {},
                (current, persistenceFailure, setupFailure) -> published.set(current));
        when(backend.signCustomPayoutTx(any())).thenAnswer(invocation -> {
            var checkpoint = saved.get();
            assertInstanceOf(DepositTxConfirmed.class, checkpoint.trade().getExecution().state());
            assertTrue(checkpoint.trade().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    checkpoint.trade().getMediation().orElseThrow().myDecision().state());
            assertEquals(Set.of(MediationResultAcceptedEvent.class),
                    checkpoint.processedEventTypes().get(Flow.MY_MEDIATION_DECISION));
            assertFalse(checkpoint.processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByMyselfAcceptedEvent.class));
            assertEquals(List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION,
                    new MediationResultByMyselfAcceptedEvent(result))),
                    checkpoint.pendingInputs());
            return new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false);
        });
        try {
            lane.resume().join();
            var pending = assertInstanceOf(SubmissionResult.Pending.class,
                    lane.submit(new MediationResultAcceptedEvent()).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var deferred = assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
            assertSame(published.get(), deferred.snapshot());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    published.get().document().getMediation().orElseThrow().myDecision().state());
            assertInstanceOf(DepositTxConfirmed.class, published.get().document().getExecution().state());
            assertTrue(published.get().document().getExecution().data().getMyCustomPayoutPsbt().isEmpty());
            verify(backend, never()).signCustomPayoutTx(any());
            assertTrue(effects.isEmpty());
            assertEquals(record.toProto(false), saved.get().toProto(false));

            failWrites.set(false);
            var resumed = lane.resume().join();

            assertSame(resumed.document(), saved.get().trade());
            assertInstanceOf(CustomPayoutSigned.class, resumed.document().getExecution().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    saved.get().trade().getMediation().orElseThrow().myDecision().state());
            assertTrue(saved.get().pendingInputs().isEmpty());
            assertTrue(saved.get().pendingEffects().isEmpty());
            assertTrue(saved.get().processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByMyselfAcceptedEvent.class));
            assertEquals(1, effects.size());
            assertInstanceOf(MuSigCustomPayoutPsbtMessage.class, ((Effect.SendMessage<?, ?>) effects.getFirst()).message());
            verify(backend, times(1)).signCustomPayoutTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void savedLocalAcceptanceSurvivesRestartAndSigningFailure(boolean signingFails) throws Exception {
        var trade = tradeWithMediationResult();
        trade = trade.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class,
                        MediationClosedEvent.class), Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, new MediationResultByMyselfAcceptedEvent(result))),
                List.of(),
                List.of()));
        if (signingFails) {
            when(backend.signCustomPayoutTx(any())).thenThrow(new IllegalStateException("Signing unavailable"));
        } else {
            when(backend.signCustomPayoutTx(any())).thenReturn(
                    new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false));
        }
        var effects = new ArrayList<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var restored = savedRecord(service, tradeId).trade();
                var decision = restored.getMediation().orElseThrow().myDecision();
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class, decision.state());
                assertEquals(Set.of(MediationResultAcceptedEvent.class),
                        saved(service).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                                Set.of()));
                if (signingFails) {
                    assertInstanceOf(Failed.class, restored.getExecution().state());
                    assertEquals(new MuSigTradeState.Failed(), restored.getState());
                    assertTrue(restored.getExecution().data().getMyCustomPayoutPsbt().isEmpty());
                    assertEquals("Signing unavailable",
                            restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                } else {
                    assertInstanceOf(CustomPayoutSigned.class, restored.getExecution().state());
                    assertEquals(new MuSigTradeState.Active(), restored.getState());
                    assertEquals(TX_ID, restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().getTxId());
                }
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(1, effects.size());
                assertEquals(signingFails ? MuSigReportErrorMessage.class : MuSigCustomPayoutPsbtMessage.class,
                        ((Effect.SendMessage<?, ?>) effects.getFirst()).message().getClass());
                verify(backend, times(1)).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "missingResult, A signed mediation result is not available",
            "wrongContract, Invalid mediator signature or contract hash",
            "wrongSignature, Invalid mediator signature or contract hash",
            "invalidAmounts, Custom payout amounts must equal totalPayoutAmount",
            "differentProposal, The accepted proposal does not match the stored signed mediation result",
            "noPayout, The mediator did not propose a payout"
    })
    void savedAcceptanceIsRevalidatedBeforeSigning(String invalidData, String expectedError) throws Exception {
        var resultBuilder = result.toProto(false).toBuilder();
        switch (invalidData) {
            case "wrongContract" -> {
                byte[] wrongHash = result.getContractHash();
                wrongHash[0] ^= 1;
                resultBuilder.setContractHash(ByteString.copyFrom(wrongHash));
            }
            case "invalidAmounts" -> resultBuilder.setProposedSellerPayoutAmount(60_001);
            case "noPayout" -> resultBuilder
                    .setMediationPayoutDistributionType(MediationPayoutDistributionType.NO_PAYOUT.toProtoEnum())
                    .clearProposedBuyerPayoutAmount()
                    .clearProposedSellerPayoutAmount();
        }
        var storedResult = MuSigMediationResult.fromProto(resultBuilder.build());
        var signingKeys = invalidData.equals("wrongSignature") ? KeyGeneration.generateDefaultEcKeyPair() : mediatorKeys;
        var signedResult = new MuSigV2MediationData.SignedResult(storedResult,
                MuSigMediationResultService.signMediationResult(storedResult, signingKeys));
        var mediationData = invalidData.equals("missingResult") ? MuSigV2MediationData.initial() :
                MuSigV2MediationData.initial().withResult(signedResult);
        var trade = tradeAt(new DepositTxConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(), mediationData))
                .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
        var acceptedResult = invalidData.equals("differentProposal")
                ? MuSigMediationResult.fromProto(storedResult.toProto(false).toBuilder()
                        .setSummaryNotes("Another proposal").build())
                : storedResult;
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class, MediationClosedEvent.class),
                        Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(new Delivery<>(Flow.EXECUTION, new MediationResultByMyselfAcceptedEvent(acceptedResult))),
                List.of(),
                List.of()));
        var effects = new ArrayList<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, restored.getExecution().state());
                assertInstanceOf(MuSigTradeState.Failed.class, restored.getState());
                assertEquals(expectedError, restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                assertTrue(restored.getExecution().data().getMyCustomPayoutPsbt().isEmpty());
                assertInstanceOf(MuSigMediationState.Closed.class, restored.getMediation().orElseThrow().state());
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                        restored.getMediation().orElseThrow().myDecision().state());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(1, effects.size());
                assertInstanceOf(MuSigReportErrorMessage.class,
                        ((Effect.SendMessage<?, ?>) effects.getFirst()).message());
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void finalizationWaitsForThePeerAcceptanceCheckpointToSucceed() {
        var signed = signedCustomPayoutTrade();
        var trade = signed.withPeerMediationDecision(MuSigV2MediationDecision.initial());
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class)),
                List.of(),
                List.of(),
                List.of());
        var saved = new AtomicReference<>(record);
        var failWrites = new AtomicBoolean(true);
        var saveFailure = new IllegalStateException("Disk unavailable");
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, record.processedEventTypes()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                checkpoint -> {
                    var next = MuSigV2TradeRecord.fromCheckpoint(checkpoint);
                    if (failWrites.get() && next.pendingInputs().stream()
                            .anyMatch(input -> input.event() instanceof MediationResultByPeerAcceptedEvent)) {
                        throw saveFailure;
                    }
                    saved.set(next);
                },
                (current, effect) -> fail("No outgoing message is expected"),
                ignored -> {},
                (current, persistenceFailure, setupFailure) -> published.set(current));
        when(backend.customCloseTrade(any())).thenAnswer(invocation -> {
            var checkpoint = saved.get();
            var execution = checkpoint.trade().getExecution();
            assertInstanceOf(CustomPayoutSigned.class, execution.state());
            assertTrue(execution.data().getPeerCustomPayoutPsbt().isEmpty());
            assertFalse(checkpoint.processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    checkpoint.trade().getMediation().orElseThrow().peerDecision().state());
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    checkpoint.processedEventTypes().get(Flow.PEER_MEDIATION_DECISION));
            assertEquals(List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION,
                    new MediationResultByPeerAcceptedEvent(new PeerCustomPayoutPsbt(TX_ID, new byte[]{7})))), checkpoint.pendingInputs());
            return bisq.trade.protobuf.CustomCloseTradeResponse.newBuilder()
                    .setCustomPayoutTx(ByteString.copyFrom(new byte[]{11})).build();
        });
        try {
            lane.resume().join();
            var message = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
            var pending = assertInstanceOf(SubmissionResult.Pending.class, lane.submit(message).join());
            assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
            assertSame(saveFailure, pending.cause());
            var deferred = assertInstanceOf(ProcessingResult.Deferred.class, pending.processingResult());
            assertSame(published.get(), deferred.snapshot());

            verify(backend, never()).customCloseTrade(any());
            assertInstanceOf(CustomPayoutSigned.class, published.get().document().getExecution().state());
            assertTrue(published.get().document().getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    published.get().document().getMediation().orElseThrow().peerDecision().state());
            assertEquals(trade.toProto(false), saved.get().trade().toProto(false));
            assertEquals(record.processedEventTypes(), saved.get().processedEventTypes());
            assertEquals(List.of(new Delivery<>(Flow.PEER_MEDIATION_DECISION, message)), saved.get().pendingInputs());
            assertTrue(saved.get().pendingEffects().isEmpty());

            failWrites.set(false);
            var resumed = lane.resume().join();

            assertSame(resumed.document(), saved.get().trade());
            assertInstanceOf(CustomPayoutClosedTrade.class, resumed.document().getExecution().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                    saved.get().trade().getMediation().orElseThrow().peerDecision().state());
            assertTrue(saved.get().pendingInputs().isEmpty());
            assertTrue(saved.get().pendingEffects().isEmpty());
            assertTrue(saved.get().processedEventTypes().get(Flow.EXECUTION)
                    .contains(MediationResultByPeerAcceptedEvent.class));
            var peerPsbt = saved.get().trade().getExecution().data().getPeerCustomPayoutPsbt().orElseThrow();
            assertEquals(TX_ID, peerPsbt.getTxId());
            assertArrayEquals(new byte[]{7}, peerPsbt.getPsbt());
            verify(backend, times(1)).customCloseTrade(any());
            verify(backend, never()).signCustomPayoutTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aSavedPeerAcceptanceFinalizesAfterRestart() throws Exception {
        var peerPsbt = new PeerCustomPayoutPsbt(TX_ID, new byte[]{7});
        seed(pendingCustomSettlement(TX_ID));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("Finalization does not send a message"));
            try {
                var closed = savedRecord(service, tradeId).trade();
                assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
                assertEquals(TX_ID, closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getTxId());
                assertArrayEquals(peerPsbt.getPsbt(),
                        closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getPsbt());
                assertEquals(Set.of(MediationResultByPeerAcceptedEvent.class),
                        saved(service).processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()));
                assertTrue(saved(service).pendingInputs().isEmpty());
                verify(backend, times(1)).customCloseTrade(new CustomCloseTradeRequest(tradeId, peerPsbt.getPsbt()).toProto(false));
                verify(backend, never()).signCustomPayoutTx(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @ParameterizedTest
    @CsvSource({
            "missingMediation, false",
            "missingResult, false",
            "noPayout, false",
            "myInit, false",
            "myRejected, false",
            "myFailed, false",
            "peerInit, false",
            "peerRejected, false",
            "peerFailed, false",
            "myRejected, true"
    })
    void savedFinalizationRequiresRecordedAgreementWithoutBlockingOtherTrades(String invalidAgreement,
                                                                              boolean mismatchingTxId) throws Exception {
        var signed = signedCustomPayoutTrade();
        MuSigV2Trade<?> trade = switch (invalidAgreement) {
            case "missingMediation" -> MuSigV2Trade.fromProto(signed.toProto(false).toBuilder().clearMediation().build());
            case "missingResult" -> signed.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                    MuSigV2MediationData.initial()));
            case "noPayout" -> {
                var noPayout = MuSigMediationResult.fromProto(result.toProto(false).toBuilder()
                        .setMediationPayoutDistributionType(MediationPayoutDistributionType.NO_PAYOUT.toProtoEnum())
                        .clearProposedBuyerPayoutAmount()
                        .clearProposedSellerPayoutAmount()
                        .build());
                yield signed.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(noPayout,
                                MuSigMediationResultService.signMediationResult(noPayout, mediatorKeys)))));
            }
            case "myInit" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Init()));
            case "myRejected" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected()));
            case "myFailed" -> signed.withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Failed()));
            case "peerInit" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Init()));
            case "peerRejected" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected()));
            case "peerFailed" -> signed.withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Failed()));
            default -> throw new IllegalArgumentException(invalidAgreement);
        };
        var pending = pendingCustomSettlement(mismatchingTxId ? "34".repeat(32) : TX_ID);
        var other = buyerTradeAt(new PaymentInitiated());
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})));
        seed(new MuSigV2TradeRecord(trade,
                        trade.getMediation().isPresent() ? pending.processedEventTypes() : Map.of(),
                        pending.pendingInputs(),
                        List.of(),
                        List.of()),
                new MuSigV2TradeRecord(other,
                        Map.of(),
                        List.of(new Delivery<>(Flow.EXECUTION, confirmation)),
                        List.of(),
                        List.of()));
        var effects = new ConcurrentLinkedQueue<Effect>();

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> {
                var messageSent = assertInstanceOf(MuSigTradeMessage.class, ((Effect.SendMessage<?, ?>) effect).message());
                if (messageSent.getTradeId().equals(tradeId)) {
                    effects.add(effect);
                }
            });
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, restored.getExecution().state());
                assertInstanceOf(MuSigTradeState.Failed.class, restored.getState());
                assertEquals("Cannot finalize custom payout without a signed payout proposal accepted by both traders",
                        restored.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                assertEquals(trade.getMediation().map(mediation -> mediation.toProto(false)),
                        restored.getMediation().map(mediation -> mediation.toProto(false)));
                assertEquals(trade.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().toProto(false),
                        restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().toProto(false));
                assertTrue(restored.getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
                assertTrue(restored.getExecution().data().getCustomCloseTradeResponse().isEmpty());
                var savedRecord = service.getPersistence().read().orElseThrow().getTrades().stream()
                        .filter(record -> record.trade().getId().equals(tradeId)).findFirst().orElseThrow();
                assertEquals(restored.toProto(false), savedRecord.trade().toProto(false));
                assertTrue(savedRecord.pendingInputs().isEmpty());
                assertTrue(savedRecord.pendingEffects().isEmpty());
                assertFalse(savedRecord.processedEventTypes().get(Flow.EXECUTION)
                        .contains(MediationResultByPeerAcceptedEvent.class));
                assertEquals(1, effects.size());
                assertInstanceOf(MuSigReportErrorMessage.class,
                        ((Effect.SendMessage<?, ?>) effects.element()).message());
                assertInstanceOf(TradeClosed.class,
                        await(whenExecutionState(service, other.getId(), TradeClosed.class)));
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void aPeerFirstPayoutMismatchSurvivesRestartWithoutBlockingOtherTrades() throws Exception {
        var record = pendingCustomSettlement("34".repeat(32));
        var other = buyerTradeAt(new PaymentInitiated());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(record, new MuSigV2TradeRecord(other,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, confirmation)),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 3; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var unresolved = savedRecord(service, tradeId).trade();
                assertInstanceOf(CustomPayoutSigned.class, unresolved.getExecution().state());
                await(resumeLane(service, tradeId));
                assertSame(unresolved, savedRecord(service, tradeId).trade());
                await(submitMessage(service,
                        peerDecision(true, MuSigMediationResultService.getMediationResultHash(result))));
                assertSame(unresolved, savedRecord(service, tradeId).trade());
                var savedRecord = service.getPersistence().read().orElseThrow().getTrades().stream()
                        .filter(candidate -> candidate.trade().getId().equals(tradeId)).findFirst().orElseThrow();
                assertEquals(record.toProto(false), savedRecord.toProto(false));
                assertInstanceOf(TradeClosed.class,
                        await(whenExecutionState(service, other.getId(), TradeClosed.class)));
                assertEquals(restart == 0 ? 1 : 0, effects.size());
                if (restart == 0) {
                    assertInstanceOf(CooperativeClosureMessage_G.class,
                            ((Effect.SendMessage<?, ?>) effects.element()).message());
                }
                verify(backend, never()).signCustomPayoutTx(any());
                verify(backend, never()).customCloseTrade(any());
                verify(backend, times(1)).closeTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void aKnownPayoutMismatchIsIgnoredAcrossRestartWithoutConsumingAcceptance() throws Exception {
        var signed = pendingCustomSettlement(TX_ID).trade();
        var trade = signed.withPeerMediationDecision(MuSigV2MediationDecision.initial());
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        var wrong = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                hash,
                "34".repeat(32),
                new byte[]{7});
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.PEER_MEDIATION_DECISION, wrong)),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> fail("Ignored messages must not produce effects"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertEquals(MuSigV2TradeRecord.initial(trade).toProto(false), saved(service).toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("Finalization does not send a message"));
        try {
            assertEquals(MuSigV2TradeRecord.initial(trade).toProto(false), saved(restoredService).toProto(false));
            var valid = peerDecision(true, hash);
            await(submitMessage(restoredService, valid));
            var closed = savedRecord(restoredService, valid.getTradeId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            var peerDecision = closed.getMediation().orElseThrow().peerDecision();
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, peerDecision.state());
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            assertEquals(TX_ID, closed.getExecution().data().getPeerCustomPayoutPsbt().orElseThrow().getTxId());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            await(submitMessage(restoredService, valid));
            assertSame(closed, savedRecord(restoredService, valid.getTradeId()).trade());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Completed work must not be replayed"));
        try {
            assertInstanceOf(CustomPayoutClosedTrade.class,
                    savedRecord(restartedService, tradeId).trade().getExecution().state());
            assertTrue(saved(restartedService).pendingInputs().isEmpty());
            verify(backend, never()).signCustomPayoutTx(any());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void executionFailureStopsTheParentAndDiscardsPendingMediationInputs() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        var payment = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new byte[]{7});
        var opened = new MediationOpenedEvent(new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId(),
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty()));
        // The missing redacted signatures cause execution to fail before the mediation input runs.
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, payment),
                        new Delivery<>(MuSigProtocol.Flow.MEDIATION, opened)),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> {});
        try {
            var resumed = savedRecord(service, tradeId).trade();
            assertInstanceOf(Failed.class, resumed.getExecution().state());
            assertEquals(new MuSigTradeState.Failed(), resumed.getState());
            assertEquals(Set.of(ExecutionFailedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertEquals(trade.toProto(false).getMediation(),
                    resumed.toProto(false).getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertEquals(resumed.toProto(false), saved(service).trade().toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCheckpointIsNotIncludedInOtherSaves(boolean saveOtherTrade) throws Exception {
        var persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigV2TradeStore> persistence = mock(Persistence.class);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        doReturn(persistence).when(persistenceService).getOrCreatePersistence(
                any(),
                any(DbSubDirectory.class),
                anyString(),
                any(MuSigV2TradeStore.class));
        var lastWritten = new AtomicReference<MuSigV2TradeStore>();
        var failNextWrite = new AtomicBoolean();
        var diskFailure = new IOException("Disk unavailable");
        when(persistence.persistAsync(any())).thenAnswer(invocation -> {
            if (failNextWrite.getAndSet(false)) {
                return CompletableFuture.failedFuture(diskFailure);
            }
            lastWritten.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        var initial = MuSigV2TradeRecord.initial(tradeAt(new TradeClosed()));
        var other = buyerTradeAt(new TradeClosed());
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            service.getPersistableStore().put(initial);
            service.getPersistableStore().put(MuSigV2TradeRecord.initial(other));
            lastWritten.set(service.getPersistableStore().getClone());
            assertTrue(await(service.initialize()));
            await(service.getRecoveryFuture());

            failNextWrite.set(true);
            var failure = assertThrows(ExecutionException.class, () -> await(service.archiveTrade(tradeId)));
            assertSame(diskFailure, bisq.common.util.ExceptionUtil.getRootCause(failure));
            assertInstanceOf(MuSigTradeState.Historical.class, service.getTradeReadModel(tradeId).getState());
            assertTrue(service.getTradeReadModel(tradeId).getPersistenceError().isPresent());

            if (saveOtherTrade) {
                await(service.archiveTrade(other.getId()));
            } else {
                assertTrue(await(service.persist()));
            }
            var written = lastWritten.get().toProto(false).getTradeByIdOrThrow(tradeId);
            assertEquals(initial.trade().getState().toProto(false), written.getTrade().getState());
            assertEquals(initial.toProto(false), written);
            assertEquals(initial.toProto(false), savedRecord(service, tradeId).toProto(false));

            await(resumeLane(service, tradeId));
            assertInstanceOf(MuSigTradeState.Historical.class, savedRecord(service, tradeId).trade().getState());
            assertEquals(saveOtherTrade ? new MuSigTradeState.Historical() : new MuSigTradeState.Active(),
                    savedRecord(service, other.getId()).trade().getState());
            assertEquals(service.getPersistableStore().toProto(false), lastWritten.get().toProto(false));
            assertTrue(service.getTradeReadModel(tradeId).getPersistenceError().isEmpty());
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void persistenceFailureDuringErrorHandlingIsReportedByRecovery(boolean failAfterReportSent) throws Exception {
        var persistenceService = mock(PersistenceService.class);
        @SuppressWarnings("unchecked")
        Persistence<MuSigV2TradeStore> persistence = mock(Persistence.class);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        doReturn(persistence).when(persistenceService).getOrCreatePersistence(
                any(),
                any(DbSubDirectory.class),
                anyString(),
                any(MuSigV2TradeStore.class));
        var lastWritten = new AtomicReference<MuSigV2TradeStore>();
        var report = new AtomicReference<MuSigReportErrorMessage>();
        var diskFailure = new IOException("Disk unavailable");
        when(persistence.persistAsync(any())).thenAnswer(invocation -> {
            MuSigV2TradeStore store = invocation.getArgument(0);
            boolean failed = store.getTrades().getFirst().trade().getExecution().state() instanceof Failed;
            if (failed && (!failAfterReportSent || report.get() != null)) {
                return CompletableFuture.failedFuture(diskFailure);
            }
            lastWritten.set(store);
            return CompletableFuture.completedFuture(null);
        });
        var backendFailure = new IllegalStateException("Backend unavailable");
        when(backend.customCloseTrade(any())).thenThrow(backendFailure);
        mockNetworkSend(effect ->
                report.set((MuSigReportErrorMessage) ((Effect.SendMessage<?, ?>) effect).message()));
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            service.getPersistableStore().put(pendingCustomSettlement(TX_ID));
            lastWritten.set(service.getPersistableStore().getClone());

            assertTrue(await(service.initialize()));
            var completion = assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture()));

            assertSame(diskFailure, bisq.common.util.ExceptionUtil.getRootCause(completion));
            assertTrue(service.getTradeReadModel(tradeId).getMyFailure().orElseThrow().errorMessage()
                    .contains("Backend unavailable"));
            assertInstanceOf(Failed.class,
                    await(whenExecutionState(service, tradeId, Failed.class)));
            var savedRecord = lastWritten.get().getTrades().getFirst();
            if (failAfterReportSent) {
                assertInstanceOf(Failed.class, savedRecord.trade().getExecution().state());
                var pending = (MuSigReportErrorMessage) ((Effect.SendMessage<?, ?>) savedRecord.pendingEffects().getFirst()).message();
                assertEquals(report.get().getId(), pending.getId());
            } else {
                assertInstanceOf(CustomPayoutSigned.class, savedRecord.trade().getExecution().state());
                assertNull(report.get(), "Nothing may be sent before the failure is durably recorded");
            }
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void errorReportDeliveryFailureWhileResumingIsReportedByRecovery() throws Exception {
        seed(pendingCustomSettlement(TX_ID));
        var backendFailure = new IllegalStateException("Backend unavailable");
        when(backend.customCloseTrade(any())).thenThrow(backendFailure);
        var rejectedReport = new AtomicReference<MuSigReportErrorMessage>();
        var deliveryFailure = new IllegalStateException("Transport unavailable");

        var completion = assertThrows(ExecutionException.class, () -> startAndAwaitRecovery(effect -> {
            rejectedReport.set((MuSigReportErrorMessage) ((Effect.SendMessage<?, ?>) effect).message());
            throw deliveryFailure;
        }));

        assertSame(deliveryFailure, completion.getCause());
        assertTrue(rejectedReport.get().getErrorMessage().contains("Backend unavailable"));
        var effects = new ArrayList<Effect>();
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertInstanceOf(Failed.class,
                    await(whenExecutionState(service, tradeId, Failed.class)));
            assertEquals(1, effects.size());
            var replayed = (MuSigReportErrorMessage) ((Effect.SendMessage<?, ?>) effects.getFirst()).message();
            assertEquals(rejectedReport.get().getId(), replayed.getId());
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(backend, times(1)).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void peerDecisionsForNoPayoutAreIgnoredAndRemovedFromPendingWork(boolean accepted,
                                                                   boolean beforeCaseClosure) throws Exception {
        var noPayout = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.TRADE_ALREADY_SETTLED,
                MediationPayoutDistributionType.NO_PAYOUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        var trade = tradeAt(new DepositTxConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Open(), MuSigV2MediationData.initial()));
        try (var lane = new MuSigTradeLaneFixture<>(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effect -> fail("Ignoring a peer decision must not produce effects"),
                entry -> fail("Ignoring a peer decision must not produce a trade log"),
                contract::getTakeOfferDate)) {
            var message = peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(noPayout));
            if (beforeCaseClosure) {
                assertInstanceOf(ProcessingResult.Deferred.class, lane.handle(message));
                assertSame(trade, lane.getTrade());
                assertEquals(List.of(new Delivery<>(Flow.PEER_MEDIATION_DECISION, message)),
                        lane.getSavedRecord().pendingInputs());
                assertTrue(lane.getSavedRecord().processedEventTypes().get(Flow.PEER_MEDIATION_DECISION).isEmpty());
            }
            var caseClosed = new MediationClosedEvent(new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId(),
                    MediationCaseState.CLOSED,
                    Optional.of(noPayout),
                    Optional.of(MuSigMediationResultService.signMediationResult(noPayout, mediatorKeys))));
            assertInstanceOf(ProcessingResult.Applied.class, lane.handle(caseClosed));
            var closed = lane.getTrade();
            if (!beforeCaseClosure) {
                assertInstanceOf(ProcessingResult.Ignored.class, lane.handle(message));
            }
            assertTrue(lane.getSavedRecord().pendingInputs().isEmpty());
            lane.resume();

            assertSame(closed, lane.getTrade());
            assertSame(trade.getExecution(), closed.getExecution());
            assertInstanceOf(MuSigTradeState.Active.class, closed.state());
            var mediation = closed.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationState.Closed.class, mediation.state());
            assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.peerDecision().state());
            var saved = lane.getSavedRecord();
            assertEquals(Set.of(MediationClosedEvent.class), saved.processedEventTypes().get(Flow.MEDIATION));
            assertTrue(saved.processedEventTypes().get(Flow.PEER_MEDIATION_DECISION).isEmpty());
            assertTrue(saved.pendingInputs().isEmpty());
            assertTrue(saved.pendingEffects().isEmpty());
            assertEquals(closed.toProto(false), saved.trade().toProto(false));
            verifyNoInteractions(backend);
        }
    }

    @Test
    void aFailedParentDiscardsPeerDecisionInputsWithoutChangingItsChildren() throws Exception {
        var noPayout = new MuSigMediationResult(ContractService.getContractHash(contract),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.NO_PAYOUT,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
        var failed = tradeAt(new Failed()).withState(new MuSigTradeState.Failed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(noPayout,
                                new byte[]{1, 2})),
                                Optional.empty())));
        var message = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                MuSigMediationResultService.getMediationResultHash(noPayout),
                TX_ID,
                new byte[]{7});
        seed(new MuSigV2TradeRecord(failed,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class,
                        MediationClosedEvent.class)),
                List.of(new Delivery<>(MuSigProtocol.Flow.PEER_MEDIATION_DECISION, message)),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("No report is expected"));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertEquals(failed.toProto(false).getExecution(),
                        restored.toProto(false).getExecution());
                assertEquals(new MuSigTradeState.Failed(), restored.getState());
                var mediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Closed.class, mediation.state());
                assertTrue(mediation.data().failure().isEmpty());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
                var peer = mediation.peerDecision();
                assertInstanceOf(MuSigMediationDecisionState.Init.class, peer.state());
                assertTrue(peer.data().failure().isEmpty());
                assertTrue(saved(service).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                        Set.of()).isEmpty());
                await(submitMessage(service, message));
                assertSame(restored, savedRecord(service, message.getTradeId()).trade());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
            } finally {
                service.shutdown().join();
            }
        }
        verifyNoInteractions(backend);
    }

    @Test
    void aCaseFailureStopsItsChildrenAndRecoveryContinuesNormalSettlement() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed())
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Open(), MuSigV2MediationData.initial()));
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        byte[] wrongSignature = MuSigMediationResultService.signMediationResult(result, KeyGeneration.generateDefaultEcKeyPair());
        var invalidResult = new MediationClosedEvent(new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                tradeId,
                mediator,
                MediationCaseState.CLOSED,
                Optional.of(result),
                Optional.of(wrongSignature)));
        var peerAcceptance = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        var closure = new CooperativeClosureMessage_G(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new ByteArray(new byte[]{7}));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(
                        new Delivery<>(MuSigProtocol.Flow.PEER_MEDIATION_DECISION, peerAcceptance),
                        new Delivery<>(MuSigProtocol.Flow.MEDIATION, invalidResult),
                        new Delivery<>(MuSigProtocol.Flow.EXECUTION, closure)),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> assertFalse(effect instanceof Effect.SendMessage));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(TradeClosed.class, restored.getExecution().state());
                assertEquals(new MuSigTradeState.Active(), restored.getState());
                assertTrue(restored.getExecution().data().getMyFailure().isEmpty());
                var mediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Failed.class, mediation.state());
                assertTrue(mediation.data().failure().isPresent());
                assertTrue(mediation.data().signedResult().isEmpty());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
                assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.peerDecision().state());
                assertTrue(mediation.myDecision().data().failure().isEmpty());
                assertTrue(mediation.peerDecision().data().failure().isEmpty());
                var corrected = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                        tradeId,
                        mediator,
                        MediationCaseState.CLOSED,
                        Optional.of(result),
                        Optional.of(MuSigMediationResultService.signMediationResult(result, mediatorKeys)));
                await(submitMessage(service, corrected));
                assertSame(restored, savedRecord(service, corrected.getTradeId()).trade());
                await(submitMessage(service, peerAcceptance));
                assertSame(restored, savedRecord(service, peerAcceptance.getTradeId()).trade());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
                verify(backend, times(1)).closeTrade(any());
                verify(backend, never()).customCloseTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void anOwnDecisionFailureSurvivesRestartAndDoesNotStopThePeerDecision() throws Exception {
        var details = new MuSigV2FailureDetails(TradeProtocolFailure.UNKNOWN,
                "Decision processing failed",
                "Decision trace");
        var own = new MuSigV2MediationDecision<>(new MuSigMediationDecisionState.Failed(),
                new MuSigV2MediationDecisionData(Optional.of(details)));
        var mediation = MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                MuSigMediationResultService.signMediationResult(result, mediatorKeys)));
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(
                FlowSnapshot.of(new MuSigMediationState.Closed(),
                        mediation)).withMyMediationDecision(own);
        var peerAcceptance = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class,
                        MediationClosedEvent.class), Flow.MY_MEDIATION_DECISION, Set.of(MuSigProcessingFailedEvent.class)),
                List.of(new Delivery<>(MuSigProtocol.Flow.PEER_MEDIATION_DECISION, peerAcceptance)),
                List.of(),
                List.of()));

        for (int restart = 0; restart < 2; restart++) {
            var service = startAndAwaitRecovery(effect -> fail("No outgoing work is expected"));
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
                assertEquals(new MuSigTradeState.Active(), restored.getState());
                var restoredMediation = restored.getMediation().orElseThrow();
                assertInstanceOf(MuSigMediationState.Closed.class, restoredMediation.state());
                assertTrue(restoredMediation.data().failure().isEmpty());
                assertEquals(own, restoredMediation.myDecision());
                assertInstanceOf(MuSigMediationDecisionState.Accepted.class,
                        restoredMediation.peerDecision().state());
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertEquals(restored.toProto(false), saved(service).trade().toProto(false));
            } finally {
                service.shutdown().join();
            }
        }
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void localRejectionDiscardsPeerAcceptanceWorkAcrossRestartInEitherOrder(boolean peerFirst) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                        new byte[]{1, 2}))));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class, MediationClosedEvent.class)),
                List.of(),
                List.of(),
                List.of()));
        var peer = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        var effects = new ConcurrentLinkedQueue<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            if (peerFirst) {
                await(submitMessage(service, peer));
                var pending = saved(service).pendingInputs();
                assertEquals(1, pending.size());
                assertEquals(MuSigProtocol.Flow.EXECUTION, pending.getFirst().flow());
                assertInstanceOf(MediationResultByPeerAcceptedEvent.class, pending.getFirst().event());
            } else {
                await(service.rejectMediationResult(tradeId));
                assertTrue(saved(service).pendingInputs().isEmpty());
            }
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            if (peerFirst) {
                assertEquals(1, saved(restoredService).pendingInputs().size());
                await(restoredService.rejectMediationResult(tradeId));
            } else {
                await(submitMessage(restoredService, peer));
            }
            var updated = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            var mediation = updated.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation.peerDecision().state());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertEquals(updated.toProto(false), saved(restoredService).trade().toProto(false));
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Discarded work must not be replayed"));
        try {
            var restored = savedRecord(restartedService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
            assertFalse(saved(restartedService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
            var mediation = restored.getMediation().orElseThrow();
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class, mediation.myDecision().state());
            assertInstanceOf(MuSigMediationDecisionState.Accepted.class, mediation.peerDecision().state());
            assertEquals(Set.of(MediationResultRejectedEvent.class),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                            Set.of()));
            assertEquals(Set.of(MuSigCustomPayoutPsbtMessage.class),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            await(submitMessage(restartedService, peer));
            assertSame(restored, savedRecord(restartedService, peer.getTradeId()).trade());
            await(restartedService.rejectMediationResult(tradeId));
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
            await(resumeLane(restartedService, tradeId));
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
            assertTrue(saved(restartedService).pendingInputs().isEmpty());
        } finally {
            restartedService.shutdown().join();
        }
        assertEquals(1, effects.size());
        var send = assertInstanceOf(Effect.SendMessage.class, effects.element());
        assertInstanceOf(MuSigMediationResultRejectionMessage.class, send.message());
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ignoringASavedPeerDecisionPersistsItsRemovalWithoutConsumingTheType(boolean accepted) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                        new byte[]{1, 2})),
                        Optional.empty())));
        byte[] wrongHash = MuSigMediationResultService.getMediationResultHash(result);
        wrongHash[0] ^= 1;
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class,
                        MediationClosedEvent.class)),
                List.of(new Delivery<>(MuSigProtocol.Flow.PEER_MEDIATION_DECISION, peerDecision(accepted, wrongHash))),
                List.of(),
                List.of()));

        var service = startAndAwaitRecovery(effect -> fail("Ignored messages must not produce effects"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            var record = saved(service);
            assertTrue(record.pendingInputs().isEmpty());
            assertTrue(record.pendingEffects().isEmpty());
            assertEquals(trade.toProto(false), record.trade().toProto(false));
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            var valid = peerDecision(accepted, MuSigMediationResultService.getMediationResultHash(result));
            await(submitMessage(restoredService, valid));
            var updated = savedRecord(restoredService, valid.getTradeId()).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            assertEquals(accepted ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                    updated.getMediation().orElseThrow().peerDecision().state());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                    Set.of()).contains(valid.getClass()));
            assertEquals(updated.toProto(false), saved(restoredService).trade().toProto(false));
        } finally {
            restoredService.shutdown().join();
        }
        verifyNoInteractions(backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void earlyContradictoryPeerDecisionsSurviveRestartAndOnlyTheFirstBecomesFinal(boolean acceptedFirst) throws Exception {
        var trade = tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Open(),
                MuSigV2MediationData.initial()));
        seed(new MuSigV2TradeRecord(trade,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class)),
                List.of(),
                List.of(),
                List.of()));
        byte[] hash = MuSigMediationResultService.getMediationResultHash(result);
        var first = peerDecision(acceptedFirst, hash);
        var conflicting = peerDecision(!acceptedFirst, hash);

        var service = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            await(submitMessage(service, first));
            await(submitMessage(service, conflicting));
            var saved = saved(service);
            assertEquals(2, saved.pendingInputs().size());
            assertTrue(saved.pendingInputs().stream().allMatch(input -> input.flow() == MuSigProtocol.Flow.PEER_MEDIATION_DECISION));
            assertInstanceOf(MuSigMediationDecisionState.Init.class,
                    saved.trade().getMediation().orElseThrow().peerDecision().state());
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            assertEquals(2, saved(restoredService).pendingInputs().size());
            var closed = new MuSigMediationStateChangeMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId(),
                    MediationCaseState.CLOSED,
                    Optional.of(result),
                    Optional.of(MuSigMediationResultService.signMediationResult(result, mediatorKeys)));
            await(submitMessage(restoredService, closed));
            var updated = savedRecord(restoredService, closed.getTradeId()).trade();
            assertInstanceOf(DepositTxConfirmed.class, updated.getExecution().state());
            var mediation = updated.getMediation().orElseThrow();
            assertEquals(Set.of(MediationOpenedEvent.class, MediationClosedEvent.class),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of()));
            assertEquals(acceptedFirst ? new MuSigMediationDecisionState.Accepted() : new MuSigMediationDecisionState.Rejected(),
                    mediation.peerDecision().state());
            assertEquals(Set.of(first.getClass()),
                    saved(restoredService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION,
                            Set.of()));
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION,
                    Set.of()).isEmpty());
            assertTrue(saved(restoredService).pendingInputs().stream().noneMatch(input ->
                    input.flow() == MuSigProtocol.Flow.PEER_MEDIATION_DECISION));
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("No outgoing message is expected"));
        try {
            var before = savedRecord(restartedService, tradeId).trade();
            await(submitMessage(restartedService, conflicting));
            assertSame(before, savedRecord(restartedService, conflicting.getTradeId()).trade());
            await(submitMessage(restartedService, first));
            assertSame(before, savedRecord(restartedService, first.getTradeId()).trade());
            assertEquals(Set.of(first.getClass()),
                    saved(restartedService).processedEventTypes().getOrDefault(Flow.PEER_MEDIATION_DECISION, Set.of()));
        } finally {
            restartedService.shutdown().join();
        }
        verifyNoInteractions(backend);
    }

    @Test
    void everyCombinationOfDecisionStatesAndTheirIndependentHistoriesRoundTrips() throws Exception {
        List<MuSigMediationDecisionState> states = List.of(new MuSigMediationDecisionState.Init(),
                new MuSigMediationDecisionState.Accepted(),
                new MuSigMediationDecisionState.Rejected(),
                new MuSigMediationDecisionState.Failed());
        for (var mine : states) {
            for (var peer : states) {
                Class<?>[] myTypes = switch (mine) {
                    case MuSigMediationDecisionState.Init ignored -> new Class<?>[0];
                    case MuSigMediationDecisionState.Accepted ignored -> new Class<?>[]{MediationResultAcceptedEvent.class};
                    case MuSigMediationDecisionState.Rejected ignored -> new Class<?>[]{MediationResultRejectedEvent.class};
                    case MuSigMediationDecisionState.Failed ignored -> new Class<?>[]{MuSigProcessingFailedEvent.class};
                };
                Class<?>[] peerTypes = switch (peer) {
                    case MuSigMediationDecisionState.Init ignored -> new Class<?>[0];
                    case MuSigMediationDecisionState.Accepted ignored -> new Class<?>[]{MuSigCustomPayoutPsbtMessage.class};
                    case MuSigMediationDecisionState.Rejected ignored -> new Class<?>[]{MuSigMediationResultRejectionMessage.class};
                    case MuSigMediationDecisionState.Failed ignored -> new Class<?>[]{MuSigProcessingFailedEvent.class};
                };
                var mediation = new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                        new byte[]{1, 2})), Optional.empty());
                var trade = tradeAt(new DepositTxConfirmed())
                        .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(), mediation))
                        .withMyMediationDecision(decisionSnapshot(mine))
                        .withPeerMediationDecision(decisionSnapshot(peer));
                var record = new MuSigV2TradeRecord(trade,
                        Map.of(Flow.MY_MEDIATION_DECISION, Set.of(myTypes),
                                Flow.PEER_MEDIATION_DECISION, Set.of(peerTypes)),
                        List.of(),
                        List.of(),
                        List.of());
                var proto = record.toProto(false);
                var restoredRecord = MuSigV2TradeRecord.fromProto(
                        bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
                var restored = restoredRecord.trade().getMediation().orElseThrow();
                assertEquals(record.processedEventTypes(), restoredRecord.processedEventTypes());
                assertEquals(decisionSnapshot(mine), restored.myDecision());
                assertEquals(decisionSnapshot(peer), restored.peerDecision());
                assertEquals(proto, restoredRecord.toProto(false));
            }
        }
    }

    @Test
    void overdueTimeoutRunsAfterRestartAndItsCancellationIsPersisted() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed());
        var scheduled = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                Instant.parse("2100-01-01T00:00:00.123456789Z"),
                new SellersCloseTradeTimeoutEvent());
        Clock clock = () -> scheduled.triggerAt().plusSeconds(1).toEpochMilli();
        seed(new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(scheduled), List.of()));
        var service = startAndAwaitRecovery(effect -> {}, clock);
        try {
            // Await the backend callback explicitly: the timer thread may enqueue after a snapshot query.
            verify(backend, timeout(10_000).times(1)).closeTrade(any());
            var closed = savedRecord(service, tradeId).trade();
            assertInstanceOf(SellerForceClosedTrade.class, closed.getExecution().state());
            assertTrue(saved(service).scheduledEvents().isEmpty());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> {}, clock);
        try {
            assertInstanceOf(SellerForceClosedTrade.class,
                    await(whenExecutionState(restoredService, tradeId, SellerForceClosedTrade.class)));
            verify(backend, times(1)).closeTrade(any());
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void recordRoundTripPreservesEveryArtifactAndProcessedType() throws UnresolvableProtobufException {
        var signature = new ContractSignatureData(new byte[20],
                new byte[70],
                myIdentity.getKeyBundle().getKeyPair().getPublic());
        var data = MuSigV2TradeExecutionData.builder()
                .myPubKeyShares(Optional.of(PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance())))
                .myContractSignature(Optional.of(signature))
                .peerContractSignature(Optional.of(new ContractSignatureData(new byte[20],
                        new byte[71],
                        contract.getMaker().getNetworkId().getPubKey().getPublicKey())))
                .peerPubKeyShares(Optional.of(PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance())))
                .peerNonceShares(Optional.of(NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance())))
                .myNonceShares(Optional.of(NonceSharesMessage.fromProto(bisq.trade.protobuf.NonceSharesMessage.getDefaultInstance())))
                .myPartialSignatures(Optional.of(PartialSignaturesMessage.fromProto(bisq.trade.protobuf.PartialSignaturesMessage.getDefaultInstance())))
                .peerRedactedPartialSignatures(Optional.of(PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance())))
                .peerPartialSignatures(Optional.of(PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance())))
                .myDepositPsbt(Optional.of(new DepositPsbt(new byte[]{5})))
                .peerDepositPsbt(Optional.of(new DepositPsbt(new byte[]{6})))
                .peerAccountPayload(Optional.of(new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR"))))
                .mySwapTxSignatureResponse(Optional.of(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8})))
                .peersOutputPrvKeyShare(Optional.of(new ByteArray(new byte[]{9})))
                .myCloseTradeResponse(Optional.of(new CloseTradeResponse(new byte[]{10})))
                .myCustomPayoutPsbt(Optional.of(new CustomPayoutPsbt(new byte[]{11}, TX_ID, 90_000, 60_000)))
                .peerCustomPayoutPsbt(Optional.of(new PeerCustomPayoutPsbt(TX_ID, new byte[]{12})))
                .customCloseTradeResponse(Optional.of(new CustomCloseTradeResponse(new byte[]{13})))
                .myFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.UNKNOWN,
                        "Local failure",
                        "Local stack trace")))
                .peerFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                        "Peer failure",
                        "Peer stack trace")))
                .build();
        var trade = tradeAt(new CustomPayoutClosedTrade())
                .withExecution(FlowSnapshot.of(new CustomPayoutClosedTrade(),
                        data))
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                                new byte[]{14})), Optional.empty())))
                .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()))
                .withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Rejected()))
                .withState(new MuSigTradeState.Historical());
        var record = new MuSigV2TradeRecord(trade,
                Map.of(Flow.EXECUTION, Set.of(DepositTxConfirmedEvent.class, MediationResultByPeerAcceptedEvent.class),
                        Flow.MEDIATION, Set.of(MediationOpenedEvent.class, MediationClosedEvent.class),
                        Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class),
                        Flow.PEER_MEDIATION_DECISION, Set.of(MuSigMediationResultRejectionMessage.class)),
                List.of(),
                List.of(),
                List.of());
        var restoredRecord = MuSigV2TradeRecord.fromProto(record.toProto(false));
        assertEquals(record.toProto(false), restoredRecord.toProto(false));
        var proto = restoredRecord.trade().toProto(false);
        var restored = restoredRecord.trade();
        assertEquals(proto, restored.toProto(false));
        assertEquals(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTCLOSEDTRADE,
                proto.getExecution().getState().getMessageCase());
        assertEquals(List.of(bisq.trade.protobuf.MuSigV2EventType.DEPOSIT_CONFIRMED,
                        bisq.trade.protobuf.MuSigV2EventType.MEDIATION_RESULT_BY_PEER_ACCEPTED),
                record.toProto(false).getProcessedEventTypesOrThrow(Flow.EXECUTION.name()).getTypesList());
        assertEquals(List.of(bisq.trade.protobuf.MuSigV2EventType.MEDIATION_OPENED,
                        bisq.trade.protobuf.MuSigV2EventType.MEDIATION_CLOSED),
                record.toProto(false).getProcessedEventTypesOrThrow(Flow.MEDIATION.name()).getTypesList());
        assertEquals(contract.toProto(false), proto.getData().getContract());
        assertEquals(myIdentity.toProto(false), proto.getData().getMyIdentity());
        assertEquals(myAccountPayload.toProto(false), proto.getData().getMyAccountPayload());
        assertEquals(new MuSigTradeState.Historical(), restored.getState());
        assertEquals(2, restoredRecord.processedEventTypes().get(Flow.EXECUTION).size());
        assertEquals(Set.of(MediationOpenedEvent.class, MediationClosedEvent.class),
                restoredRecord.processedEventTypes().get(Flow.MEDIATION));
        assertEquals(signature.toProto(false),
                restored.getExecution().data().getMyContractSignature().orElseThrow().toProto(false));
        assertEquals(data.getMyFailure(), restored.getExecution().data().getMyFailure());
        assertEquals(data.getPeerFailure(), restored.getExecution().data().getPeerFailure());
        assertArrayEquals(new byte[]{14},
                restored.getMediation().orElseThrow().data().signedResult().orElseThrow().signature());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void failureReportsAndTheirAbsenceSurviveRestart(boolean hasMyFailure, boolean hasPeerFailure) throws Exception {
        var builder = MuSigV2TradeExecutionData.builder();
        if (hasMyFailure) {
            builder.myFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                    "Local failure",
                    "Local stack trace")));
        }
        if (hasPeerFailure) {
            builder.peerFailure(Optional.of(new MuSigV2FailureDetails(TradeProtocolFailure.PRICE_DEVIATION,
                    "Peer failure",
                    "Peer stack trace")));
        }
        var data = builder.build();
        SellerAsTakerState state = new Init();
        if (hasMyFailure) {
            state = new Failed();
        } else if (hasPeerFailure) {
            state = new FailedAtPeer();
        }
        var trade = tradeAt(state).withExecution(FlowSnapshot.of(state, data));
        seed(MuSigV2TradeRecord.initial(trade));

        var service = startAndAwaitRecovery(effect -> fail("Restoring failure details must not produce effects"));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(state, restored.getExecution().state());
            assertEquals(data.getMyFailure(), restored.getExecution().data().getMyFailure());
            assertEquals(data.getPeerFailure(), restored.getExecution().data().getPeerFailure());
            assertEquals(trade.toProto(false), saved(service).trade().toProto(false));
        } finally {
            service.shutdown().join();
        }
        verifyNoInteractions(backend, asyncBackend);
    }

    @Test
    void everySellerStateSurvivesTradeSerialization() throws Exception {
        List<SellerAsTakerState> states = List.of(
                new Init(),
                new TradeInitialized(),
                new NonceSharesAndPartialSignaturesCreated(),
                new DepositTxSignedAndPublished(),
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
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.class);
        for (SellerAsTakerState state : states) {
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(tradeAt(state).serialize());
            coveredCases.add(proto.getExecution().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(state, restored.getExecution().state());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.MESSAGE_NOT_SET,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PAYMENTINITIATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADEINITIALIZEDANDNONCESHARESCREATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PARTIALSIGNATURESCREATEDANDDEPOSITTXSIGNED)), coveredCases);
    }

    @Test
    void everyMediationStateSurvivesTradeSerialization() throws Exception {
        List<MuSigMediationState> states = List.of(new MuSigMediationState.Init(),
                new MuSigMediationState.Requested(),
                new MuSigMediationState.Open(),
                new MuSigMediationState.Closed(),
                new MuSigMediationState.Failed());
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigMediationState.MessageCase.class);
        for (MuSigMediationState state : states) {
            var trade = tradeAt(new DepositTxConfirmed())
                    .withMediation(FlowSnapshot.of(state, MuSigV2MediationData.initial()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            coveredCases.add(proto.getMediation().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(state, restored.getMediation().orElseThrow().state());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigMediationState.MessageCase.MESSAGE_NOT_SET)),
                coveredCases);
    }

    @Test
    void everyBuyerStateAndThePeerSwapSignatureSurviveSerialization() throws Exception {
        List<BuyerAsTakerState> states = List.of(new Init(),
                new TradeInitialized(),
                new NonceSharesAndPartialSignaturesCreated(),
                new DepositTxSignedAndPublished(),
                new AccountPayloadReceived(),
                new DepositTxConfirmed(),
                new PaymentInitiated(),
                new CustomPayoutSigned(),
                new CustomPayoutClosedTrade(),
                new TradeClosed(),
                new Failed(),
                new FailedAtPeer());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var coveredCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.class);
        for (var state : states) {
            var trade = buyerTradeAt(state);
            trade = trade.withExecution(FlowSnapshot.of(state, trade.getExecution().data().toBuilder()
                    .peerSwapTxSignature(Optional.of(signature)).build()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            coveredCases.add(proto.getExecution().getState().getMessageCase());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(TradeRole.BUYER_AS_TAKER, restored.getMyRole());
            assertEquals(state, restored.getExecution().state());
            assertEquals(signature, restored.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            assertEquals(proto, restored.toProto(false));
        }
        assertEquals(EnumSet.of(
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.INIT,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADEINITIALIZED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.NONCESHARESANDPARTIALSIGNATURESCREATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.DEPOSITTXSIGNEDANDPUBLISHED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.ACCOUNTPAYLOADRECEIVED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.DEPOSITTXCONFIRMED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.PAYMENTINITIATED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTSIGNED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.CUSTOMPAYOUTCLOSEDTRADE,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.TRADECLOSED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.FAILED,
                bisq.trade.protobuf.MuSigTradeExecutionState.MessageCase.FAILEDATPEER), coveredCases);
    }

    @Test
    void persistedStateMustBelongToThePersistedRole() {
        var seller = tradeAt(new PaymentReceiptConfirmed()).toProto(false);
        var buyer = buyerTradeAt(new PaymentInitiated()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(seller.toBuilder()
                .setData(seller.getData().toBuilder().setMyRole(TradeRole.BUYER_AS_TAKER.toProtoEnum())).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(buyer.toBuilder()
                .setData(buyer.getData().toBuilder().setMyRole(TradeRole.SELLER_AS_TAKER.toProtoEnum())).build()));
    }

    @Test
    void bothTakerRolesRestoreFromTheSameStore() throws Exception {
        var seller = tradeAt(new DepositTxConfirmed());
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        seed(MuSigV2TradeRecord.initial(seller),
                MuSigV2TradeRecord.initial(buyer));
        var service = startAndAwaitRecovery(effect -> fail("No send is expected"));
        try {


        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> fail("No send is expected"));
        try {
            assertEquals(seller.toProto(false), savedRecord(restoredService, seller.getId()).trade().toProto(false));
            assertEquals(buyer.toProto(false), savedRecord(restoredService, buyer.getId()).trade().toProto(false));
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void buyerRetainsEarlyConfirmationAcrossRestartAndClosesAfterANewPaymentCommand() throws Exception {
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                buyer.getId(),
                "1.0.0",
                buyer.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(new MuSigV2TradeRecord(buyer,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, confirmation)),
                List.of(),
                List.of()));
        when(backend.getPartialSignatures(any())).thenReturn(bisq.trade.protobuf.PartialSignaturesMessage.newBuilder()
                .setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{6})).build());
        var effects = new ArrayList<Effect>();
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertInstanceOf(DepositTxConfirmed.class,
                    await(whenExecutionState(service, buyer.getId(), DepositTxConfirmed.class)));
            verify(backend, never()).closeTrade(any());
            await(service.paymentInitiated(buyer.getId()));
            var closed = savedRecord(service, buyer.getId()).trade();
            assertInstanceOf(TradeClosed.class, closed.getExecution().state());
            assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            assertInstanceOf(PaymentInitiatedMessage_E.class,
                    ((Effect.SendMessage<?, ?>) effects.getFirst()).message());
            assertInstanceOf(CooperativeClosureMessage_G.class,
                    ((Effect.SendMessage<?, ?>) effects.getLast()).message());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertTrue(saved(service).scheduledEvents().isEmpty());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> fail("No completed send should replay"));
        try {
            var closed = savedRecord(restoredService, buyer.getId()).trade();
            assertInstanceOf(TradeClosed.class, closed.getExecution().state());
            assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            await(restoredService.paymentInitiated(buyer.getId()));
            assertSame(closed, savedRecord(restoredService, buyer.getId()).trade());
            await(submitMessage(restoredService, confirmation));
            assertSame(closed, savedRecord(restoredService, confirmation.getTradeId()).trade());
        } finally {
            restoredService.shutdown().join();
        }
        verify(backend, times(1)).getPartialSignatures(any());
        verify(backend, times(1)).closeTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void buyerMediatedSettlementSurvivesRestartInEitherAcceptanceOrder(boolean peerFirst) throws Exception {
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        var buyerResult = new MuSigMediationResult(ContractService.getContractHash(buyer.getContract()),
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(90_000L),
                Optional.of(60_000L),
                Optional.empty(),
                Optional.empty());
        buyer = buyer.withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(buyerResult,
                        MuSigMediationResultService.signMediationResult(buyerResult, mediatorKeys))), Optional.empty())));
        var peerAcceptance = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                buyer.getId(),
                "1.0.0",
                buyer.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                MuSigMediationResultService.getMediationResultHash(buyerResult),
                TX_ID,
                new byte[]{7});
        when(backend.signCustomPayoutTx(any())).thenReturn(
                new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000).toProto(false));
        var effects = new ArrayList<Effect>();

        seed(new MuSigV2TradeRecord(buyer,
                Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class, MediationClosedEvent.class)),
                List.of(),
                List.of(),
                List.of()));
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {

            if (peerFirst) {
                await(submitMessage(service, peerAcceptance));
                var waiting = savedRecord(service, peerAcceptance.getTradeId()).trade();
                assertInstanceOf(DepositTxConfirmed.class, waiting.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Init(),
                        waiting.getMediation().orElseThrow().myDecision().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        waiting.getMediation().orElseThrow().peerDecision().state());
                assertEquals(1, saved(service).pendingInputs().size());
                assertInstanceOf(MediationResultByPeerAcceptedEvent.class,
                        saved(service).pendingInputs().getFirst().event());
                verify(backend, never()).signCustomPayoutTx(any());
            } else {
                await(service.acceptMediationResult(buyer.getId()));
                var signed = savedRecord(service, buyer.getId()).trade();
                assertInstanceOf(CustomPayoutSigned.class, signed.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        signed.getMediation().orElseThrow().myDecision().state());
                assertEquals(new MuSigMediationDecisionState.Init(),
                        signed.getMediation().orElseThrow().peerDecision().state());
                assertTrue(saved(service).pendingInputs().isEmpty());
            }
            assertTrue(saved(service).scheduledEvents().isEmpty());
            verify(backend, never()).customCloseTrade(any());
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(restoredService, buyer.getId()).trade();
            if (peerFirst) {
                assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
                assertEquals(new MuSigMediationDecisionState.Accepted(),
                        restored.getMediation().orElseThrow().peerDecision().state());
                assertEquals(1, saved(restoredService).pendingInputs().size());
                await(restoredService.acceptMediationResult(buyer.getId()));
            } else {
                assertInstanceOf(CustomPayoutSigned.class, restored.getExecution().state());
                assertEquals(TX_ID, restored.getExecution().data().getMyCustomPayoutPsbt().orElseThrow().getTxId());
                await(submitMessage(restoredService, peerAcceptance));
            }
            var closed = savedRecord(restoredService, buyer.getId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    closed.getMediation().orElseThrow().myDecision().state());
            assertEquals(new MuSigMediationDecisionState.Accepted(),
                    closed.getMediation().orElseThrow().peerDecision().state());
            assertArrayEquals(new byte[]{7}, closed.getExecution().data().getPeerCustomPayoutPsbt()
                    .orElseThrow().getPsbt());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            assertTrue(saved(restoredService).scheduledEvents().isEmpty());
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("No completed send should replay"));
        try {
            var closed = savedRecord(restartedService, buyer.getId()).trade();
            assertInstanceOf(CustomPayoutClosedTrade.class, closed.getExecution().state());
            await(submitMessage(restartedService, peerAcceptance));
            assertSame(closed, savedRecord(restartedService, peerAcceptance.getTradeId()).trade());
            await(restartedService.acceptMediationResult(buyer.getId()));
            assertSame(closed, savedRecord(restartedService, buyer.getId()).trade());
        } finally {
            restartedService.shutdown().join();
        }
        assertTrue(effects.stream().noneMatch(ScheduledEvent.class::isInstance));
        assertEquals(1, effects.stream().filter(Effect.SendMessage.class::isInstance).count());
        verify(backend, times(1)).signCustomPayoutTx(any());
        verify(backend, times(1)).customCloseTrade(any());
        verify(backend, never()).closeTrade(any());
    }

    @Test
    void tradeStateSurvivesFilePersistenceAndServiceRestart() throws Exception {
        for (var tradeState : List.<MuSigTradeState>of(new MuSigTradeState.Active(),
                new MuSigTradeState.Failed(),
                new MuSigTradeState.Historical())) {
            var trade = tradeAt(new SellerForceClosedTrade()).withState(tradeState);
            seed(new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(), List.of()));

            var service = startAndAwaitRecovery(effect -> {});
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertEquals(tradeState, restored.getState());
                assertEquals(trade.toProto(false), restored.toProto(false));
                assertEquals(tradeState, saved(service).trade().getState());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void archivingRejectsAnUnfinishedTradeWithoutChangingItsDocument() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        seed(MuSigV2TradeRecord.initial(trade));

        var service = startAndAwaitRecovery(effect -> fail("No effects expected"));
        try {
            var before = savedRecord(service, tradeId).trade();
            var failure = assertThrows(ExecutionException.class, () -> await(service.archiveTrade(tradeId)));

            assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                    assertInstanceOf(MuSigProtocolException.class, failure.getCause()).getCategory());
            assertSame(before, savedRecord(service, tradeId).trade());
            assertEquals(MuSigV2TradeRecord.initial(trade).toProto(false), saved(service).toProto(false));
            verifyNoInteractions(backend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void archivingPreservesChildrenAndDiscardsPendingAndLateBusinessInputsAcrossRestart() throws Exception {
        var trade = tradeAt(new TradeClosed());
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
                Optional.of(result),
                Optional.of(MuSigMediationResultService.signMediationResult(result, mediatorKeys)));
        var peer = peerDecision(true, MuSigMediationResultService.getMediationResultHash(result));
        seed(MuSigV2TradeRecord.initial(trade));
        bisq.trade.protobuf.MuSigV2Trade archived;

        var service = startAndAwaitRecovery(effect -> fail("Archiving must not produce effects"));
        try {
            var before = savedRecord(service, tradeId).trade();
            assertEquals(new MuSigTradeState.Active(), before.getState());
            await(submitMessage(service, closed));
            await(submitMessage(service, peer));
            assertEquals(2, saved(service).pendingInputs().size());

            await(service.archiveTrade(tradeId));
            var historical = savedRecord(service, tradeId).trade();

            assertEquals(new MuSigTradeState.Historical(), historical.getState());
            assertInstanceOf(MuSigTradeState.Historical.class, historical.state());
            assertEquals(Set.of(TradeArchivedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertSame(before.getExecution(), historical.getExecution());
            assertSame(before.getMediation(), historical.getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            archived = historical.toProto(false);
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("Historical children must stay stopped"));
        try {
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(archived, restored.toProto(false));
            await(submitMessage(restoredService, opened));
            assertSame(restored, savedRecord(restoredService, opened.getTradeId()).trade());
            await(submitMessage(restoredService, closed));
            assertSame(restored, savedRecord(restoredService, closed.getTradeId()).trade());
            await(submitMessage(restoredService, peer));
            assertSame(restored, savedRecord(restoredService, peer.getTradeId()).trade());
            await(restoredService.onDepositTxConfirmed(tradeId));
            assertSame(restored, savedRecord(restoredService, tradeId).trade());
            for (var command : List.of(restoredService.archiveTrade(tradeId),
                    restoredService.requestMediation(tradeId),
                    restoredService.acceptMediationResult(tradeId),
                    restoredService.rejectMediationResult(tradeId),
                    restoredService.paymentReceiptConfirmed(tradeId))) {
                var failure = assertThrows(ExecutionException.class, () -> await(command));
                assertEquals(MuSigFailureCategory.INPUT_REJECTED,
                        assertInstanceOf(MuSigProtocolException.class, failure.getCause()).getCategory());
            }
            assertSame(restored, savedRecord(restoredService, tradeId).trade());
            assertEquals(archived, saved(restoredService).trade().toProto(false));
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void executionAndParentFailureCommitSeparatelyAndTheFailedParentCanBeArchived() throws UnresolvableProtobufException {
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        var records = new ArrayList<MuSigV2TradeRecord>();
        var effects = new ArrayList<Effect>();
        var failure = new IllegalStateException("Signing unavailable");
        when(backend.signSwapTx(any())).thenThrow(failure);
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var published = new AtomicReference<>(initialCheckpoint.snapshot());
        records.add(MuSigV2TradeRecord.fromCheckpoint(initialCheckpoint));
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                checkpoint -> records.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint)),
                (current, effect) -> {
                    // The execution failure and its parent notification are saved before sending the report.
                    var saved = records.getLast();
                    assertSame(current.document(), saved.trade());
                    assertInstanceOf(MuSigTradeState.Active.class, saved.trade().state());
                    assertInstanceOf(Failed.class, saved.trade().getExecution().state());
                    assertEquals(List.of(new Delivery<>(Flow.TRADE, new ExecutionFailedEvent())), saved.pendingInputs());
                    assertEquals(effect, saved.pendingEffects().getFirst());
                    assertInstanceOf(MuSigReportErrorMessage.class, ((Effect.SendMessage<?, ?>) effect).message());
                    effects.add(effect);
                },
                ignored -> {},
                (current, persistenceFailure, setupFailure) -> published.set(current));
        try {
            lane.resume().join();
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            var failedResult = assertInstanceOf(ProcessingResult.Failed.class, completed.processingResult());
            var reported = assertInstanceOf(MuSigProtocolException.class, failedResult.cause());

            assertSame(failure, reported.getCause());
            assertSame(published.get(), failedResult.snapshot());
            var failed = published.get().document();
            assertInstanceOf(MuSigTradeState.Failed.class, failed.state());
            assertEquals(Set.of(ExecutionFailedEvent.class), records.getLast().processedEventTypes().get(Flow.TRADE));
            assertSame(trade.getMediation(), failed.getMediation());
            var executionFailure = records.stream()
                    .filter(record -> record.trade().getExecution().state() instanceof Failed)
                    .findFirst().orElseThrow();
            assertInstanceOf(MuSigTradeState.Active.class, executionFailure.trade().state());
            assertEquals(List.of(new Delivery<>(Flow.TRADE, new ExecutionFailedEvent())), executionFailure.pendingInputs());
            assertSame(executionFailure.trade().getExecution(), failed.getExecution());
            assertSame(failed, records.getLast().trade());
            assertTrue(records.getLast().pendingInputs().isEmpty());
            assertTrue(records.getLast().pendingEffects().isEmpty());
            assertEquals(1, effects.size());

            var archived = assertInstanceOf(SubmissionResult.Completed.class, lane.submit(new TradeArchivedEvent()).join());
            var applied = assertInstanceOf(ProcessingResult.Applied.class, archived.processingResult());

            assertSame(published.get(), applied.snapshot());
            var historical = published.get().document();
            assertInstanceOf(MuSigTradeState.Historical.class, historical.state());
            assertSame(failed.getExecution(), historical.getExecution());
            assertSame(failed.getMediation(), historical.getMediation());
            assertSame(historical, records.getLast().trade());
            assertEquals(Set.of(ExecutionFailedEvent.class, TradeArchivedEvent.class),
                    records.getLast().processedEventTypes().get(Flow.TRADE));
            assertEquals(historical.toProto(false), MuSigV2Trade.fromProto(historical.toProto(false)).toProto(false));
            assertEquals(1, effects.size());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            lane.shutdown().join();
        }
    }

    @Test
    void aSavedExecutionFailureNotifiesTheParentAfterRestart() throws Exception {
        var trade = tradeAt(new PaymentInitiatedMessageReceived());
        var records = new ArrayList<MuSigV2TradeRecord>();
        when(backend.signSwapTx(any())).thenThrow(new IllegalStateException("Signing unavailable"));
        Clock clock = contract::getTakeOfferDate;
        var processor = MuSigTradeLaneFixture.createProcessor(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                clock);
        var initialCheckpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>(
                new StateMachineSnapshot<>(trade, Map.of()),
                List.of(),
                List.of(),
                List.of());
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        ignored -> processor)
                .build();
        var lane = new ProcessingLane<>(trade.getId(),
                Optional.of(initialCheckpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                checkpoint -> records.add(MuSigV2TradeRecord.fromCheckpoint(checkpoint)),
                (current, effect) -> {},
                ignored -> {},
                (current, persistenceFailure, setupFailure) -> {});
        try {
            lane.resume().join();
            var completed = assertInstanceOf(SubmissionResult.Completed.class,
                    lane.submit(new PaymentReceiptConfirmedEvent()).join());
            assertInstanceOf(ProcessingResult.Failed.class, completed.processingResult());
        } finally {
            lane.shutdown().join();
        }

        // Simulate a restart from the checkpoint before sending the report and notifying the parent.
        var beforeParentReaction = records.stream()
                .filter(record -> record.trade().getExecution().state() instanceof Failed &&
                        record.trade().state() instanceof MuSigTradeState.Active)
                .findFirst().orElseThrow();
        assertEquals(List.of(new Delivery<>(Flow.TRADE, new ExecutionFailedEvent())), beforeParentReaction.pendingInputs());
        assertTrue(beforeParentReaction.processedEventTypes().getOrDefault(Flow.TRADE, Set.of()).isEmpty());
        assertEquals(2, beforeParentReaction.pendingEffects().size());
        assertEquals(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID), beforeParentReaction.pendingEffects().getFirst());
        var errorReport = assertInstanceOf(Effect.SendMessage.class, beforeParentReaction.pendingEffects().getLast());
        assertInstanceOf(MuSigReportErrorMessage.class, errorReport.message());
        seed(beforeParentReaction);
        var effects = new ArrayList<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(new MuSigTradeState.Failed(), restored.getState());
            assertEquals(Set.of(ExecutionFailedEvent.class),
                    saved(service).processedEventTypes().getOrDefault(Flow.TRADE,
                            Set.of()));
            assertEquals(beforeParentReaction.trade().toProto(false).getExecution(),
                    restored.toProto(false).getExecution());
            assertEquals(beforeParentReaction.trade().toProto(false).getMediation(),
                    restored.toProto(false).getMediation());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertTrue(saved(service).pendingEffects().isEmpty());
            assertEquals(List.of(errorReport), effects);
            await(service.archiveTrade(tradeId));
            assertEquals(new MuSigTradeState.Historical(), savedRecord(service, tradeId).trade().getState());
            assertEquals(List.of(errorReport), effects);
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void archivingDoesNotLoseAnOutgoingMessageWhenSendingStillFails() throws Exception {
        var trade = buyerTradeAt(new TradeClosed());
        var message = new CooperativeClosureMessage_G(StringUtils.createUid(),
                trade.getId(),
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                new ByteArray(new byte[]{8}));
        var outgoing = new Effect.SendMessage<>(message, contract.getMaker().getNetworkId());
        var record = new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(), List.of(outgoing));
        seed(record);

        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        var attempts = new ConcurrentLinkedQueue<Effect>();
        var sendFailure = new IllegalStateException("Transport unavailable");
        mockNetworkSend(effect -> {
            attempts.add(effect);
            throw sendFailure;
        });
        bisq.trade.protobuf.MuSigV2Trade archived;
        var service = serviceWithClock(services, grpcClient, contract::getTakeOfferDate);
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertTrue(await(service.initialize()));
            assertSame(sendFailure,
                    assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture())).getCause());
            assertEquals(List.of(outgoing), List.copyOf(attempts));
            var before = savedRecord(service, trade.getId()).trade();

            assertSame(sendFailure,
                    assertThrows(ExecutionException.class, () -> await(service.archiveTrade(trade.getId()))).getCause());

            var historical = savedRecord(service, trade.getId()).trade();
            assertEquals(new MuSigTradeState.Historical(), historical.getState());
            assertSame(before.getExecution(), historical.getExecution());
            assertSame(before.getMediation(), historical.getMediation());
            assertEquals(historical.getState(),
                    service.getTradeReadModel(trade.getId()).stateObservable().get());
            var saved = saved(service);
            archived = historical.toProto(false);
            assertEquals(archived, saved.trade().toProto(false));
            assertEquals(List.of(outgoing), saved.pendingEffects());
            assertTrue(saved.pendingInputs().isEmpty());
            assertEquals(Set.of(TradeArchivedEvent.class), saved.processedEventTypes().get(Flow.TRADE));
            assertEquals(List.of(outgoing, outgoing), List.copyOf(attempts));
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }

        var effects = new ConcurrentLinkedQueue<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertEquals(archived, savedRecord(restoredService, trade.getId()).trade().toProto(false));
            assertEquals(1, effects.size());
            var sent = assertInstanceOf(Effect.SendMessage.class, effects.element());
            assertEquals(message.toProto(false), ((CooperativeClosureMessage_G) sent.message()).toProto(false));
            assertTrue(saved(restoredService).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }

        var restartedService = startAndAwaitRecovery(effect -> fail("Completed sends must not replay"));
        try {
            assertEquals(archived, savedRecord(restartedService, trade.getId()).trade().toProto(false));
            assertTrue(saved(restartedService).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stoppedParentSkipsChildRecoveryButRetriesCommittedOutgoingMessages(boolean historical) throws Exception {
        var tradeState = historical ? new MuSigTradeState.Historical() : new MuSigTradeState.Failed();
        var trade = tradeAt(new AccountPayloadReceived()).withState(tradeState);
        var report = new MuSigReportErrorMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                "Saved failure",
                "Saved stack trace",
                TradeProtocolFailure.UNKNOWN);
        var outgoing = new Effect.SendMessage<>(report, contract.getMaker().getNetworkId());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, new DepositTxConfirmedEvent())),
                List.of(),
                List.of(outgoing, new ObserveDepositTxConfirmation())));

        for (int restart = 0; restart < 2; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
                assertTrue(saved(service).pendingInputs().isEmpty());
                assertTrue(saved(service).pendingEffects().isEmpty());
                assertEquals(restart == 0 ? 1 : 0, effects.size());
                if (restart == 0) {
                    var sent = assertInstanceOf(Effect.SendMessage.class, effects.element());
                    assertEquals(report.toProto(false), ((MuSigReportErrorMessage) sent.message()).toProto(false));
                }
                verifyNoInteractions(backend, asyncBackend);
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void everyProtocolAndPersistedEventTypeRoundTripsWithItsPayloadAndProcessedType() throws Exception {
        List<Event> events = persistableEvents();
        Set<Class<?>> eventClasses = events.stream().map(Object::getClass).collect(Collectors.toSet());
        Set<Class<?>> declaredEventClasses = Stream.of(
                        MuSigTradeLaneFixture.tradeRules(),
                        BuyerAsMakerProtocol.transitions(),
                        BuyerAsTakerProtocol.transitions(),
                        SellerAsMakerProtocol.transitions(),
                        SellerAsTakerProtocol.transitions(),
                        MuSigMediationProtocol.transitions(),
                        MuSigMyMediationDecisionProtocol.transitions(),
                        MuSigPeerMediationDecisionProtocol.transitions())
                .flatMap(table -> table.getRules().stream())
                .map(Rule::on)
                .collect(Collectors.toSet());
        for (Class<?> eventClass : declaredEventClasses) {
            var eventType = MuSigV2EventType.fromEventClass(eventClass);
            assertEquals(eventClass, MuSigV2EventType.fromProto(eventType.toProtoEnum()).getEventClass());
            assertTrue(eventClasses.contains(eventClass),
                    () -> "Missing round-trip fixture for " + eventClass.getName());
        }

        var trade = tradeAt(new DepositTxConfirmed());
        var inputs = events.stream().map(event -> new Delivery<>(MuSigProtocol.Flow.EXECUTION, event)).toList();
        var record = new MuSigV2TradeRecord(trade, Map.of(Flow.EXECUTION, eventClasses), inputs, List.of(), List.of());
        var checkpoint = record.toCheckpoint();
        assertSame(trade, checkpoint.snapshot().document());
        assertEquals(record.processedEventTypes(), checkpoint.snapshot().processedEventTypes());
        var proto = record.toProto(false);
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(checkpoint).toProto(false));
        var restoredRecord = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        var restoredCheckpoint = restoredRecord.toCheckpoint();
        var coveredTypes = new HashSet<String>();
        var coveredTradeEventCases = EnumSet.noneOf(bisq.trade.protobuf.MuSigTradeEvent.EventCase.class);
        assertEquals(events.size(), restoredRecord.pendingInputs().size());
        for (int i = 0; i < events.size(); i++) {
            Event event = events.get(i);
            Event restored = restoredRecord.pendingInputs().get(i).event();
            var persistedEvent = proto.getPendingInputs(i).getEvent();
            coveredTypes.add(persistedEvent.getPayload().getTypeUrl());
            if (event instanceof MuSigTradeEvent) {
                assertTrue(persistedEvent.getPayload().is(bisq.trade.protobuf.TradeEvent.class));
                coveredTradeEventCases.add(persistedEvent.getPayload().unpack(bisq.trade.protobuf.TradeEvent.class)
                        .getMuSigTradeEvent().getEventCase());
            }
            assertEquals(MuSigProtocol.Flow.EXECUTION, restoredRecord.pendingInputs().get(i).flow());
            assertEquals(event.getClass(), restored.getClass());
            assertEquals(event.toProto(false), restored.toProto(false));
            assertSame(restoredRecord.pendingInputs().get(i), restoredCheckpoint.pendingWork().get(i).value());
            assertEquals(event instanceof Command, restoredCheckpoint.pendingWork().get(i).command());
        }
        assertEquals(Set.of("type.googleapis.com/trade.TradeEvent",
                "type.googleapis.com/network.EnvelopePayloadMessage"),
                coveredTypes);
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigTradeEvent.EventCase.EVENT_NOT_SET)),
                coveredTradeEventCases);
        assertEquals(EnumSet.complementOf(EnumSet.of(bisq.trade.protobuf.MuSigV2EventType.TYPE_UNSPECIFIED,
                        bisq.trade.protobuf.MuSigV2EventType.UNRECOGNIZED)),
                EnumSet.copyOf(proto.getProcessedEventTypesOrThrow(Flow.EXECUTION.name()).getTypesList()));
        assertEquals(eventClasses, restoredRecord.processedEventTypes().get(Flow.EXECUTION));
        assertEquals(proto, restoredRecord.toProto(false));
        assertEquals(restoredCheckpoint, MuSigV2TradeRecord.fromCheckpoint(restoredCheckpoint).toCheckpoint());
    }

    @Test
    void outgoingMessagesAndScheduledWorkRoundTripWithTheirOriginalData() throws Exception {
        List<Effect> effects = new ArrayList<>();
        for (Event event : persistableEvents()) {
            if (event instanceof MuSigTradeMessage message) {
                effects.add(new Effect.SendMessage<>(message, message.getReceiver()));
            }
        }
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var request = new MuSigMediationRequest(tradeId,
                contract,
                userProfile("taker", myIdentity.getNetworkId()),
                userProfile("maker", contract.getMaker().getNetworkId()),
                List.of(),
                mediator);
        effects.add(new Effect.SendMessage<>(request, mediator));
        var report = new MuSigDisputeCaseDataMessage(tradeId,
                myIdentity.getNetworkId(),
                ContractService.getContractHash(contract),
                List.of());
        effects.add(new Effect.SendMessage<>(report, mediator));
        var response = new MuSigDisputeCasePaymentDetailsResponse(tradeId,
                myIdentity.getNetworkId(),
                myAccountPayload,
                new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR")));
        effects.add(new Effect.SendMessage<>(response, mediator));
        var scheduled = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                Instant.parse("2030-01-01T00:00:00.123456789Z"),
                new SellersCloseTradeTimeoutEvent());
        effects.add(scheduled);
        effects.add(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID));
        effects.add(new ActivateMediationChat());
        effects.add(new ObserveDepositTxConfirmation());
        effects.add(new RemoveTakenOffer());
        var record = new MuSigV2TradeRecord(tradeAt(new DepositTxConfirmed()),
                Map.of(),
                List.of(),
                List.of(scheduled),
                effects);
        var proto = record.toProto(false);
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(record.toCheckpoint()).toProto(false));
        var restored = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        var checkpoint = restored.toCheckpoint();
        assertEquals(restored.pendingEffects(), checkpoint.pendingEffects());
        assertEquals(List.of(scheduled), checkpoint.scheduledInputs());
        assertEquals(effects.size(), restored.pendingEffects().size());
        for (int i = 0; i < effects.size(); i++) {
            assertEquals(effects.get(i).getClass(), restored.pendingEffects().get(i).getClass());
        }
        assertEquals(List.of(scheduled), restored.scheduledEvents());
        assertEquals(proto, restored.toProto(false));
        assertEquals(proto, MuSigV2TradeRecord.fromCheckpoint(checkpoint).toProto(false));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void laneCheckpointRejectsInconsistentCommandClassification(boolean command) {
        Event event = command ? new PaymentReceiptConfirmedEvent() : new DepositTxConfirmedEvent();
        var delivery = new Delivery<>(Flow.EXECUTION, event);
        Checkpoint<StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow>, Event, Delivery<Flow, Event>> checkpoint =
                new Checkpoint<>(new StateMachineSnapshot<>(tradeAt(new DepositTxConfirmed()), Map.of()),
                        List.of(new PendingWork<>(delivery, !command)),
                        List.of(),
                        List.of());

        var failure = assertThrows(IllegalArgumentException.class, () -> MuSigV2TradeRecord.fromCheckpoint(checkpoint));

        assertTrue(failure.getMessage().contains("command classification does not match"));
    }

    @Test
    void incompleteTradesAndUnknownPersistedTypesAreRejected() {
        var proto = tradeAt(new DepositTxConfirmed()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .clearData().build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setExecution(proto.getExecution().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setMediation(proto.getMediation().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearContract()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearMyIdentity()).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .setData(proto.getData().toBuilder().clearMyAccountPayload()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setExecution(proto.getExecution().toBuilder().clearState()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setMediation(proto.getMediation().toBuilder().clearState()).build()));
        var record = MuSigV2TradeRecord.initial(tradeAt(new DepositTxConfirmed())).toProto(false);
        for (var flow : Flow.values()) {
            for (int eventTypeValue : List.of(0, 999)) {
                var types = bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.newBuilder()
                        .addTypesValue(eventTypeValue).build();
                assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(record.toBuilder()
                        .putProcessedEventTypes(flow.name(), types).build()));
            }
        }
        for (String flow : List.of("", "UNKNOWN")) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(record.toBuilder()
                    .putProcessedEventTypes(flow,
                            bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.getDefaultInstance()).build()));
        }
    }

    @Test
    void missingProcessingHistoryIsRejectedInsteadOfResettingDuplicateProtection() {
        var proto = MuSigV2TradeRecord.initial(tradeAt(new DepositTxConfirmed())).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .clearProcessedEventTypes().build()));
        for (var flow : Flow.values()) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                    .removeProcessedEventTypes(flow.name()).build()));
        }
    }

    @Test
    void missingAndUnknownStateVariantsAreRejected() throws Exception {
        var proto = tradeAt(new DepositTxConfirmed()).toProto(false);
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                .clearState().build()));
        byte[] unknownState = UnknownFieldSet.newBuilder()
                .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                .build().toByteArray();
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(proto.toBuilder()
                    .setState(state).build()));
        }
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeExecutionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeExecutionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setExecution(proto.getExecution().toBuilder().setState(state)).build()));
        }
        var buyer = buyerTradeAt(new Init()).toProto(false);
        for (var state : List.of(bisq.trade.protobuf.MuSigTradeExecutionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigTradeExecutionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(buyer.toBuilder()
                        .setExecution(buyer.getExecution().toBuilder().setState(state)).build()));
        }
        for (var state : List.of(bisq.trade.protobuf.MuSigMediationState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigMediationState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class,
                    () -> MuSigV2Trade.fromProto(proto.toBuilder()
                        .setMediation(proto.getMediation().toBuilder().setState(state)).build()));
        }
    }

    @Test
    void incompleteAndUnknownDecisionSnapshotsAreRejected() throws Exception {
        var proto = MuSigV2Mediation.initial().toProto(false);
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder().clearMyDecision().build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder().clearPeerDecision().build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                        .setMyDecision(proto.getMyDecision().toBuilder().clearData()).build()));
        assertThrows(UnresolvableProtobufException.class,
                () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                        .setPeerDecision(proto.getPeerDecision().toBuilder().clearData()).build()));
        byte[] unknownState = UnknownFieldSet.newBuilder()
                .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                .build().toByteArray();
        for (var state : List.of(bisq.trade.protobuf.MuSigMediationDecisionState.getDefaultInstance(),
                bisq.trade.protobuf.MuSigMediationDecisionState.parseFrom(unknownState))) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                    .setMyDecision(proto.getMyDecision().toBuilder().setState(state)).build()));
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Mediation.fromProto(proto.toBuilder()
                    .setPeerDecision(proto.getPeerDecision().toBuilder().setState(state)).build()));
        }

    }

    @Test
    void allPendingInputDestinationsRoundTrip() throws Exception {
        List<Delivery<Flow, Event>> pending = List.of(
                new Delivery<>(Flow.TRADE, new TradeArchivedEvent()),
                new Delivery<>(Flow.EXECUTION, new DepositTxConfirmedEvent()),
                new Delivery<>(Flow.EXECUTION, new DepositConfirmationSkippedEvent()),
                new Delivery<>(Flow.MEDIATION, new MediationRequestedEvent()),
                new Delivery<>(Flow.MY_MEDIATION_DECISION, new MediationResultAcceptedEvent()),
                new Delivery<>(Flow.PEER_MEDIATION_DECISION,
                        peerDecision(true, MuSigMediationResultService.getMediationResultHash(result))));
        assertEquals(EnumSet.allOf(Flow.class), EnumSet.copyOf(pending.stream().map(Delivery::flow).toList()));
        var record = new MuSigV2TradeRecord(tradeAt(new DepositTxConfirmed()), Map.of(), pending, List.of(), List.of());
        var proto = record.toProto(false);
        var restored = MuSigV2TradeRecord.fromProto(bisq.trade.protobuf.MuSigV2TradeRecord.parseFrom(proto.toByteArray()));
        assertEquals(pending.stream().map(Delivery::flow).toList(),
                restored.pendingInputs().stream().map(Delivery::flow).toList());
        assertEquals(proto, restored.toProto(false));
    }

    @Test
    void missingUnknownAndMalformedEventPayloadsAreRejected() {
        assertThrows(UnresolvableProtobufException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.getDefaultInstance()));
        var unknown = Any.newBuilder().setTypeUrl("type.googleapis.com/future.Event").build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(unknown).build()));
        var malformed = Any.newBuilder().setTypeUrl("type.googleapis.com/trade.TradeEvent")
                .setValue(ByteString.copyFrom(new byte[]{(byte) 0xff})).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(malformed).build()));
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder()
                        .setPayload(Any.pack(bisq.trade.protobuf.TradeMessage.getDefaultInstance())).build()));
    }

    @Test
    void missingAndUnknownTradeEventFamiliesAreRejected() throws Exception {
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder()
                        .setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.getDefaultInstance())).build()));
        var futureTradeEvent = bisq.trade.protobuf.TradeEvent.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                        .build())
                .build();
        var envelope = bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(futureTradeEvent)).build();
        var parsed = bisq.common.protobuf.Event.parseFrom(envelope.toByteArray());

        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(parsed));
    }

    @Test
    void missingAndUnknownTradeEventVariantsAreRejected() throws Exception {
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.newBuilder()
                        .setMuSigTradeEvent(bisq.trade.protobuf.MuSigTradeEvent.getDefaultInstance()).build())).build()));
        var futureTradeEvent = bisq.trade.protobuf.MuSigTradeEvent.newBuilder()
                .setUnknownFields(UnknownFieldSet.newBuilder()
                        .addField(100, UnknownFieldSet.Field.newBuilder().addLengthDelimited(ByteString.EMPTY).build())
                        .build())
                .build();
        var envelope = bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(bisq.trade.protobuf.TradeEvent.newBuilder()
                .setMuSigTradeEvent(futureTradeEvent).build())).build();
        var parsed = bisq.common.protobuf.Event.parseFrom(envelope.toByteArray());
        assertTrue(parsed.getPayload().is(bisq.trade.protobuf.TradeEvent.class));
        assertThrows(UnresolvableProtobufMessageException.class, () -> eventFromProto(parsed));
    }

    @Test
    void unknownFlowNamesAndPayloadsInTheWrongCategoryAreRejected() {
        var input = new Delivery<>(MuSigProtocol.Flow.EXECUTION, new DepositTxConfirmedEvent())
                .toProto(false, MuSigProtocol.Flow::name);
        var proto = MuSigV2TradeRecord.initial(tradeAt(new DepositTxConfirmed())).toProto(false);
        for (String flow : List.of("", "UNKNOWN")) {
            assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                    .addPendingInputs(input.toBuilder().setFlow(flow)).build()));
        }
        assertThrows(UnresolvableProtobufException.class, () -> eventFromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(Any.pack(new CancelScheduledEvent("timer").toProto(false))).build()));
        assertThrows(UnresolvableProtobufException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder()
                        .setPayload(Any.pack(new DepositTxConfirmedEvent().toProto(false)))).build()));
        var send = bisq.common.protobuf.SendMessage.newBuilder()
                .setMessage(Any.pack(new DepositTxConfirmedEvent().toProto(false)))
                .setReceiver(Any.pack(myIdentity.getNetworkId().toProto(false))).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder().setPayload(Any.pack(send))).build()));
        var message = persistableEvents().stream().filter(MuSigTradeMessage.class::isInstance).findFirst().orElseThrow();
        var wrongReceiver = send.toBuilder()
                .setMessage(Any.pack(message.toProto(false)))
                .setReceiver(Any.pack(new DepositTxConfirmedEvent().toProto(false))).build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> MuSigV2TradeRecord.fromProto(proto.toBuilder()
                .addPendingEffects(bisq.common.protobuf.Effect.newBuilder().setPayload(Any.pack(wrongReceiver))).build()));
    }

    private static Event eventFromProto(bisq.common.protobuf.Event proto) throws UnresolvableProtobufException {
        return Event.fromProto(proto, StateMachineResolver::eventFromAny);
    }

    private MuSigV2MediationDecision<MuSigMediationDecisionState> decisionSnapshot(
            MuSigMediationDecisionState state) {
        return new MuSigV2MediationDecision<>(state, MuSigV2MediationDecisionData.initial());
    }

    private MuSigTradeMessage peerDecision(boolean accepted, byte[] resultHash) {
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

    private MuSigV2Trade<SellerAsTakerState> signedCustomPayoutTrade() {
        return tradeAt(new CustomPayoutSigned()).withExecution(FlowSnapshot.of(new CustomPayoutSigned(),
                MuSigV2TradeExecutionData.builder().myCustomPayoutPsbt(Optional.of(
                        new CustomPayoutPsbt(new byte[]{6}, TX_ID, 90_000, 60_000))).build()))
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                        new MuSigV2MediationData(Optional.of(new MuSigV2MediationData.SignedResult(result,
                                new byte[]{1, 2})), Optional.empty())))
                .withMyMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()))
                .withPeerMediationDecision(decisionSnapshot(new MuSigMediationDecisionState.Accepted()));
    }

    private MuSigV2Trade<SellerAsTakerState> tradeWithMediationResult() throws GeneralSecurityException {
        return tradeAt(new DepositTxConfirmed()).withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(),
                MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                        MuSigMediationResultService.signMediationResult(result, mediatorKeys)))));
    }

    private MuSigV2Trade<SellerAsTakerState> tradeWithPaymentDetails(MuSigMediationState state) {
        var trade = tradeAt(new DepositTxConfirmed());
        var execution = trade.getExecution();
        var peerPayload = new RevolutAccountPayload(StringUtils.createUid(), "peer-user", List.of("EUR"));
        return trade.withExecution(execution.to(execution.state(),
                        execution.data().toBuilder().peerAccountPayload(Optional.of(peerPayload)).build()))
                .withMediation(FlowSnapshot.of(state, MuSigV2MediationData.initial()));
    }

    private MuSigV2TradeRecord pendingCustomSettlement(String peerTxId) {
        var peer = new PeerCustomPayoutPsbt(peerTxId, new byte[]{7});
        return new MuSigV2TradeRecord(signedCustomPayoutTrade(),
                Map.of(Flow.MY_MEDIATION_DECISION, Set.of(MediationResultAcceptedEvent.class), Flow.PEER_MEDIATION_DECISION, Set.of(MuSigCustomPayoutPsbtMessage.class)),
                List.of(new Delivery<>(MuSigProtocol.Flow.EXECUTION, new MediationResultByPeerAcceptedEvent(peer))),
                List.of(),
                List.of());
    }

    private MuSigV2Trade<SellerAsTakerState> tradeAt(SellerAsTakerState state) {
        return MuSigV2TradeTestFactory.tradeAt(contract,
                myIdentity,
                TradeRole.SELLER_AS_TAKER,
                "1.0.0",
                myAccountPayload,
                state);
    }

    private MuSigV2Trade<BuyerAsTakerState> buyerTradeAt(BuyerAsTakerState state) {
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

    private List<Event> persistableEvents() {
        NetworkId sender = myIdentity.getNetworkId();
        NetworkId receiver = contract.getMaker().getNetworkId();
        String version = "1.0.0";
        String id = StringUtils.createUid();
        var signature = new ContractSignatureData(new byte[20],
                new byte[70],
                myIdentity.getKeyBundle().getKeyPair().getPublic());
        var pubKeys = assertDoesNotThrow(() -> PubKeyShares.fromProto(bisq.trade.protobuf.PubKeyShares.getDefaultInstance()));
        var nonces = assertDoesNotThrow(() -> NonceShares.fromProto(bisq.trade.protobuf.NonceShares.getDefaultInstance()));
        var partialSignatures = assertDoesNotThrow(() -> PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance()));
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        byte[] resultHash = MuSigMediationResultService.getMediationResultHash(result);
        return List.of(
                new MuSigTakeOfferEvent(),
                new DepositTxConfirmedEvent(),
                new DepositConfirmationSkippedEvent(),
                new PaymentReceiptConfirmedEvent(),
                new PaymentInitiatedEvent(),
                new SellersCloseTradeTimeoutEvent(),
                new BuyersCloseTradeTimeoutEvent(),
                new MediationResultAcceptedEvent(),
                new MediationResultRejectedEvent(),
                new MediationRequestedEvent(),
                new TradeArchivedEvent(),
                new ExecutionFailedEvent(),
                new MuSigProcessingFailedEvent(TradeProtocolFailure.UNKNOWN, "Local failure", "Local stack trace"),
                new MuSigReportErrorMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.UNKNOWN),
                new MediationOpenedEvent(new MuSigMediationStateChangeMessage(id,
                        tradeId,
                        mediator,
                        MediationCaseState.OPEN,
                        Optional.empty(),
                        Optional.empty())),
                new MediationClosedEvent(new MuSigMediationStateChangeMessage(id,
                        tradeId,
                        mediator,
                        MediationCaseState.CLOSED,
                        Optional.of(result),
                        Optional.of(new byte[70]))),
                new MediationPaymentDetailsRequestedEvent(new MuSigDisputeCasePaymentDetailsRequest(tradeId, mediator)),
                new MediationResultByMyselfAcceptedEvent(result),
                new MediationResultByPeerAcceptedEvent(new PeerCustomPayoutPsbt(TX_ID, new byte[]{1, 2})),
                new SetupTradeMessage_A(id, tradeId, version, sender, receiver, contract, signature, pubKeys),
                new SetupTradeMessage_B(id, tradeId, version, sender, receiver, contract, signature, pubKeys, nonces),
                new SetupTradeMessage_C(id, tradeId, version, sender, receiver, nonces, partialSignatures),
                new SetupTradeMessage_D(id, tradeId, version, sender, receiver, partialSignatures, new byte[]{3}),
                new SendAccountPayloadAndDepositTxMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        new ByteArray(new byte[]{4}),
                        myAccountPayload),
                new SendAccountPayloadMessage(id, tradeId, version, sender, receiver, myAccountPayload),
                new PaymentInitiatedMessage_E(id, tradeId, version, sender, receiver, new byte[]{5}),
                new PaymentReceivedMessage_F(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{6}, new byte[]{7}))),
                new CooperativeClosureMessage_G(id, tradeId, version, sender, receiver, new ByteArray(new byte[]{8})),
                new MuSigCustomPayoutPsbtMessage(id,
                        tradeId,
                        version,
                        sender,
                        receiver,
                        resultHash,
                        TX_ID,
                        new byte[]{9}),
                new MuSigMediationResultRejectionMessage(id, tradeId, version, sender, receiver, resultHash));
    }

    private static UserProfile userProfile(String name, NetworkId networkId) {
        var proofOfWork = new ProofOfWork(networkId.getPubKey().getHash(), 0, null, 1.0, new byte[72], 0);
        return new UserProfile(1, name, proofOfWork, 0, networkId, "", "", "1.0.0");
    }

    private void mockNetworkSend(Consumer<Effect> onSend) {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            onSend.accept(new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
    }

    private MuSigTradeService startAndAwaitRecovery() throws Exception {
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        return initializeAndAwaitRecovery(
                serviceWithClock(services, grpcClient, contract::getTakeOfferDate),
                persistenceService);
    }

    private MuSigTradeService startAndAwaitRecovery(Consumer<Effect> effects) throws Exception {
        return startAndAwaitRecovery(effects, contract::getTakeOfferDate);
    }

    private MuSigTradeService startAndAwaitRecovery(Consumer<Effect> effects, Clock clock) throws Exception {
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effects);
        var service = serviceWithClock(services, grpcClient, clock);
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

    private void seed(MuSigV2TradeRecord... records) throws Exception {
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

    private MuSigV2TradeRecord saved(MuSigTradeService service) {
        return service.getPersistence().read().orElseThrow().getTrades().getFirst();
    }

    private static NetworkId networkId(String name, int port, KeyPair keys) {
        return new NetworkId(new AddressByTransportTypeMap(Map.of(TransportType.CLEAR,
                new ClearnetAddress("127.0.0.1", port))), new PubKey(keys.getPublic(), name));
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(15, TimeUnit.SECONDS);
    }
}
