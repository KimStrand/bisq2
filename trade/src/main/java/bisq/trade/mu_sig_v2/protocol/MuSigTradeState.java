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

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;

/** The trade's overall state, stored directly in its document. */
public sealed interface MuSigTradeState extends MuSigState, PersistableProto {
    record Active() implements MuSigTradeState {}

    record Failed() implements MuSigTradeState {}

    record Historical() implements MuSigTradeState {}

    @Override
    default bisq.trade.protobuf.MuSigTradeState.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigTradeState.newBuilder();
        return switch (this) {
            case Active ignored -> builder.setActive(bisq.trade.protobuf.MuSigTradeState.Active.getDefaultInstance());
            case Failed ignored -> builder.setFailed(bisq.trade.protobuf.MuSigTradeState.Failed.getDefaultInstance());
            case Historical ignored -> builder.setHistorical(bisq.trade.protobuf.MuSigTradeState.Historical.getDefaultInstance());
        };
    }

    @Override
    default bisq.trade.protobuf.MuSigTradeState toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    static MuSigTradeState fromProto(bisq.trade.protobuf.MuSigTradeState proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getMessageCase()) {
                case ACTIVE -> new Active();
                case FAILED -> new Failed();
                case HISTORICAL -> new Historical();
                case MESSAGE_NOT_SET -> throw new IllegalArgumentException("Missing or unknown trade state");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
