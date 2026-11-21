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

import bisq.common.processing.ProtocolLogMessage;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.CloseTradeRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/**
 * The seller closes after waiting for peer cooperation in normal settlement.
 * Whichever closure input succeeds first consumes the stage; the other becomes unreachable.
 */
@Slf4j
public final class SellersCloseTradeTimeoutEventHandler
        extends MuSigTransitionHandler<PaymentReceiptConfirmed, SellersCloseTradeTimeoutEvent, SellerForceClosedTrade, MuSigV2TradeExecutionData> {

    public SellersCloseTradeTimeoutEventHandler(MuSigProtocolContext context,
                                                FlowSnapshot<PaymentReceiptConfirmed, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<SellerForceClosedTrade, MuSigV2TradeExecutionData, Event> process(SellersCloseTradeTimeoutEvent event) {
        CloseTradeResponse myCloseTradeResponse = callBackend("closeTrade",
                () -> blockingStub.closeTrade(CloseTradeRequest.newBuilder()
                        .setTradeId(tradeId)
                        .build()),
                CloseTradeResponse::fromProto);

        return new Transition<>(flow.to(new SellerForceClosedTrade(), data.toBuilder()
                .myCloseTradeResponse(Optional.of(myCloseTradeResponse))
                .tradeCompletedDate(Optional.of(context.clock().now()))
                .build()),
                List.of(new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text(
                        "Seller force-closed the trade after the closing timeout."))),
                List.of(),
                List.of(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID)));
    }
}
