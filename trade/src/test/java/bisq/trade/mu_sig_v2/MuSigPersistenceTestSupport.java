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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.mu_sig_v2;

import bisq.common.processing.Effect;
import bisq.common.statemachine.StateMachineResolver;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.message.ExternalNetworkMessage;
import bisq.network.p2p.message.NetworkMessageResolver;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.RemoveTakenOffer;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.protocol.messages.TradeMessage;
import bisq.trade.protocol.v2.events.TradeEvent;

/** The ResolverConfig registrations needed by isolated trade-module persistence tests. */
final class MuSigPersistenceTestSupport {
    static void registerResolvers() {
        NetworkMessageResolver.addResolver("trade.TradeMessage", TradeMessage.class, TradeMessage.getNetworkMessageResolver());
        NetworkMessageResolver.addResolver("support.MuSigMediationRequest", MuSigMediationRequest.class, MuSigMediationRequest.getNetworkMessageResolver());
        NetworkMessageResolver.addResolver("support.MuSigDisputeCaseDataMessage", MuSigDisputeCaseDataMessage.class, MuSigDisputeCaseDataMessage.getNetworkMessageResolver());
        NetworkMessageResolver.addResolver("support.MuSigDisputeCasePaymentDetailsResponse",
                MuSigDisputeCasePaymentDetailsResponse.class, MuSigDisputeCasePaymentDetailsResponse.getNetworkMessageResolver());

        StateMachineResolver.addResolver("network.EnvelopePayloadMessage", bisq.network.protobuf.EnvelopePayloadMessage.class,
                EnvelopePayloadMessage::fromProto);
        StateMachineResolver.addResolver("network.identity.NetworkId", bisq.network.identity.protobuf.NetworkId.class,
                NetworkId::fromProto);
        StateMachineResolver.addResolver("common.SendMessage", bisq.common.protobuf.SendMessage.class,
                proto -> Effect.SendMessage.fromProto(proto,
                        any -> ExternalNetworkMessage.class.cast(StateMachineResolver.fromAny(any)),
                        any -> NetworkId.class.cast(StateMachineResolver.fromAny(any))));
        StateMachineResolver.addResolver("trade.TradeEvent", bisq.trade.protobuf.TradeEvent.class,
                TradeEvent::fromProto);
        StateMachineResolver.addResolver("trade.ActivateMediationChat", bisq.trade.protobuf.ActivateMediationChat.class,
                ActivateMediationChat::fromProto);
        StateMachineResolver.addResolver("trade.ObserveDepositTxConfirmation", bisq.trade.protobuf.ObserveDepositTxConfirmation.class,
                ObserveDepositTxConfirmation::fromProto);
        StateMachineResolver.addResolver("trade.RemoveTakenOffer", bisq.trade.protobuf.RemoveTakenOffer.class,
                RemoveTakenOffer::fromProto);
    }
}
