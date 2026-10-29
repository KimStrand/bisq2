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

package bisq.trade.mu_sig_v2.protocol.execution;

import bisq.common.processing.Effect;
import bisq.common.proto.UnresolvableProtobufException;

/** Starts observation after checkpointing; on restart the flow recreates it only while still needed. */
public record ObserveDepositTxConfirmation() implements Effect {
    @Override
    public bisq.trade.protobuf.ObserveDepositTxConfirmation.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.ObserveDepositTxConfirmation.newBuilder();
    }

    @Override
    public bisq.trade.protobuf.ObserveDepositTxConfirmation toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static ObserveDepositTxConfirmation fromProto(bisq.trade.protobuf.ObserveDepositTxConfirmation proto) throws UnresolvableProtobufException {
        try {
            return new ObserveDepositTxConfirmation();
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    @Override
    public boolean replayOnRestart() {
        return false;
    }
}
