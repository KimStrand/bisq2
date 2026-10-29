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

import bisq.common.data.ByteArray;
import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData.MuSigV2TradeExecutionDataBuilder;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.DepositTxSignatureRequest;
import bisq.trade.protobuf.PublishDepositTxRequest;

import java.util.List;
import java.util.Optional;

/** Publishing the deposit transaction is irreversible and happens here, inside the handler. */
public abstract class BaseSetupTradeMessage_D_Handler
        extends MuSigTransitionHandler<NonceSharesAndPartialSignaturesCreated, SetupTradeMessage_D,
        DepositTxSignedAndPublished, MuSigV2TradeExecutionData> {

    protected BaseSetupTradeMessage_D_Handler(
            MuSigProtocolContext context,
            FlowSnapshot<NonceSharesAndPartialSignaturesCreated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected abstract void verify(SetupTradeMessage_D message);

    @Override
    protected Transition<DepositTxSignedAndPublished, MuSigV2TradeExecutionData, Event> process(SetupTradeMessage_D message) {
        try {
            DepositPsbt peerDepositPsbt = new DepositPsbt(message.getDepositPsbt());
            DepositTxSignatureRequest depositTxSignatureRequest = DepositTxSignatureRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setPeersPartialSignatures(PartialSignaturesMessage.from(message.getPartialSignatures()).toProto(true))
                    .build();
            DepositPsbt myDepositPsbt = DepositPsbt.fromProto(
                    callBackend("signDepositTx", () -> blockingStub.signDepositTx(depositTxSignatureRequest)));
            callBackend("publishDepositTx", () -> blockingStub.publishDepositTx(PublishDepositTxRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setPeersDepositPsbt(peerDepositPsbt.toProto(true))
                    .build()));

            SendAccountPayloadAndDepositTxMessage outgoing = new SendAccountPayloadAndDepositTxMessage(
                    StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    new ByteArray(myDepositPsbt.getDepositPsbt()),
                    context.myAccountPayload());
            MuSigV2TradeExecutionDataBuilder builder = data.toBuilder()
                    .myDepositPsbt(Optional.of(myDepositPsbt))
                    .peerDepositPsbt(Optional.of(peerDepositPsbt));
            setPeerPartialSignatures(builder, message.getPartialSignatures());
            return new Transition<>(flow.to(new DepositTxSignedAndPublished(), builder.build()),
                    List.of(new Effect.SendMessage<>(outgoing, context.peerNetworkId())),
                    List.of(new LogEntry.Shared(new LogEntry.Text(
                            "Taker received peers partialSignatures and depositPsbt.\n"
                                    + "Taker created depositPsbt.\n"
                                    + "Taker published deposit tx.\n"
                                    + "Taker sends deposit tx and account payload to maker"))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }

    protected abstract void setPeerPartialSignatures(MuSigV2TradeExecutionDataBuilder builder,
                                                     PartialSignatures partialSignatures);
}
