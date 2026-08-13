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
import bisq.common.fsm.FsmModel;
import bisq.common.fsm.State;
import bisq.common.market.Market;
import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.observable.collection.ObservableSet;
import bisq.contract.bisq_easy.BisqEasyContract;
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

/**
 * Deterministically characterizes the check-then-act race in the unchanged
 * Bisq 2.1.11 maker protocol factory. Production code is not modified; a spy
 * pauses the two public guard reads after each has captured the same empty
 * result, then controls write ordering through the public persist() call.
 */
class BisqEasyDuplicateProtocolCreationEvidenceTest {
    @Test
    void concurrentCreationSplitsStoredAndActiveModelsUntilProtocolIsRebuilt(@TempDir Path tempDir)
            throws Exception {
        NetworkId takerNetworkId = createNetworkId("buyer-taker-race");
        NetworkId makerNetworkId = createNetworkId("seller-maker-race");
        BisqEasyOffer offer = createOffer(makerNetworkId);
        BisqEasyContract contract = createContract(offer, takerNetworkId);

        NetworkService networkService = mock(NetworkService.class);
        ServiceProvider serviceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(serviceProvider.getNetworkService()).thenReturn(networkService);
        when(serviceProvider.getPersistenceService()).thenReturn(new PersistenceService(tempDir));
        when(serviceProvider.getIdentityService().findAnyIdentityByNetworkId(makerNetworkId))
                .thenReturn(Optional.of(createIdentity(makerNetworkId)));

        BisqEasyTradeService tradeService = spy(new BisqEasyTradeService(serviceProvider, AppType.DESKTOP));
        when(serviceProvider.getBisqEasyTradeService()).thenReturn(tradeService);

        CountDownLatch bothReadEmptyProtocolMap = new CountDownLatch(2);
        CountDownLatch bothReadMissingTrade = new CountDownLatch(2);
        CountDownLatch threadAEnteredPersist = new CountDownLatch(1);
        CountDownLatch threadBEnteredPersist = new CountDownLatch(1);
        CountDownLatch threadACompletedCreation = new CountDownLatch(1);

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (isCreationThread()) {
                bothReadEmptyProtocolMap.countDown();
                await(bothReadEmptyProtocolMap, "both protocol-map reads");
            }
            return result;
        }).when(tradeService).findProtocol(anyString());

        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if (isCreationThread()) {
                bothReadMissingTrade.countDown();
                await(bothReadMissingTrade, "both trade-existence reads");
                if (Thread.currentThread().getName().equals("creation-thread-B")) {
                    await(threadAEnteredPersist, "thread A adding its trade first");
                }
            }
            return result;
        }).when(tradeService).tradeExists(anyString());

        doAnswer(invocation -> {
            if (Thread.currentThread().getName().equals("creation-thread-A")) {
                threadAEnteredPersist.countDown();
                await(threadBEnteredPersist, "thread B adding its equal trade model");
            } else if (Thread.currentThread().getName().equals("creation-thread-B")) {
                threadBEnteredPersist.countDown();
                await(threadACompletedCreation, "thread A registering its protocol first");
            }
            return CompletableFuture.completedFuture(true);
        }).when(tradeService).persist();

        AtomicReference<BisqEasyProtocol> protocolA = new AtomicReference<>();
        AtomicReference<BisqEasyProtocol> protocolB = new AtomicReference<>();
        AtomicReference<Throwable> failureA = new AtomicReference<>();
        AtomicReference<Throwable> failureB = new AtomicReference<>();

        Thread threadA = new Thread(() -> {
            try {
                protocolA.set(invokeMakerCreatesProtocol(
                        tradeService, contract, takerNetworkId, makerNetworkId));
            } catch (Throwable throwable) {
                failureA.set(throwable);
            } finally {
                threadACompletedCreation.countDown();
            }
        }, "creation-thread-A");
        Thread threadB = new Thread(() -> {
            try {
                protocolB.set(invokeMakerCreatesProtocol(
                        tradeService, contract, takerNetworkId, makerNetworkId));
            } catch (Throwable throwable) {
                failureB.set(throwable);
            }
        }, "creation-thread-B");

        BisqEasyTradeService restartedTradeService = null;
        try {
            threadA.start();
            threadB.start();
            threadA.join(5_000);
            threadB.join(5_000);

            assertTrue(!threadA.isAlive() && !threadB.isAlive());
            assertNull(failureA.get());
            assertNull(failureB.get());
            assertNotNull(protocolA.get());
            assertNotNull(protocolB.get());
            assertNotSame(protocolA.get(), protocolB.get());

            String tradeId = protocolA.get().getTrade().getId();
            assertEquals(tradeId, protocolB.get().getTrade().getId());
            assertNotSame(protocolA.get().getTrade(), protocolB.get().getTrade());

            // B is deliberately released after A completes, so its put replaces
            // A in tradeProtocolById. A still exists but can no longer be found.
            assertSame(protocolB.get(), tradeService.findProtocol(tradeId).orElseThrow());
            assertNotSame(protocolA.get(), tradeService.findProtocol(tradeId).orElseThrow());

            // The store's set collapses the equal-id models and keeps A, while
            // the protocol map was overwritten with B. The UI/persistence model
            // and the model receiving later protocol messages are now different
            // objects for the same trade ID.
            assertEquals(1, tradeService.getTrades().size());
            BisqEasyTrade storedTrade = tradeService.findTrade(tradeId).orElseThrow();
            assertSame(protocolA.get().getTrade(), storedTrade);
            assertNotSame(protocolB.get().getTrade(), storedTrade);

            // Put both objects at the state in which the seller can press
            // "Payment received". The state setup is reflective because this
            // characterization test needs to isolate the object-identity race;
            // state mutation after setup still goes through the real service,
            // protocol, FSM and event handler.
            setState(storedTrade, BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION);
            setState(protocolB.get().getTrade(),
                    BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION);

            // The UI passes its stored model (A), but the service routes only
            // by ID and therefore invokes the active protocol backed by B.
            tradeService.sellerConfirmFiatReceipt(storedTrade);

            assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                    storedTrade.getTradeState());
            assertEquals(BisqEasyTradeState.SELLER_CONFIRMED_FIAT_RECEIPT,
                    protocolB.get().getTrade().getTradeState());

            // A restart serializes/restores the stored model A and rebuilds a
            // protocol around that same object. Retrying the identical action
            // now advances the object observed by the UI.
            BisqEasyTradeStore restoredStore = BisqEasyTradeStore.fromProto(
                    tradeService.getPersistableStore().toProto(false));
            NetworkService restartedNetworkService = mock(NetworkService.class);
            when(restartedNetworkService.getConfidentialMessageServices()).thenReturn(Set.of());
            AlertService restartedAlertService = mock(AlertService.class);
            when(restartedAlertService.getAuthorizedAlertDataSet())
                    .thenReturn(new ObservableSet<AuthorizedAlertData>());
            ServiceProvider restartedServiceProvider = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
            when(restartedServiceProvider.getNetworkService()).thenReturn(restartedNetworkService);
            when(restartedServiceProvider.getPersistenceService())
                    .thenReturn(new PersistenceService(tempDir.resolve("restart")));
            when(restartedServiceProvider.getBondedRolesService().getAlertService())
                    .thenReturn(restartedAlertService);

            restartedTradeService = spy(new BisqEasyTradeService(
                    restartedServiceProvider, AppType.DESKTOP));
            when(restartedServiceProvider.getBisqEasyTradeService()).thenReturn(restartedTradeService);
            doAnswer(invocation -> CompletableFuture.completedFuture(true))
                    .when(restartedTradeService).persist();
            restartedTradeService.getPersistableStore().applyPersisted(restoredStore);
            restartedTradeService.initialize().join();

            BisqEasyTrade restoredTrade = restartedTradeService.findTrade(tradeId).orElseThrow();
            BisqEasyProtocol rebuiltProtocol = restartedTradeService.findProtocol(tradeId).orElseThrow();

            assertSame(restoredTrade, rebuiltProtocol.getTrade());
            assertEquals(BisqEasyTradeState.SELLER_RECEIVED_FIAT_SENT_CONFIRMATION,
                    restoredTrade.getTradeState());

            restartedTradeService.sellerConfirmFiatReceipt(restoredTrade);

            assertEquals(BisqEasyTradeState.SELLER_CONFIRMED_FIAT_RECEIPT,
                    restoredTrade.getTradeState());
        } finally {
            if (threadA.isAlive()) {
                threadA.interrupt();
            }
            if (threadB.isAlive()) {
                threadB.interrupt();
            }
            if (restartedTradeService != null) {
                restartedTradeService.shutdown().join();
            }
            tradeService.shutdown().join();
        }
    }

    private static boolean isCreationThread() {
        return Thread.currentThread().getName().startsWith("creation-thread-");
    }

    private static BisqEasyProtocol invokeMakerCreatesProtocol(BisqEasyTradeService tradeService,
                                                                BisqEasyContract contract,
                                                                NetworkId sender,
                                                                NetworkId receiver) throws Throwable {
        Method method = BisqEasyTradeService.class.getDeclaredMethod(
                "makerCreatesProtocol", BisqEasyContract.class, NetworkId.class, NetworkId.class);
        method.setAccessible(true);
        try {
            return (BisqEasyProtocol) method.invoke(tradeService, contract, sender, receiver);
        } catch (InvocationTargetException exception) {
            throw exception.getCause();
        }
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
                new Market("BTC", "EUR", "Bitcoin", "Euro"),
                new BaseSideFixedAmountSpec(100_000),
                new MarketPriceSpec(),
                List.of(BitcoinPaymentMethod.fromPaymentRail(BitcoinPaymentRail.MAIN_CHAIN)),
                List.of(FiatPaymentMethod.fromPaymentRail(FiatPaymentRail.NATIONAL_BANK)),
                "",
                List.of("en"),
                "1.0.0");
    }

    private static BisqEasyContract createContract(BisqEasyOffer offer, NetworkId takerNetworkId) {
        return new BisqEasyContract(
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
}
