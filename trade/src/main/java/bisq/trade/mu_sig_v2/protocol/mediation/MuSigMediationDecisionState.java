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

/** One party's decision on the single proposal. Accepted, Rejected and Failed have no outgoing transitions. */
public sealed interface MuSigMediationDecisionState extends MuSigState, PersistableProto {
    record Init() implements MuSigMediationDecisionState {}
    /** Commitment to the proposal; local signing is a separate execution transition and can still fail. */
    record Accepted() implements MuSigMediationDecisionState {}
    record Rejected() implements MuSigMediationDecisionState {}
    record Failed() implements MuSigMediationDecisionState {}

    @Override
    default bisq.trade.protobuf.MuSigMediationDecisionState.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigMediationDecisionState.newBuilder();
        return switch (this) {
            case Init ignored -> builder.setInit(bisq.trade.protobuf.MuSigMediationDecisionState.Init.getDefaultInstance());
            case Accepted ignored -> builder.setAccepted(bisq.trade.protobuf.MuSigMediationDecisionState.Accepted.getDefaultInstance());
            case Rejected ignored -> builder.setRejected(bisq.trade.protobuf.MuSigMediationDecisionState.Rejected.getDefaultInstance());
            case Failed ignored -> builder.setFailed(bisq.trade.protobuf.MuSigMediationDecisionState.Failed.getDefaultInstance());
        };
    }

    @Override
    default bisq.trade.protobuf.MuSigMediationDecisionState toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    static MuSigMediationDecisionState fromProto(bisq.trade.protobuf.MuSigMediationDecisionState proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getMessageCase()) {
                case INIT -> new Init();
                case ACCEPTED -> new Accepted();
                case REJECTED -> new Rejected();
                case FAILED -> new Failed();
                case MESSAGE_NOT_SET -> throw new IllegalArgumentException("Missing or unknown mediation decision state");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
