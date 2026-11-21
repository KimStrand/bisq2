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

package bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker;

import bisq.common.statemachine.FlowSnapshot;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.taker.BaseSetupTradeMessage_B_Handler;

/** The buyer withholds the swap partial signature until payment is initiated. */
public final class SetupTradeMessage_B_Handler extends BaseSetupTradeMessage_B_Handler {
    public SetupTradeMessage_B_Handler(MuSigProtocolContext context,
                                       FlowSnapshot<TradeInitialized, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected PartialSignatures getPartialSignatures(PartialSignaturesMessage myPartialSignatures) {
        return PartialSignatures.from(myPartialSignatures, true);
    }
}
