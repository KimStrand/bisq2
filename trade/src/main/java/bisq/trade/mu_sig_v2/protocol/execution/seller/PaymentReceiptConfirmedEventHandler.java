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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.presentation.formatters.AmountFormatter;
import bisq.trade.mu_sig.MuSigTradeUtils;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.SwapTxSignatureRequest;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/**
 * Releasing our swap tx signature lets the buyer take the BTC. As for every transition, the lane
 * saves the resulting state and outgoing effects before dispatching them.
 */
@Slf4j
public final class PaymentReceiptConfirmedEventHandler
        extends MuSigTransitionHandler<PaymentInitiatedMessageReceived, PaymentReceiptConfirmedEvent, PaymentReceiptConfirmed, MuSigV2TradeExecutionData> {

    public PaymentReceiptConfirmedEventHandler(MuSigProtocolContext context,
                                               FlowSnapshot<PaymentInitiatedMessageReceived, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<PaymentReceiptConfirmed, MuSigV2TradeExecutionData, Event> process(PaymentReceiptConfirmedEvent event) {
        try {
            SwapTxSignatureResponse mySwapTxSignatureResponse = SwapTxSignatureResponse.fromProto(
                    callBackend("signSwapTx", () -> blockingStub.signSwapTx(SwapTxSignatureRequest.newBuilder()
                            .setTradeId(tradeId)
                            .setSellerReadyToRelease(true)
                            .build())));

            PaymentReceivedMessage_F outgoing = new PaymentReceivedMessage_F(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    SwapTxSignature.from(mySwapTxSignatureResponse));
            List<LogEntry> logs = new ArrayList<>();
            logs.add(new LogEntry.Shared(new LogEntry.Text("Seller confirmed payment receipt.\n"
                    + "Seller sends swapTxSignature to buyer.")));
            serviceProvider.getChatService().getMuSigOpenTradeChannelService().findChannelByTradeId(tradeId)
                    .ifPresent(channel -> logs.add(new LogEntry.Shared(new LogEntry.Localized(
                            "muSig.trade.state.phase3a.logMessage",
                            List.of(channel.getMyUserIdentity().getUserName(),
                                    AmountFormatter.formatAmountWithCodeByMonetaryType(MuSigTradeUtils.getNonBtcSideMonetary(contract)))))));
            return new Transition<>(flow.to(new PaymentReceiptConfirmed(), data.toBuilder()
                    .mySwapTxSignatureResponse(Optional.of(mySwapTxSignatureResponse))
                    .build()),
                    List.of(new Effect.SendMessage<>(outgoing, context.peerNetworkId()), new ScheduledEvent<>(CLOSE_TRADE_TIMEOUT_ID,
                            Instant.ofEpochMilli(context.clock().now()).plus(CLOSE_TRADE_TIMEOUT),
                            new SellersCloseTradeTimeoutEvent())),
                    logs);
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
