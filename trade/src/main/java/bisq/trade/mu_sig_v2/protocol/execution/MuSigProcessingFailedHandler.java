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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;

import java.util.List;
import java.util.Optional;

/** Records a local failure and notifies the peer with the existing V1 error message. */
public final class MuSigProcessingFailedHandler<S extends MuSigTradeExecutionState>
        extends MuSigTransitionHandler<S, MuSigProcessingFailedEvent, Failed, MuSigV2TradeExecutionData> {
    public MuSigProcessingFailedHandler(MuSigProtocolContext context, FlowSnapshot<S, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Failed, MuSigV2TradeExecutionData, Event> process(MuSigProcessingFailedEvent event) {
        var nextData = data.toBuilder()
                .myFailure(Optional.of(new MuSigV2FailureDetails(event.getTradeProtocolFailure(),
                        event.getErrorMessage(),
                        event.getStackTrace())))
                .build();
        var message = new MuSigReportErrorMessage(StringUtils.createUid(),
                tradeId,
                context.protocolVersion(),
                context.myNetworkId(),
                context.peerNetworkId(),
                StringUtils.truncate(event.getErrorMessage(), MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE),
                StringUtils.truncate(event.getStackTrace(), MuSigReportErrorMessage.MAX_LENGTH_STACKTRACE),
                event.getTradeProtocolFailure());
        return new Transition<>(flow.to(new Failed(), nextData),
                List.of(new CancelScheduledEvent(MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID),
                        new Effect.SendMessage<>(message, context.peerNetworkId())),
                List.of(new LogEntry.Shared(new LogEntry.Text(
                        "An error occurred. We send the error report to our peer."))),
                List.of(new ExecutionFailedEvent()));
    }
}
