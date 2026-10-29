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
import bisq.support.dispute.ChatMessagePruning;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Requested;

import java.util.ArrayList;
import java.util.List;

final class MediationRequestedEventHandler
        extends MuSigMediationHandler<Init, MediationRequestedEvent, Requested> {

    MediationRequestedEventHandler(MuSigProtocolContext context,
                                   FlowSnapshot<Init, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Requested, MuSigV2MediationData, Event> process(MediationRequestedEvent event) {
        var mediator = contract.getMediator().orElseThrow().getNetworkId();
        var profiles = serviceProvider.getUserService().getUserProfileService();
        var requester = profiles.findUserProfile(context.myIdentity().getId()).orElseThrow();
        var peer = profiles.findUserProfile(context.peerNetworkId().getId()).orElseThrow();
        var channel = serviceProvider.getChatService()
                .getMuSigOpenTradeChannelService()
                .findChannelByTradeId(tradeId)
                .orElseThrow();
        var request = ChatMessagePruning.createWithMaybePrunedMessages(
                new ArrayList<>(channel.getChatMessages()),
                tradeId,
                messages -> new MuSigMediationRequest(tradeId, contract, requester, peer, messages, mediator));
        return new Transition<>(flow.to(new Requested(), data),
                List.of(new ActivateMediationChat(), new Effect.SendMessage<>(request, mediator)),
                List.of(new LogEntry.Shared(new LogEntry.Localized("muSig.mediation.requester.tradeLogMessage",
                        List.of(requester.getUserName())))));
    }
}
