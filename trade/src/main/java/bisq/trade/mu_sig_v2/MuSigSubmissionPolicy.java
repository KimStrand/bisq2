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
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertDataUtils;
import bisq.common.application.ApplicationVersion;
import bisq.common.platform.Version;
import bisq.common.processing.Input;
import bisq.network.identity.NetworkId;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRestrictedException;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.user.banned.UserProfileBannedException;

import java.util.Optional;

/** Application restrictions for new submissions, evaluated without reading or changing a trade. */
final class MuSigSubmissionPolicy {
    private final ServiceProvider serviceProvider;
    private final AppType appType;

    MuSigSubmissionPolicy(ServiceProvider serviceProvider, AppType appType) {
        this.serviceProvider = serviceProvider;
        this.appType = appType;
    }

    void check(Input input) {
        if (requiresTradingAllowed(input)) {
            verifyTradingAllowed();
        }
        incomingSender(input).ifPresent(sender -> {
            if (serviceProvider.getUserService().getBannedUserService().isUserProfileBanned(sender)) {
                throw new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED,
                        "Incoming MuSig message sender is banned",
                        new UserProfileBannedException(sender.getId()));
            }
        });
    }

    private boolean requiresTradingAllowed(Input input) {
        return input instanceof InitializeTrade ||
                input instanceof DepositConfirmationSkippedEvent ||
                input instanceof PaymentInitiatedEvent ||
                input instanceof PaymentReceiptConfirmedEvent ||
                input instanceof MediationResultAcceptedEvent ||
                input instanceof MuSigTradeMessage;
    }

    private Optional<NetworkId> incomingSender(Input input) {
        return switch (input) {
            case MuSigTradeMessage message -> Optional.of(message.getSender());
            case MediationOpenedEvent event -> Optional.of(event.getMessage().getSenderNetworkId());
            case MediationClosedEvent event -> Optional.of(event.getMessage().getSenderNetworkId());
            case MediationPaymentDetailsRequestedEvent event -> Optional.of(event.getMessage().getSenderNetworkId());
            default -> Optional.empty();
        };
    }

    private void verifyTradingAllowed() {
        var alerts = serviceProvider.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet();
        if (AuthorizedAlertDataUtils.isTradingHalted(alerts.stream(), appType)) {
            throw TradeRestrictedException.haltTrading();
        }
        var minVersion = AuthorizedAlertDataUtils.findMinRequiredVersionForTrading(alerts.stream(), appType);
        if (minVersion.isPresent() && !ApplicationVersion.getVersion().aboveOrEqual(new Version(minVersion.get()))) {
            throw TradeRestrictedException.minVersionRequired(minVersion.get());
        }
    }
}
