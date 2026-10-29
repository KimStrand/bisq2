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

package bisq.trade.mu_sig_v2.protocol.execution.seller;

import bisq.common.processing.LogEntry;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.SwapTxSignatureRequest;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

/**
 * The seller computes the swap tx signature on receipt rather than at the end of the trade, so a
 * bad peer signature fails the trade now. The response is redacted and discarded; {@code signSwapTx}
 * is called again when it is safe to release the escrow.
 */
@Slf4j
public final class PaymentInitiatedMessage_E_Handler
        extends MuSigTransitionHandler<DepositTxConfirmed, PaymentInitiatedMessage_E, PaymentInitiatedMessageReceived, MuSigV2TradeExecutionData> {

    public PaymentInitiatedMessage_E_Handler(MuSigProtocolContext context,
                                             FlowSnapshot<DepositTxConfirmed, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<PaymentInitiatedMessageReceived, MuSigV2TradeExecutionData, Event> process(
            PaymentInitiatedMessage_E message) {
        PartialSignatures peerRedactedPartialSignatures = data.getPeerRedactedPartialSignatures()
                .orElseThrow(() -> new IllegalStateException(
                        "Peer redacted partial signatures are required before processing payment initiation"));
        byte[] peersSwapTxInputPartialSignature = message.getSwapTxInputPartialSignature();
        //noinspection ResultOfMethodCallIgnored
        callBackend("signSwapTx", () -> blockingStub.signSwapTx(SwapTxSignatureRequest.newBuilder()
                .setTradeId(tradeId)
                .setSwapTxInputPeersPartialSignature(ByteString.copyFrom(peersSwapTxInputPartialSignature))
                .setSellerReadyToRelease(false)
                .build()));

        PartialSignatures peerPartialSignatures = PartialSignatures.toUnRedacted(
                peerRedactedPartialSignatures,
                peersSwapTxInputPartialSignature);
        return new Transition<>(flow.to(new PaymentInitiatedMessageReceived(), data.toBuilder()
                .peerPartialSignatures(Optional.of(peerPartialSignatures))
                .build()),
                List.of(),
                List.of(new LogEntry.Shared(new LogEntry.Text(
                        "Seller received the message that the buyer has initiated the payment"))));
    }
}
