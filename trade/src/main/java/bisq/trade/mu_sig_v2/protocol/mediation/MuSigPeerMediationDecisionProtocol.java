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
import bisq.common.statemachine.RuleTable;
import bisq.common.statemachine.StateMachineDefinition;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Accepted;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Failed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;

public final class MuSigPeerMediationDecisionProtocol
        extends StateMachineDefinition<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
        MuSigReadOnlyContext, MuSigProtocolContext> {
    private static final RuleTable<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<MuSigMediationDecisionState, MuSigV2MediationDecisionData,
                            Event, MuSigProtocolContext>builder()
                    .rule(Init.class,
                            MuSigCustomPayoutPsbtMessage.class,
                            MuSigCustomPayoutPsbtMessageHandler::new,
                            Accepted.class)

                    .rule(Init.class,
                            MuSigMediationResultRejectionMessage.class,
                            MuSigMediationResultRejectionMessageHandler::new,
                            Rejected.class)

                    .fromAny(MuSigProcessingFailedEvent.class,
                            MediationDecisionProcessingFailedHandler::new,
                            Failed.class)
                    .build();

    @Override
    public boolean isFailed(MuSigMediationDecisionState state) {
        return state instanceof Failed;
    }

    @Override
    public boolean canHandle(MuSigReadOnlyContext context,
                             FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> flow,
                             Event event) {
        return context.mediation().orElseThrow().state() instanceof MuSigMediationState.Closed &&
                super.canHandle(context, flow, event);
    }

    public static RuleTable<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
            MuSigProtocolContext> transitions() {
        return RULES;
    }

    @Override
    protected RuleTable<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
