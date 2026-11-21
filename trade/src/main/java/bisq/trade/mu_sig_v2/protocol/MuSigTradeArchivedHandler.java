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
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState.Historical;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;

import java.util.List;

/** Preserves all child records and committed outgoing work when moving a trade to history. */
final class MuSigTradeArchivedHandler<S extends MuSigTradeState>
        extends MuSigTransitionHandler<S, TradeArchivedEvent, Historical, MuSigV2TradeData> {
    MuSigTradeArchivedHandler(MuSigProtocolContext context, FlowSnapshot<S, MuSigV2TradeData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Historical, MuSigV2TradeData, Event> process(TradeArchivedEvent event) {
        return new Transition<>(flow.to(new Historical(), data), List.of());
    }
}
