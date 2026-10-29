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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Rejected;

import java.util.ArrayList;
import java.util.List;

final class MediationResultRejectedEventHandler
        extends MuSigMediationDecisionHandler<Init, MediationResultRejectedEvent, Rejected> {

    MediationResultRejectedEventHandler(MuSigProtocolContext context,
                                        FlowSnapshot<Init, MuSigV2MediationDecisionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Rejected, MuSigV2MediationDecisionData, Event> process(MediationResultRejectedEvent event) {
        var message = new MuSigMediationResultRejectionMessage(StringUtils.createUid(),
                tradeId,
                context.protocolVersion(),
                context.myNetworkId(),
                context.peerNetworkId(),
                MuSigMediationResultService.getMediationResultHash(payoutResult()));
        List<LogEntry> logs = new ArrayList<>();
        serviceProvider.getChatService().getMuSigOpenTradeChannelService().findChannelByTradeId(tradeId)
                .ifPresent(channel -> logs.add(new LogEntry.Shared(new LogEntry.Localized(
                        "muSig.mediation.result.rejected.tradeLogMessage",
                        List.of(channel.getMyUserIdentity().getUserName())))));
        return new Transition<>(flow.to(new Rejected(), data),
                List.of(new Effect.SendMessage<>(message, context.peerNetworkId())),
                logs);
    }
}
