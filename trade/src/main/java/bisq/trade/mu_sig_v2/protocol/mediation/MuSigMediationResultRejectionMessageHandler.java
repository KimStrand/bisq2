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
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;

import java.util.List;

final class MuSigMediationResultRejectionMessageHandler
        extends MuSigMediationDecisionHandler<Init, MuSigMediationResultRejectionMessage, Rejected> {

    MuSigMediationResultRejectionMessageHandler(MuSigProtocolContext context,
                                                FlowSnapshot<Init, MuSigV2MediationDecisionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(MuSigMediationResultRejectionMessage message) {
        verifyPeerDecision(message, message.getMediationResultHash());
    }

    @Override
    protected Transition<Rejected, MuSigV2MediationDecisionData, Event> process(MuSigMediationResultRejectionMessage message) {
        return new Transition<>(flow.to(new Rejected(), data),
                List.of());
    }
}
