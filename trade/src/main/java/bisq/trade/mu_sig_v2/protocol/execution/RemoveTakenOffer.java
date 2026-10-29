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

/**
 * Requests best-effort removal of the trade's offer after checkpointing.
 * The maker handler selects this effect using the setting; execution does not recheck it.
 */
public record RemoveTakenOffer() implements Effect {
    @Override
    public bisq.trade.protobuf.RemoveTakenOffer.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.RemoveTakenOffer.newBuilder();
    }

    @Override
    public bisq.trade.protobuf.RemoveTakenOffer toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static RemoveTakenOffer fromProto(bisq.trade.protobuf.RemoveTakenOffer proto) throws UnresolvableProtobufException {
        try {
            return new RemoveTakenOffer();
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
