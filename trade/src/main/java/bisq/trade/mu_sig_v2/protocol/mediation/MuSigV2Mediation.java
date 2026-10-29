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
import bisq.common.statemachine.FlowSnapshot;
import static com.google.common.base.Preconditions.checkArgument;

/** The mediation case's state and business data, persisted together with both child decision snapshots. */
public record MuSigV2Mediation<S extends MuSigMediationState>(
        S state,
        MuSigV2MediationData data,
        MuSigV2MediationDecision<? extends MuSigMediationDecisionState> myDecision,
        MuSigV2MediationDecision<? extends MuSigMediationDecisionState> peerDecision)
        implements FlowSnapshot<S, MuSigV2MediationData>, PersistableProto {
    public static MuSigV2Mediation<MuSigMediationState.Init> initial() {
        return new MuSigV2Mediation<>(new MuSigMediationState.Init(),
                MuSigV2MediationData.initial(),
                MuSigV2MediationDecision.initial(),
                MuSigV2MediationDecision.initial());
    }

    /** Applies only case state and data; a parent transition cannot replace decision snapshots. */
    public <T extends MuSigMediationState> MuSigV2Mediation<T> withCase(
            FlowSnapshot<? extends T, MuSigV2MediationData> next) {
        return new MuSigV2Mediation<>(next.state(), next.data(), myDecision, peerDecision);
    }

    public MuSigV2Mediation<S> withMyDecision(
            FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> next) {
        return new MuSigV2Mediation<>(state, data, MuSigV2MediationDecision.from(next), peerDecision);
    }

    public MuSigV2Mediation<S> withPeerDecision(
            FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> next) {
        return new MuSigV2Mediation<>(state, data, myDecision, MuSigV2MediationDecision.from(next));
    }

    @Override
    public bisq.trade.protobuf.MuSigV2Mediation.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigV2Mediation.newBuilder()
                .setState(state.toProto(serializeForHash))
                .setData(data.toProto(serializeForHash))
                .setMyDecision(myDecision.toProto(serializeForHash))
                .setPeerDecision(peerDecision.toProto(serializeForHash));
    }

    @Override
    public bisq.trade.protobuf.MuSigV2Mediation toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2Mediation<MuSigMediationState> fromProto(bisq.trade.protobuf.MuSigV2Mediation proto) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasState() && proto.hasData() && proto.hasMyDecision() && proto.hasPeerDecision(),
                    "Incomplete MuSigV2Mediation snapshot");
            return new MuSigV2Mediation<>(MuSigMediationState.fromProto(proto.getState()),
                    MuSigV2MediationData.fromProto(proto.getData()),
                    MuSigV2MediationDecision.fromProto(proto.getMyDecision()),
                    MuSigV2MediationDecision.fromProto(proto.getPeerDecision()));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
