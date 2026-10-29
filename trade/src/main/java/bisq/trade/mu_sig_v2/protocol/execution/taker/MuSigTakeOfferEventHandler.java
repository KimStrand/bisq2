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
import bisq.contract.ContractSignatureData;
import bisq.trade.mu_sig.messages.grpc.PubKeySharesResponse;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.protobuf.PubKeySharesRequest;
import bisq.trade.protobuf.Role;
import bisq.user.profile.UserProfile;
import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Optional;

/**
 * Initializes either taker role from the protocol context. Only the sendable
 * {@link PubKeyShares} is kept; the response it is derived from is never read again.
 */
@Slf4j
public final class MuSigTakeOfferEventHandler
        extends MuSigTransitionHandler<Init, MuSigTakeOfferEvent, TradeInitialized, MuSigV2TradeExecutionData> {

    public MuSigTakeOfferEventHandler(MuSigProtocolContext context,
                                      FlowSnapshot<Init, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<TradeInitialized, MuSigV2TradeExecutionData, Event> process(MuSigTakeOfferEvent event) {
        try {
            Role backendRole = switch (context.myRole()) {
                case BUYER_AS_TAKER -> Role.BUYER_AS_TAKER;
                case SELLER_AS_TAKER -> Role.SELLER_AS_TAKER;
                default -> throw new IllegalArgumentException("Taking an offer requires a taker role");
            };
            PubKeySharesResponse response = PubKeySharesResponse.fromProto(
                    callBackend("initTrade", () -> blockingStub.initTrade(PubKeySharesRequest.newBuilder()
                            .setTradeId(tradeId)
                            .setMyRole(backendRole)
                            .build())));
            PubKeyShares myPubKeyShares = PubKeyShares.from(response);
            ContractSignatureData myContractSignature;
            try {
                myContractSignature = serviceProvider.getContractService()
                        .signContract(contract, context.myIdentity().getKeyBundle().getKeyPair());
            } catch (GeneralSecurityException exception) {
                log.error("Signing contract failed", exception);
                throw new MuSigProtocolException(MuSigFailureCategory.OPERATION_FAILED,
                        "Signing contract failed",
                        exception);
            }

            SetupTradeMessage_A message = new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    contract,
                    myContractSignature,
                    myPubKeyShares);
            String role = context.myRole().isBuyer() ? "Taker (as buyer)" : "Taker (as seller)";
            return new Transition<>(flow.to(new TradeInitialized(), data.toBuilder()
                    .myPubKeyShares(Optional.of(myPubKeyShares))
                    .myContractSignature(Optional.of(myContractSignature))
                    .build()),
                    List.of(new Effect.SendMessage<>(message, context.peerNetworkId())),
                    List.of(new LogEntry.Shared(new LogEntry.Localized("muSig.trade.protocol.logMessage.takeOffer",
                                    List.of(userName(contract.getTaker().getNetworkId().getId()),
                                            userName(contract.getOffer().getMakersUserProfileId()),
                                            contract.getOffer().getShortId()))),
                            new LogEntry.Shared(new LogEntry.Text(role + " created his pubKeyShares.\n"
                                    + role + " sent his pubKeyShares to maker."))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }

    private String userName(String userProfileId) {
        return serviceProvider.getUserService().getUserProfileService().findUserProfile(userProfileId)
                .map(UserProfile::getUserName)
                .orElse(userProfileId);
    }
}
