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

package bisq.trade.mu_sig_v2;

import bisq.account.accounts.AccountPayload;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.processing.Effect;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.timer.Clock;
import bisq.contract.Party;
import bisq.contract.Role;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationRequest;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.BuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.user.identity.UserIdentity;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
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

class MuSigMediationAdmissionTest {
    private final Clock clock = () -> 1_750_000_000_000L;
    private final MusigGrpcClient grpcClient = mock(MusigGrpcClient.class, RETURNS_DEEP_STUBS);
    private final ServiceProvider services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
    private final MuSigContract contract = mock(MuSigContract.class, RETURNS_DEEP_STUBS);
    private final Identity identity = mock(Identity.class);
    private final NetworkId mediatorNetworkId = networkId("mediator");
    private final List<Effect> effects = new ArrayList<>();

    @ParameterizedTest
    @MethodSource("executionRoles")
    <S extends MuSigTradeExecutionState> void paymentDetailsAreAnsweredOnceWithoutChangingEitherFlow(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol) {
        configureTrade(role);
        S state = stateFamily.cast(new MuSigTradeExecutionState.DepositTxConfirmed());
        var peerPayload = mock(AccountPayload.class);
        for (MuSigMediationState caseState : List.of(new MuSigMediationState.Requested(),
                new MuSigMediationState.Open(),
                new MuSigMediationState.Closed())) {
            effects.clear();
            var trade = tradeWithResult(role, state);
            var execution = trade.getExecution();
            var caseData = caseState instanceof MuSigMediationState.Closed
                    ? trade.getMediation().orElseThrow().data() : MuSigV2MediationData.initial();
            trade = trade.withExecution(execution.to(state,
                            execution.data().toBuilder().peerAccountPayload(Optional.of(peerPayload)).build()))
                    .withMediation(FlowSnapshot.of(caseState, caseData));
            try (var lane = new MuSigTradeLaneFixture<>(services, grpcClient,
                    trade,
                    stateFamily,
                    executionProtocol,
                    effects::add,
                    ignored -> {},
                    clock)) {
                var request = new MediationPaymentDetailsRequestedEvent(
                        new MuSigDisputeCasePaymentDetailsRequest(trade.getId(), mediatorNetworkId));

                lane.handle(request);
                var answered = lane.getTrade();
                lane.handle(request);

                assertSame(answered, lane.getTrade());
                assertSame(trade.getExecution(), answered.getExecution());
                assertSame(caseState, answered.getMediation().orElseThrow().state());
                assertSame(caseData, answered.getMediation().orElseThrow().data());
                assertTrue(lane.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                        .contains(MediationPaymentDetailsRequestedEvent.class));
                assertEquals(1, effects.size());
                var send = assertInstanceOf(Effect.SendMessage.class, effects.getFirst());
                var response = assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, send.message());
                assertEquals(trade.getId(), response.getTradeId());
                assertSame(identity.getNetworkId(), response.getSenderNetworkId());
                assertSame(mediatorNetworkId, send.receiver());
                assertSame(role.isMaker() ? peerPayload : trade.getMyAccountPayload(), response.getTakerAccountPayload());
                assertSame(role.isMaker() ? trade.getMyAccountPayload() : peerPayload, response.getMakerAccountPayload());
            }
        }
        verifyNoInteractions(grpcClient.getBlockingStub());
    }

    @ParameterizedTest
    @MethodSource("depositBoundaries")
    <S extends MuSigTradeExecutionState> void requestingRequiresTheDepositToBePublishedOrReceived(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            S beforeDeposit,
            S depositAvailable) {
        configureTrade(role);
        var caseProtocol = new MuSigMediationProtocol();
        try (var early = createLane(role, stateFamily, executionProtocol, beforeDeposit)) {
            var before = early.getTrade();
            var earlyContext = new MuSigProtocolContext(services, grpcClient, before, clock);

            assertThrows(IllegalStateException.class, () -> caseProtocol.admit(
                    earlyContext,
                    before.getMediation().orElseThrow(),
                    new MediationRequestedEvent()));
            assertRejected(() -> early.handle(new MediationRequestedEvent()));
            early.resume();

            assertSame(before, early.getTrade());
            assertTrue(effects.isEmpty());
            assertTrue(early.getSavedRecord().pendingInputs().isEmpty());
            assertTrue(early.getProcessedEventTypes().getOrDefault(Flow.MEDIATION, Set.of()).isEmpty());
        }

        try (var ready = createLane(role, stateFamily, executionProtocol, depositAvailable)) {
            assertEquals(depositAvailable instanceof AccountPayloadReceived
                    ? List.of(new ObserveDepositTxConfirmation()) : List.of(), effects);
            effects.clear();
            var execution = ready.getTrade().getExecution();
            var readyContext = new MuSigProtocolContext(services, grpcClient, ready.getTrade(), clock);
            assertDoesNotThrow(() -> caseProtocol.admit(
                    readyContext,
                    ready.getTrade().getMediation().orElseThrow(),
                    new MediationRequestedEvent()));
            ready.handle(new MediationRequestedEvent());

            assertSame(execution, ready.getTrade().getExecution());
            assertInstanceOf(MuSigMediationState.Requested.class, ready.getTrade().getMediation().orElseThrow().state());
            assertEquals(2, effects.size());
            assertInstanceOf(ActivateMediationChat.class, effects.getFirst());
            var send = assertInstanceOf(Effect.SendMessage.class, effects.getLast());
            var request = assertInstanceOf(MuSigMediationRequest.class, send.message());
            assertEquals(ready.getTrade().getId(), request.getTradeId());
            assertSame(mediatorNetworkId, send.receiver());
        }
    }

    @ParameterizedTest
    @MethodSource("depositBoundaries")
    <S extends MuSigTradeExecutionState> void rejectingASignedResultRequiresTheDepositToBePublishedOrReceived(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            S beforeDeposit,
            S depositAvailable) {
        configureTrade(role);
        var decisionProtocol = new MuSigMyMediationDecisionProtocol();
        try (var early = createLaneWithResult(role, stateFamily, executionProtocol, beforeDeposit)) {
            var before = early.getTrade();
            var earlyContext = new MuSigProtocolContext(services, grpcClient, before, clock);

            assertThrows(IllegalStateException.class, () -> decisionProtocol.admit(
                    earlyContext,
                    before.getMediation().orElseThrow().myDecision(),
                    new MediationResultRejectedEvent()));
            assertRejected(() -> early.handle(new MediationResultRejectedEvent()));
            early.resume();

            assertSame(before, early.getTrade());
            assertTrue(effects.isEmpty());
            assertTrue(early.getSavedRecord().pendingInputs().isEmpty());
            assertTrue(early.getProcessedEventTypes().getOrDefault(Flow.MY_MEDIATION_DECISION, Set.of()).isEmpty());
        }

        try (var ready = createLaneWithResult(role, stateFamily, executionProtocol, depositAvailable)) {
            assertEquals(depositAvailable instanceof AccountPayloadReceived
                    ? List.of(new ObserveDepositTxConfirmation()) : List.of(), effects);
            effects.clear();
            var execution = ready.getTrade().getExecution();
            var readyContext = new MuSigProtocolContext(services, grpcClient, ready.getTrade(), clock);
            assertDoesNotThrow(() -> decisionProtocol.admit(readyContext,
                    ready.getTrade().getMediation().orElseThrow().myDecision(),
                    new MediationResultRejectedEvent()));
            ready.handle(new MediationResultRejectedEvent());
            var rejected = ready.getTrade();
            ready.handle(new MediationResultRejectedEvent());

            assertSame(rejected, ready.getTrade());
            assertSame(execution, rejected.getExecution());
            assertInstanceOf(MuSigMediationState.Closed.class, rejected.getMediation().orElseThrow().state());
            assertInstanceOf(MuSigMediationDecisionState.Rejected.class,
                    rejected.getMediation().orElseThrow().myDecision().state());
            assertEquals(1, effects.size());
            var send = assertInstanceOf(Effect.SendMessage.class, effects.getFirst());
            var message = assertInstanceOf(MuSigMediationResultRejectionMessage.class, send.message());
            assertEquals(rejected.getId(), message.getTradeId());
            assertSame(message.getReceiver(), send.receiver());
        }
        verifyNoInteractions(grpcClient.getBlockingStub());
    }

    @ParameterizedTest
    @MethodSource("executionRoles")
    <S extends MuSigTradeExecutionState> void acceptanceAdmissionMatchesExecutionSigningRulesInEveryState(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol) throws ReflectiveOperationException {
        configureTrade(role);
        var decisionProtocol = new MuSigMyMediationDecisionProtocol();
        for (Class<?> stateClass : stateFamily.getPermittedSubclasses()) {
            S state = stateFamily.cast(stateClass.getDeclaredConstructor().newInstance());
            var trade = tradeWithResult(role, state);
            var context = new MuSigProtocolContext(services, grpcClient, trade, clock);
            var decision = trade.getMediation().orElseThrow().myDecision();
            var command = new MediationResultAcceptedEvent();

            if (executionProtocol.canHandle(state, MediationResultByMyselfAcceptedEvent.class)) {
                assertDoesNotThrow(() -> decisionProtocol.admit(context, decision, command), stateClass.getSimpleName());
            } else {
                assertThrows(IllegalStateException.class,
                        () -> decisionProtocol.admit(context, decision, command),
                        stateClass.getSimpleName());
            }
        }
        assertTrue(effects.isEmpty());
        verifyNoInteractions(grpcClient.getBlockingStub());
    }

    @ParameterizedTest
    @MethodSource("depositBoundaries")
    <S extends MuSigTradeExecutionState> void flowViewsStayBoundToTheirSnapshotWhileNewContextsSeeReplacements(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            S beforeDeposit,
            S depositAvailable) {
        configureTrade(role);
        var before = tradeWithResult(role, beforeDeposit);
        MuSigReadOnlyContext original = new MuSigProtocolContext(services, grpcClient, before, clock);
        String txId = "12".repeat(32);
        var execution = before.getExecution().data().toBuilder()
                .myCustomPayoutPsbt(Optional.of(new CustomPayoutPsbt(new byte[]{1}, txId, 100_000, 100_000)))
                .peerAccountPayload(Optional.of(mock(AccountPayload.class)))
                .build();
        var mediation = before.getMediation().orElseThrow();
        var peerDecision = mediation.peerDecision();
        var after = before.withExecution(FlowSnapshot.of(depositAvailable, execution))
                .withPeerMediationDecision(peerDecision.to(new MuSigMediationDecisionState.Rejected(),
                        peerDecision.data()));
        MuSigReadOnlyContext updated = new MuSigProtocolContext(services, grpcClient, after, clock);

        assertSame(beforeDeposit, original.execution().state());
        assertTrue(original.execution().data().myCustomPayoutTxId().isEmpty());
        assertTrue(original.execution().data().peerAccountPayload().isEmpty());
        assertInstanceOf(MuSigMediationDecisionState.Init.class,
                original.mediation().orElseThrow().data().peerDecisionState());

        assertSame(depositAvailable, updated.execution().state());
        assertEquals(Optional.of(txId), updated.execution().data().myCustomPayoutTxId());
        assertEquals(execution.getPeerAccountPayload(), updated.execution().data().peerAccountPayload());
        assertInstanceOf(MuSigMediationDecisionState.Rejected.class,
                updated.mediation().orElseThrow().data().peerDecisionState());
        assertSame(mediation.data().signedResult().orElseThrow(),
                updated.mediation().orElseThrow().data().signedResult().orElseThrow());
        verifyNoInteractions(grpcClient.getBlockingStub());
    }

    private static Stream<Arguments> executionRoles() {
        return Stream.of(
                Arguments.of(TradeRole.BUYER_AS_MAKER, BuyerAsMakerState.class, new BuyerAsMakerProtocol()),
                Arguments.of(TradeRole.SELLER_AS_MAKER, SellerAsMakerState.class, new SellerAsMakerProtocol()),
                Arguments.of(TradeRole.BUYER_AS_TAKER, BuyerAsTakerState.class, new BuyerAsTakerProtocol()),
                Arguments.of(TradeRole.SELLER_AS_TAKER, SellerAsTakerState.class, new SellerAsTakerProtocol()));
    }

    private static Stream<Arguments> depositBoundaries() {
        return Stream.of(
                Arguments.of(TradeRole.BUYER_AS_MAKER,
                        BuyerAsMakerState.class,
                        new BuyerAsMakerProtocol(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived()),
                Arguments.of(TradeRole.SELLER_AS_MAKER,
                        SellerAsMakerState.class,
                        new SellerAsMakerProtocol(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived()),
                Arguments.of(TradeRole.BUYER_AS_TAKER,
                        BuyerAsTakerState.class,
                        new BuyerAsTakerProtocol(),
                        new NonceSharesAndPartialSignaturesCreated(),
                        new DepositTxSignedAndPublished()),
                Arguments.of(TradeRole.SELLER_AS_TAKER,
                        SellerAsTakerState.class,
                        new SellerAsTakerProtocol(),
                        new NonceSharesAndPartialSignaturesCreated(),
                        new DepositTxSignedAndPublished()));
    }

    private <S extends MuSigTradeExecutionState> MuSigTradeLaneFixture<S> createLane(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            S state) {
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                identity,
                role,
                "1.0.0",
                mock(AccountPayload.class),
                state);
        return new MuSigTradeLaneFixture<>(services, grpcClient,
                trade,
                stateFamily,
                executionProtocol,
                effects::add,
                ignored -> {},
                clock);
    }

    private <S extends MuSigTradeExecutionState> MuSigTradeLaneFixture<S> createLaneWithResult(
            TradeRole role,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> executionProtocol,
            S state) {
        return new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeWithResult(role, state),
                stateFamily,
                executionProtocol,
                effects::add,
                ignored -> {},
                clock);
    }

    private <S extends MuSigTradeExecutionState> MuSigV2Trade<S> tradeWithResult(TradeRole role, S state) {
        var result = new MuSigMediationResult(new byte[20],
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(100_000L),
                Optional.of(100_000L),
                Optional.empty(),
                Optional.empty());
        var mediation = MuSigV2MediationData.initial().withResult(new MuSigV2MediationData.SignedResult(result,
                new byte[]{1}));
        return MuSigV2TradeTestFactory.tradeAt(contract, identity, role, "1.0.0", mock(AccountPayload.class), state)
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Closed(), mediation));
    }

    private void configureTrade(TradeRole role) {
        var maker = networkId("maker");
        var taker = networkId("taker");
        var makerProfile = profile(maker);
        var takerProfile = profile(taker);
        var mediatorProfile = profile(mediatorNetworkId);
        when(contract.getOffer().getId()).thenReturn("offer");
        when(contract.getMaker()).thenReturn(new Party(Role.MAKER, maker));
        when(contract.getTaker()).thenReturn(new Party(Role.TAKER, taker));
        when(contract.toProto(false)).thenReturn(bisq.contract.protobuf.Contract.getDefaultInstance());
        when(contract.getMediator()).thenReturn(Optional.of(mediatorProfile));
        when(identity.getId()).thenReturn(role.isMaker() ? "maker" : "taker");
        when(identity.getNetworkId()).thenReturn(role.isMaker() ? maker : taker);
        var profiles = services.getUserService().getUserProfileService();
        when(profiles.findUserProfile("maker")).thenReturn(Optional.of(makerProfile));
        when(profiles.findUserProfile("taker")).thenReturn(Optional.of(takerProfile));
        var channels = services.getChatService().getMuSigOpenTradeChannelService();
        var userIdentity = mock(UserIdentity.class);
        when(userIdentity.getUserName()).thenReturn(role.isMaker() ? "maker" : "taker");
        var channel = mock(MuSigOpenTradeChannel.class);
        when(channel.getMyUserIdentity()).thenReturn(userIdentity);
        when(channel.getChatMessages()).thenReturn(new ObservableSet<>());
        when(channels.findChannelByTradeId(TradeV2.createId("offer", "taker", 0)))
                .thenReturn(Optional.of(channel));
    }

    private static NetworkId networkId(String id) {
        var networkId = mock(NetworkId.class);
        when(networkId.getId()).thenReturn(id);
        when(networkId.toProto(false)).thenReturn(bisq.network.identity.protobuf.NetworkId.getDefaultInstance());
        return networkId;
    }

    private static UserProfile profile(NetworkId networkId) {
        String id = networkId.getId();
        var profile = mock(UserProfile.class);
        when(profile.getId()).thenReturn(id);
        when(profile.getUserName()).thenReturn(id);
        when(profile.getNetworkId()).thenReturn(networkId);
        when(profile.toProto(false)).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        return profile;
    }
}
