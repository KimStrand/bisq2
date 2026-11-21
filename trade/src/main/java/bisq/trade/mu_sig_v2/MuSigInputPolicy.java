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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.mu_sig_v2;

import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertDataUtils;
import bisq.common.application.ApplicationVersion;
import bisq.common.application.DevMode;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.platform.Version;
import bisq.common.processing.Input;
import bisq.common.processing.lane.InputPolicy;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRestrictedException;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.user.banned.UserProfileBannedException;

import java.util.Optional;

/**
 * Application restrictions for inputs, evaluated without reading or changing a trade. A trade restriction is a halt
 * of trading or a minimum version for trading. It refuses an input which arrives, and it pauses an input which a
 * lane keeps (trade-restrictions.md and input-processing.md).
 */
final class MuSigInputPolicy implements InputPolicy {
    private final ServiceProvider serviceProvider;
    private final AppType appType;

    MuSigInputPolicy(ServiceProvider serviceProvider, AppType appType) {
        this.serviceProvider = serviceProvider;
        this.appType = appType;
    }

    @Override
    public void check(Input input) {
        if (input instanceof DepositConfirmationSkippedEvent && !DevMode.isDevMode()) {
            throw new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED,
                    "Skipping deposit confirmation is only available in development mode",
                    new IllegalStateException("Not in development mode"));
        }
        if (requiresTradingAllowed(input)) {
            findTradeRestriction().ifPresent(restriction -> {
                throw restriction;
            });
        }
        incomingSender(input).ifPresent(sender -> {
            if (serviceProvider.getUserService().getBannedUserService().isUserProfileBanned(sender)) {
                throw new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED,
                        "Incoming MuSig message sender is banned",
                        new UserProfileBannedException(sender.getId()));
            }
        });
    }

    /**
     * True while a trade restriction applies and the lane keeps this input for the trade protocol. The lane then
     * does not start its handler, and the input stays saved until the restriction ends. A restriction exists to
     * stop the handlers of the protocol, so an input which arrived or became due before it must not run either.
     */
    @Override
    public boolean isPaused(Input input) {
        return waitsForTradingAllowed(input) && findTradeRestriction().isPresent();
    }

    // The router refuses these inputs when they arrive during a trade restriction.
    private boolean requiresTradingAllowed(Input input) {
        return input instanceof InitializeTrade ||
                input instanceof DepositConfirmationSkippedEvent ||
                input instanceof PaymentInitiatedEvent ||
                input instanceof PaymentReceiptConfirmedEvent ||
                input instanceof MediationResultAcceptedEvent ||
                input instanceof MuSigTradeMessage;
    }

    // The lane keeps these inputs: a message of the peer, and each reaction to a restricted input, which the lane
    // saves as an input of its own. The reactions are the taker's first step, the close timers, and the signing
    // and the finalization of a mediated payout. A command of the user is not in this list, because the lane
    // does not keep a command.
    private boolean waitsForTradingAllowed(Input input) {
        return input instanceof MuSigTradeMessage ||
                input instanceof MuSigTakeOfferEvent ||
                input instanceof SellersCloseTradeTimeoutEvent ||
                input instanceof BuyersCloseTradeTimeoutEvent ||
                input instanceof MediationResultByMyselfAcceptedEvent ||
                input instanceof MediationResultByPeerAcceptedEvent;
    }

    private Optional<NetworkId> incomingSender(Input input) {
        return switch (input) {
            case MuSigTradeMessage message -> Optional.of(message.getSender());
            case MuSigMediationOpenedMessage message -> Optional.of(message.getSenderNetworkId());
            case MuSigMediationClosedMessage message -> Optional.of(message.getSenderNetworkId());
            case MuSigDisputeCasePaymentDetailsRequest request -> Optional.of(request.getSenderNetworkId());
            default -> Optional.empty();
        };
    }

    private Optional<TradeRestrictedException> findTradeRestriction() {
        ObservableSet<AuthorizedAlertData> alerts =
                serviceProvider.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet();
        if (AuthorizedAlertDataUtils.isTradingHalted(alerts.stream(), appType)) {
            return Optional.of(TradeRestrictedException.haltTrading());
        }
        Optional<String> minVersion =
                AuthorizedAlertDataUtils.findMinRequiredVersionForTrading(alerts.stream(), appType);
        if (minVersion.isPresent() && !ApplicationVersion.getVersion().aboveOrEqual(new Version(minVersion.get()))) {
            return Optional.of(TradeRestrictedException.minVersionRequired(minVersion.get()));
        }
        return Optional.empty();
    }
}
