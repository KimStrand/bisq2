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
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
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
        try {
            ByteArray peersOutputPrvKeyShare = message.getPeerOutputPrvKeyShare();
            CloseTradeResponse myCloseTradeResponse = CloseTradeResponse.fromProto(
                    callBackend("closeTrade", () -> blockingStub.closeTrade(CloseTradeRequest.newBuilder()
                            .setTradeId(tradeId)
                            .setMyOutputPeersPrvKeyShare(ByteString.copyFrom(peersOutputPrvKeyShare.getBytes()))
                            .build())));

            return new Transition<>(flow.to(new TradeClosed(), data.toBuilder()
                    .peersOutputPrvKeyShare(Optional.of(peersOutputPrvKeyShare))
                    .myCloseTradeResponse(Optional.of(myCloseTradeResponse))
                    .build()),
                    List.of(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID)),
                    List.of(new LogEntry.Shared(new LogEntry.Text("Seller received peersOutputPrvKeyShare.\n"
                            + "Seller closed trade."))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
