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
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyBtcAddressMessage;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyConfirmFiatSentMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BisqEasyTradeServiceStartupHandoffTest {
    private static final String BITCOIN_PAYMENT_DATA = "bc1qxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzxyzx";

    @Test
    void messageProcessedDuringStartupHandoffReachesTradeProtocol(@TempDir Path tempDir)
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("handoff");
        Harness harness = createHarness(tempDir);
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasyBtcAddressMessage btcAddressMessage = createBtcAddressMessage(testData, trade.getId());

        // This models the old snapshot-then-register sequence. The message is processed while no listener is
        // registered, so restoring that sequence in BisqEasyTradeService makes the assertion below fail.
        AtomicReference<ConfidentialMessageService.Listener> legacyListener = new AtomicReference<>();
        ConfidentialMessageService confidentialMessageService = mock(ConfidentialMessageService.class);
        when(confidentialMessageService.getProcessedEnvelopePayloadMessages()).thenAnswer(invocation -> {
            Optional.ofNullable(legacyListener.get()).ifPresent(listener -> listener.onMessage(btcAddressMessage));
            return Set.<EnvelopePayloadMessage>of();
        });
        when(harness.networkService().getConfidentialMessageServices()).thenReturn(Set.of(confidentialMessageService));
        doAnswer(invocation -> {
            legacyListener.set(invocation.getArgument(0));
            return null;
        }).when(harness.networkService()).addConfidentialMessageListener(any());

        // The new network operation guarantees that the same in-window message is either replayed or delivered
        // live. The registry tests cover the two possible lock orderings; this test covers the real trade lifecycle.
        doAnswer(invocation -> {
            ConfidentialMessageService.Listener listener = invocation.getArgument(0);
            listener.onMessage(btcAddressMessage);
            return null;
        }).when(harness.networkService()).addConfidentialMessageListenerAndReplay(any());

        harness.tradeService().getPersistableStore().addTrade(trade);
        try {
            harness.tradeService().initialize().join();

            BisqEasyTradeState expectedState = BisqEasyTradeState
                    .MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_RECEIVED_BTC_ADDRESS;
            assertEquals(expectedState, trade.getTradeState());
        } finally {
            shutdownAndAwaitPersistence(harness);
        }
    }

    @Test
    void liveMessageBeforeStartupReplayIsHandledAsOutOfOrder(@TempDir Path tempDir)
            throws UnresolvableProtobufEnumException {
        TestData testData = createTestData("ordering");
        Harness harness = createHarness(tempDir);
        BisqEasyTrade trade = createTradeWaitingForBtcAddress(testData);
        BisqEasyConfirmFiatSentMessage fiatSentMessage = new BisqEasyConfirmFiatSentMessage(
                "fiat-sent-ordering",
                trade.getId(),
                BisqEasyProtocol.VERSION,
                testData.takerNetworkId(),
                testData.makerNetworkId());
        BisqEasyBtcAddressMessage btcAddressMessage = createBtcAddressMessage(testData, trade.getId());

        doAnswer(invocation -> {
            ConfidentialMessageService.Listener listener = invocation.getArgument(0);

            // A new live message can run after registration but before an older snapshot message is replayed.
            listener.onMessage(fiatSentMessage);
            assertEquals(1, trade.getEventQueue().size());

            listener.onMessage(btcAddressMessage);
            return null;
        }).when(harness.networkService()).addConfidentialMessageListenerAndReplay(any());

        harness.tradeService().getPersistableStore().addTrade(trade);
        try {
            harness.tradeService().initialize().join();

            assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION, trade.getTradeState());
            assertTrue(trade.getEventQueue().isEmpty());
        } finally {
            shutdownAndAwaitPersistence(harness);
        }
    }

    private static Harness createHarness(Path persistenceDir) {
        NetworkService networkService = mock(NetworkService.class);
        AlertService alertService = mock(AlertService.class);
        ObservableSet<AuthorizedAlertData> authorizedAlerts = new ObservableSet<>();
        when(alertService.getAuthorizedAlertDataSet()).thenReturn(authorizedAlerts);

        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getPersistenceService()).thenReturn(new PersistenceService(persistenceDir));
        when(serviceProvider.getBondedRolesService().getAlertService()).thenReturn(alertService);

        BisqEasyTradeService tradeService = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(tradeService);
        return new Harness(tradeService, networkService);
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
        String stateName = BisqEasyTradeState
                .MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS
                .name();
        bisq.trade.protobuf.Trade proto = freshTrade.toProto(false).toBuilder()
                .setState(stateName)
                .build();
        return BisqEasyTrade.fromProto(proto);
    }

    private static BisqEasyBtcAddressMessage createBtcAddressMessage(TestData testData, String tradeId) {
        return new BisqEasyBtcAddressMessage(
                "btc-address-" + tradeId,
                tradeId,
                BisqEasyProtocol.VERSION,
                testData.takerNetworkId(),
                testData.makerNetworkId(),
                BITCOIN_PAYMENT_DATA,
                testData.offer());
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
                new BitcoinPaymentMethodSpec(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                new FiatPaymentMethodSpec(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
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

    private record Harness(BisqEasyTradeService tradeService,
                           NetworkService networkService) {
    }

    private record TestData(NetworkId takerNetworkId,
                            NetworkId makerNetworkId,
                            BisqEasyOffer offer,
                            BisqEasyContract contract) {
    }
}
