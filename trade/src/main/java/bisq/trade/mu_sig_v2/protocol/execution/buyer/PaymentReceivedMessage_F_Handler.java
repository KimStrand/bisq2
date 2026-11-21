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

package bisq.trade.mu_sig_v2.protocol.execution.buyer;

import bisq.common.data.ByteArray;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.CloseTradeRequest;
import com.google.protobuf.ByteString;

import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/** Closes with the seller's released key share, then sends the seller their output key share. */
public final class PaymentReceivedMessage_F_Handler
        extends MuSigTransitionHandler<PaymentInitiated, PaymentReceivedMessage_F, TradeClosed, MuSigV2TradeExecutionData> {

    public PaymentReceivedMessage_F_Handler(MuSigProtocolContext context,
                                            FlowSnapshot<PaymentInitiated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<TradeClosed, MuSigV2TradeExecutionData, Event> process(PaymentReceivedMessage_F message) {
        SwapTxSignature peerSwapTxSignature = message.getSwapTxSignature();
        CloseTradeRequest request = CloseTradeRequest.newBuilder()
                .setTradeId(tradeId)
                .setMyOutputPeersPrvKeyShare(ByteString.copyFrom(peerSwapTxSignature.getPeerOutputPrvKeyShare()))
                .build();
        CloseTradeResponse response = callBackend("closeTrade",
                () -> blockingStub.closeTrade(request),
                CloseTradeResponse::fromProto);
        CooperativeClosureMessage_G outgoing = new CooperativeClosureMessage_G(StringUtils.createUid(),
                tradeId,
                context.protocolVersion(),
                context.myNetworkId(),
                context.peerNetworkId(),
                new ByteArray(response.getPeerOutputPrvKeyShare().clone()));
        return new Transition<>(flow.to(new TradeClosed(), data.toBuilder()
                .peerSwapTxSignature(Optional.of(peerSwapTxSignature))
                .myCloseTradeResponse(Optional.of(response))
                .tradeCompletedDate(Optional.of(context.clock().now()))
                .build()),
                List.of(new SendMessage<>(outgoing, context.peerNetworkId()),
                        new ProtocolLogMessage.Shared(
                                new ProtocolLogMessage.Text("Buyer received the seller's payment confirmation.\n"
                        + "Buyer closed the trade and sent the seller's output key share."))),
                List.of(),
                List.of(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID)));
    }
}
