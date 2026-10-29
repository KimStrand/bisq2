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

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.Effect;
import bisq.common.processing.StepResult;
import bisq.common.statemachine.Delivery;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.contract.Party;
import bisq.contract.Role;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigTradeProcessorTest {
    private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class, RETURNS_DEEP_STUBS);
    private final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
    private final MuSigContract contract = mock(MuSigContract.class, RETURNS_DEEP_STUBS);
    private final Identity identity = mock(Identity.class);
    private final NetworkId maker = networkId("maker");
    private final NetworkId taker = networkId("taker");
    private final NetworkId mediator = networkId("mediator");

    @BeforeEach
    void setUp() {
        when(contract.getOffer().getId()).thenReturn("offer");
        when(contract.getMaker()).thenReturn(new Party(Role.MAKER, maker));
        when(contract.getTaker()).thenReturn(new Party(Role.TAKER, taker));
        var mediatorProfile = mock(UserProfile.class);
        when(mediatorProfile.getId()).thenReturn("mediator");
        when(mediatorProfile.getNetworkId()).thenReturn(mediator);
        when(contract.getMediator()).thenReturn(Optional.of(mediatorProfile));
        when(identity.getNetworkId()).thenReturn(taker);
    }

    @Test
    void earlyPeerInputIsDeferredButAnUnavailableCommandIsRejected() {
        var trade = tradeAt(new MuSigTradeExecutionState.Init());
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var peerMessage = paymentInitiated(trade, maker);
        processor.validateInput(snapshot, peerMessage);

        assertInstanceOf(StepResult.Deferred.class,
                processor.processStep(snapshot, processor.route(snapshot, peerMessage).getFirst()));

        var command = new PaymentReceiptConfirmedEvent();
        var result = assertInstanceOf(StepResult.Rejected.class,
                processor.processStep(snapshot, processor.route(snapshot, command).getFirst()));
        var failure = assertInstanceOf(MuSigProtocolException.class, result.cause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        assertTrue(snapshot.processedEventTypes().isEmpty());
        assertInstanceOf(MuSigTradeExecutionState.Init.class, trade.getExecution().state());
        verifyNoInteractions(services);
    }

    @Test
    void externalValidationRejectsWrongSendersAndInternalFactsBeforeProcessing() {
        var trade = tradeAt(new MuSigTradeExecutionState.Init());
        var snapshot = snapshot(trade);
        var processor = processor(trade);
        List<Event> invalidInputs = List.of(paymentInitiated(trade, taker),
                new MediationOpenedEvent(mediationMessage(trade, maker, MediationCaseState.OPEN)),
                new ExecutionFailedEvent());

        for (var event : invalidInputs) {
            var failure = assertThrows(MuSigProtocolException.class, () -> processor.validateInput(snapshot, event));
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        }
        assertDoesNotThrow(() -> processor.validateInput(snapshot, paymentInitiated(trade, maker)));
        assertDoesNotThrow(() -> processor.validateInput(snapshot,
                new MediationOpenedEvent(mediationMessage(trade, mediator, MediationCaseState.OPEN))));
        assertTrue(snapshot.processedEventTypes().isEmpty());
        verifyNoInteractions(services);
    }

    @Test
    void mediationTransitionReturnsOutgoingWorkAndUsesTheSuppliedHistory() {
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        trade = trade.withMediation(FlowSnapshot.of(new MuSigMediationState.Requested(),
                trade.getMediation().orElseThrow().data()));
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var event = new MediationOpenedEvent(mediationMessage(trade, mediator, MediationCaseState.OPEN));
        processor.validateInput(snapshot, event);
        var delivery = processor.route(snapshot, event).getFirst();

        var result = applied(processor.processStep(snapshot, delivery));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigMediationState.Open.class, next.document().getMediation().orElseThrow().state());
        assertSame(trade.getExecution(), next.document().getExecution());
        assertInstanceOf(MuSigMediationState.Requested.class, trade.getMediation().orElseThrow().state());
        assertEquals(Map.of(Flow.MEDIATION, Set.of(MediationOpenedEvent.class)), next.processedEventTypes());
        assertInstanceOf(ActivateMediationChat.class, result.output().effects().getFirst());
        assertTrue(processor.route(next, event).isEmpty());
        assertInstanceOf(StepResult.Ignored.class, processor.processStep(next, delivery));
        // The processor retains neither the new document nor its history.
        assertEquals(List.of(delivery), processor.route(snapshot, event));
        verifyNoInteractions(services.getChatService(), services.getNetworkService());
    }

    @Test
    void executionFailureReturnsTheParentReactionWithoutProcessingIt() {
        // Missing partial signatures represent an invalid local document at this stage.
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var message = paymentInitiated(trade, maker);
        processor.validateInput(snapshot, message);

        var result = failed(processor.processStep(snapshot, processor.route(snapshot, message).getFirst()));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigTradeExecutionState.Failed.class, next.document().getExecution().state());
        assertInstanceOf(MuSigTradeState.Active.class, next.document().getState());
        assertSame(trade.getMediation().orElseThrow(), next.document().getMediation().orElseThrow());
        assertEquals(MuSigFailureCategory.PROTOCOL_FAILED,
                assertInstanceOf(MuSigProtocolException.class, result.cause()).getCategory());
        assertEquals(Map.of(Flow.EXECUTION, Set.of(MuSigProcessingFailedEvent.class)), next.processedEventTypes());
        var send = result.output().effects().stream().filter(Effect.SendMessage.class::isInstance)
                .map(Effect.SendMessage.class::cast).findFirst().orElseThrow();
        assertInstanceOf(MuSigReportErrorMessage.class, send.message());
        var raised = assertInstanceOf(ExecutionFailedEvent.class, result.output().raisedEvents().getFirst());

        // The owner checkpoints the failure and then submits the raised reaction as another step.
        var parent = applied(processor.processStep(next, processor.route(next, raised).getFirst()));
        assertInstanceOf(MuSigTradeState.Failed.class, parent.nextSnapshot().document().getState());
        assertInstanceOf(MuSigTradeState.Active.class, next.document().getState());
        verifyNoInteractions(services.getNetworkService(), grpcClient.getBlockingStub());
    }

    @Test
    void mediationFailureStopsItsChildrenWithoutFailingExecution() {
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        trade = trade.withMediation(FlowSnapshot.of(new MuSigMediationState.Open(),
                trade.getMediation().orElseThrow().data()));
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        // The assigned mediator closes the case without supplying the required signed result.
        var event = new MediationClosedEvent(mediationMessage(trade, mediator, MediationCaseState.CLOSED));
        processor.validateInput(snapshot, event);

        var result = failed(processor.processStep(snapshot, processor.route(snapshot, event).getFirst()));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigMediationState.Failed.class, next.document().getMediation().orElseThrow().state());
        assertSame(trade.getExecution(), next.document().getExecution());
        assertInstanceOf(MuSigTradeState.Active.class, next.document().getState());
        assertTrue(result.output().raisedEvents().isEmpty());
        assertTrue(result.output().effects().isEmpty());
        assertInstanceOf(StepResult.Rejected.class, processor.processStep(next,
                new Delivery<>(Flow.MY_MEDIATION_DECISION, new MediationResultAcceptedEvent())));
    }

    @Test
    void recoveryRequestsDepositObservationOnlyWhileExecutionIsWaitingAndEnabled() {
        var waiting = tradeAt(new MuSigTradeExecutionState.AccountPayloadReceived());
        var processor = processor(waiting);

        assertEquals(List.of(new ObserveDepositTxConfirmation()), processor.recoveryEffects(snapshot(waiting)));
        assertTrue(processor.recoveryEffects(snapshot(tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed())))
                .isEmpty());
        assertTrue(processor.recoveryEffects(snapshot(tradeAt(new MuSigTradeExecutionState.Failed())))
                .isEmpty());
        var failedTrade = waiting.withProtocol(FlowSnapshot.of(new MuSigTradeState.Failed(), waiting.data()));
        var historicalTrade = waiting.withProtocol(FlowSnapshot.of(new MuSigTradeState.Historical(), waiting.data()));
        assertTrue(processor.recoveryEffects(snapshot(failedTrade)).isEmpty());
        assertTrue(processor.recoveryEffects(snapshot(historicalTrade)).isEmpty());
        var mediationFailed = waiting.withMediation(FlowSnapshot.of(new MuSigMediationState.Failed(),
                waiting.getMediation().orElseThrow().data()));
        assertEquals(List.of(new ObserveDepositTxConfirmation()), processor.recoveryEffects(snapshot(mediationFailed)));
        verifyNoInteractions(services);
    }

    @Test
    void absentMediatorRejectsMediationWhileParentAndExecutionRemainRoutable() {
        when(contract.getMediator()).thenReturn(Optional.empty());
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var request = new MediationRequestedEvent();

        assertTrue(processor.route(snapshot, request).isEmpty());
        var failure = assertThrows(MuSigProtocolException.class,
                () -> processor.validateInput(snapshot, request));
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());

        var payment = paymentInitiated(trade, maker);
        assertEquals(List.of(new Delivery<>(Flow.EXECUTION, payment)), processor.route(snapshot, payment));
        var executionFailed = new ExecutionFailedEvent();
        assertEquals(List.of(new Delivery<>(Flow.TRADE, executionFailed)), processor.route(snapshot, executionFailed));
    }

    private MuSigV2Trade<SellerAsTakerState> tradeAt(SellerAsTakerState state) {
        return MuSigV2TradeTestFactory.tradeAt(contract,
                identity,
                TradeRole.SELLER_AS_TAKER,
                MuSigProtocol.VERSION,
                mock(AccountPayload.class),
                state);
    }

    private MuSigTradeProcessor<SellerAsTakerState> processor(MuSigV2Trade<SellerAsTakerState> trade) {
        return MuSigTradeProcessor.assemble(services, grpcClient,
                trade,
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                () -> 1_750_000_000_000L);
    }

    private StateMachineSnapshot<MuSigV2Trade<SellerAsTakerState>, Flow> snapshot(
            MuSigV2Trade<SellerAsTakerState> trade) {
        return new StateMachineSnapshot<>(trade, Map.of());
    }

    private PaymentInitiatedMessage_E paymentInitiated(MuSigV2Trade<?> trade, NetworkId sender) {
        return new PaymentInitiatedMessage_E(UUID.randomUUID().toString(),
                trade.getId(),
                trade.getProtocolVersion(),
                sender,
                taker,
                new byte[]{1});
    }

    private MuSigMediationStateChangeMessage mediationMessage(MuSigV2Trade<?> trade,
                                                             NetworkId sender,
                                                             MediationCaseState state) {
        return new MuSigMediationStateChangeMessage(UUID.randomUUID().toString(),
                trade.getId(),
                sender,
                state,
                Optional.empty(),
                Optional.empty());
    }

    private static NetworkId networkId(String id) {
        var networkId = mock(NetworkId.class);
        when(networkId.getId()).thenReturn(id);
        return networkId;
    }

    private static <S, I> StepResult.Applied<S, I> applied(StepResult<S, I> result) {
        assertInstanceOf(StepResult.Applied.class, result);
        return (StepResult.Applied<S, I>) result;
    }

    private static <S, I> StepResult.Failed<S, I> failed(StepResult<S, I> result) {
        assertInstanceOf(StepResult.Failed.class, result);
        return (StepResult.Failed<S, I>) result;
    }
}
