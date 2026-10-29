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

import bisq.common.application.DevMode;
import bisq.common.processing.StepResult.Applied;
import bisq.common.statemachine.Delivery;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowRegistration;
import bisq.common.statemachine.StateMachineProcessor;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.network.identity.NetworkId;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.InternalEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationCommand;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.BuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * MuSig's synchronous state-machine processor. It binds the parent, execution and optional mediation flows,
 * validates external inputs and classifies failures. The caller supplies the current document and processing
 * history; this processor retains no live trade, queue or unfinished work and dispatches no outgoing effects.
 * Handlers still perform synchronous backend operations as part of a transition.
 */
@Slf4j
public final class MuSigTradeProcessor<S extends MuSigTradeExecutionState>
        extends StateMachineProcessor<MuSigV2Trade<S>, Event, Flow> {
    private final ServiceProvider serviceProvider;
    private final Class<S> stateFamily;
    private final Function<MuSigV2Trade<S>, MuSigProtocolContext> contextFactory;

    /** Selects the execution definition by role; the document is used for configuration, not retained. */
    public static MuSigTradeProcessor<?> create(ServiceProvider serviceProvider, MusigGrpcClient musigGrpcClient,
                                                MuSigV2Trade<?> trade, Clock clock) {
        return switch (trade.getMyRole()) {
            case SELLER_AS_TAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    SellerAsTakerState.class,
                    new SellerAsTakerProtocol(),
                    clock);
            case BUYER_AS_TAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    BuyerAsTakerState.class,
                    new BuyerAsTakerProtocol(),
                    clock);
            case BUYER_AS_MAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    BuyerAsMakerState.class,
                    new BuyerAsMakerProtocol(),
                    clock);
            case SELLER_AS_MAKER -> assemble(serviceProvider,
                    musigGrpcClient,
                    trade,
                    SellerAsMakerState.class,
                    new SellerAsMakerProtocol(),
                    clock);
        };
    }

    static <S extends MuSigTradeExecutionState> MuSigTradeProcessor<S> assemble(
            ServiceProvider serviceProvider,
            MusigGrpcClient musigGrpcClient,
            MuSigV2Trade<?> trade,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            Clock clock) {
        Function<MuSigV2Trade<S>, MuSigProtocolContext> contextFactory = snapshot ->
                new MuSigProtocolContext(serviceProvider, musigGrpcClient, snapshot, clock);
        Function<MuSigV2Trade<S>, MuSigReadOnlyContext> readOnlyContext = contextFactory::apply;
        BiFunction<MuSigV2Trade<S>, Event, MuSigProtocolContext> handlerContext =
                (snapshot, event) -> contextFactory.apply(snapshot);
        var flows = new ArrayList<FlowRegistration<MuSigV2Trade<S>, Event, Flow>>();
        flows.add(new MuSigProtocol().bind(Flow.TRADE,
                Optional.empty(),
                snapshot -> snapshot,
                MuSigV2Trade::withProtocol,
                readOnlyContext,
                handlerContext));
        flows.add(executionProtocol.bind(Flow.EXECUTION,
                Optional.of(Flow.TRADE),
                MuSigV2Trade::getExecution,
                MuSigV2Trade::withExecution,
                readOnlyContext,
                handlerContext));
        if (trade.getMediation().isPresent()) {
            flows.add(new MuSigMediationProtocol().bind(Flow.MEDIATION,
                    Optional.of(Flow.TRADE),
                    snapshot -> snapshot.getMediation().orElseThrow(),
                    MuSigV2Trade::withMediation,
                    readOnlyContext,
                    handlerContext));
            flows.add(new MuSigMyMediationDecisionProtocol().bind(Flow.MY_MEDIATION_DECISION,
                    Optional.of(Flow.MEDIATION),
                    snapshot -> snapshot.getMediation().orElseThrow().myDecision(),
                    MuSigV2Trade::withMyMediationDecision,
                    readOnlyContext,
                    handlerContext));
            flows.add(new MuSigPeerMediationDecisionProtocol().bind(Flow.PEER_MEDIATION_DECISION,
                    Optional.of(Flow.MEDIATION),
                    snapshot -> snapshot.getMediation().orElseThrow().peerDecision(),
                    MuSigV2Trade::withPeerMediationDecision,
                    readOnlyContext,
                    handlerContext));
        }
        var processor = new MuSigTradeProcessor<>(serviceProvider, stateFamily, contextFactory, flows);
        processor.tradeForRole(trade);
        return processor;
    }

    private MuSigTradeProcessor(ServiceProvider serviceProvider,
                                Class<S> stateFamily,
                                Function<MuSigV2Trade<S>, MuSigProtocolContext> contextFactory,
                                List<FlowRegistration<MuSigV2Trade<S>, Event, Flow>> flowRegistrations) {
        super(flowRegistrations);
        this.serviceProvider = serviceProvider;
        this.stateFamily = stateFamily;
        this.contextFactory = contextFactory;
    }

    MuSigV2Trade<S> tradeForRole(MuSigV2Trade<?> trade) {
        checkArgument(stateFamily.isInstance(trade.getExecution().state()),
                "Execution state does not match the trade role");
        // The document is immutable, and its execution state family was checked above.
        @SuppressWarnings("unchecked")
        var typedTrade = (MuSigV2Trade<S>) trade;
        return typedTrade;
    }


    /* --------------------------------------------------------------------- */
    // Input validation
    /* --------------------------------------------------------------------- */

    @Override
    public void validateInput(StateMachineSnapshot<MuSigV2Trade<S>, Flow> snapshot, Event event) {
        try {
            validateInput(snapshot.document(), event);
        } catch (RuntimeException failure) {
            throw onInputRejected(failure);
        }
    }

    void validateInput(MuSigV2Trade<S> snapshot, Event event) {
        checkArgument(!(event instanceof InternalEvent) && !(event instanceof ExecutionFailedEvent) &&
                        !(event instanceof MuSigProcessingFailedEvent),
                "Internal facts must come from the protocol");
        if (event instanceof MuSigTradeMessage message) {
            message.verify();
            var context = contextFactory.apply(snapshot);
            checkArgument(message.getTradeId().equals(context.tradeId()), "Wrong trade");
            checkArgument(message.getSender().equals(context.peerNetworkId()), "Wrong peer");
            checkArgument(message.getReceiver().equals(context.myNetworkId()), "Wrong receiver");
            checkArgument(message.getProtocolVersion().equals(context.protocolVersion()), "Wrong protocol version");
        } else if (event instanceof MediationOpenedEvent opened) {
            validateMediatorMessage(snapshot, opened.getMessage(), MediationCaseState.OPEN);
        } else if (event instanceof MediationClosedEvent closed) {
            validateMediatorMessage(snapshot, closed.getMessage(), MediationCaseState.CLOSED);
        } else if (event instanceof MediationPaymentDetailsRequestedEvent requested) {
            var message = requested.getMessage();
            message.verify();
            validateMediator(snapshot, message.getTradeId(), message.getSenderNetworkId());
        }
        if (event instanceof DepositConfirmationSkippedEvent || event instanceof MediationRequestedEvent) {
            admitCommand(snapshot, event);
        }
        if (event instanceof MediationCommand) {
            checkState(snapshot.getMediation().isPresent(), "No mediator is assigned to this trade");
        }
    }

    private void validateMediatorMessage(MuSigV2Trade<S> snapshot,
                                         MuSigMediationStateChangeMessage message,
                                         MediationCaseState expected) {
        message.verify();
        validateMediator(snapshot, message.getTradeId(), message.getSenderNetworkId());
        checkArgument(message.getMediationCaseState() == expected, "Wrong mediation case state");
    }

    private void validateMediator(MuSigV2Trade<S> snapshot, String tradeId, NetworkId sender) {
        var context = contextFactory.apply(snapshot);
        checkArgument(tradeId.equals(context.tradeId()), "Wrong mediation trade");
        var mediator = context.contract().getMediator()
                .orElseThrow(() -> new IllegalArgumentException("No mediator is assigned to this trade"));
        checkArgument(sender.equals(mediator.getNetworkId()), "Wrong mediator");
    }


    /* --------------------------------------------------------------------- */
    // Flow transitions and failure handling
    /* --------------------------------------------------------------------- */

    @Override
    protected void admitCommand(MuSigV2Trade<S> trade, Event event) {
        if (event instanceof MediationRequestedEvent) {
            // Like V1, mediation remains available during a trading halt, but not to a banned local trader.
            checkArgument(!serviceProvider.getUserService().getBannedUserService()
                            .isUserProfileBanned(trade.getMyIdentity().getNetworkId()),
                    "Cannot request mediation because the local trader is banned");
        } else if (event instanceof DepositConfirmationSkippedEvent) {
            checkState(DevMode.isDevMode(), "Skipping deposit confirmation is only available in development mode");
            checkState(trade.getState() instanceof MuSigTradeState.Active, "Trade is not active");
            checkState(trade.getExecution().state() instanceof MuSigTradeExecutionState.AccountPayloadReceived,
                    "Trade is not waiting for deposit confirmation");
        }
    }

    @Override
    protected Optional<Applied<MuSigV2Trade<S>, Event>> apply(
            MuSigV2Trade<S> snapshot,
            Delivery<Flow, Event> delivery) {
        try {
            return super.apply(snapshot, delivery);
        } catch (RuntimeException failure) {
            throw MuSigProtocolException.processingFailure(failure);
        }
    }

    @Override
    protected RuntimeException onInputRejected(RuntimeException failure) {
        return new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED, failure.getMessage(), failure);
    }

    @Override
    protected Optional<Event> onProcessingFailure(
            MuSigV2Trade<S> snapshot,
            Delivery<Flow, Event> delivery,
            RuntimeException failure) {
        if (delivery.event() instanceof MuSigProcessingFailedEvent) {
            return Optional.empty();
        }
        var classified = MuSigProtocolException.processingFailure(failure);
        log.warn("Trade {}: {} while handling {}",
                snapshot.getId(),
                classified.getCategory(),
                delivery.event().getClass().getSimpleName(),
                classified);
        return Optional.of(MuSigProcessingFailedEvent.fromException(classified));
    }
}
