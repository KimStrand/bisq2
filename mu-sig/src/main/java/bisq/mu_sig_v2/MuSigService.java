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

import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.bonded_roles.market_price.NoMarketPriceAvailableException;
import bisq.common.application.DevMode;
import bisq.common.application.Service;
import bisq.common.timer.Clock;
import bisq.common.timer.SystemClock;
import bisq.common.util.StringUtils;
import bisq.support.arbitration.mu_sig.NoMuSigArbitratorAvailableException;
import bisq.support.mediation.mu_sig.NoMuSigMediatorAvailableException;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig.arbitration.MuSigTraderArbitrationService;
import bisq.trade.mu_sig.mediation.MuSigTraderMediationService;
import bisq.trade.mu_sig_v2.InitializeTrade;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.user.banned.BannedUserService;
import bisq.user.banned.RateLimitExceededException;
import bisq.user.banned.UserProfileBannedException;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileIgnoredException;
import bisq.user.profile.UserProfileService;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static com.google.common.base.Preconditions.checkState;

/** Application setup for the V2 experiment. The application still uses the V1 MuSigService. */
@Slf4j
public final class MuSigService implements Service {
    private final Object lifecycleLock = new Object();
    private final MuSigTradeService muSigTradeService;
    private final UserProfileService userProfileService;
    private final BannedUserService bannedUserService;
    private final MuSigTraderMediationService muSigTraderMediationService;
    private final MuSigTraderArbitrationService muSigTraderArbitrationService;
    private final MarketPriceService marketPriceService;
    private final Clock clock;
    private Optional<CompletableFuture<Boolean>> initialization = Optional.empty();
    private Optional<CompletableFuture<Boolean>> shutdown = Optional.empty();

    public MuSigService(MuSigTradeService muSigTradeService,
                        UserProfileService userProfileService,
                        BannedUserService bannedUserService,
                        MuSigTraderMediationService muSigTraderMediationService,
                        MuSigTraderArbitrationService muSigTraderArbitrationService,
                        MarketPriceService marketPriceService) {
        this(muSigTradeService,
                userProfileService,
                bannedUserService,
                muSigTraderMediationService,
                muSigTraderArbitrationService,
                marketPriceService,
                new SystemClock());
    }

    @VisibleForTesting
    MuSigService(MuSigTradeService muSigTradeService,
                 UserProfileService userProfileService,
                 BannedUserService bannedUserService,
                 MuSigTraderMediationService muSigTraderMediationService,
                 MuSigTraderArbitrationService muSigTraderArbitrationService,
                 MarketPriceService marketPriceService,
                 Clock clock) {
        this.muSigTradeService = muSigTradeService;
        this.userProfileService = userProfileService;
        this.bannedUserService = bannedUserService;
        this.muSigTraderMediationService = muSigTraderMediationService;
        this.muSigTraderArbitrationService = muSigTraderArbitrationService;
        this.marketPriceService = marketPriceService;
        this.clock = clock;
    }

