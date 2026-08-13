/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.bisq_easy;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.common.market.Market;
import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.proto.UnresolvableProtobufEnumException;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.message.NetworkMessageResolver;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.PersistenceService;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.trade.ServiceProvider;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasySellerAsMakerProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatSentMessage;
import bisq.trade.protocol.messages.TradeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes the unchanged Bisq 2.1.11 ordering and lifecycle behaviour.
 *
 * These tests describe what the released code does. They do not depend on or
 * assert the behaviour of any proposed fix.
 */
class BisqEasyTradeExistingBehaviourTest {
    private static final String BITCOIN_PAYMENT_DATA = "bc1qxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzx";

    static {
        NetworkMessageResolver.addResolver("trade.TradeMessage", TradeMessage.getNetworkMessageResolver());
    }

    @Test
    void runtimeOutOfOrderMessageIsRetriedAfterPrerequisiteTransition()
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("runtime");
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasySellerAsMakerProtocol protocol = createProtocol(trade);

        protocol.handle(createFiatSentMessage(testData, trade.getId(), "fiat-runtime"));

        assertEquals(1, trade.getEventQueue().size());
        assertEquals(waitingForBtcAddressState(), trade.getTradeState());

        protocol.handle(createBtcAddressMessage(testData, trade.getId(), "btc-runtime"));

        assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION, trade.getTradeState());
        assertTrue(trade.getEventQueue().isEmpty());
    }

    @Test
    void concurrentRuntimeDeliveryAlwaysConvergesThroughTradeService(@TempDir Path tempDir)
            throws Exception {
        NetworkService networkService = mock(NetworkService.class);
        when(networkService.getConfidentialMessageServices()).thenReturn(Set.of());
        Harness harness = createHarness(tempDir, networkService);

        int iterations = 64;
        TestData[] testDataByIteration = new TestData[iterations];
        BisqEasyTrade[] tradeByIteration = new BisqEasyTrade[iterations];
        for (int i = 0; i < iterations; i++) {
            TestData testData = createTestData("concurrent-" + i);
            BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
            testDataByIteration[i] = testData;
            tradeByIteration[i] = trade;
            harness.tradeService().getPersistableStore().addTrade(trade);
        }

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            harness.tradeService().initialize().join();

            for (int i = 0; i < iterations; i++) {
                TestData testData = testDataByIteration[i];
                BisqEasyTrade trade = tradeByIteration[i];
                BisqEasyConfirmFiatSentMessage fiatSentMessage =
                        createFiatSentMessage(testData, trade.getId(), "fiat-concurrent-" + i);
                BisqEasyBtcAddressMessage btcAddressMessage =
                        createBtcAddressMessage(testData, trade.getId(), "btc-concurrent-" + i);

                runConcurrently(
                        executor,
                        () -> harness.tradeService().onMessage(fiatSentMessage),
                        () -> harness.tradeService().onMessage(btcAddressMessage));

                assertEquals(
                        BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                        trade.getTradeState(),
                        "iteration " + i);
                assertTrue(trade.getEventQueue().isEmpty(), "iteration " + i);
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
            shutdownAndAwaitPersistence(harness);
        }
    }

    @Test
    void restartDropsQueuedFutureMessage()
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("restart");
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasySellerAsMakerProtocol protocol = createProtocol(trade);

        protocol.handle(createFiatSentMessage(testData, trade.getId(), "fiat-restart"));

        BisqEasyTrade restoredTrade = BisqEasyTrade.fromProto(trade.toProto(false));

        assertEquals(waitingForBtcAddressState(), restoredTrade.getTradeState());
        assertTrue(restoredTrade.getEventQueue().isEmpty());

        BisqEasySellerAsMakerProtocol restoredProtocol = createProtocol(restoredTrade);
        restoredProtocol.handle(createBtcAddressMessage(testData, restoredTrade.getId(), "btc-restart"));

        assertEquals(
                BisqEasyTradeState
                        .MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS,
                restoredTrade.getTradeState());
        assertTrue(restoredTrade.getEventQueue().isEmpty());
    }

    @Test
    void startupReplayThenListenerRegistrationCanLoseMessage(@TempDir Path tempDir)
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("handoff");
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasyBtcAddressMessage btcAddressMessage =
                createBtcAddressMessage(testData, trade.getId(), "btc-handoff");
        AtomicReference<ConfidentialMessageService.Listener> registeredListener = new AtomicReference<>();

        ConfidentialMessageService confidentialMessageService = mock(ConfidentialMessageService.class);
        when(confidentialMessageService.getProcessedEnvelopePayloadMessages()).thenAnswer(invocation -> {
            Optional.ofNullable(registeredListener.get())
                    .ifPresent(listener -> listener.onMessage(btcAddressMessage));
            return Set.<EnvelopePayloadMessage>of();
        });

        NetworkService networkService = mock(NetworkService.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if (methodName.equals("addConfidentialMessageListener")) {
                registeredListener.set(invocation.getArgument(0));
                return null;
            }
            if (methodName.equals("getConfidentialMessageServices")) {
                return Set.of(confidentialMessageService);
            }
            return org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation);
        });

        Harness harness = createHarness(tempDir, networkService);
        harness.tradeService().getPersistableStore().addTrade(trade);
        try {
            harness.tradeService().initialize().join();

            assertEquals(waitingForBtcAddressState(), trade.getTradeState());
            assertTrue(registeredListener.get() != null);
        } finally {
            shutdownAndAwaitPersistence(harness);
        }
    }

    @Test
    void futureMessageRemainsQueuedWhilePrerequisiteIsMissing()
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("missing-prerequisite");
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasySellerAsMakerProtocol protocol = createProtocol(trade);
        BisqEasyConfirmFiatSentMessage fiatSentMessage =
                createFiatSentMessage(testData, trade.getId(), "fiat-missing-prerequisite");

        protocol.handle(fiatSentMessage);
        protocol.handle(fiatSentMessage);

        assertEquals(waitingForBtcAddressState(), trade.getTradeState());
        assertEquals(1, trade.getEventQueue().size());
    }

    private static BisqEasySellerAsMakerProtocol createProtocol(BisqEasyTrade trade) {
        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        return new BisqEasySellerAsMakerProtocol(serviceProvider, trade);
    }

    private static void runConcurrently(ExecutorService executor,
                                        Runnable first,
                                        Runnable second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        Future<?> firstFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            first.run();
        });
        Future<?> secondFuture = executor.submit(() -> {
            ready.countDown();
            await(start);
            second.run();
        });

        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        firstFuture.get(5, TimeUnit.SECONDS);
        secondFuture.get(5, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test gate");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    private static Harness createHarness(Path persistenceDir, NetworkService networkService) {
        AlertService alertService = mock(AlertService.class);
        ObservableSet<AuthorizedAlertData> authorizedAlerts = new ObservableSet<>();
        when(alertService.getAuthorizedAlertDataSet()).thenReturn(authorizedAlerts);

        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getPersistenceService()).thenReturn(new PersistenceService(persistenceDir));
        when(serviceProvider.getBondedRolesService().getAlertService()).thenReturn(alertService);

        BisqEasyTradeService tradeService = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(tradeService);
        return new Harness(tradeService);
    }

    private static void shutdownAndAwaitPersistence(Harness harness) {
        harness.tradeService().getPersistence()
                .persistAsync(harness.tradeService().getPersistableStore().getClone())
                .join();
        harness.tradeService().shutdown().join();
    }

    private static BisqEasyTrade createTradeWaitingForBtcAddress(TestData testData)
            throws UnresolvableProtobufEnumException {
        BisqEasyTrade freshTrade = new BisqEasyTrade(
                testData.contract(),
                false,
                false,
                createIdentity(testData.makerNetworkId()),
                testData.offer(),
                testData.takerNetworkId(),
                testData.makerNetworkId());
        bisq.trade.protobuf.Trade proto = freshTrade.toProto(false).toBuilder()
                .setState(waitingForBtcAddressState().name())
                .build();
        return BisqEasyTrade.fromProto(proto);
    }

    private static BisqEasyConfirmFiatSentMessage createFiatSentMessage(TestData testData,
                                                                         String tradeId,
                                                                         String messageId) {
        return new BisqEasyConfirmFiatSentMessage(
                messageId,
                tradeId,
                BisqEasyProtocol.VERSION,
                testData.takerNetworkId(),
                testData.makerNetworkId());
    }

    private static BisqEasyBtcAddressMessage createBtcAddressMessage(TestData testData,
                                                                      String tradeId,
                                                                      String messageId) {
        return new BisqEasyBtcAddressMessage(
                messageId,
                tradeId,
                BisqEasyProtocol.VERSION,
                testData.takerNetworkId(),
                testData.makerNetworkId(),
                BITCOIN_PAYMENT_DATA,
                testData.offer());
    }

    private static BisqEasyTradeState waitingForBtcAddressState() {
        return BisqEasyTradeState
                .MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS;
    }

    private static TestData createTestData(String idSuffix) {
        NetworkId takerNetworkId = createNetworkId("buyer-taker-" + idSuffix);
        NetworkId makerNetworkId = createNetworkId("seller-maker-" + idSuffix);
        BisqEasyOffer offer = new BisqEasyOffer(
                makerNetworkId,
                Direction.SELL,
                new Market("BTC", "EUR", "Bitcoin", "Euro"),
                new BaseSideFixedAmountSpec(100_000),
                new MarketPriceSpec(),
                List.of(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                List.of(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                "",
                List.of("en"),
                "1.0.0");
        BisqEasyContract contract = new BisqEasyContract(
                System.currentTimeMillis(),
                offer,
                takerNetworkId,
                100_000,
                3_500_000,
                new BitcoinPaymentMethodSpec(
                        BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                new FiatPaymentMethodSpec(
                        FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                Optional.empty(),
                new MarketPriceSpec(),
                0);
        return new TestData(takerNetworkId, makerNetworkId, offer, contract);
    }

    private static Identity createIdentity(NetworkId networkId) {
        KeyBundle keyBundle = new KeyBundle(
                "test-key-bundle",
                KeyGeneration.generateDefaultEcKeyPair(),
                TorKeyGeneration.generateKeyPair(),
                I2PKeyGeneration.generateKeyPair());
        return new Identity("test-id", networkId, keyBundle);
    }

    private static NetworkId createNetworkId(String keyId) {
        KeyPair keyPair = KeyGeneration.generateDefaultEcKeyPair();
        Address address = Address.from("127.0.0.1", 1000);
        return new NetworkId(
                new AddressByTransportTypeMap(Map.of(address.getTransportType(), address)),
                new PubKey(keyPair.getPublic(), keyId));
    }

    private record Harness(BisqEasyTradeService tradeService) {
    }

    private record TestData(NetworkId takerNetworkId,
                            NetworkId makerNetworkId,
                            BisqEasyOffer offer,
                            BisqEasyContract contract) {
    }
}
