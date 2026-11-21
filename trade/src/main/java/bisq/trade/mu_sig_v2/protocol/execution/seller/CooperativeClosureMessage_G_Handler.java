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

import bisq.common.data.ByteArray;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.CloseTradeRequest;
import com.google.protobuf.ByteString;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/** The buyer's output key share arrives and the seller closes the trade cooperatively. */
@Slf4j
public final class CooperativeClosureMessage_G_Handler
        extends MuSigTransitionHandler<PaymentReceiptConfirmed, CooperativeClosureMessage_G, TradeClosed, MuSigV2TradeExecutionData> {

    public CooperativeClosureMessage_G_Handler(MuSigProtocolContext context,
                                               FlowSnapshot<PaymentReceiptConfirmed, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<TradeClosed, MuSigV2TradeExecutionData, Event> process(CooperativeClosureMessage_G message) {
        ByteArray peersOutputPrvKeyShare = message.getPeerOutputPrvKeyShare();
        CloseTradeResponse myCloseTradeResponse = callBackend("closeTrade",
                () -> blockingStub.closeTrade(CloseTradeRequest.newBuilder()
                        .setTradeId(tradeId)
                        .setMyOutputPeersPrvKeyShare(ByteString.copyFrom(peersOutputPrvKeyShare.getBytes()))
                        .build()),
                CloseTradeResponse::fromProto);

        return new Transition<>(flow.to(new TradeClosed(), data.toBuilder()
                .peersOutputPrvKeyShare(Optional.of(peersOutputPrvKeyShare))
                .myCloseTradeResponse(Optional.of(myCloseTradeResponse))
                .tradeCompletedDate(Optional.of(context.clock().now()))
                .build()),
                List.of(new ProtocolLogMessage.Shared(
                        new ProtocolLogMessage.Text("Seller received peersOutputPrvKeyShare.\n"
                                + "Seller closed trade."))),
                List.of(),
                List.of(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID)));
    }
}
