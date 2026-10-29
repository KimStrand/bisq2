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

package bisq.trade.mu_sig_v2.protocol.execution;

import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeRequest;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState.Accepted;

import java.util.List;
import java.util.Optional;

import static bisq.support.mediation.MediationPayoutDistributionType.NO_PAYOUT;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/** Finalizes with the peer payout from the event, recording it in execution only on success. */
public final class CustomPayoutFinalizationHandler extends
        MuSigTransitionHandler<CustomPayoutSigned, MediationResultByPeerAcceptedEvent, CustomPayoutClosedTrade, MuSigV2TradeExecutionData> {
    public CustomPayoutFinalizationHandler(MuSigProtocolContext context,
                                           FlowSnapshot<CustomPayoutSigned, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    /** Also used by readiness so inconsistent agreement data reaches verification instead of waiting. */
    public static boolean hasRecordedAgreement(MuSigReadOnlyContext context) {
        return context.mediation().map(mediation -> {
            var data = mediation.data();
            return data.myDecisionState() instanceof Accepted &&
                    data.peerDecisionState() instanceof Accepted &&
                    data.signedResult()
                            .filter(signed -> signed.result().getMediationPayoutDistributionType() != NO_PAYOUT)
                            .isPresent();
        }).orElse(false);
    }

    @Override
    protected void verify(MediationResultByPeerAcceptedEvent event) {
        checkState(hasRecordedAgreement(context),
                "Cannot finalize custom payout without a signed payout proposal accepted by both traders");
        checkArgument(data.getMyCustomPayoutPsbt().orElseThrow().getTxId()
                        .equals(event.getPsbt().getTxId()),
                "The peer signed a different custom payout transaction");
    }

    @Override
    protected Transition<CustomPayoutClosedTrade, MuSigV2TradeExecutionData, Event> process(MediationResultByPeerAcceptedEvent event) {
        try {
            var request = new CustomCloseTradeRequest(tradeId, event.getPsbt().getPsbt());
            var response = CustomCloseTradeResponse.fromProto(
                    callBackend("customCloseTrade", () -> blockingStub.customCloseTrade(request.toProto(false))));
            checkArgument(response.getCustomPayoutTx().length > 0, "Custom payout transaction must not be empty");
            return new Transition<>(flow.to(new CustomPayoutClosedTrade(), data.toBuilder()
                    .peerCustomPayoutPsbt(Optional.of(event.getPsbt()))
                    .customCloseTradeResponse(Optional.of(response)).build()),
                    List.of(),
                    List.of(new LogEntry.Shared(new LogEntry.Text("Trade closed with the mediated payout"))));
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
