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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig.messages.network.handler.PartialSignaturesRequestUtil;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData.MuSigV2TradeExecutionDataBuilder;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.DepositTxSignatureRequest;
import bisq.trade.protobuf.PartialSignaturesRequest;

import java.util.List;
import java.util.Optional;

public abstract class BaseSetupTradeMessage_C_Handler
        extends MuSigTransitionHandler<TradeInitializedAndNonceSharesCreated, SetupTradeMessage_C,
        PartialSignaturesCreatedAndDepositTxSigned, MuSigV2TradeExecutionData> {

    protected BaseSetupTradeMessage_C_Handler(
            MuSigProtocolContext context,
            FlowSnapshot<TradeInitializedAndNonceSharesCreated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected abstract void verify(SetupTradeMessage_C message);

    @Override
    protected Transition<PartialSignaturesCreatedAndDepositTxSigned, MuSigV2TradeExecutionData, Event> process(
            SetupTradeMessage_C message) {
        try {
            long redirectionAmount = data.getMyNonceShares().orElseThrow().getRedirectionAmountMsat();
            var request = PartialSignaturesRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setPeersNonceShares(NonceSharesMessage.from(message.getNonceShares()).toProto(true))
                    .addAllRedirectionReceivers(PartialSignaturesRequestUtil.getBurningMenDPTReceivers(redirectionAmount))
                    .build();
            var myPartialSignatures = PartialSignaturesMessage.fromProto(
                    callBackend("getPartialSignatures", () -> blockingStub.getPartialSignatures(request)));
            var depositRequest = DepositTxSignatureRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setPeersPartialSignatures(PartialSignaturesMessage.from(message.getPartialSignatures()).toProto(true))
                    .build();
            var myDepositPsbt = DepositPsbt.fromProto(
                    callBackend("signDepositTx", () -> blockingStub.signDepositTx(depositRequest)));
            var outgoing = new SetupTradeMessage_D(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    getPartialSignatures(myPartialSignatures),
                    myDepositPsbt.getDepositPsbt());
            MuSigV2TradeExecutionDataBuilder builder = data.toBuilder()
                    .peerNonceShares(Optional.of(message.getNonceShares()))
                    .myPartialSignatures(Optional.of(myPartialSignatures))
                    .myDepositPsbt(Optional.of(myDepositPsbt));
            setPeerPartialSignatures(builder, message.getPartialSignatures());
            String role = context.myRole().isBuyer() ? "Maker (as buyer)" : "Maker (as seller)";
            String signatures = context.myRole().isBuyer() ? "redacted partialSignatures" : "partialSignatures";
            String peerRole = context.myRole().isBuyer() ? "seller" : "buyer";
            return new Transition<>(flow.to(new PartialSignaturesCreatedAndDepositTxSigned(), builder.build()),
                    List.of(new Effect.SendMessage<>(outgoing, context.peerNetworkId())),
                    List.of(new LogEntry.Shared(new LogEntry.Text(
                            role + " received peers nonceShares and partialSignatures.\n"
                                    + role + " created his partialSignatures and depositPsbt.\n"
                                    + role + " sent his " + signatures + " and depositPsbt to " + peerRole + "."))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }

    protected abstract PartialSignatures getPartialSignatures(PartialSignaturesMessage myPartialSignatures);

    protected abstract void setPeerPartialSignatures(MuSigV2TradeExecutionDataBuilder builder,
                                                     PartialSignatures partialSignatures);
}