    @Override
    public CompletableFuture<Boolean> initialize() {
        CompletableFuture<Boolean> started;
        synchronized (lifecycleLock) {
            if (shutdown.isPresent()) {
                return CompletableFuture.failedFuture(new IllegalStateException("MuSig V2 is stopping or stopped"));
            }
            if (initialization.isPresent()) {
                return initialization.get().copy();
            }
            started = new CompletableFuture<>();
            initialization = Optional.of(started);
        }
        try {
            muSigTradeService.getMusigGrpcClient().initialize()
                    .thenCompose(result -> {
                        checkState(result, "MuSig backend initialization failed");
                        return muSigTradeService.initialize();
                    })
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            started.complete(result);
                        } else {
                            started.completeExceptionally(failure);
                        }
                    });
        } catch (RuntimeException failure) {
            started.completeExceptionally(failure);
        }
        return started.copy();
    }

    @Override
    public CompletableFuture<Boolean> shutdown() {
        CompletableFuture<Boolean> started;
        CompletableFuture<Boolean> stopped;
        synchronized (lifecycleLock) {
            if (shutdown.isPresent()) {
                return shutdown.get().copy();
            }
            started = initialization.orElseGet(() -> CompletableFuture.completedFuture(true));
            stopped = new CompletableFuture<>();
            shutdown = Optional.of(stopped);
        }
        // A failed or unfinished start may still own resources. Drain V2 before closing its client.
        started.handle((result, failure) -> null)
                .thenCompose(ignored -> muSigTradeService.shutdown())
                .thenCompose(result -> {
                    checkState(result, "MuSig trade service shutdown failed");
                    return muSigTradeService.getMusigGrpcClient().shutdown();
                })
                .thenApply(result -> {
                    checkState(result, "MuSig backend shutdown failed");
                    return true;
                })
                .whenComplete((result, failure) -> {
                    if (failure == null) {
                        stopped.complete(result);
                    } else {
                        stopped.completeExceptionally(failure);
                    }
                });
        return stopped.copy();
    }

    /**
     * Completes with the trade ID after the initial save, chat setup and first local protocol step succeed.
     * Does not wait for a peer response. Validation and preparation failures complete the future exceptionally;
     * required setup failures also appear in the read model's preparationError until setup succeeds.
     * Observe {@link MuSigTradeService#getTradeReadModels()} for the read model and use its observables for state changes.
     * Cancelling the returned future does not cancel accepted preparation or startup work.
     */
    public CompletableFuture<String> takeOffer(TakeOffer takeOffer) {
        try {
            var offer = takeOffer.getOffer();
            var takerIdentity = takeOffer.getTakerIdentity();
            String makerId = offer.getMakersUserProfileId();
            String takerId = takerIdentity.getId();
            validateUserProfile(makerId);
            validateUserProfile(takerId);
            if (userProfileService.isChatUserIgnored(makerId)) {
                throw new UserProfileIgnoredException(makerId);
            }
            userProfileService.findUserProfile(makerId)
                    .orElseThrow(() -> new IllegalArgumentException("Maker's user profile is not present"));

            var mediator = muSigTraderMediationService.selectMediator(makerId, takerId, offer.getId());
            if (!DevMode.isDevMode() && mediator.isEmpty()) {
                throw new NoMuSigMediatorAvailableException();
            }
            var arbitrator = muSigTraderArbitrationService.selectArbitrator(makerId,
                    takerId,
                    offer.getId(),
                    mediator.map(UserProfile::getId));
            if (!DevMode.isDevMode() && arbitrator.isEmpty()) {
                throw new NoMuSigArbitratorAvailableException();
            }
            long marketPrice = marketPriceService.findMarketPrice(offer.getMarket())
                    .map(price -> price.getPriceQuote().getValue())
                    .orElseThrow(() -> new NoMarketPriceAvailableException(offer.getMarket()));

            var identity = takerIdentity.getIdentity();
            long takeOfferDate = clock.now();
            String tradeId = TradeV2.createId(offer.getId(), identity.getNetworkId().getId(), takeOfferDate);
            var command = new InitializeTrade(takeOfferDate,
                    identity,
                    offer,
                    takeOffer.getBaseAmount(),
                    takeOffer.getQuoteAmount(),
                    takeOffer.getPaymentMethodSpec(),
                    takeOffer.getAccount().getAccountPayload(),
                    mediator,
                    arbitrator,
                    marketPrice);
            return muSigTradeService.initializeTrade(tradeId, command)
                    .whenComplete((ignored, failure) -> {
                        if (failure == null) {
                            return;
                        }
                        var cause = failure instanceof CompletionException ? failure.getCause() : failure;
                        if (cause instanceof TradeRestrictedException ||
                                cause instanceof MuSigProtocolException protocolException &&
                                        protocolException.getCategory() == MuSigFailureCategory.INPUT_REJECTED) {
                            log.warn("Taking offer rejected for trade {}: {}",
                                    StringUtils.sanitizeForLog(tradeId), StringUtils.sanitizeForLog(cause.getMessage()));
                        } else {
                            log.warn("Taking offer failed for trade {}", StringUtils.sanitizeForLog(tradeId), failure);
                        }
                    })
                    .thenApply(ignored -> tradeId);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void validateUserProfile(String userProfileId) throws UserProfileBannedException, RateLimitExceededException {
        if (bannedUserService.isUserProfileBanned(userProfileId)) {
            throw new UserProfileBannedException(userProfileId);
        }
        if (bannedUserService.isRateLimitExceeding(userProfileId)) {
            throw new RateLimitExceededException(userProfileId);
        }
    }
}
