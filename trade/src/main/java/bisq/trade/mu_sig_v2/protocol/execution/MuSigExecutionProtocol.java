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

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.Effect;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.FlowView;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Failed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Base of the four execution protocols, one per trade role. A role protocol contains only its rule table. This class
 * holds what all roles share: when the flow counts as failed, the two rules for the peer's acceptance of a mediation
 * result, the recovery after a restart and the read-only view for other flows.
 * <p>
 * Each rule names the state it starts from, the input it accepts, the handler which runs and the state it reaches.
 * The compiler checks a rule against its handler: the handler's source state, input type and target state must be
 * the ones which the rule names. The sealed state interface of a role restricts the states of its rules.
 * <p>
 * Inputs are the MuSig trade messages and events known from V1, plus the events which the mediation flows raise.
 */
public abstract class MuSigExecutionProtocol<S extends MuSigTradeExecutionState>
        extends MuSigProtocol<S, MuSigV2TradeExecutionData> {
    public static final String CLOSE_TRADE_TIMEOUT_ID = "closeTrade";
    public static final Duration CLOSE_TRADE_TIMEOUT = Duration.ofHours(24);

    @Override
    public boolean isFailed(S state) {
        return state.isFailed();
    }

    @Override
    protected boolean shouldDiscard(MuSigReadOnlyContext context,
                                    FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow,
                                    Event event) {
        // Once signed, an inconsistent decision must reach finalization verification and fail execution.
        return event instanceof MediationResultByPeerAcceptedEvent &&
                !(flow.state() instanceof CustomPayoutSigned) &&
                context.mediation()
                        .map(mediation -> mediation.data().myDecisionState() instanceof Rejected ||
                                mediation.data().myDecisionState() instanceof Failed).orElse(false);
    }

    @Override
    protected boolean isReady(MuSigReadOnlyContext context,
                              FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow,
                              Event event) {
        if (!(event instanceof MediationResultByPeerAcceptedEvent peer) ||
                !CustomPayoutFinalizationHandler.hasRecordedAgreement(context)) {
            return true;
        }
        // A peer-first mismatch keeps its payout data pending, as V1 prevents finalization in this case.
        // Missing local data still reaches the handler's integrity check.
        return flow.data().getMyCustomPayoutPsbt()
                .map(myPsbt -> myPsbt.getTxId().equals(peer.getPsbt().getTxId()))
                .orElse(true);
    }

    /** After a restart, a flow which still waits for the deposit confirmation observes the deposit again. */
    @Override
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow) {
        return flow.state() instanceof AccountPayloadReceived ? List.of(new ObserveDepositTxConfirmation()) : List.of();
    }

    public FlowView<MuSigTradeExecutionState, MuSigExecutionReadOnlyData> readOnlyView(
            FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow) {
        S state = flow.state();
        MuSigExecutionReadOnlyData data = new MuSigExecutionReadOnlyData() {
            @Override
            public Optional<String> myCustomPayoutTxId() {
                return flow.data().getMyCustomPayoutPsbt().map(psbt -> psbt.getTxId());
            }

            @Override
            public Optional<AccountPayload<?>> peerAccountPayload() {
                return flow.data().getPeerAccountPayload();
            }

            @Override
            public boolean canSignCustomPayout() {
                return rules().canHandle(state, MediationResultByMyselfAcceptedEvent.class);
            }
        };
        return new FlowView<>(state, data);
    }
}
