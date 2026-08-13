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

package bisq.trade.bisq_easy;

import bisq.account.payment_method.BitcoinPaymentMethod;
import bisq.account.payment_method.BitcoinPaymentMethodSpec;
import bisq.account.payment_method.BitcoinPaymentRail;
import bisq.account.payment_method.fiat.FiatPaymentMethod;
import bisq.account.payment_method.fiat.FiatPaymentMethodSpec;
import bisq.account.payment_method.fiat.FiatPaymentRail;
import bisq.bonded_roles.market_price.MarketPrice;
import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.chat.bisq_easy.offerbook.BisqEasyOfferbookChannel;
import bisq.chat.bisq_easy.offerbook.BisqEasyOfferbookChannelService;
import bisq.common.fsm.FsmModel;
import bisq.common.fsm.State;
import bisq.common.market.Market;
import bisq.common.monetary.Monetary;
import bisq.common.monetary.PriceQuote;
import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.observable.Observable;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.observable.map.ObservableHashMap;
import bisq.contract.ContractService;
import bisq.contract.ContractSignatureData;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.NetworkExecutors;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.message.NetworkMessageResolver;
import bisq.network.p2p.node.NodesById;
import bisq.network.p2p.services.confidential.ConfidentialMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.offer.Direction;
import bisq.offer.amount.spec.BaseSideFixedAmountSpec;
import bisq.offer.bisq_easy.BisqEasyOffer;
import bisq.offer.price.spec.MarketPriceSpec;
import bisq.persistence.PersistenceService;
import bisq.security.ConfidentialData;
import bisq.security.HybridEncryption;
import bisq.security.keys.I2PKeyGeneration;
import bisq.security.keys.KeyBundle;
import bisq.security.keys.KeyBundleService;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.security.keys.TorKeyGeneration;
import bisq.settings.SettingsService;
import bisq.trade.ServiceProvider;
import bisq.trade.Trade;
import bisq.trade.bisq_easy.protocol.BisqEasyProtocol;
import bisq.trade.bisq_easy.protocol.BisqEasyTradeState;
import bisq.trade.bisq_easy.protocol.messages.BisqEasyTakeOfferRequest;
import bisq.trade.protocol.messages.TradeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Joins the separately reproduced network-delivery and protocol-creation
 * behaviours into one lifecycle using unchanged production code.
 *
 * <p>The timing hooks only make a valid production interleaving
 * deterministic. Decryption, message resolution, request validation, protocol
 * handling, state transitions, store serialization and restart restoration all
 * use the real implementations.</p>
 */
class BisqEasyCrossTransportLifecycleEvidenceTest {
    private static final Market MARKET = new Market("BTC", "EUR", "Bitcoin", "Euro");
    private static final long BASE_AMOUNT = 100_000;
    private static final PriceQuote MARKET_PRICE = PriceQuote.fromFiatPrice(35_000, "EUR");

