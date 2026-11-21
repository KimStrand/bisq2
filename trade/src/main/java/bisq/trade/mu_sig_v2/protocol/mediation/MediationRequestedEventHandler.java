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

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeMessage;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.ChatMessagePruning;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Requested;
import bisq.user.profile.UserProfile;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

final class MediationRequestedEventHandler
        extends MuSigMediationHandler<Init, MediationRequestedEvent, Requested> {

    MediationRequestedEventHandler(MuSigProtocolContext context,
                                   FlowSnapshot<Init, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Requested, MuSigV2MediationData, Event> process(MediationRequestedEvent event) {
        NetworkId mediator = contract.getMediator().orElseThrow().getNetworkId();
        MuSigOpenTradeChannel channel = serviceProvider.getChatService()
                .getMuSigOpenTradeChannelService()
                .findChannelByTradeId(tradeId)
                .orElseThrow();
        // The trade chat holds both profiles since the trade started. The profile service can lose the profile of a
        // peer who stays offline, because a profile is network data which expires.
        UserProfile requester = channel.getMyUserIdentity().getUserProfile();
        UserProfile peer = channel.getPeer();
        // The mediator ignores a request which carries a message of another trade, channel or sender. The chat can
        // hold such a message, from the peer or from a dispute agent, so the request carries only the messages of the
        // two traders of this trade (rule 6 of docs/specifications/chat/private-channel-membership.md).
        String channelIdOfTrade = MuSigOpenTradeChannel.createId(tradeId);
        ArrayList<MuSigOpenTradeMessage> messagesOfTrade = channel.getChatMessages().stream()
                .filter(message -> message.getTradeId().equals(tradeId) &&
                        message.getChannelId().equals(channelIdOfTrade) &&
                        (message.getSenderUserProfile().getId().equals(requester.getId()) ||
                                message.getSenderUserProfile().getId().equals(peer.getId())))
                .collect(Collectors.toCollection(ArrayList::new));
        MuSigMediationRequest request = ChatMessagePruning.createWithMaybePrunedMessages(
                messagesOfTrade,
                tradeId,
                messages -> new MuSigMediationRequest(tradeId, contract, requester, peer, messages, mediator));
        return new Transition<>(flow.to(new Requested(), data),
                List.of(new ActivateMediationChat(), new SendMessage<>(request, mediator),
                        new ProtocolLogMessage.Shared(
                                new ProtocolLogMessage.Localized("muSig.mediation.requester.tradeLogMessage",
                        List.of(requester.getUserName())))));
    }
}
