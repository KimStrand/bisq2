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

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Closed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Open;

import java.util.List;

final class MediationClosedEventHandler
        extends MuSigMediationHandler<Open, MediationClosedEvent, Closed> {

    MediationClosedEventHandler(MuSigProtocolContext context,
                                FlowSnapshot<Open, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(MediationClosedEvent event) {
        var message = event.getMessage();
        new MuSigV2MediationData.SignedResult(message.getMuSigMediationResult().orElseThrow(),
                message.getMediationResultSignature().orElseThrow()).verify(contract);
    }

    @Override
    protected Transition<Closed, MuSigV2MediationData, Event> process(MediationClosedEvent event) {
        var message = event.getMessage();
        var result = new MuSigV2MediationData.SignedResult(message.getMuSigMediationResult().orElseThrow(),
                message.getMediationResultSignature().orElseThrow());
        return new Transition<>(flow.to(new Closed(), data.withResult(result)), List.of());
    }
}