    @Test
    void duplicateCrossTransportTakeOfferCanSplitModelUntilRestart(@TempDir Path tempDir)
            throws Exception {
        NetworkExecutors.initialize(8);
        NetworkMessageResolver.addResolver("trade.TradeMessage", TradeMessage.getNetworkMessageResolver());

        KeyPair makerKeyPair = KeyGeneration.generateDefaultEcKeyPair();
        KeyPair takerKeyPair = KeyGeneration.generateDefaultEcKeyPair();
        String receiverKeyId = "maker-receiver-key";
        NetworkId makerNetworkId = createNetworkId(receiverKeyId, makerKeyPair);
        NetworkId takerNetworkId = createNetworkId("taker-sender-key", takerKeyPair);
        Identity makerIdentity = createIdentity(makerNetworkId, makerKeyPair);
        BisqEasyOffer offer = createOffer(makerNetworkId);
        BisqEasyContract contract = createContract(offer, takerNetworkId);
        String tradeId = Trade.createId(
                offer.getId(), takerNetworkId.getId(), contract.getTakeOfferDate());

        ContractService contractService = new ContractService(null);
        ContractSignatureData takerSignature = contractService.signContract(contract, takerKeyPair);
        BisqEasyTakeOfferRequest request = new BisqEasyTakeOfferRequest(
                "take-offer-request", tradeId, BisqEasyProtocol.VERSION,
                takerNetworkId, makerNetworkId, contract, takerSignature);
        ConfidentialMessage encryptedRequest = encrypt(
                request, receiverKeyId, makerKeyPair, takerKeyPair);

        Harness original = createHarness(
                tempDir, makerIdentity, contractService, true);
        BisqEasyTradeService tradeService = original.tradeService();
        ConfidentialMessageService firstTransport = createConfidentialService(
                receiverKeyId, makerKeyPair);
        ConfidentialMessageService secondTransport = createConfidentialService(
                receiverKeyId, makerKeyPair);

        ThreadLocal<String> delivery = new ThreadLocal<>();
        AtomicInteger deliveryCounter = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        CountDownLatch callbacksCompleted = new CountDownLatch(2);

        CountDownLatch bothReadEmptyProtocolMap = new CountDownLatch(2);
        CountDownLatch bothReadMissingTrade = new CountDownLatch(2);
        CountDownLatch deliveryAEnteredFirstPersist = new CountDownLatch(1);
        CountDownLatch deliveryBEnteredFirstPersist = new CountDownLatch(1);
        CountDownLatch deliveryAHandledRequest = new CountDownLatch(1);
        AtomicInteger deliveryAPersistCount = new AtomicInteger();
        AtomicInteger deliveryBPersistCount = new AtomicInteger();

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (delivery.get() != null) {
                bothReadEmptyProtocolMap.countDown();
                await(bothReadEmptyProtocolMap, "both protocol-map reads");
            }
            return result;
        }).when(tradeService).findProtocol(anyString());

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            String currentDelivery = delivery.get();
            if (currentDelivery != null) {
                bothReadMissingTrade.countDown();
                await(bothReadMissingTrade, "both trade-store reads");
                if (currentDelivery.equals("B")) {
                    await(deliveryAEnteredFirstPersist, "delivery A storing its trade first");
                }
            }
            return result;
        }).when(tradeService).tradeExists(anyString());

        doAnswer(invocation -> {
            String currentDelivery = delivery.get();
            if (currentDelivery == null) {
                return CompletableFuture.completedFuture(true);
            }

            AtomicInteger persistCount = currentDelivery.equals("A")
                    ? deliveryAPersistCount : deliveryBPersistCount;
            int count = persistCount.incrementAndGet();
            if (currentDelivery.equals("A") && count == 1) {
                deliveryAEnteredFirstPersist.countDown();
                await(deliveryBEnteredFirstPersist, "delivery B adding its equal trade");
            } else if (currentDelivery.equals("B") && count == 1) {
                deliveryBEnteredFirstPersist.countDown();
                await(deliveryAHandledRequest, "delivery A registering and handling its protocol");
            } else if (currentDelivery.equals("A") && count == 2) {
                deliveryAHandledRequest.countDown();
            }
            return CompletableFuture.completedFuture(true);
        }).when(tradeService).persist();

        ConfidentialMessageService.Listener listener = message -> {
            String label = deliveryCounter.incrementAndGet() == 1 ? "A" : "B";
            delivery.set(label);
            try {
                tradeService.onMessage(message);
            } catch (Throwable throwable) {
                callbackFailure.compareAndSet(null, throwable);
            } finally {
                delivery.remove();
                callbacksCompleted.countDown();
            }
        };
        firstTransport.addListener(listener);
        secondTransport.addListener(listener);

        BisqEasyTradeService restartedTradeService = null;
        try {
            // Each service accepts the same encrypted request once. Their
            // listener callbacks are dispatched independently and can overlap.
            firstTransport.onMessage(encryptedRequest, null, null);
            secondTransport.onMessage(encryptedRequest, null, null);

            assertTrue(callbacksCompleted.await(10, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertEquals(2, deliveryCounter.get());
            assertEquals(1, firstTransport.getProcessedEnvelopePayloadMessages().size());
            assertEquals(1, secondTransport.getProcessedEnvelopePayloadMessages().size());

            assertEquals(1, tradeService.getTrades().size());
            BisqEasyTrade storedTrade = tradeService.findTrade(tradeId).orElseThrow();
            BisqEasyProtocol activeProtocol = tradeService.findProtocol(tradeId).orElseThrow();
            BisqEasyTrade activeTrade = activeProtocol.getTrade();

            assertNotSame(storedTrade, activeTrade);
            assertEquals(
                    BisqEasyTradeState.MAKER_SENT_TAKE_OFFER_RESPONSE__SELLER_DID_NOT_SENT_ACCOUNT_DATA__SELLER_DID_NOT_RECEIVED_BTC_ADDRESS,
                    storedTrade.getTradeState());
            assertEquals(storedTrade.getTradeState(), activeTrade.getTradeState());

            // This represents the later UI action. The UI holds the stored
            // object, while service dispatch resolves the active protocol by ID.
            setState(storedTrade, BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION);
            setState(activeTrade, BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION);
            tradeService.sellerConfirmFiatReceipt(storedTrade);

            assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                    storedTrade.getTradeState());
            assertEquals(BisqEasyTradeState.SELLER_CONFIRMED_FIAT_RECEIPT,
                    activeTrade.getTradeState());

            // Persist the exact stored/UI model to disk. It still contains the
            // pre-action state because the action changed the other object.
            tradeService.getPersistence()
                    .persistAsync(tradeService.getPersistableStore().getClone())
                    .join();
            assertTrue(tradeService.getPersistence().getStorePath().toFile().isFile());

            Harness restarted = createHarness(
                    tempDir, makerIdentity, contractService, false);
            restartedTradeService = restarted.tradeService();
            assertTrue(restartedTradeService.readPersisted().isPresent());
            restartedTradeService.initialize().join();

            BisqEasyTrade restoredTrade = restartedTradeService.findTrade(tradeId).orElseThrow();
            BisqEasyProtocol rebuiltProtocol = restartedTradeService.findProtocol(tradeId).orElseThrow();
            assertSame(restoredTrade, rebuiltProtocol.getTrade());
            assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                    restoredTrade.getTradeState());

            // Retrying after restart now mutates the object observed by the UI.
            restartedTradeService.sellerConfirmFiatReceipt(restoredTrade);
            assertEquals(BisqEasyTradeState.SELLER_CONFIRMED_FIAT_RECEIPT,
                    restoredTrade.getTradeState());
        } finally {
            if (restartedTradeService != null) {
                restartedTradeService.shutdown().join();
            }
            original.tradeService().shutdown().join();
            firstTransport.shutdown();
            secondTransport.shutdown();
            NetworkExecutors.shutdown();
        }
    }

    private static Harness createHarness(Path appDataDir,
                                         Identity makerIdentity,
                                         ContractService contractService,
                                         boolean useSpy) {
        NetworkService networkService = mock(NetworkService.class);
        when(networkService.getConfidentialMessageServices()).thenReturn(Set.of());
        when(networkService.confidentialSend(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(new SendMessageResult()));

        SettingsService settingsService = mock(SettingsService.class);
        when(settingsService.getCloseMyOfferWhenTaken()).thenReturn(new Observable<>(false));
        when(settingsService.getMaxTradePriceDeviation()).thenReturn(new Observable<>(0.05));
        when(settingsService.getNumDaysAfterRedactingTradeData()).thenReturn(new Observable<>(90));
        when(settingsService.getDoAutoAddToContactList()).thenReturn(false);

        AlertService alertService = mock(AlertService.class);
        when(alertService.getAuthorizedAlertDataSet())
                .thenReturn(new ObservableSet<AuthorizedAlertData>());

        BisqEasyOfferbookChannelService offerbookChannelService =
                mock(BisqEasyOfferbookChannelService.class);
        when(offerbookChannelService.getChannels())
                .thenReturn(new ObservableSet<BisqEasyOfferbookChannel>());

        MarketPrice marketPrice = mock(MarketPrice.class);
        when(marketPrice.getPriceQuote()).thenReturn(MARKET_PRICE);
        ObservableHashMap<Market, MarketPrice> marketPrices = new ObservableHashMap<>();
        marketPrices.put(MARKET, marketPrice);
        MarketPriceService marketPriceService = mock(MarketPriceService.class);
        when(marketPriceService.getMarketPriceByCurrencyMap()).thenReturn(marketPrices);
        when(marketPriceService.findMarketPrice(MARKET)).thenReturn(Optional.of(marketPrice));

        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getPersistenceService()).thenReturn(new PersistenceService(appDataDir));
        when(serviceProvider.getSettingsService()).thenReturn(settingsService);
        when(serviceProvider.getContractService()).thenReturn(contractService);
        when(serviceProvider.getIdentityService().findAnyIdentityByNetworkId(
                makerIdentity.getNetworkId())).thenReturn(Optional.of(makerIdentity));
        when(serviceProvider.getBondedRolesService().getAlertService()).thenReturn(alertService);
        when(serviceProvider.getBondedRolesService().getMarketPriceService())
                .thenReturn(marketPriceService);
        when(serviceProvider.getChatService().getBisqEasyOfferbookChannelService())
                .thenReturn(offerbookChannelService);
        when(serviceProvider.getSupportService().getBisqEasyMediationRequestService()
                .selectMediator(anyString(), anyString(), anyString())).thenReturn(Optional.empty());

        BisqEasyTradeService base = new BisqEasyTradeService(serviceProvider, AppType.DESKTOP);
        BisqEasyTradeService tradeService = useSpy ? spy(base) : base;
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(tradeService);
        return new Harness(tradeService);
    }

    private static ConfidentialMessageService createConfidentialService(String receiverKeyId,
                                                                          KeyPair receiverKeyPair) {
        NodesById nodesById = mock(NodesById.class);
        KeyBundleService keyBundleService = mock(KeyBundleService.class);
        when(keyBundleService.findKeyPair(receiverKeyId)).thenReturn(Optional.of(receiverKeyPair));
        return new ConfidentialMessageService(
                nodesById, keyBundleService, Optional.empty(), Optional.empty());
    }

    private static ConfidentialMessage encrypt(EnvelopePayloadMessage request,
                                               String receiverKeyId,
                                               KeyPair receiverKeyPair,
                                               KeyPair senderKeyPair) throws Exception {
        ConfidentialData confidentialData = HybridEncryption.encryptAndSign(
                request.serialize(), receiverKeyPair.getPublic(), senderKeyPair);
        return ConfidentialMessage.fromProto(
                bisq.network.protobuf.ConfidentialMessage.newBuilder()
                        .setConfidentialData(confidentialData.toProto(false))
                        .setReceiverKeyId(receiverKeyId)
                        .build());
    }

    private static void setState(BisqEasyTrade trade, State state) throws Exception {
        Method method = FsmModel.class.getDeclaredMethod("setNewState", State.class);
        method.setAccessible(true);
        method.invoke(trade, state);
    }

    private static void await(CountDownLatch latch, String description) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for " + description);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(exception);
        }
    }

    private static BisqEasyOffer createOffer(NetworkId makerNetworkId) {
        return new BisqEasyOffer(
                makerNetworkId,
                Direction.SELL,
                MARKET,
                new BaseSideFixedAmountSpec(BASE_AMOUNT),
                new MarketPriceSpec(),
                List.of(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                List.of(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                "",
                List.of("en"),
                BisqEasyProtocol.VERSION);
    }

    private static BisqEasyContract createContract(BisqEasyOffer offer,
                                                   NetworkId takerNetworkId) {
        long quoteAmount = MARKET_PRICE
                .toQuoteSideMonetary(Monetary.from(BASE_AMOUNT, "BTC"))
                .getValue();
        return new BisqEasyContract(
                System.currentTimeMillis(),
                offer,
                takerNetworkId,
                BASE_AMOUNT,
                quoteAmount,
                new BitcoinPaymentMethodSpec(
                        BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                new FiatPaymentMethodSpec(
                        FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                Optional.empty(),
                new MarketPriceSpec(),
                0);
    }

    private static Identity createIdentity(NetworkId networkId, KeyPair keyPair) {
        KeyBundle keyBundle = new KeyBundle(
                "maker-key-bundle",
                keyPair,
                TorKeyGeneration.generateKeyPair(),
                I2PKeyGeneration.generateKeyPair());
        return new Identity("maker-identity", networkId, keyBundle);
    }

    private static NetworkId createNetworkId(String keyId, KeyPair keyPair) {
        Address address = Address.from("127.0.0.1", 1000);
        return new NetworkId(
                new AddressByTransportTypeMap(Map.of(address.getTransportType(), address)),
                new PubKey(keyPair.getPublic(), keyId));
    }

    private record Harness(BisqEasyTradeService tradeService) {
    }
}
