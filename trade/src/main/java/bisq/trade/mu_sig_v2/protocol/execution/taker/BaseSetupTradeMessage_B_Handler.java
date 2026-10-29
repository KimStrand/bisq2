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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.handler.MuSigContractVerifier;
import bisq.trade.mu_sig.messages.network.handler.NonceSharesRequestUtil;
import bisq.trade.mu_sig.messages.network.handler.PartialSignaturesRequestUtil;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.NonceSharesRequest;
import bisq.trade.protobuf.PartialSignaturesRequest;

import java.util.List;
import java.util.Optional;

public abstract class BaseSetupTradeMessage_B_Handler
        extends MuSigTransitionHandler<TradeInitialized, SetupTradeMessage_B, NonceSharesAndPartialSignaturesCreated,
        MuSigV2TradeExecutionData> {

    protected BaseSetupTradeMessage_B_Handler(MuSigProtocolContext context,
                                              FlowSnapshot<TradeInitialized, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SetupTradeMessage_B message) {
        MuSigContractVerifier.verifyPeer(serviceProvider.getContractService(),
                contract,
                data.getMyContractSignature().orElseThrow(() ->
                        new IllegalStateException("Our contract signature is required to verify the peer's contract")),
                message.getContract(),
                message.getContractSignatureData());
    }

    @Override
    protected Transition<NonceSharesAndPartialSignaturesCreated, MuSigV2TradeExecutionData, Event> process(
            SetupTradeMessage_B message) {
        try {
            NonceSharesRequest nonceSharesRequest =
                    NonceSharesRequestUtil.createRequest(tradeId, context.tradeAmount(), message.getPubKeyShares());
            NonceSharesMessage myNonceShares =
                    NonceSharesMessage.fromProto(callBackend("getNonceShares",
                            () -> blockingStub.getNonceShares(nonceSharesRequest)));

            PartialSignaturesRequest partialSignaturesRequest = PartialSignaturesRequest.newBuilder()
                    .setTradeId(tradeId)
                    .setPeersNonceShares(NonceSharesMessage.from(message.getNonceShares()).toProto(true))
                    .addAllRedirectionReceivers(PartialSignaturesRequestUtil
                            .getBurningMenDPTReceivers(myNonceShares.getRedirectionAmountMsat()))
                    .build();
            PartialSignaturesMessage myPartialSignatures =
                    PartialSignaturesMessage.fromProto(
                            callBackend("getPartialSignatures",
                                    () -> blockingStub.getPartialSignatures(partialSignaturesRequest)));

            SetupTradeMessage_C outgoing = new SetupTradeMessage_C(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    NonceShares.from(myNonceShares),
                    getPartialSignatures(myPartialSignatures));
            String role = context.myRole().isBuyer() ? "Taker (as buyer)" : "Taker (as seller)";
            String peerRole = context.myRole().isBuyer() ? "seller" : "buyer";
            return new Transition<>(flow.to(new NonceSharesAndPartialSignaturesCreated(), data.toBuilder()
                    .peerContractSignature(Optional.of(message.getContractSignatureData()))
                    .peerPubKeyShares(Optional.of(message.getPubKeyShares()))
                    .peerNonceShares(Optional.of(message.getNonceShares()))
                    .myNonceShares(Optional.of(myNonceShares))
                    .myPartialSignatures(Optional.of(myPartialSignatures))
                    .build()),
                    List.of(new Effect.SendMessage<>(outgoing, context.peerNetworkId())),
                    List.of(new LogEntry.Shared(new LogEntry.Text(role + " received peers pubKeyShares and nonceShares.\n"
                            + role + " created his nonceShares and partialSignatures.\n"
                            + role + " sent his nonceShares and his partialSignatures to " + peerRole + "."))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }

    protected abstract PartialSignatures getPartialSignatures(PartialSignaturesMessage myPartialSignatures);
}
