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

package bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker;

import bisq.common.statemachine.FlowSnapshot;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData.MuSigV2TradeExecutionDataBuilder;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.maker.BaseSetupTradeMessage_C_Handler;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public final class SetupTradeMessage_C_Handler extends BaseSetupTradeMessage_C_Handler {
    public SetupTradeMessage_C_Handler(
            MuSigProtocolContext context,
            FlowSnapshot<TradeInitializedAndNonceSharesCreated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SetupTradeMessage_C message) {
        checkArgument(message.getPartialSignatures().getSwapTxInputPartialSignature().isPresent(),
                "We are the buyer and expect that the swapTxInputPartialSignature is not redacted (optional is present)");
    }

    @Override
    protected PartialSignatures getPartialSignatures(PartialSignaturesMessage myPartialSignatures) {
        return PartialSignatures.from(myPartialSignatures, true);
    }

    @Override
    protected void setPeerPartialSignatures(MuSigV2TradeExecutionDataBuilder builder,
                                            PartialSignatures partialSignatures) {
        builder.peerPartialSignatures(Optional.of(partialSignatures));
    }
}
