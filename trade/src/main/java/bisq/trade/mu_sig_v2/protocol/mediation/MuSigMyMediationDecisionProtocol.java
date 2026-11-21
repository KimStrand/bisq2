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
import bisq.common.statemachine.FlowView;
import bisq.common.statemachine.RuleTable;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Accepted;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Failed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;
import static bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol.allowsRequestOrRejection;
import static bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol.checkInitializedAndUnresolved;
import static com.google.common.base.Preconditions.checkState;

/** The local trader's decision about the mediation result: accept or reject. Either one is final. */
public final class MuSigMyMediationDecisionProtocol
        extends MuSigProtocol<MuSigMediationDecisionState, MuSigV2MediationDecisionData> {
    private static final RuleTable<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<MuSigMediationDecisionState, MuSigV2MediationDecisionData,
                            Event, MuSigProtocolContext>builder()
                    .rule(Init.class,
                            MediationResultAcceptedEvent.class,
                            MediationResultAcceptedEventHandler::new,
                            Accepted.class)

                    .rule(Init.class,
                            MediationResultRejectedEvent.class,
                            MediationResultRejectedEventHandler::new,
                            Rejected.class)

                    .fromAny(MuSigProcessingFailedEvent.class,
                            MediationDecisionProcessingFailedHandler::new,
                            Failed.class)
                    .build();

    @Override
    public boolean isFailed(MuSigMediationDecisionState state) {
        return state instanceof Failed;
    }

    /**
     * All conditions for the local trader's two commands. This flow receives no peer messages, so nothing waits for
     * the case to close: a command which comes too early is rejected here with its reason. The peer's decisions wait
     * in {@link MuSigPeerMediationDecisionProtocol#isReady}.
     */
    @Override
    public void admit(MuSigReadOnlyContext context,
                      FlowSnapshot<? extends MuSigMediationDecisionState, MuSigV2MediationDecisionData> flow,
                      Event event) {
        if (!(event instanceof MediationResultAcceptedEvent) && !(event instanceof MediationResultRejectedEvent)) {
            return;
        }
        MuSigTradeExecutionState executionState = context.execution().state();
        checkInitializedAndUnresolved(executionState);
        FlowView<MuSigMediationState, MuSigMediationReadOnlyData> mediation = context.mediation().orElseThrow();
        checkState(mediation.state() instanceof MuSigMediationState.Closed && mediation.data().signedResult().isPresent(),
                "A signed mediation result is not available yet");
        checkState(mediation.data().signedResult().orElseThrow().result().getMediationPayoutDistributionType() !=
                        MediationPayoutDistributionType.NO_PAYOUT,
                "The mediator did not propose a payout");
        if (event instanceof MediationResultAcceptedEvent) {
            checkState(!(mediation.data().peerDecisionState() instanceof Rejected), "The peer rejected this proposal");
            checkState(!(mediation.data().peerDecisionState() instanceof Failed), "The peer decision flow failed");
            checkState(context.execution().data().canSignCustomPayout(),
                    "Execution cannot commit to a custom payout in this state");
        } else {
            checkState(allowsRequestOrRejection(executionState),
                    "Mediation result rejection requires the deposit transaction to be available");
        }
    }

    @Override
    public RuleTable<MuSigMediationDecisionState, MuSigV2MediationDecisionData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
