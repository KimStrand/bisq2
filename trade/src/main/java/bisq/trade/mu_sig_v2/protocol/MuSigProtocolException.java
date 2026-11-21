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

package bisq.trade.mu_sig_v2.protocol;

import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import lombok.Getter;

/** Reports the category to the caller while retaining V1's concrete failure reason and cause. */
@Getter
public final class MuSigProtocolException extends TradeProtocolException {
    private final MuSigFailureCategory category;

    public MuSigProtocolException(MuSigFailureCategory category, String message, Throwable cause) {
        super(message, reason(cause), cause);
        this.category = category;
    }

    static MuSigProtocolException processingFailure(RuntimeException failure) {
        return failure instanceof MuSigProtocolException classified ? classified :
                new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED, failure.getMessage(), failure);
    }

    private static TradeProtocolFailure reason(Throwable cause) {
        return cause instanceof TradeProtocolException failure ?
                failure.getTradeProtocolFailure() : TradeProtocolFailure.UNKNOWN;
    }
}
