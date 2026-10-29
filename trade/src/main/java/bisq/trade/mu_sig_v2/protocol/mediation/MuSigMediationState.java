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

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.trade.mu_sig_v2.protocol.MuSigState;

/** Case closure makes the result available; the two decisions continue independently. */
public sealed interface MuSigMediationState extends MuSigState, PersistableProto {
    record Init() implements MuSigMediationState {}
    record Requested() implements MuSigMediationState {}
    record Open() implements MuSigMediationState {}
    record Closed() implements MuSigMediationState {}
    record Failed() implements MuSigMediationState {}

    @Override
    default bisq.trade.protobuf.MuSigMediationState.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigMediationState.newBuilder();
        return switch (this) {
            case Init ignored -> builder.setInit(bisq.trade.protobuf.MuSigMediationState.Init.getDefaultInstance());
            case Requested ignored -> builder.setRequested(bisq.trade.protobuf.MuSigMediationState.Requested.getDefaultInstance());
            case Open ignored -> builder.setOpen(bisq.trade.protobuf.MuSigMediationState.Open.getDefaultInstance());
            case Closed ignored -> builder.setClosed(bisq.trade.protobuf.MuSigMediationState.Closed.getDefaultInstance());
            case Failed ignored -> builder.setFailed(bisq.trade.protobuf.MuSigMediationState.Failed.getDefaultInstance());
        };
    }

    @Override
    default bisq.trade.protobuf.MuSigMediationState toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    static MuSigMediationState fromProto(bisq.trade.protobuf.MuSigMediationState proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getMessageCase()) {
                case INIT -> new Init();
                case REQUESTED -> new Requested();
                case OPEN -> new Open();
                case CLOSED -> new Closed();
                case FAILED -> new Failed();
                case MESSAGE_NOT_SET -> throw new IllegalArgumentException("Missing or unknown mediation state");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
