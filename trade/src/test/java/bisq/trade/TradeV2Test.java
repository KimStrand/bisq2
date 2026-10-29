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
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class TradeV2Test {
    @Test
    void idHelpersRemainCompatibleWithExistingTrades() {
        String offerId = "offer-id";
        String takerPubKeyHash = "taker-pub-key-hash";
        long takeOfferDate = 1_750_000_000_000L;

        assertEquals(TradeId.create(offerId, takerPubKeyHash),
                TradeV2.createId(offerId, takerPubKeyHash));
        assertEquals(TradeId.create(offerId, takerPubKeyHash, takeOfferDate),
                TradeV2.createId(offerId, takerPubKeyHash, takeOfferDate));
        assertEquals(TradeId.create(offerId, takerPubKeyHash, takeOfferDate),
                TradeV2.createId(offerId, takerPubKeyHash, Optional.of(takeOfferDate)));
        assertEquals(TradeId.create(offerId, takerPubKeyHash),
                TradeV2.createId(offerId, takerPubKeyHash, Optional.empty()));
    }

    @Test
    void differentOffersTakersAndAttemptsHaveDifferentIds() {
        String id = TradeV2.createId("offer", "taker", 100L);

        assertNotEquals(id, TradeV2.createId("another-offer", "taker", 100L));
        assertNotEquals(id, TradeV2.createId("offer", "another-taker", 100L));
        assertNotEquals(id, TradeV2.createId("offer", "taker", 101L));
    }
}
