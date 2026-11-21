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
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.contract.ContractService;
import bisq.support.dispute.ChatMessagePruning;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Open;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Requested;
import bisq.user.profile.UserProfile;

import java.util.ArrayList;
import java.util.List;

final class MuSigMediationOpenedMessageHandler<S extends MuSigMediationState>
        extends MuSigMediationHandler<S, MuSigMediationOpenedMessage, Open> {

    MuSigMediationOpenedMessageHandler(MuSigProtocolContext context,
                                FlowSnapshot<S, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Open, MuSigV2MediationData, Event> process(MuSigMediationOpenedMessage message) {
        UserProfile mediator = contract.getMediator().orElseThrow();
        List<Effect> effects = new ArrayList<>();
        effects.add(new ActivateMediationChat());
        if (flow.state() instanceof Init) {
            byte[] contractHash = ContractService.getContractHash(contract);
            MuSigDisputeCaseDataMessage report = ChatMessagePruning.createWithMaybePrunedMessages(
                    serviceProvider.getChatService()
                            .getMuSigOpenTradeChannelService()
                            .findChannelByTradeId(tradeId)
                            .map(channel -> new ArrayList<>(channel.getChatMessages()))
                            .orElseGet(ArrayList::new),
                    tradeId,
                    messages -> new MuSigDisputeCaseDataMessage(tradeId,
                            context.myNetworkId(),
                            contractHash,
                            messages));
            effects.add(new SendMessage<>(report, mediator.getNetworkId()));
        }
        String key = flow.state() instanceof Requested
                ? "authorizedRole.mediator.message.toRequester"
                : "authorizedRole.mediator.message.toNonRequester";
        ProtocolLogMessage.Local notice = new ProtocolLogMessage.Local(new ProtocolLogMessage.Localized(key, List.of()),
                mediator.getId());
        effects.add(notice);
        return new Transition<>(flow.to(new Open(), data),
                effects);
    }
}
