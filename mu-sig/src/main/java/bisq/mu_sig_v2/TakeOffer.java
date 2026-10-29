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
import bisq.account.payment_method.PaymentMethodSpec;
import bisq.common.monetary.Monetary;
import bisq.common.processing.Command;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.user.identity.UserIdentity;
import lombok.Getter;

/** Application command containing the taker's choices before a trade is created. */
@Getter
public final class TakeOffer implements Command {
    private final UserIdentity takerIdentity;
    private final MuSigOffer offer;
    private final Monetary baseAmount;
    private final Monetary quoteAmount;
    private final PaymentMethodSpec<?> paymentMethodSpec;
    private final Account<?, ?> account;

    public TakeOffer(UserIdentity takerIdentity,
                     MuSigOffer offer,
                     Monetary baseAmount,
                     Monetary quoteAmount,
                     PaymentMethodSpec<?> paymentMethodSpec,
                     Account<?, ?> account) {
        this.takerIdentity = takerIdentity;
        this.offer = offer;
        this.baseAmount = baseAmount;
        this.quoteAmount = quoteAmount;
        this.paymentMethodSpec = paymentMethodSpec;
        this.account = account;
    }
}
