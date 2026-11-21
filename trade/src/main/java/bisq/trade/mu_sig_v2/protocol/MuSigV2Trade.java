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

import bisq.account.accounts.AccountPayload;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.FlowSnapshot;
import bisq.contract.Party;
import bisq.contract.TradeId;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigBuyerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigBuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;
import lombok.Getter;

import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The complete local trade document and parent flow snapshot. Each flow stores its own state and data.
 * Only the owning processing lane replaces this document; processing history and pending work belong to its record.
 */
@Getter
public final class MuSigV2Trade<S extends MuSigTradeExecutionState> implements PersistableProto {
    private final String id;
    private final MuSigTradeState state;
    private final MuSigV2TradeData data;
    private final MuSigV2TradeExecution<? extends S> execution;
    private final Optional<MuSigV2Mediation<? extends MuSigMediationState>> mediation;

    public MuSigV2Trade(String id,
                        MuSigTradeState state,
                        MuSigV2TradeData data,
                        MuSigV2TradeExecution<? extends S> execution,
                        Optional<MuSigV2Mediation<? extends MuSigMediationState>> mediation) {
        this.id = id;
        this.state = state;
        this.data = data;
        this.execution = execution;
        this.mediation = mediation;
        verify();
    }

    /** Creates the complete initial document using the current protocol version and initial flow states. */
    public static MuSigV2Trade<?> create(MuSigContract contract,
                                         Identity myIdentity,
                                         TradeRole myRole,
                                         AccountPayload<?> myAccountPayload) {
        MuSigV2TradeData data = new MuSigV2TradeData(contract,
                myIdentity,
                myRole,
                MuSigProtocol.VERSION,
                myAccountPayload);
        return new MuSigV2Trade<>(TradeId.fromContract(contract),
                new MuSigTradeState.Active(),
                data,
                MuSigV2TradeExecution.initial(),
                contract.getMediator().map(ignored -> MuSigV2Mediation.initial()));
    }

    @Override
    public bisq.trade.protobuf.MuSigV2Trade.Builder getBuilder(boolean serializeForHash) {
        bisq.trade.protobuf.MuSigV2Trade.Builder builder = bisq.trade.protobuf.MuSigV2Trade.newBuilder()
                .setId(getId())
                .setState(state.toProto(serializeForHash))
                .setData(data.toProto(serializeForHash))
                .setExecution(execution.toProto(serializeForHash));
        mediation.ifPresent(flow -> builder.setMediation(flow.toProto(serializeForHash)));
        return builder;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2Trade toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2Trade<?> fromProto(bisq.trade.protobuf.MuSigV2Trade proto) throws UnresolvableProtobufException {
        try {
            checkArgument(!proto.getId().isBlank() && proto.hasState() && proto.hasData()
                    && proto.hasExecution(), "Incomplete trade document");
            MuSigV2TradeData data = MuSigV2TradeData.fromProto(proto.getData());
            MuSigV2TradeExecution<MuSigTradeExecutionState> execution =
                    MuSigV2TradeExecution.fromProto(proto.getExecution());
            return new MuSigV2Trade<>(proto.getId(),
                    MuSigTradeState.fromProto(proto.getState()),
                    data,
                    execution,
                    proto.hasMediation() ? Optional.of(MuSigV2Mediation.fromProto(proto.getMediation())) : Optional.empty());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    // Each role has its own execution protocol and state family; MuSigTradeProcessor selects the protocol by role.
    private void verify() {
        S executionState = execution.state();
        boolean belongsToRole = switch (data.myRole()) {
            case SELLER_AS_TAKER -> executionState instanceof MuSigSellerAsTakerState;
            case BUYER_AS_TAKER -> executionState instanceof MuSigBuyerAsTakerState;
            case BUYER_AS_MAKER -> executionState instanceof MuSigBuyerAsMakerState;
            case SELLER_AS_MAKER -> executionState instanceof MuSigSellerAsMakerState;
        };
        checkArgument(belongsToRole, "Execution state does not match the trade role");
    }

    /** Applies only the parent's state and data, preserving the currently installed children. */
    public MuSigV2Trade<S> withTradeFlow(FlowSnapshot<? extends MuSigTradeState, MuSigV2TradeData> next) {
        return new MuSigV2Trade<>(getId(), next.state(), next.data(), execution, mediation);
    }

    public MuSigV2Trade<S> withExecution(FlowSnapshot<? extends S, MuSigV2TradeExecutionData> next) {
        return new MuSigV2Trade<>(getId(), state, data, MuSigV2TradeExecution.from(next), mediation);
    }

    /** Applies a case transition; both decision snapshots are preserved by the mediation writer. */
    public MuSigV2Trade<S> withMediation(FlowSnapshot<? extends MuSigMediationState, MuSigV2MediationData> next) {
        return new MuSigV2Trade<>(getId(),
                state,
                data,
                execution,
                Optional.of(mediation.orElseThrow().withCase(next)));
    }

    public MuSigV2Trade<S> withMyMediationDecision(
            FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> next) {
        return new MuSigV2Trade<>(getId(),
                state,
                data,
                execution,
                Optional.of(mediation.orElseThrow().withMyDecision(next)));
    }

    public MuSigV2Trade<S> withPeerMediationDecision(
            FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> next) {
        return new MuSigV2Trade<>(getId(),
                state,
                data,
                execution,
                Optional.of(mediation.orElseThrow().withPeerDecision(next)));
    }

    public String getShortId() {
        return id.substring(0, 8);
    }

    public MuSigContract getContract() {
        return data.contract();
    }

    public Identity getMyIdentity() {
        return data.myIdentity();
    }

    public TradeRole getMyRole() {
        return data.myRole();
    }

    /** The other party of the contract. */
    public Party getPeerParty() {
        return peerParty(getContract(), getMyRole());
    }

    /** The party of the contract which does not have the given role. */
    public static Party peerParty(MuSigContract contract, TradeRole myRole) {
        return myRole.isMaker() ? contract.getTaker() : contract.getMaker();
    }

    /** The party of the contract which has the given role. */
    public static Party myParty(MuSigContract contract, TradeRole myRole) {
        return myRole.isMaker() ? contract.getMaker() : contract.getTaker();
    }

    public String getProtocolVersion() {
        return data.protocolVersion();
    }

    public AccountPayload<?> getMyAccountPayload() {
        return data.myAccountPayload();
    }

    /** The flows of this trade. The three mediation flows exist only if the trade has a mediation case. */
    public Set<Flow> getFlows() {
        return mediation.isPresent()
                ? Set.of(Flow.TRADE,
                        Flow.EXECUTION,
                        Flow.MEDIATION,
                        Flow.MY_MEDIATION_DECISION,
                        Flow.PEER_MEDIATION_DECISION)
                : Set.of(Flow.TRADE, Flow.EXECUTION);
    }
}
