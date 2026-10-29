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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;

import bisq.common.statemachine.FlowSnapshot;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData.MuSigV2TradeExecutionDataBuilder;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.taker.BaseSetupTradeMessage_D_Handler;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public final class SetupTradeMessage_D_Handler extends BaseSetupTradeMessage_D_Handler {
    public SetupTradeMessage_D_Handler(
            MuSigProtocolContext context,
            FlowSnapshot<NonceSharesAndPartialSignaturesCreated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SetupTradeMessage_D message) {
        checkArgument(message.getPartialSignatures().getSwapTxInputPartialSignature().isEmpty(),
                "We are the seller and expect that the swapTxInputPartialSignature is redacted (optional is empty)");
    }

    @Override
    protected void setPeerPartialSignatures(MuSigV2TradeExecutionDataBuilder builder,
                                            PartialSignatures partialSignatures) {
        builder.peerRedactedPartialSignatures(Optional.of(partialSignatures));
    }
}
