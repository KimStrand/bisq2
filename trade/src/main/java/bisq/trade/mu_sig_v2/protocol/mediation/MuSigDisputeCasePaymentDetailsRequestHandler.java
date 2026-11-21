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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
final class MuSigDisputeCasePaymentDetailsRequestHandler<S extends MuSigMediationState>
        extends MuSigMediationHandler<S, MuSigDisputeCasePaymentDetailsRequest, S> {

    MuSigDisputeCasePaymentDetailsRequestHandler(MuSigProtocolContext context,
                                                FlowSnapshot<S, MuSigV2MediationData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(MuSigDisputeCasePaymentDetailsRequest request) {
        if (context.flows().execution().data().peerAccountPayload().isEmpty()) {
            log.warn("Ignoring payment details request for trade {} because account payloads are incomplete", tradeId);
            ignoreInput();
        }
    }

    @Override
    protected Transition<S, MuSigV2MediationData, Event> process(MuSigDisputeCasePaymentDetailsRequest request) {
        AccountPayload<?> myPayload = context.myAccountPayload();
        AccountPayload<?> peerPayload = context.flows().execution().data().peerAccountPayload().orElseThrow();
        MuSigDisputeCasePaymentDetailsResponse response = new MuSigDisputeCasePaymentDetailsResponse(tradeId,
                context.myNetworkId(),
                context.myRole().isMaker() ? peerPayload : myPayload,
                context.myRole().isMaker() ? myPayload : peerPayload);
        return new Transition<>(flow,
                List.of(new SendMessage<>(response, request.getSenderNetworkId())));
    }
}
