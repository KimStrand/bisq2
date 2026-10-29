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
import bisq.contract.ContractSignatureData;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PubKeySharesResponse;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.handler.MuSigContractVerifier;
import bisq.trade.mu_sig.messages.network.handler.NonceSharesRequestUtil;
import bisq.trade.mu_sig.messages.network.handler.maker.MuSigTakeOfferRequestValidator;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.PubKeySharesRequest;
import bisq.trade.protobuf.Role;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

public final class SetupTradeMessage_A_Handler
        extends MuSigTransitionHandler<Init, SetupTradeMessage_A, TradeInitializedAndNonceSharesCreated,
        MuSigV2TradeExecutionData> {
    private ContractSignatureData myContractSignature;

    public SetupTradeMessage_A_Handler(MuSigProtocolContext context,
                                       FlowSnapshot<Init, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(SetupTradeMessage_A message) {
        if (serviceProvider.getUserService().getUserProfileService().isChatUserIgnored(message.getSender().getId())) {
            throw new TradeProtocolException("The offer is not available anymore.",
                    TradeProtocolFailure.OFFER_NOT_AVAILABLE);
        }
        // A restored INIT trade skips creation admission, so bind the signature to the taker here too.
        MuSigTakeOfferRequestValidator.validateIdentity(serviceProvider.getContractService(), message);
        try {
            myContractSignature = serviceProvider.getContractService()
                    .signContract(contract, context.myIdentity().getKeyBundle().getKeyPair());
        } catch (GeneralSecurityException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.OPERATION_FAILED,
                    "Signing contract failed",
                    exception);
        }
        MuSigContractVerifier.verifyPeer(serviceProvider.getContractService(),
                contract,
                myContractSignature,
                message.getContract(),
                message.getContractSignatureData());
    }

    @Override
    protected Transition<TradeInitializedAndNonceSharesCreated, MuSigV2TradeExecutionData, Event> process(
            SetupTradeMessage_A message) {
        try {
            Role backendRole = switch (context.myRole()) {
                case BUYER_AS_MAKER -> Role.BUYER_AS_MAKER;
                case SELLER_AS_MAKER -> Role.SELLER_AS_MAKER;
                default -> throw new IllegalArgumentException("Handling an offer request requires a maker role");
            };
            var response = PubKeySharesResponse.fromProto(
                    callBackend("initTrade", () -> blockingStub.initTrade(PubKeySharesRequest.newBuilder()
                            .setTradeId(tradeId)
                            .setMyRole(backendRole)
                            .build())));
            var myPubKeyShares = PubKeyShares.from(response);
            var peerPubKeyShares = message.getPubKeyShares();
            var request = NonceSharesRequestUtil.createRequest(tradeId, context.tradeAmount(), peerPubKeyShares);
            var myNonceShares = NonceSharesMessage.fromProto(
                    callBackend("getNonceShares", () -> blockingStub.getNonceShares(request)));
            var outgoing = new SetupTradeMessage_B(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    contract,
                    myContractSignature,
                    myPubKeyShares,
                    NonceShares.from(myNonceShares));
            String role = context.myRole().isBuyer() ? "Maker (as buyer)" : "Maker (as seller)";
            return new Transition<>(flow.to(new TradeInitializedAndNonceSharesCreated(), data.toBuilder()
                    .myContractSignature(Optional.of(myContractSignature))
                    .peerContractSignature(Optional.of(message.getContractSignatureData()))
                    .myPubKeyShares(Optional.of(myPubKeyShares))
                    .peerPubKeyShares(Optional.of(peerPubKeyShares))
                    .myNonceShares(Optional.of(myNonceShares))
                    .build()),
                    List.of(new Effect.SendMessage<>(outgoing, context.peerNetworkId())),
                    List.of(new LogEntry.Shared(new LogEntry.Text(role + " received peers pubKeyShares.\n"
                            + role + " created his pubKeyShares and nonceShares.\n"
                            + role + " sent his pubKeyShares and his nonceShares to taker."))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
