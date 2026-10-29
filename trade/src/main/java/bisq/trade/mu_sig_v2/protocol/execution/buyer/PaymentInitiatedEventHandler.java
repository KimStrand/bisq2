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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.PartialSignaturesRequest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/** Releases the buyer's withheld partial signature and schedules a timeout; buyer recovery is still unfinished. */
public final class PaymentInitiatedEventHandler
        extends MuSigTransitionHandler<DepositTxConfirmed, PaymentInitiatedEvent, PaymentInitiated,
        MuSigV2TradeExecutionData> {

    public PaymentInitiatedEventHandler(MuSigProtocolContext context,
                                        FlowSnapshot<DepositTxConfirmed, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<PaymentInitiated, MuSigV2TradeExecutionData, Event> process(PaymentInitiatedEvent event) {
        try {
            var request = PartialSignaturesRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setBuyerReadyToRelease(true)
                    .build();
            var partialSignatures = PartialSignaturesMessage.fromProto(
                    callBackend("getPartialSignatures", () -> blockingStub.getPartialSignatures(request)));
            byte[] swapPartialSignature = PartialSignatures.from(partialSignatures, false)
                    .getSwapTxInputPartialSignature()
                    .orElseThrow();
            var message = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    swapPartialSignature);
            List<LogEntry> logs = new ArrayList<>();
            serviceProvider.getChatService().getMuSigOpenTradeChannelService().findChannelByTradeId(tradeId)
                    .ifPresent(channel -> logs.add(new LogEntry.Shared(new LogEntry.Localized(
                            "muSig.trade.state.phase2a.tradeLogMessage.buyer",
                            List.of(channel.getMyUserIdentity().getUserName(),
                                    contract.getOffer().getMarket().getQuoteCurrencyCode())))));
            return new Transition<>(flow.to(new PaymentInitiated(), data.toBuilder()
                    .myPartialSignatures(Optional.of(partialSignatures))
                    .build()),
                    List.of(new Effect.SendMessage<>(message, context.peerNetworkId()),
                            new ScheduledEvent<>(CLOSE_TRADE_TIMEOUT_ID,
                                    Instant.ofEpochMilli(context.clock().now()).plus(CLOSE_TRADE_TIMEOUT),
                                    new BuyersCloseTradeTimeoutEvent())),
                    logs);
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
