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

package bisq.trade.mu_sig_v2.protocol;

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowRegistration;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.StateMachineProcessor;
import bisq.common.timer.Clock;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.MuSigBuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.MuSigBuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.MuSigSellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import com.google.common.annotations.VisibleForTesting;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;


/**
 * MuSig's synchronous state-machine processor. It binds the parent, execution and optional mediation flows,
 * validates external inputs and classifies failures. The caller supplies the current document and processing
 * history; this processor retains no live trade, queue or unfinished work and dispatches no outgoing effects.
 * Handlers still perform synchronous backend operations as part of a transition.
 */
@Slf4j
public final class MuSigTradeProcessor<S extends MuSigTradeExecutionState>
        extends StateMachineProcessor<MuSigV2Trade<S>, Event, Flow> {

    /**
     * Selects the execution definition by role; the document is used for configuration, not retained.
     */
    public static MuSigTradeProcessor<?> create(ServiceProvider serviceProvider, MusigGrpcClient musigGrpcClient,
                                                MuSigV2Trade<?> trade, Clock clock) {
        return switch (trade.getMyRole()) {
            case SELLER_AS_TAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    new MuSigSellerAsTakerProtocol(),
                    clock);
            case BUYER_AS_TAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    new MuSigBuyerAsTakerProtocol(),
                    clock);
            case BUYER_AS_MAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    new MuSigBuyerAsMakerProtocol(),
                    clock);
            case SELLER_AS_MAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    new MuSigSellerAsMakerProtocol(),
                    clock);
        };
    }

    @VisibleForTesting
    static <S extends MuSigTradeExecutionState> MuSigTradeProcessor<S> assemble(
            ServiceProvider serviceProvider,
            MusigGrpcClient musigGrpcClient,
            MuSigV2Trade<?> trade,
            MuSigExecutionProtocol<S> executionProtocol,
            Clock clock) {
        FlowRegistration.Builder<MuSigV2Trade<S>, Event, Flow, MuSigReadOnlyContext, MuSigProtocolContext> flows =
                FlowRegistration.<MuSigV2Trade<S>, Event, Flow, MuSigReadOnlyContext, MuSigProtocolContext>builder(
                                document -> MuSigReadOnlyContext.of(document, executionProtocol),
                                document -> new MuSigProtocolContext(serviceProvider,
                                        musigGrpcClient,
                                        document,
                                        executionProtocol,
                                        clock))
                        .add(new MuSigTradeProtocol(),
                                Flow.TRADE,
                                Optional.empty(),
                                document -> FlowSnapshot.of(document.getState(), document.getData()),
                                MuSigV2Trade::withTradeFlow)
                        .add(executionProtocol,
                                Flow.EXECUTION,
                                Optional.of(Flow.TRADE),
                                MuSigV2Trade::getExecution,
                                MuSigV2Trade::withExecution);
        if (trade.getMediation().isPresent()) {
            flows.add(new MuSigMediationProtocol(),
                            Flow.MEDIATION,
                            Optional.of(Flow.TRADE),
                            document -> {
                                MuSigV2Mediation<?> mediation = document.getMediation().orElseThrow();
                                return FlowSnapshot.of(mediation.state(), mediation.data());
                            },
                            MuSigV2Trade::withMediation)
                    .add(new MuSigMyMediationDecisionProtocol(),
                            Flow.MY_MEDIATION_DECISION,
                            Optional.of(Flow.MEDIATION),
                            document -> document.getMediation().orElseThrow().myDecision(),
                            MuSigV2Trade::withMyMediationDecision)
                    .add(new MuSigPeerMediationDecisionProtocol(),
                            Flow.PEER_MEDIATION_DECISION,
                            Optional.of(Flow.MEDIATION),
                            document -> document.getMediation().orElseThrow().peerDecision(),
                            MuSigV2Trade::withPeerMediationDecision);
        }
        return new MuSigTradeProcessor<>(flows.build());
    }

    private MuSigTradeProcessor(List<FlowRegistration<MuSigV2Trade<S>, Event, Flow>> flowRegistrations) {
        super(flowRegistrations);
    }


    /* --------------------------------------------------------------------- */
    // Flow transitions and failure handling
    /* --------------------------------------------------------------------- */

    @Override
    protected RuntimeException classifyProcessingFailure(RuntimeException failure) {
        return MuSigProtocolException.processingFailure(failure);
    }

    @Override
    protected RuntimeException onInputRejected(RuntimeException failure) {
        return new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED, failure.getMessage(), failure);
    }

    @Override
    protected Optional<Event> onProcessingFailure(
            MuSigV2Trade<S> document,
            Flow flowId,
            Event input,
            RuntimeException failure) {
        if (input instanceof MuSigProcessingFailedEvent) {
            return Optional.empty();
        }
        // The processor passes the exception which classifyProcessingFailure returned.
        MuSigProtocolException classified = (MuSigProtocolException) failure;
        log.warn("Trade {}: {} while handling {}",
                document.getId(),
                classified.getCategory(),
                input.getClass().getSimpleName(),
                classified);
        return Optional.of(MuSigProcessingFailedEvent.fromException(classified));
    }
}
