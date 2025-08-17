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

package bisq.mu_sig;

import bisq.account.AccountService;
import bisq.account.accounts.Account;
import bisq.account.accounts.AccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.market_price.MarketPriceService;
import bisq.bonded_roles.market_price.NoMarketPriceAvailableException;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.chat.ChatService;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.chat.priv.LeavePrivateChatManager;
import bisq.common.application.DevMode;
import bisq.common.application.LifecycleService;
import bisq.common.market.Market;
import bisq.common.monetary.Monetary;
import bisq.common.observable.Observable;
import bisq.common.observable.Pin;
import bisq.common.observable.collection.ReadOnlyObservableSet;
import bisq.common.util.CompletableFutureUtils;
import bisq.contract.ContractService;
import bisq.identity.Identity;
import bisq.identity.IdentityService;
import bisq.network.NetworkService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.services.data.BroadcastResult;
import bisq.offer.Direction;
import bisq.offer.OfferService;
import bisq.offer.amount.spec.AmountSpec;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.offer.options.OfferOption;
import bisq.offer.price.spec.PriceSpec;
import bisq.persistence.PersistenceService;
import bisq.presentation.notifications.SystemNotificationService;
import bisq.security.SecurityService;
import bisq.settings.SettingsService;
import bisq.support.SupportService;
import bisq.support.mediation.MediationRequestService;
import bisq.support.mediation.NoMediatorAvailableException;
import bisq.trade.TradeService;
import bisq.trade.mu_sig.MuSigTrade;
import bisq.trade.mu_sig.MuSigTradeService;
import bisq.trade.mu_sig.protocol.MuSigProtocol;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import bisq.user.banned.RateLimitExceededException;
import bisq.user.banned.UserProfileBannedException;
import bisq.user.identity.UserIdentity;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import static bisq.common.util.CompletableFutureUtils.logOnFailure;
import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class MuSigService extends LifecycleService {
    private final PersistenceService persistenceService;
    private final SecurityService securityService;
    private final NetworkService networkService;
    private final IdentityService identityService;
    private final BondedRolesService bondedRolesService;
    private final AccountService accountService;
    private final OfferService offerService;
    private final ContractService contractService;
    private final UserService userService;
    private final ChatService chatService;
    private final LeavePrivateChatManager leavePrivateChatManager;
    private final SettingsService settingsService;
    private final SupportService supportService;
    private final SystemNotificationService systemNotificationService;
    private final TradeService tradeService;
    private final UserIdentityService userIdentityService;
    private final MarketPriceService marketPriceService;
    private final AlertService alertService;
    private final MuSigOfferService muSigOfferService;
    private final BannedUserService bannedUserService;

    @Getter
    private final Observable<Boolean> muSigActivated = new Observable<>(false);
    private final MediationRequestService mediationRequestService;
    private final MuSigTradeService muSigTradeService;
    private final MuSigOpenTradeChannelService muSigOpenTradeChannelService;
    private final UserProfileService userProfileService;
    private Pin muSigActivatedPin;

    public MuSigService(PersistenceService persistenceService,
                        SecurityService securityService,
                        NetworkService networkService,
                        IdentityService identityService,
                        BondedRolesService bondedRolesService,
                        AccountService accountService,
                        OfferService offerService,
                        ContractService contractService,
                        UserService userService,
                        ChatService chatService,
                        SettingsService settingsService,
                        SupportService supportService,
                        SystemNotificationService systemNotificationService,
                        TradeService tradeService) {
        this.persistenceService = persistenceService;
        this.securityService = securityService;
        this.networkService = networkService;
        this.identityService = identityService;
        this.bondedRolesService = bondedRolesService;
        marketPriceService = bondedRolesService.getMarketPriceService();
        this.accountService = accountService;
        this.offerService = offerService;
        muSigOfferService = offerService.getMuSigOfferService();
        this.contractService = contractService;
        this.userService = userService;
        this.chatService = chatService;
        leavePrivateChatManager = chatService.getLeavePrivateChatManager();
        this.settingsService = settingsService;
        this.supportService = supportService;
        this.systemNotificationService = systemNotificationService;
        this.tradeService = tradeService;
        userProfileService = userService.getUserProfileService();
        userIdentityService = userService.getUserIdentityService();
        mediationRequestService = supportService.getMediationRequestService();
        alertService = bondedRolesService.getAlertService();
        bannedUserService = userService.getBannedUserService();
        muSigTradeService = tradeService.getMuSigTradeService();
        muSigOpenTradeChannelService = chatService.getMuSigOpenTradeChannelService();
    }


    /* --------------------------------------------------------------------- */
    // Service
    /* --------------------------------------------------------------------- */

    public CompletableFuture<Boolean> initialize() {
        log.info("initialize");
        muSigActivatedPin = settingsService.getMuSigActivated().addObserver(activated -> {
            muSigActivated.set(DevMode.isDevMode() && activated);
            if (muSigActivated.get()) {
                activate();
            } else {
                if (getState() == State.ACTIVATED || getState() == State.ACTIVATING_FAILED) {
                    deactivate();
                }
            }
        });
        return CompletableFuture.completedFuture(true);
    }


    public CompletableFuture<Boolean> shutdown() {
        log.info("shutdown");
        if (muSigActivatedPin != null) {
            muSigActivatedPin.unbind();
            muSigActivatedPin = null;
        }
        return CompletableFuture.completedFuture(true);
    }


    /* --------------------------------------------------------------------- */
    // LifecycleService
    /* --------------------------------------------------------------------- */

    @Override
    protected CompletableFuture<Boolean> doActivate() {
        return CompletableFutureUtils.allOf(logOnFailure(offerService.initializeMuSigOfferService()),
                        logOnFailure(tradeService.initializeMuSigTradeService())
                )
                .thenApply(list -> list.stream().allMatch(result -> result));
    }

    @Override
    public CompletableFuture<Boolean> doDeactivate() {
        return CompletableFutureUtils.allOf(logOnFailure(offerService.shutdownMuSigOfferService()),
                        logOnFailure(tradeService.shutdownMuSigTradeService())
                )
                .thenApply(list -> list.stream().allMatch(result -> result));
    }


    /* --------------------------------------------------------------------- */
    // Offer API
    /* --------------------------------------------------------------------- */

    public MuSigOffer createAndGetMuSigOffer(String id,
                                             Direction direction,
                                             Market market,
                                             AmountSpec amountSpec,
                                             PriceSpec priceSpec,
                                             List<PaymentMethod<?>> fiatPaymentMethods,
                                             List<? extends OfferOption> offerOptions) {
        checkArgument(isActivated());
        NetworkId makerNetworkId = userIdentityService.getSelectedUserIdentity().getUserProfile().getNetworkId();
        return new MuSigOffer(id,
                makerNetworkId,
                direction,
                market,
                amountSpec,
                priceSpec,
                fiatPaymentMethods,
                offerOptions,
                MuSigProtocol.VERSION);
    }

    public CompletableFuture<BroadcastResult> publishOffer(MuSigOffer muSigOffer) throws UserProfileBannedException, RateLimitExceededException {
        checkArgument(isActivated());
        validateUserProfile(muSigOffer.getMakersUserProfileId());
        return muSigOfferService.publishAndAddOffer(muSigOffer);
    }

    public CompletableFuture<BroadcastResult> removeOffer(MuSigOffer muSigOffer) throws UserProfileBannedException, RateLimitExceededException {
        checkArgument(isActivated());
        validateUserProfile(muSigOffer.getMakersUserProfileId());
        return muSigOfferService.removeOffer(muSigOffer);
    }

    public void republishMyOffers() {
        checkArgument(isActivated());
        muSigOfferService.republishMyOffers();
    }


    /* --------------------------------------------------------------------- */
    // Trade API
    /* --------------------------------------------------------------------- */

    public MuSigProtocol takerCreatesProtocol(UserIdentity takerIdentity,
                                              MuSigOffer muSigOffer,
                                              Monetary takersBaseSideAmount,
                                              Monetary takersQuoteSideAmount,
                                              PaymentMethodSpec<?> fiatPaymentMethodSpec,
                                              Account<?, ?> takersAccount)
            throws UserProfileBannedException, NoMediatorAvailableException,
            NoMarketPriceAvailableException, RateLimitExceededException {

        checkArgument(isActivated());
        String makersUserProfileId = muSigOffer.getMakersUserProfileId();
        validateUserProfile(makersUserProfileId);
        validateUserProfile(takerIdentity.getId());

        Optional<UserProfile> mediator = mediationRequestService.selectMediator(makersUserProfileId,
                takerIdentity.getId(),
                muSigOffer.getId());
        if (!DevMode.isDevMode() && mediator.isEmpty()) {
            throw new NoMediatorAvailableException();
        }

        return takerCreatesProtocol(takerIdentity,
                muSigOffer,
                takersBaseSideAmount,
                takersQuoteSideAmount,
                fiatPaymentMethodSpec,
                takersAccount.getAccountPayload(),
                mediator);
    }

    private MuSigProtocol takerCreatesProtocol(UserIdentity takerIdentity,
                                               MuSigOffer muSigOffer,
                                               Monetary takersBaseSideAmount,
                                               Monetary takersQuoteSideAmount,
                                               PaymentMethodSpec<?> paymentMethodSpec,
                                               AccountPayload<?> takersAccountPayload,
                                               Optional<UserProfile> mediator
                                         ) throws NoMarketPriceAvailableException {

        log.info("Selected mediator for trade {}: {}", muSigOffer.getShortId(), mediator.map(UserProfile::getUserName).orElse("N/A"));
        Optional<Long> marketPrice = marketPriceService.findMarketPrice(muSigOffer.getMarket())
                .map(price -> price.getPriceQuote().getValue());
        if (marketPrice.isEmpty()) {
            throw new NoMarketPriceAvailableException(muSigOffer.getMarket());
        }

        log.info("Market price for trade {}: {}", muSigOffer.getShortId(), marketPrice.get());
        return takerCreatesProtocol(takerIdentity,
                muSigOffer,
                takersBaseSideAmount,
                takersQuoteSideAmount,
                paymentMethodSpec,
                takersAccountPayload,
                mediator,
                marketPrice.get());
    }

    private MuSigProtocol takerCreatesProtocol(UserIdentity takerIdentity,
                                               MuSigOffer muSigOffer,
                                               Monetary takersBaseSideAmount,
                                               Monetary takersQuoteSideAmount,
                                               PaymentMethodSpec<?> paymentMethodSpec,
                                               AccountPayload<?> takersAccountPayload,
                                               Optional<UserProfile> mediator,
                                               long marketPrice) {
        return muSigTradeService.takerCreatesProtocol(takerIdentity.getIdentity(),
                muSigOffer,
                takersBaseSideAmount,
                takersQuoteSideAmount,
                paymentMethodSpec,
                takersAccountPayload,
                mediator,
                muSigOffer.getPriceSpec(),
                marketPrice);
    }

    public void createMuSigOpenTradeChannel(MuSigTrade muSigTrade, UserIdentity takerIdentity) {
        checkArgument(isActivated());
        String tradeId = muSigTrade.getId();
        Optional<MuSigOpenTradeChannel> channel = muSigOpenTradeChannelService.findChannelByTradeId(tradeId);
        if (channel.isEmpty()) {
            Optional<UserProfile> makersUserProfile = userProfileService.findUserProfile(muSigTrade.getOffer().getMakersUserProfileId());
            checkArgument(makersUserProfile.isPresent(), "Makers user profile is not present");
            muSigOpenTradeChannelService.traderCreatesChannel(tradeId,
                    takerIdentity,
                    makersUserProfile.get(),
                    Optional.empty());
        } else {
            log.warn("When taking an offer it is expected that no MuSigOpenTradeChannel for that trade ID exist yet. " +
                    "In case of failed take offer attempts though it might be that there is a channel present.");
        }
    }

    public void takeOffer(MuSigTrade muSigTrade) {
        checkArgument(isActivated());
        // Starts the trade protocol
        muSigTradeService.takeOffer(muSigTrade);
    }

    public void closeTrade(MuSigTrade muSigTrade, MuSigOpenTradeChannel channel) {
        checkArgument(isActivated());
        muSigTradeService.closeTrade(muSigTrade);
        leavePrivateChatManager.leaveChannel(channel);
    }


    /* --------------------------------------------------------------------- */
    // Markets API
    /* --------------------------------------------------------------------- */

    // TODO: sort and store custom markets


    /* --------------------------------------------------------------------- */
    // Misc
    /* --------------------------------------------------------------------- */

    public Set<MuSigOffer> getOffers() {
        return muSigOfferService.getMuSigOfferbookService().getOffers();
    }

    public ReadOnlyObservableSet<MuSigOffer> getObservableOffers() {
        return muSigOfferService.getMuSigOfferbookService().getObservableOffers();
    }

    public void validateUserProfile(String userProfileId) throws UserProfileBannedException, RateLimitExceededException {
        if (bannedUserService.isUserProfileBanned(userProfileId)) {
            throw new UserProfileBannedException(userProfileId);
        }

        if (bannedUserService.isRateLimitExceeding(userProfileId)) {
            throw new RateLimitExceededException(userProfileId);
        }
    }

    public boolean isAccountDataBanned(AccountPayload<?> accountPayload) {
        // TODO: Implement account data validation logic to check against banned payment information
        return false;
    }

    //todo
    private void validateUserProfile(MuSigOffer muSigOffer) {
        Optional<Identity> activeIdentity = identityService.findActiveIdentity(muSigOffer.getMakerNetworkId());
        if (activeIdentity.isEmpty()) {
            throw new RuntimeException("No identity found for networkNodeId used in the muSigOffer");
        }

        String profileId = activeIdentity.get().getId();
        if (bannedUserService.isRateLimitExceeding(profileId)) {
            throw new RuntimeException("Rate limit was exceeding");
        }
        if (bannedUserService.isUserProfileBanned(profileId)) {
            throw new RuntimeException("User profile is banned");
        }
    }
}
