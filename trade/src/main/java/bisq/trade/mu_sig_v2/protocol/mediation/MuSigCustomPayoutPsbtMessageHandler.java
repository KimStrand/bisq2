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
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Accepted;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Init;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
final class MuSigCustomPayoutPsbtMessageHandler
        extends MuSigMediationDecisionHandler<Init, MuSigCustomPayoutPsbtMessage, Accepted> {

    MuSigCustomPayoutPsbtMessageHandler(MuSigProtocolContext context,
                                        FlowSnapshot<Init, MuSigV2MediationDecisionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(MuSigCustomPayoutPsbtMessage message) {
        verifyPeerDecision(message, message.getMediationResultHash());
        if (context.execution().data().myCustomPayoutTxId().filter(txId -> !txId.equals(message.getTxId())).isPresent()) {
            log.warn("Trade {}: Ignoring MuSigCustomPayoutPsbtMessage {} because the transaction ID does not match the local custom payout",
                    tradeId,
                    message.getId());
            ignoreInput();
        }
    }

    @Override
    protected Transition<Accepted, MuSigV2MediationDecisionData, Event> process(MuSigCustomPayoutPsbtMessage message) {
        var psbt = new PeerCustomPayoutPsbt(message.getTxId(), message.getPsbt());
        return new Transition<>(flow.to(new Accepted(), data),
                List.of(),
                List.of(),
                List.of(new MediationResultByPeerAcceptedEvent(psbt)));
    }
}
