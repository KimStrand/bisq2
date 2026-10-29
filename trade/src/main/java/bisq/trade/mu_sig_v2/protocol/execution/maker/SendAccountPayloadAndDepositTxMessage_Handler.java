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

package bisq.trade.mu_sig_v2.protocol.execution.maker;

import bisq.common.data.ByteArray;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.handler.MuSigContractVerifier;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.RemoveTakenOffer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class SendAccountPayloadAndDepositTxMessage_Handler
        extends MuSigTransitionHandler<PartialSignaturesCreatedAndDepositTxSigned, SendAccountPayloadAndDepositTxMessage,
        AccountPayloadReceived, MuSigV2TradeExecutionData> {

    public SendAccountPayloadAndDepositTxMessage_Handler(
            MuSigProtocolContext context,
            FlowSnapshot<PartialSignaturesCreatedAndDepositTxSigned, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SendAccountPayloadAndDepositTxMessage message) {
        MuSigContractVerifier.verifyPeerAccountPayload(context.peerParty(),
                context.offerId(),
                message.getAccountPayload());
    }

    @Override
    protected Transition<AccountPayloadReceived, MuSigV2TradeExecutionData, Event> process(
            SendAccountPayloadAndDepositTxMessage message) {
        var outgoing = new SendAccountPayloadMessage(StringUtils.createUid(),
                tradeId,
                context.protocolVersion(),
                context.myNetworkId(),
                context.peerNetworkId(),
                context.myAccountPayload());
        List<Effect> effects = new ArrayList<>();
        effects.add(new ObserveDepositTxConfirmation());
        if (serviceProvider.getSettingsService().getCloseMyOfferWhenTaken().get()) {
            effects.add(new RemoveTakenOffer());
        }
        effects.add(new Effect.SendMessage<>(outgoing, context.peerNetworkId()));
        return new Transition<>(flow.to(new AccountPayloadReceived(), data.toBuilder()
                .peerAccountPayload(Optional.of(message.getAccountPayload()))
                .peerDepositTx(Optional.of(new ByteArray(message.getDepositTx().getBytes().clone())))
                .build()),
                effects,
                List.of(new LogEntry.Shared(new LogEntry.Text(
                        "Maker received the peers account payload and deposit transaction.\n"
                                + "Maker starts listening for blockchain confirmation and sends his account payload."))));
    }
}
