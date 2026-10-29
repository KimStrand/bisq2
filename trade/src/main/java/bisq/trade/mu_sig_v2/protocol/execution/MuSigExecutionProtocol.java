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
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.FlowView;
import bisq.common.statemachine.StateMachineDefinition;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Failed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;

import java.time.Duration;
import java.util.Optional;

public abstract class MuSigExecutionProtocol<S extends MuSigTradeExecutionState>
        extends StateMachineDefinition<S, MuSigV2TradeExecutionData, Event,
        MuSigReadOnlyContext, MuSigProtocolContext> {
    public static final String CLOSE_TRADE_TIMEOUT_ID = "closeTrade";
    public static final Duration CLOSE_TRADE_TIMEOUT = Duration.ofHours(24);

    @Override
    public boolean isFailed(S state) {
        return state instanceof MuSigTradeExecutionState.Failed || state instanceof MuSigTradeExecutionState.FailedAtPeer;
    }

    @Override
    protected boolean shouldDiscard(MuSigReadOnlyContext context,
                                    FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow,
                                    Event event,
                                    boolean command) {
        // Once signed, an inconsistent decision must reach finalization verification and fail execution.
        return event instanceof MediationResultByPeerAcceptedEvent &&
                !(flow.state() instanceof CustomPayoutSigned) &&
                context.mediation()
                        .map(mediation -> mediation.data().myDecisionState() instanceof Rejected ||
                                mediation.data().myDecisionState() instanceof Failed).orElse(false);
    }

    @Override
    public boolean canHandle(MuSigReadOnlyContext context,
                             FlowSnapshot<? extends S, MuSigV2TradeExecutionData> flow,
                             Event event) {
        if (!super.canHandle(context, flow, event)) {
            return false;
        }
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

    public static FlowView<MuSigTradeExecutionState, MuSigExecutionReadOnlyData> readOnlyView(
            FlowSnapshot<? extends MuSigTradeExecutionState, MuSigV2TradeExecutionData> flow) {
        var data = new MuSigExecutionReadOnlyData() {
            @Override
            public Optional<String> myCustomPayoutTxId() {
                return flow.data().getMyCustomPayoutPsbt().map(psbt -> psbt.getTxId());
            }

            @Override
            public Optional<AccountPayload<?>> peerAccountPayload() {
                return flow.data().getPeerAccountPayload();
            }
        };
        return new FlowView<>(flow.state(), data);
    }
}
