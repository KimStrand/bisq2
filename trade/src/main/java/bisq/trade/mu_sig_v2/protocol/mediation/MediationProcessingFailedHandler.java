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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Failed;

import java.util.List;

/** Records a terminal flow failure without sending the fatal trade error message. */
final class MediationProcessingFailedHandler
        extends MuSigMediationHandler<MuSigMediationState, MuSigProcessingFailedEvent, Failed> {
    MediationProcessingFailedHandler(MuSigProtocolContext context,
                                     FlowSnapshot<MuSigMediationState, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Failed, MuSigV2MediationData, Event> process(MuSigProcessingFailedEvent event) {
        var failure = new MuSigV2FailureDetails(event.getTradeProtocolFailure(),
                event.getErrorMessage(),
                event.getStackTrace());
        return new Transition<>(flow.to(new Failed(), data.withFailure(failure)),
                List.of(),
                List.of(new LogEntry.Shared(new LogEntry.Text("Mediation case processing failed"))));
    }
}
