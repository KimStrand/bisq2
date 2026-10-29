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

import bisq.common.processing.Effect;
import bisq.common.processing.LogEntry;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.mu_sig.MuSigCustomPayoutValidation;
import bisq.trade.mu_sig.MuSigFeeRateProvider;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbtRequest;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static com.google.common.base.Preconditions.checkArgument;

/** Signs after local acceptance has been checkpointed, without waiting for the peer's decision. */
public final class MediationResultByMyselfAcceptedEventHandler<S extends MuSigTradeExecutionState>
        extends MuSigTransitionHandler<S, MediationResultByMyselfAcceptedEvent, CustomPayoutSigned, MuSigV2TradeExecutionData> {
    public MediationResultByMyselfAcceptedEventHandler(MuSigProtocolContext context,
                                                       FlowSnapshot<S, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected void verify(MediationResultByMyselfAcceptedEvent event) {
        var signedResult = context.mediation().orElseThrow().data().signedResult()
                .orElseThrow(() -> new IllegalStateException("A signed mediation result is not available"));
        checkArgument(signedResult.result().equals(event.getResult()),
                "The accepted proposal does not match the stored signed mediation result");
        checkArgument(signedResult.result().getMediationPayoutDistributionType() !=
                        MediationPayoutDistributionType.NO_PAYOUT,
                "The mediator did not propose a payout");
        // Also runs when a saved acceptance event resumes after restart.
        signedResult.verify(contract);
    }

    @Override
    protected Transition<CustomPayoutSigned, MuSigV2TradeExecutionData, Event> process(MediationResultByMyselfAcceptedEvent event) {
        try {
            var request = new CustomPayoutPsbtRequest(tradeId,
                    event.getResult().getProposedSellerPayoutAmount().orElseThrow(),
                    MuSigFeeRateProvider.getPreparedTxFeeRate());
            var psbt = CustomPayoutPsbt.fromProto(
                    callBackend("signCustomPayoutTx", () -> blockingStub.signCustomPayoutTx(request.toProto(false))));
            MuSigCustomPayoutValidation.validatePsbt(psbt.getPsbt());
            // As in V1, returned payout amount checks await the backend's deposit accounting fix.
            var message = new MuSigCustomPayoutPsbtMessage(StringUtils.createUid(),
                    tradeId,
                    context.protocolVersion(),
                    context.myNetworkId(),
                    context.peerNetworkId(),
                    MuSigMediationResultService.getMediationResultHash(event.getResult()),
                    psbt.getTxId(),
                    psbt.getPsbt());
            String role = context.myRole().isBuyer() ? "Buyer" : "Seller";
            List<LogEntry> logs = new ArrayList<>();
            logs.add(new LogEntry.Shared(new LogEntry.Text(role + " signed the mediated payout")));
            // Match V1: announce acceptance after successful signing, not the earlier decision checkpoint.
            serviceProvider.getChatService().getMuSigOpenTradeChannelService().findChannelByTradeId(tradeId)
                    .ifPresent(channel -> logs.add(new LogEntry.Shared(new LogEntry.Localized(
                            "muSig.mediation.result.accepted.tradeLogMessage",
                            List.of(channel.getMyUserIdentity().getUserName())))));
            List<Effect> effects = new ArrayList<>();
            if (flow.state() instanceof PaymentInitiated) {
                effects.add(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID));
            }
            effects.add(new Effect.SendMessage<>(message, context.peerNetworkId()));
            return new Transition<>(flow.to(new CustomPayoutSigned(), data.toBuilder()
                    .myCustomPayoutPsbt(Optional.of(psbt)).build()),
                    effects,
                    logs);
        } catch (UnresolvableProtobufException exception) {
            throw new MuSigProtocolException(MuSigFailureCategory.PROTOCOL_FAILED,
                    "Invalid MuSig backend response", exception);
        }
    }
}
