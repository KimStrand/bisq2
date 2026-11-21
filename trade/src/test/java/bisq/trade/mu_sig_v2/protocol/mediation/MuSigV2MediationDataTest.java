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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.common.data.ByteArray;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class MuSigV2MediationDataTest {
    private final MuSigMediationResult result = new MuSigMediationResult(new byte[20],
            MediationResultReason.OTHER,
            MediationPayoutDistributionType.CUSTOM_PAYOUT,
            Optional.of(100_000L),
            Optional.of(100_000L),
            Optional.empty(),
            Optional.empty());

    @Test
    void aSignedResultKeepsItsSignatureWhenTheCallerChangesItsArray() {
        byte[] signature = {1, 2, 3};
        var signedResult = new MuSigV2MediationData.SignedResult(result, new ByteArray(signature));

        signature[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, signedResult.signature().getBytes());
    }

    @Test
    void aReaderCannotChangeTheSignatureOfASignedResult() {
        var signedResult = new MuSigV2MediationData.SignedResult(result, new ByteArray(new byte[]{1, 2, 3}));

        signedResult.signature().getBytes()[0] = 9;

        assertArrayEquals(new byte[]{1, 2, 3}, signedResult.signature().getBytes());
    }

    @Test
    void signedResultsWithTheSameSignatureBytesAreEqual() {
        var first = new MuSigV2MediationData.SignedResult(result, new ByteArray(new byte[]{1, 2, 3}));
        var second = new MuSigV2MediationData.SignedResult(result, new ByteArray(new byte[]{1, 2, 3}));

        assertEquals(first, second);
        assertEquals(first.hashCode(), second.hashCode());
    }
}
