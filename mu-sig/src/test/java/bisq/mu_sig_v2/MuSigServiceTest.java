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

package bisq.mu_sig_v2;

import bisq.account.accounts.Account;
import bisq.account.accounts.AccountPayload;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.bonded_roles.market_price.MarketPrice;
import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.common.market.Market;
import bisq.common.monetary.Monetary;
import bisq.common.monetary.PriceQuote;
import bisq.common.timer.Clock;
import bisq.common.util.ExceptionUtil;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig.arbitration.MuSigTraderArbitrationService;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.mediation.MuSigTraderMediationService;
import bisq.trade.mu_sig_v2.InitializeTrade;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.user.banned.BannedUserService;
import bisq.user.banned.UserProfileBannedException;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigServiceTest {
    private static final long TAKE_OFFER_DATE = 1_750_000_000_000L;
    private static final String TRADE_ID = TradeV2.createId("offer", "taker-network-id", TAKE_OFFER_DATE);
    private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class);
    private final MuSigTradeService trades = mock(MuSigTradeService.class);
    private final UserProfileService profiles = mock(UserProfileService.class);
    private final BannedUserService bannedUsers = mock(BannedUserService.class);
    private final MuSigTraderMediationService mediation = mock(MuSigTraderMediationService.class);
    private final MuSigTraderArbitrationService arbitration = mock(MuSigTraderArbitrationService.class);
    private final MarketPriceService prices = mock(MarketPriceService.class);
    private final Clock clock = mock(Clock.class);
    private final MuSigService service = new MuSigService(trades,
            profiles,
            bannedUsers,
            mediation,
            arbitration,
            prices,
            clock);
    private final UserIdentity taker = mock(UserIdentity.class);
    private final Identity identity = mock(Identity.class);
    private final Account<?, ?> account = mock(Account.class);
    private final AccountPayload<?> accountPayload = mock(AccountPayload.class);
    private final MuSigOffer offer = mock(MuSigOffer.class);
    private final Market market = mock(Market.class);
    private final TakeOffer takeOffer = new TakeOffer(taker,
            offer,
            mock(Monetary.class),
            mock(Monetary.class),
            mock(PaymentMethodSpec.class),
            account);
    private final UserProfile maker = mock(UserProfile.class);
    private final UserProfile mediator = mock(UserProfile.class);
    private final UserProfile arbitrator = mock(UserProfile.class);
    private final CompletableFuture<Void> creation = new CompletableFuture<>();

    @BeforeEach
    void setUp() {
        when(trades.getMusigGrpcClient()).thenReturn(grpcClient);
        when(clock.now()).thenReturn(TAKE_OFFER_DATE, TAKE_OFFER_DATE + 1);
        var networkId = mock(NetworkId.class);
        when(networkId.getId()).thenReturn("taker-network-id");
        when(identity.getNetworkId()).thenReturn(networkId);
        when(taker.getIdentity()).thenReturn(identity);
        when(taker.getId()).thenReturn("taker");
        when(offer.getId()).thenReturn("offer");
        when(offer.getMakersUserProfileId()).thenReturn("maker");
        when(offer.getMarket()).thenReturn(market);
        doReturn(accountPayload).when(account).getAccountPayload();
        when(mediator.getId()).thenReturn("mediator");
        when(mediation.selectMediator("maker", "taker", "offer")).thenReturn(Optional.of(mediator));
        when(arbitration.selectArbitrator("maker", "taker", "offer", Optional.of("mediator")))
                .thenReturn(Optional.of(arbitrator));
        var marketPrice = mock(MarketPrice.class);
        var priceQuote = mock(PriceQuote.class);
        when(marketPrice.getPriceQuote()).thenReturn(priceQuote);
        when(priceQuote.getValue()).thenReturn(100_000L);
        when(prices.findMarketPrice(market)).thenReturn(Optional.of(marketPrice));
        when(profiles.findUserProfile("maker")).thenReturn(Optional.of(maker));
        when(trades.initializeTrade(eq(TRADE_ID), any(InitializeTrade.class))).thenReturn(creation);
    }

    @Test
    void lifecycleInitializesTheClientBeforeV2AndClosesItAfterV2() throws Exception {
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));

        assertTrue(service.initialize().get(10, TimeUnit.SECONDS));
        assertTrue(service.initialize().get(10, TimeUnit.SECONDS));
        assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));
        assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));

        var order = inOrder(trades, grpcClient);
        order.verify(grpcClient).initialize();
        order.verify(trades).initialize();
        order.verify(trades).shutdown();
        order.verify(grpcClient).shutdown();
        verify(grpcClient).initialize();
        verify(trades).initialize();
        verify(trades).shutdown();
        verify(grpcClient).shutdown();
        assertInstanceOf(IllegalStateException.class, failureOf(service.initialize()));
    }

    @Test
    void shutdownWaitsForBothClientAndTradeStartup() throws Exception {
        var clientStarted = new CompletableFuture<Boolean>();
        var tradeStarted = new CompletableFuture<Boolean>();
        when(grpcClient.initialize()).thenReturn(clientStarted);
        when(trades.initialize()).thenReturn(tradeStarted);
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));

        var started = service.initialize();
        var stopped = service.shutdown();
        verify(trades, never()).initialize();
        verify(trades, never()).shutdown();
        verify(grpcClient, never()).shutdown();
        assertFalse(stopped.isDone());
        assertInstanceOf(IllegalStateException.class, failureOf(service.initialize()));

        clientStarted.complete(true);
        verify(trades).initialize();
        verify(trades, never()).shutdown();
        assertFalse(stopped.isDone());
        tradeStarted.complete(true);

        assertTrue(started.get(10, TimeUnit.SECONDS));
        assertTrue(stopped.get(10, TimeUnit.SECONDS));
        var order = inOrder(trades, grpcClient);
        order.verify(trades).shutdown();
        order.verify(grpcClient).shutdown();
    }

    @Test
    void shutdownKeepsTheClientOpenUntilAcceptedWorkHasDrained() throws Exception {
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        var drained = new CompletableFuture<Boolean>();
        when(trades.shutdown()).thenReturn(drained);
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        service.initialize().get(10, TimeUnit.SECONDS);

        var stopped = service.shutdown();
        assertFalse(stopped.isDone());
        verify(grpcClient, never()).shutdown();
        drained.complete(true);
        assertTrue(stopped.get(10, TimeUnit.SECONDS));
        verify(grpcClient).shutdown();
    }

    @Test
    void failedClientStartupStillAllowsCleanupOfV2Resources() throws Exception {
        var failure = new IllegalStateException("Cannot create backend channel");
        when(grpcClient.initialize()).thenThrow(failure);
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));

        assertSame(failure, failureOf(service.initialize()));
        assertSame(failure, failureOf(service.initialize()));
        verify(grpcClient).initialize();
        verify(trades, never()).initialize();
        assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));
        verify(trades).shutdown();
        verify(grpcClient).shutdown();
    }

    @Test
    void unsuccessfulClientInitializationDoesNotStartTrades() {
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(false));
        assertInstanceOf(IllegalStateException.class, failureOf(service.initialize()));
        verify(trades, never()).initialize();
    }

    @Test
    void failedTradeStartupStillDrainsBeforeClosingTheClient() throws Exception {
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        var failure = new IllegalStateException("Listener registration failed");
        when(trades.initialize()).thenThrow(failure);
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));

        assertSame(failure, failureOf(service.initialize()));
        assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));
        var order = inOrder(trades, grpcClient);
        order.verify(trades).shutdown();
        order.verify(grpcClient).shutdown();
    }

    @Test
    void failedDrainKeepsTheClientOpenAndPreventsRestart() throws Exception {
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        service.initialize().get(10, TimeUnit.SECONDS);
        var failure = new IllegalStateException("Drain failed");
        when(trades.shutdown()).thenReturn(CompletableFuture.failedFuture(failure));

        assertSame(failure, failureOf(service.shutdown()));
        assertSame(failure, failureOf(service.shutdown()));
        verify(trades).shutdown();
        verify(grpcClient, never()).shutdown();
        assertInstanceOf(IllegalStateException.class, failureOf(service.initialize()));
    }

    @Test
    void cancellingLifecycleResultsDoesNotCancelStartupOrShutdown() throws Exception {
        var clientStarted = new CompletableFuture<Boolean>();
        when(grpcClient.initialize()).thenReturn(clientStarted);
        when(trades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        var started = service.initialize();
        assertTrue(started.cancel(false));
        var startedAgain = service.initialize();
        var stopped = service.shutdown();
        assertTrue(stopped.cancel(false));
        assertFalse(clientStarted.isCancelled());

        clientStarted.complete(true);
        assertTrue(startedAgain.get(10, TimeUnit.SECONDS));
        assertTrue(service.shutdown().get(10, TimeUnit.SECONDS));
        verify(grpcClient).initialize();
        verify(trades).initialize();
        verify(trades).shutdown();
        verify(grpcClient).shutdown();
    }

    @Test
    void anotherV2InstanceCannotCloseThisInstancesClient() throws Exception {
        var otherTrades = mock(MuSigTradeService.class);
        var otherClient = mock(MusigGrpcClient.class);
        when(otherTrades.getMusigGrpcClient()).thenReturn(otherClient);
        when(otherTrades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(otherTrades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(otherClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(otherClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        var otherService = new MuSigService(otherTrades, profiles, bannedUsers, mediation, arbitration, prices, clock);
        when(grpcClient.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.initialize()).thenReturn(CompletableFuture.completedFuture(true));
        when(trades.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        when(grpcClient.shutdown()).thenReturn(CompletableFuture.completedFuture(true));
        service.initialize().get(10, TimeUnit.SECONDS);
        otherService.initialize().get(10, TimeUnit.SECONDS);

        otherService.shutdown().get(10, TimeUnit.SECONDS);
        verify(trades, never()).shutdown();
        verify(grpcClient, never()).shutdown();
        service.shutdown().get(10, TimeUnit.SECONDS);
    }

    @Test
    void submitsOneInitializerAndWaitsForItsFirstLocalStep() throws Exception {
        var result = service.takeOffer(takeOffer);

        var command = ArgumentCaptor.forClass(InitializeTrade.class);
        verify(trades).initializeTrade(eq(TRADE_ID), command.capture());
        assertEquals(TAKE_OFFER_DATE, command.getValue().getTakeOfferDate());
        verify(clock).now();
        assertFalse(result.isDone());

        creation.complete(null);

        assertEquals(TRADE_ID, result.get(10, TimeUnit.SECONDS));
    }

    @Test
    void aMissingMakerProfilePreventsCreation() {
        when(profiles.findUserProfile("maker")).thenReturn(Optional.empty());
        assertInstanceOf(IllegalArgumentException.class, failureOf(service.takeOffer(takeOffer)));
        verify(trades, never()).initializeTrade(anyString(), any(InitializeTrade.class));
    }

    @Test
    void aBannedMakerCompletesTheFutureExceptionallyWithoutCreatingATrade() {
        when(bannedUsers.isUserProfileBanned("maker")).thenReturn(true);

        assertInstanceOf(UserProfileBannedException.class, failureOf(service.takeOffer(takeOffer)));

        verifyNoInteractions(trades);
    }

    @Test
    void registrationFailureCompletesTheFutureExceptionallyWithoutAReadModel() {
        var failure = new RejectedExecutionException("Trade service is closed");
        when(trades.initializeTrade(anyString(), any(InitializeTrade.class))).thenThrow(failure);

        assertSame(failure, failureOf(service.takeOffer(takeOffer)));
    }

    @Test
    void failedInitializationCompletesTheFutureExceptionally() {
        var failure = new IllegalStateException("Trade persistence failed");
        var result = service.takeOffer(takeOffer);
        creation.completeExceptionally(failure);

        assertSame(failure, failureOf(result));
    }

    @Test
    void cancellingTheResultDoesNotCancelStartup() {
        var result = service.takeOffer(takeOffer);
        assertTrue(result.cancel(false));
        assertFalse(creation.isDone());

        var failure = new IllegalStateException("Protocol startup failed");
        assertTrue(creation.completeExceptionally(failure));

        assertTrue(result.isCancelled());
        assertSame(failure, failureOf(creation));
    }

    private static Throwable failureOf(CompletableFuture<?> result) {
        var failure = assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        return ExceptionUtil.getRootCause(failure);
    }
}
