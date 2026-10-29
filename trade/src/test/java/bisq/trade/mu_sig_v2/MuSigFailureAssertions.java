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

import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import org.junit.jupiter.api.function.Executable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class MuSigFailureAssertions {
    private MuSigFailureAssertions() {}

    public static MuSigProtocolException assertRejected(Executable action) {
        return assertCategory(MuSigFailureCategory.INPUT_REJECTED, action);
    }

    public static MuSigProtocolException assertOperationFailure(Executable action) {
        return assertCategory(MuSigFailureCategory.OPERATION_FAILED, action);
    }

    public static MuSigProtocolException assertProtocolFailure(Executable action) {
        return assertCategory(MuSigFailureCategory.PROTOCOL_FAILED, action);
    }

    private static MuSigProtocolException assertCategory(MuSigFailureCategory category, Executable action) {
        var failure = assertThrows(MuSigProtocolException.class, action);
        assertEquals(category, failure.getCategory());
        return failure;
    }
}
