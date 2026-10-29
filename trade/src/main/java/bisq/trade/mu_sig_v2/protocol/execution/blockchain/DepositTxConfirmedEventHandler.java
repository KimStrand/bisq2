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

package bisq.trade.mu_sig_v2.protocol.execution.blockchain;

import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/** The legacy handler records nothing here; the stage exists to gate what may follow. */
@Slf4j
public final class DepositTxConfirmedEventHandler
        extends MuSigTransitionHandler<AccountPayloadReceived, DepositTxConfirmedEvent, DepositTxConfirmed,
        MuSigV2TradeExecutionData> {

    public DepositTxConfirmedEventHandler(MuSigProtocolContext context,
                                          FlowSnapshot<AccountPayloadReceived, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<DepositTxConfirmed, MuSigV2TradeExecutionData, Event> process(DepositTxConfirmedEvent event) {
        String role = context.myRole().isBuyer() ? "Buyer" : "Seller";
        return new Transition<>(flow.to(new DepositTxConfirmed(), data),
                List.of(),
                List.of(new LogEntry.Shared(new LogEntry.Text(role + " saw deposit transaction confirmed"))));
    }
}
