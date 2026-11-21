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
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Active;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Failed;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;

import java.util.List;

/** Fails the parent without rewriting the state or failure details of any child. */
final class MuSigExecutionFailedHandler
        extends MuSigTransitionHandler<Active, ExecutionFailedEvent, Failed, MuSigV2TradeData> {
    MuSigExecutionFailedHandler(MuSigProtocolContext context, FlowSnapshot<Active, MuSigV2TradeData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Failed, MuSigV2TradeData, Event> process(ExecutionFailedEvent event) {
        return new Transition<>(flow.to(new Failed(), data), List.of());
    }
}
