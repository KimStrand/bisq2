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

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable;
import bisq.common.statemachine.StateMachineDefinition;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Active;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Failed;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Historical;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import static com.google.common.base.Preconditions.checkState;

/**
 * Parent of execution and mediation. Only ACTIVE enables children; FAILED still permits archiving.
 * Successful execution closure leaves the parent active until the user archives the trade.
 */
public final class MuSigProtocol
        extends StateMachineDefinition<MuSigTradeState, MuSigV2TradeData, Event,
        MuSigReadOnlyContext, MuSigProtocolContext> {
    public static final String VERSION = "1.0.0";

    private final RuleTable<MuSigTradeState, MuSigV2TradeData, Event,
            MuSigProtocolContext> rules =
            RuleTable.<MuSigTradeState, MuSigV2TradeData, Event, MuSigProtocolContext>builder()
                    .rule(Active.class,
                            ExecutionFailedEvent.class,
                            MuSigExecutionFailedHandler::new,
                            Failed.class)

                    .rule(Active.class,
                            TradeArchivedEvent.class,
                            MuSigTradeArchivedHandler::new,
                            Historical.class)

                    .rule(Failed.class,
                            TradeArchivedEvent.class,
                            MuSigTradeArchivedHandler::new,
                            Historical.class)
                    .build();

    @Override
    public boolean isFailed(MuSigTradeState state) {
        return state instanceof Failed;
    }

    @Override
    public boolean isProcessingEnabled(MuSigTradeState state) {
        return !(state instanceof Historical);
    }

    @Override
    public boolean isChildProcessingEnabled(MuSigTradeState state) {
        return state instanceof Active;
    }

    @Override
    public void admit(MuSigReadOnlyContext context,
                      FlowSnapshot<? extends MuSigTradeState, MuSigV2TradeData> flow,
                      Event event) {
        if (event instanceof TradeArchivedEvent && flow.state() instanceof Active) {
            var executionState = context.execution().state();
            checkState(executionState instanceof TradeClosed || executionState instanceof CustomPayoutClosedTrade ||
                            executionState instanceof SellerForceClosedTrade,
                    "Only a completed or failed trade can be archived");
        }
    }

    @Override
    protected RuleTable<MuSigTradeState, MuSigV2TradeData, Event,
            MuSigProtocolContext> rules() {
        return rules;
    }

    public enum Flow { TRADE, EXECUTION, MEDIATION, MY_MEDIATION_DECISION, PEER_MEDIATION_DECISION }
}
