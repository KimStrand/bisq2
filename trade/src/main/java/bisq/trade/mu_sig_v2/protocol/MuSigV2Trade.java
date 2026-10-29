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
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * The complete local trade document and parent flow snapshot. Each flow stores its own state and data.
 * Only the owning processing lane replaces this document; processing history and pending work belong to its record.
 */
public final class MuSigV2Trade<S extends MuSigTradeExecutionState> extends TradeV2
        implements FlowSnapshot<MuSigTradeState, MuSigV2TradeData>, PersistableProto {
    private final MuSigTradeState state;
    private final MuSigV2TradeData data;
    private final MuSigV2TradeExecution<? extends S> execution;
    private final Optional<MuSigV2Mediation<? extends MuSigMediationState>> mediation;

    public MuSigV2Trade(String id,
                        MuSigTradeState state,
                        MuSigV2TradeData data,
                        MuSigV2TradeExecution<? extends S> execution,
                        Optional<MuSigV2Mediation<? extends MuSigMediationState>> mediation) {
        super(id);
        this.state = state;
        this.data = data;
        this.execution = execution;
        this.mediation = mediation;
    }

    /** Creates the complete initial document using the current protocol version and initial flow states. */
    public static MuSigV2Trade<?> create(MuSigContract contract,
                                         Identity myIdentity,
                                         TradeRole myRole,
                                         AccountPayload<?> myAccountPayload) {
        String id = createId(contract.getOffer().getId(),
                contract.getTaker().getNetworkId().getId(),
                contract.getTakeOfferDate());
        var data = new MuSigV2TradeData(contract,
                myIdentity,
                myRole,
                MuSigProtocol.VERSION,
                myAccountPayload);
        return new MuSigV2Trade<>(id,
                new MuSigTradeState.Active(),
                data,
                MuSigV2TradeExecution.initial(),
                contract.getMediator().map(ignored -> MuSigV2Mediation.initial()));
    }

    @Override
    public MuSigTradeState state() {
        return state;
    }

    @Override
    public MuSigV2TradeData data() {
        return data;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2Trade.Builder getBuilder(boolean serializeForHash) {
        requireExecutionState(data.myRole(), execution.state());
        var builder = bisq.trade.protobuf.MuSigV2Trade.newBuilder()
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
            var data = MuSigV2TradeData.fromProto(proto.getData());
            var execution = MuSigV2TradeExecution.fromProto(proto.getExecution());
            requireExecutionState(data.myRole(), execution.state());
            return new MuSigV2Trade<>(proto.getId(),
                    MuSigTradeState.fromProto(proto.getState()),
                    data,
                    execution,
                    proto.hasMediation() ? Optional.of(MuSigV2Mediation.fromProto(proto.getMediation())) : Optional.empty());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    private static void requireExecutionState(TradeRole role, MuSigTradeExecutionState state) {
        boolean valid = switch (role) {
            case SELLER_AS_TAKER -> state instanceof SellerAsTakerState;
            case BUYER_AS_TAKER -> state instanceof BuyerAsTakerState;
            case BUYER_AS_MAKER -> state instanceof BuyerAsMakerState;
            case SELLER_AS_MAKER -> state instanceof SellerAsMakerState;
        };
        checkArgument(valid, "Execution state does not match the trade role");
    }

    /** Applies only the parent's state and data, preserving the currently installed children. */
    public MuSigV2Trade<S> withProtocol(FlowSnapshot<? extends MuSigTradeState, MuSigV2TradeData> next) {
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

    public MuSigV2Trade<S> withState(MuSigTradeState state) {
        return new MuSigV2Trade<>(getId(), state, data, execution, mediation);
    }

    public MuSigTradeState getState() {
        return state;
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

    public String getProtocolVersion() {
        return data.protocolVersion();
    }

    public AccountPayload<?> getMyAccountPayload() {
        return data.myAccountPayload();
    }

    public MuSigV2TradeExecution<? extends S> getExecution() {
        return execution;
    }

    public Optional<MuSigV2Mediation<? extends MuSigMediationState>> getMediation() {
        return mediation;
    }
}
