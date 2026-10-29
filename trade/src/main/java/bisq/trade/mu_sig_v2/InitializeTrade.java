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
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.common.monetary.Monetary;
import bisq.common.processing.Command;
import bisq.identity.Identity;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.user.profile.UserProfile;
import lombok.Getter;

import java.util.Optional;

/** Initializes a taker trade through the lane's preparation, initial save, setup and first protocol step. */
@Getter
public final class InitializeTrade implements Command {
    private final long takeOfferDate;
    private final Identity takerIdentity;
    private final MuSigOffer offer;
    private final Monetary baseAmount;
    private final Monetary quoteAmount;
    private final PaymentMethodSpec<?> paymentMethodSpec;
    private final AccountPayload<?> accountPayload;
    private final Optional<UserProfile> mediator;
    private final Optional<UserProfile> arbitrator;
    private final long marketPrice;

    public InitializeTrade(long takeOfferDate,
                           Identity takerIdentity,
                           MuSigOffer offer,
                           Monetary baseAmount,
                           Monetary quoteAmount,
                           PaymentMethodSpec<?> paymentMethodSpec,
                           AccountPayload<?> accountPayload,
                           Optional<UserProfile> mediator,
                           Optional<UserProfile> arbitrator,
                           long marketPrice) {
        this.takeOfferDate = takeOfferDate;
        this.takerIdentity = takerIdentity;
        this.offer = offer;
        this.baseAmount = baseAmount;
        this.quoteAmount = quoteAmount;
        this.paymentMethodSpec = paymentMethodSpec;
        this.accountPayload = accountPayload;
        this.mediator = mediator;
        this.arbitrator = arbitrator;
        this.marketPrice = marketPrice;
    }
}
