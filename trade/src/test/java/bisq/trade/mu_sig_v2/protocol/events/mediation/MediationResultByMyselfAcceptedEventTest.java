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

package bisq.trade.mu_sig_v2.protocol.events.mediation;

import bisq.common.data.ByteArray;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class MediationResultByMyselfAcceptedEventTest {
    @Test
    void hashCannotBeChangedFromOutside() {
        byte[] hash = new byte[20];
        MediationResultByMyselfAcceptedEvent event = new MediationResultByMyselfAcceptedEvent(new ByteArray(hash));

        hash[0] = 1;
        event.getMediationResultHash().getBytes()[1] = 1;

        assertArrayEquals(new byte[20], event.getMediationResultHash().getBytes());
    }
}
