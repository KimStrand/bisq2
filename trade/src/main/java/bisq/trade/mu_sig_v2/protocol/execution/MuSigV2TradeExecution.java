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

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.FlowSnapshot;
import static com.google.common.base.Preconditions.checkArgument;

/** The flow's current state and business data, persisted together. */
public record MuSigV2TradeExecution<S extends MuSigTradeExecutionState>(S state,
                                                                        MuSigV2TradeExecutionData data)
        implements FlowSnapshot<S, MuSigV2TradeExecutionData>, PersistableProto {
    public static MuSigV2TradeExecution<MuSigTradeExecutionState.Init> initial() {
        return new MuSigV2TradeExecution<>(new MuSigTradeExecutionState.Init(),
                MuSigV2TradeExecutionData.builder().build());
    }

    public static <S extends MuSigTradeExecutionState> MuSigV2TradeExecution<S> from(
            FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow) {
        return new MuSigV2TradeExecution<>(flow.state(), flow.data());
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeExecution.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigV2TradeExecution.newBuilder()
                .setState(state.toProto(serializeForHash))
                .setData(data.toProto(serializeForHash));
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeExecution toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2TradeExecution<MuSigTradeExecutionState> fromProto(bisq.trade.protobuf.MuSigV2TradeExecution proto) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasState() && proto.hasData(), "Incomplete MuSigV2TradeExecution snapshot");
            return new MuSigV2TradeExecution<>(MuSigTradeExecutionState.fromProto(proto.getState()),
                    MuSigV2TradeExecutionData.fromProto(proto.getData()));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
