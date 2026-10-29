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

import bisq.common.processing.LogEntry;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;

import java.util.List;
import java.util.Optional;

/** Records a validated peer report without sending another error report back. */
public final class MuSigReportErrorMessageHandler<S extends MuSigTradeExecutionState>
        extends MuSigTransitionHandler<S, MuSigReportErrorMessage, FailedAtPeer, MuSigV2TradeExecutionData> {
    public MuSigReportErrorMessageHandler(MuSigProtocolContext context,
                                          FlowSnapshot<S, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<FailedAtPeer, MuSigV2TradeExecutionData, Event> process(MuSigReportErrorMessage event) {
        var nextData = data.toBuilder()
                .peerFailure(Optional.of(new MuSigV2FailureDetails(event.getTradeProtocolFailure(),
                        event.getErrorMessage(),
                        event.getStackTrace())))
                .build();
        return new Transition<>(flow.to(new FailedAtPeer(), nextData),
                List.of(new CancelScheduledEvent(MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID)),
                List.of(new LogEntry.Shared(new LogEntry.Text("We received an error report from our peer."))),
                List.of(new ExecutionFailedEvent()));
    }
}
