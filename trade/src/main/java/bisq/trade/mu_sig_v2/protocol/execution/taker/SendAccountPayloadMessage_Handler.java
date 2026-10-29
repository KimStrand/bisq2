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

package bisq.trade.mu_sig_v2.protocol.execution.taker;

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.handler.MuSigContractVerifier;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * The peer's payment account must match the salted hash the contract committed to. Watching the
 * chain starts here only because that is where the legacy handler starts it.
 */
@Slf4j
public final class SendAccountPayloadMessage_Handler
        extends MuSigTransitionHandler<DepositTxSignedAndPublished, SendAccountPayloadMessage, AccountPayloadReceived,
        MuSigV2TradeExecutionData> {

    public SendAccountPayloadMessage_Handler(MuSigProtocolContext context,
                                             FlowSnapshot<DepositTxSignedAndPublished, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SendAccountPayloadMessage message) {
        MuSigContractVerifier.verifyPeerAccountPayload(context.peerParty(),
                context.offerId(),
                message.getAccountPayload());
    }

    @Override
    protected Transition<AccountPayloadReceived, MuSigV2TradeExecutionData, Event> process(SendAccountPayloadMessage message) {
        AccountPayload<?> peerAccountPayload = message.getAccountPayload();
        return new Transition<>(flow.to(new AccountPayloadReceived(), data.toBuilder()
                .peerAccountPayload(Optional.of(peerAccountPayload))
                .build()),
                List.of(new ObserveDepositTxConfirmation()),
                List.of(new LogEntry.Shared(new LogEntry.Text("Taker received the peers account payload.\n"
                        + "Taker starts listening for blockchain confirmation of deposit tx."))));
    }
}
