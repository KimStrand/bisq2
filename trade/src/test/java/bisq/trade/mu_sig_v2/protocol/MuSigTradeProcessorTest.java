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
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.processing.HandleResult;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.processing.lane.InputValidator;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.contract.Party;
import bisq.contract.Role;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigSellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.user.banned.BannedUserService;
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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigTradeProcessorTest {
    private final InputValidator<StateMachineSnapshot<? extends MuSigV2Trade<?>, Flow>, Event> inputValidator =
            MuSigV2TradeTestFactory.inputValidator(mock(BannedUserService.class),
                    mock(MuSigOpenTradeChannelService.class));
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
        inputValidator.validate(snapshot, peerMessage);

        assertInstanceOf(HandleResult.Deferred.class,
                processor.handle(snapshot, peerMessage));

        var command = new PaymentReceiptConfirmedEvent();
        var result = assertInstanceOf(HandleResult.Rejected.class,
                processor.handle(snapshot, command));
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
        List<Event> invalidInputs = List.of(paymentInitiated(trade, taker),
                mediationOpenedMessage(trade, maker),
                new ExecutionFailedEvent());

        for (var event : invalidInputs) {
            var failure = assertThrows(MuSigProtocolException.class, () -> inputValidator.validate(snapshot, event));
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        }
        assertDoesNotThrow(() -> inputValidator.validate(snapshot, paymentInitiated(trade, maker)));
        assertDoesNotThrow(() -> inputValidator.validate(snapshot,
                mediationOpenedMessage(trade, mediator)));
        assertTrue(snapshot.processedEventTypes().isEmpty());
        verifyNoInteractions(services);
    }

    @Test
    void partiesAreRecognizedByTheirKeyAfterTheirAddressesChanged() {
        var trade = tradeAt(new MuSigTradeExecutionState.Init());
        var snapshot = snapshot(trade);
        // A network ID with the same key but other addresses, for example after its owner added a transport.
        var makerWithOtherAddresses = networkId("maker");
        var takerWithOtherAddresses = networkId("taker");
        var mediatorWithOtherAddresses = networkId("mediator");
        assertNotEquals(maker, makerWithOtherAddresses);

        assertDoesNotThrow(() -> inputValidator.validate(snapshot,
                paymentInitiated(trade, makerWithOtherAddresses, takerWithOtherAddresses)));
        assertDoesNotThrow(() -> inputValidator.validate(snapshot,
                mediationOpenedMessage(trade, mediatorWithOtherAddresses)));

        List<Event> otherKeys = List.of(paymentInitiated(trade, networkId("other"), taker),
                paymentInitiated(trade, maker, networkId("other")),
                mediationOpenedMessage(trade, networkId("other")));
        for (var event : otherKeys) {
            var failure = assertThrows(MuSigProtocolException.class, () -> inputValidator.validate(snapshot, event));
            assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        }
    }

    @Test
    void mediationTransitionReturnsOutgoingWorkAndUsesTheSuppliedHistory() {
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        trade = trade.withMediation(FlowSnapshot.of(new MuSigMediationState.Requested(),
                trade.getMediation().orElseThrow().data()));
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var event = mediationOpenedMessage(trade, mediator);
        inputValidator.validate(snapshot, event);
        var result = applied(processor.handle(snapshot, event));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigMediationState.Open.class, next.document().getMediation().orElseThrow().state());
        assertSame(trade.getExecution(), next.document().getExecution());
        assertInstanceOf(MuSigMediationState.Requested.class, trade.getMediation().orElseThrow().state());
        assertEquals(Map.of(Flow.MEDIATION, Set.of(MuSigMediationOpenedMessage.class)), next.processedEventTypes());
        assertInstanceOf(ActivateMediationChat.class, result.output().effects().getFirst());
        assertInstanceOf(HandleResult.Ignored.class, processor.handle(next, event));
        // The processor retains neither the new document nor its history.
        assertTrue(snapshot.processedEventTypes().isEmpty());
        verifyNoInteractions(services.getChatService(), services.getNetworkService());
    }

    @Test
    void executionFailureReturnsTheParentReactionWithoutProcessingIt() {
        // Missing partial signatures represent an invalid local document at this stage.
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var message = paymentInitiated(trade, maker);
        inputValidator.validate(snapshot, message);

        var result = failed(processor.handle(snapshot, message));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigTradeExecutionState.Failed.class, next.document().getExecution().state());
        assertInstanceOf(MuSigTradeState.Active.class, next.document().getState());
        assertSame(trade.getMediation().orElseThrow(), next.document().getMediation().orElseThrow());
        assertEquals(MuSigFailureCategory.PROTOCOL_FAILED,
                assertInstanceOf(MuSigProtocolException.class, result.cause()).getCategory());
        assertEquals(Map.of(Flow.EXECUTION, Set.of(MuSigProcessingFailedEvent.class)), next.processedEventTypes());
        var send = result.output().effects().stream().filter(SendMessage.class::isInstance)
                .map(SendMessage.class::cast).findFirst().orElseThrow();
        assertInstanceOf(MuSigReportErrorMessage.class, send.message());
        var raised = assertInstanceOf(ExecutionFailedEvent.class, result.output().raisedInputs().getFirst());

        // The owner checkpoints the failure and then submits the raised reaction as another step.
        var parent = applied(processor.handle(next, raised));
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
        // The assigned mediator closes the case with a result which its signature does not cover.
        var event = MuSigV2TradeTestFactory.unverifiedMediationClosedMessage(trade.getId(), mediator);
        inputValidator.validate(snapshot, event);

        var result = failed(processor.handle(snapshot, event));
        var next = result.nextSnapshot();

        assertInstanceOf(MuSigMediationState.Failed.class, next.document().getMediation().orElseThrow().state());
        assertSame(trade.getExecution(), next.document().getExecution());
        assertInstanceOf(MuSigTradeState.Active.class, next.document().getState());
        assertTrue(result.output().raisedInputs().isEmpty());
        assertEquals(List.of(new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text("Mediation case processing failed"))),
                result.output().effects());
        assertInstanceOf(HandleResult.Rejected.class, processor.handle(next,
                new MediationResultAcceptedEvent()));
    }

    @Test
    void recoveryRequestsDepositObservationOnlyWhileExecutionIsWaitingAndEnabled() {
        var waiting = tradeAt(new MuSigTradeExecutionState.AccountPayloadReceived());
        var processor = processor(waiting);

        assertEquals(List.of(new ObserveDepositTxConfirmation()), processor.createRecoveryEffects(snapshot(waiting)));
        assertTrue(processor.createRecoveryEffects(snapshot(tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed())))
                .isEmpty());
        assertTrue(processor.createRecoveryEffects(snapshot(tradeAt(new MuSigTradeExecutionState.Failed())))
                .isEmpty());
        var failedTrade = waiting.withTradeFlow(FlowSnapshot.of(new MuSigTradeState.Failed(), waiting.getData()));
        var historicalTrade = waiting.withTradeFlow(FlowSnapshot.of(new MuSigTradeState.Historical(),
                waiting.getData()));
        assertTrue(processor.createRecoveryEffects(snapshot(failedTrade)).isEmpty());
        assertTrue(processor.createRecoveryEffects(snapshot(historicalTrade)).isEmpty());
        var mediationFailed = waiting.withMediation(FlowSnapshot.of(new MuSigMediationState.Failed(),
                waiting.getMediation().orElseThrow().data()));
        assertEquals(List.of(new ObserveDepositTxConfirmation()), processor.createRecoveryEffects(snapshot(mediationFailed)));
        verifyNoInteractions(services);
    }

    @Test
    void absentMediatorRejectsMediationWhileParentAndExecutionRemainRoutable() {
        when(contract.getMediator()).thenReturn(Optional.empty());
        var trade = tradeAt(new MuSigTradeExecutionState.DepositTxConfirmed());
        var processor = processor(trade);
        var snapshot = snapshot(trade);
        var request = new MediationRequestedEvent();

        var rejected = assertInstanceOf(HandleResult.Rejected.class, processor.handle(snapshot, request));
        var failure = assertInstanceOf(MuSigProtocolException.class, rejected.cause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());

        var payment = paymentInitiated(trade, maker);
        var execution = failed(processor.handle(snapshot, payment));
        assertInstanceOf(MuSigTradeExecutionState.Failed.class, execution.nextSnapshot().document().getExecution().state());
        assertEquals(Set.of(MuSigProcessingFailedEvent.class), execution.nextSnapshot().processedEventTypes().get(Flow.EXECUTION));
        var executionFailed = new ExecutionFailedEvent();
        assertEquals(Set.of(executionFailed.getClass()), applied(processor.handle(snapshot, executionFailed)).nextSnapshot().processedEventTypes().get(Flow.TRADE));
    }

    private MuSigV2Trade<MuSigSellerAsTakerState> tradeAt(MuSigSellerAsTakerState state) {
        return MuSigV2TradeTestFactory.tradeAt(contract,
                identity,
                TradeRole.SELLER_AS_TAKER,
                MuSigProtocol.VERSION,
                mock(AccountPayload.class),
                state);
    }

    private MuSigTradeProcessor<MuSigSellerAsTakerState> processor(MuSigV2Trade<MuSigSellerAsTakerState> trade) {
        return MuSigTradeProcessor.assemble(services, grpcClient,
                trade,
                new MuSigSellerAsTakerProtocol(),
                () -> 1_750_000_000_000L);
    }

    private StateMachineSnapshot<MuSigV2Trade<MuSigSellerAsTakerState>, Flow> snapshot(
            MuSigV2Trade<MuSigSellerAsTakerState> trade) {
        return new StateMachineSnapshot<>(trade, Map.of());
    }

    private PaymentInitiatedMessage_E paymentInitiated(MuSigV2Trade<?> trade, NetworkId sender) {
        return paymentInitiated(trade, sender, taker);
    }

    private PaymentInitiatedMessage_E paymentInitiated(MuSigV2Trade<?> trade, NetworkId sender, NetworkId receiver) {
        return new PaymentInitiatedMessage_E(UUID.randomUUID().toString(),
                trade.getId(),
                trade.getProtocolVersion(),
                sender,
                receiver,
                new byte[]{1});
    }

    private MuSigMediationOpenedMessage mediationOpenedMessage(MuSigV2Trade<?> trade, NetworkId sender) {
        return new MuSigMediationOpenedMessage(UUID.randomUUID().toString(), trade.getId(), sender);
    }

    private static NetworkId networkId(String id) {
        var networkId = mock(NetworkId.class);
        when(networkId.getId()).thenReturn(id);
        return networkId;
    }

    private static <S, I> HandleResult.Applied<S, I> applied(HandleResult<S, I> result) {
        assertInstanceOf(HandleResult.Applied.class, result);
        return (HandleResult.Applied<S, I>) result;
    }

    private static <S, I> HandleResult.Failed<S, I> failed(HandleResult<S, I> result) {
        assertInstanceOf(HandleResult.Failed.class, result);
        return (HandleResult.Failed<S, I>) result;
    }
}
