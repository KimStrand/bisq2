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

package bisq.trade;

import bisq.contract.TradeId;
import lombok.Getter;

import java.util.Optional;

/** Shared identity for V2 trade documents, independent of their protocol state. */
@Getter
public abstract class TradeV2 {
    public static String createId(String offerId, String takerPubKeyHash) {
        return TradeId.create(offerId, takerPubKeyHash);
    }

    public static String createId(String offerId, String takerPubKeyHash, long takeOfferDate) {
        return TradeId.create(offerId, takerPubKeyHash, takeOfferDate);
    }

    public static String createId(String offerId, String takerPubKeyHash, Optional<Long> takeOfferDate) {
        return takeOfferDate.map(date -> TradeId.create(offerId, takerPubKeyHash, date))
                .orElseGet(() -> TradeId.create(offerId, takerPubKeyHash));
    }

    private final String id;

    protected TradeV2(String id) {
        this.id = id;
    }

    public String getShortId() {
        return id.substring(0, 8);
    }
}
