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
import bisq.contract.ContractService;
import bisq.support.dispute.ChatMessagePruning;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Open;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Requested;

import java.util.ArrayList;
import java.util.List;

final class MediationOpenedEventHandler<S extends MuSigMediationState>
        extends MuSigMediationHandler<S, MediationOpenedEvent, Open> {

    MediationOpenedEventHandler(MuSigProtocolContext context,
                                FlowSnapshot<S, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<Open, MuSigV2MediationData, Event> process(MediationOpenedEvent event) {
        var mediator = contract.getMediator().orElseThrow();
        List<Effect> effects = new ArrayList<>();
        effects.add(new ActivateMediationChat());
        if (flow.state() instanceof Init) {
            byte[] contractHash = ContractService.getContractHash(contract);
            var report = ChatMessagePruning.createWithMaybePrunedMessages(
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
            effects.add(new Effect.SendMessage<>(report, mediator.getNetworkId()));
        }
        String key = flow.state() instanceof Requested
                ? "authorizedRole.mediator.message.toRequester"
                : "authorizedRole.mediator.message.toNonRequester";
        var notice = new LogEntry.Local(new LogEntry.Localized(key, List.of()),
                mediator.getId());
        return new Transition<>(flow.to(new Open(), data), effects, List.of(notice));
    }
}
