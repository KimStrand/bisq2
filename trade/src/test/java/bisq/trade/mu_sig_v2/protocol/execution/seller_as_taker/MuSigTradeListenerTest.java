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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;

import bisq.bonded_roles.BondedRolesService;
import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertService;
import bisq.bonded_roles.security_manager.alert.AlertType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.processing.Effect;
import bisq.common.statemachine.FlowSnapshot;
import bisq.network.SendMessageResult;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsResponse;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.TradeRestrictedException;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.trade.mu_sig_v2.MuSigV2TradeRecord;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.protobuf.PubKeySharesResponse;
import bisq.user.banned.BannedUserService;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenValue;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuSigTradeListenerTest extends SellerAsTakerFixture {
    private final BannedUserService bans = mock(BannedUserService.class);
    private final ObservableSet<AuthorizedAlertData> alerts = new ObservableSet<>();
    private Consumer<Effect> onEffect = effect -> {};
    private MuSigTradeService service = serviceWithClock(services, grpcClient, serviceClock);
    private ConfidentialMessageService.Listener listener = service;

    @BeforeEach
    void initialize() throws Exception {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            var effect = new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1));
            effects.add(effect);
            onEffect.accept(effect);
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        var alertService = mock(AlertService.class);
        when(alertService.getAuthorizedAlertDataSet()).thenReturn(alerts);
        var bondedRoles = mock(BondedRolesService.class);
        when(bondedRoles.getAlertService()).thenReturn(alertService);
        when(services.getBondedRolesService()).thenReturn(bondedRoles);

        when(services.getUserService().getBannedUserService()).thenReturn(bans);
        service.initialize().get(10, TimeUnit.SECONDS);
    }

    @AfterEach
    void shutdown() {
        service.shutdown().join();
    }

    @Test
    void registersOnceAndUnregistersAtShutdown() throws Exception {
        service.initialize().get(10, TimeUnit.SECONDS);
        verify(services.getNetworkService(), times(1)).addConfidentialMessageListener(listener);
        listener.onMessage(setupMessage());

        service.shutdown().get(10, TimeUnit.SECONDS);
        service.shutdown().get(10, TimeUnit.SECONDS);

        verify(services.getNetworkService(), times(1)).removeConfidentialMessageListener(listener);
        assertDoesNotThrow(() -> listener.onMessage(setupMessage()));
        verify(blockingStub(), never()).getNonceShares(any());
    }

    @Test
    void theNetworkCallbackQueuesWorkWithoutWaitingForTheTrade() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        when(blockingStub().initTrade(any())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return PubKeySharesResponse.getDefaultInstance();
        });
        var starting = initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                .thenCompose(trade -> resumeLane(service, trade.getId()));
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));

            listener.onMessage(setupMessage());
            var afterMessage = whenExecutionState(service, TRADE_ID, NonceSharesAndPartialSignaturesCreated.class);

            assertFalse(starting.isDone());
            assertFalse(afterMessage.isDone());
            release.countDown();
            await(starting);
            assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class, await(afterMessage));
            verify(blockingStub(), times(1)).getNonceShares(any());
        } finally {
            release.countDown();
        }
    }

    @Test
    void theListenerBuffersMessagesUntilTheirTradeIsRegistered() throws Exception {
        listener.onMessage(setupMessage());
        verify(blockingStub(), never()).getNonceShares(any());

        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                .thenCompose(trade -> resumeLane(service, trade.getId())));

        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                service.getTradeReadModel(TRADE_ID).getExecutionState());
        verify(blockingStub(), times(1)).getNonceShares(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aBannedMessageDoesNotConsumeTheEventTypeOrWaitForRegistration(boolean alreadyRegistered) throws Exception {
        if (alreadyRegistered) {
            await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                    .thenCompose(trade -> resumeLane(service, trade.getId())));
        }
        when(bans.isUserProfileBanned(peerNetworkId)).thenReturn(true);
        var failure = assertInstanceOf(MuSigProtocolException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, setupMessage()))).getCause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        listener.onMessage(setupMessage());
        when(bans.isUserProfileBanned(peerNetworkId)).thenReturn(false);

        if (!alreadyRegistered) {
            await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                    .thenCompose(trade -> resumeLane(service, trade.getId())));
        }
        assertInstanceOf(TradeInitialized.class,
                await(whenExecutionState(service, TRADE_ID, TradeInitialized.class)));
        verify(blockingStub(), never()).getNonceShares(any());

        listener.onMessage(setupMessage());
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                await(whenExecutionState(service, TRADE_ID, NonceSharesAndPartialSignaturesCreated.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void emergencyRestrictionsApplyBeforeQueueing(boolean haltTrading) throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                .thenCompose(trade -> resumeLane(service, trade.getId())));
        alerts.add(restriction(haltTrading));

        assertInstanceOf(TradeRestrictedException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, setupMessage()))).getCause());
        listener.onMessage(setupMessage());
        assertInstanceOf(TradeInitialized.class,
                await(whenExecutionState(service, TRADE_ID, TradeInitialized.class)));
        verify(blockingStub(), never()).getNonceShares(any());

        alerts.clear();
        await(resumeLane(service, TRADE_ID));
        assertInstanceOf(TradeInitialized.class, savedRecord(service, TRADE_ID).trade().getExecution().state());
        listener.onMessage(setupMessage());
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                await(whenExecutionState(service, TRADE_ID, NonceSharesAndPartialSignaturesCreated.class)));
    }

    @Test
    void mediatorMessagesUseTheirOwnValidationAndRemainAvailableDuringATradingHalt() throws Exception {
        var mediatorId = networkId("mediator");
        var mediator = mock(UserProfile.class);
        when(mediator.getNetworkId()).thenReturn(mediatorId);
        when(mediator.toProto(true)).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        await(restoreTrade(MuSigV2TradeRecord.initial(tradeAt(new DepositTxConfirmed()))));
        alerts.add(restriction(true));
        var opened = new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                mediatorId,
                MediationCaseState.OPEN,
                Optional.empty(),
                Optional.empty());

        when(bans.isUserProfileBanned(mediatorId)).thenReturn(true);
        var openFailure = assertInstanceOf(MuSigProtocolException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, opened))).getCause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, openFailure.getCategory());
        var closed = new MuSigMediationStateChangeMessage(uid(),
                TRADE_ID,
                mediatorId,
                MediationCaseState.CLOSED,
                Optional.empty(),
                Optional.empty());
        var closeFailure = assertInstanceOf(MuSigProtocolException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, closed))).getCause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, closeFailure.getCategory());
        listener.onMessage(opened);
        listener.onMessage(closed);
        assertInstanceOf(MuSigMediationState.Init.class,
                savedRecord(service, TRADE_ID).trade().getMediation().orElseThrow().state());

        when(bans.isUserProfileBanned(mediatorId)).thenReturn(false);
        var openedState = whenValue(service.getTradeReadModel(TRADE_ID).mediationStateObservable(),
                state -> state.orElseThrow() instanceof MuSigMediationState.Open);
        listener.onMessage(opened);
        assertInstanceOf(MuSigMediationState.Open.class, await(openedState).orElseThrow());
    }

    @Test
    void anInvalidMessageDoesNotFailTheCallbackOrPoisonTheTradeQueue() throws Exception {
        await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload))
                .thenCompose(trade -> resumeLane(service, trade.getId())));

        var wrongPeer = new PaymentInitiatedMessage_E(uid(),
                TRADE_ID,
                "1.0.0",
                myNetworkId,
                myNetworkId,
                new byte[]{2});
        assertDoesNotThrow(() -> listener.onMessage(wrongPeer));
        assertTrue(savedRecord(service, TRADE_ID).trade().getExecution().data().getMyFailure().isEmpty());

        listener.onMessage(setupMessage());
        assertInstanceOf(NonceSharesAndPartialSignaturesCreated.class,
                await(whenExecutionState(service, TRADE_ID, NonceSharesAndPartialSignaturesCreated.class)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void paymentDetailsRequestsCheckBansAndRemainAvailableDuringTradingRestrictions(boolean haltTrading) throws Exception {
        var mediatorId = networkId("mediator");
        var mediator = mock(UserProfile.class);
        when(mediator.getNetworkId()).thenReturn(mediatorId);
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        var trade = tradeAt(new DepositTxConfirmed());
        var execution = trade.getExecution();
        trade = trade.withExecution(execution.to(execution.state(),
                        execution.data().toBuilder().peerAccountPayload(Optional.of(peerAccountPayload)).build()))
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Open(), MuSigV2MediationData.initial()));
        await(restoreTrade(MuSigV2TradeRecord.initial(trade)));
        alerts.add(restriction(haltTrading));
        var request = new MuSigDisputeCasePaymentDetailsRequest(TRADE_ID, mediatorId);

        when(bans.isUserProfileBanned(mediatorId)).thenReturn(true);
        var failure = assertInstanceOf(MuSigProtocolException.class,
                assertThrows(ExecutionException.class, () -> await(submitMessage(service, request))).getCause());
        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        listener.onMessage(request);
        assertTrue(savedRecord(service,
                TRADE_ID).processedEventTypes().getOrDefault(Flow.MEDIATION,
                Set.of()).isEmpty());
        assertTrue(effects.isEmpty());

        when(bans.isUserProfileBanned(mediatorId)).thenReturn(false);
        var answered = new CompletableFuture<Effect>();
        onEffect = answered::complete;
        listener.onMessage(request);
        await(answered);
        assertTrue(savedRecord(service, TRADE_ID).processedEventTypes().getOrDefault(Flow.MEDIATION, Set.of())
                .contains(MediationPaymentDetailsRequestedEvent.class));
        assertEquals(1, effects.size());
        var send = assertInstanceOf(Effect.SendMessage.class, effects.getFirst());
        assertEquals(mediatorId, send.receiver());
        assertInstanceOf(MuSigDisputeCasePaymentDetailsResponse.class, send.message());
        verify(blockingStub(), never()).initTrade(any());
    }

    @Test
    void unrelatedMessagesAreIgnored() {
        assertDoesNotThrow(() -> listener.onMessage(mock(EnvelopePayloadMessage.class)));
        verify(blockingStub(), never()).initTrade(any());
    }

    private AuthorizedAlertData restriction(boolean haltTrading) {
        var alert = mock(AuthorizedAlertData.class);
        when(alert.getAlertType()).thenReturn(AlertType.EMERGENCY);
        when(alert.getAppType()).thenReturn(AppType.DESKTOP);
        when(alert.isHaltTrading()).thenReturn(haltTrading);
        when(alert.isRequireVersionForTrading()).thenReturn(!haltTrading);
        when(alert.getMinVersion()).thenReturn(Optional.of("999.0.0"));
        return alert;
    }

    private <T> T await(CompletableFuture<T> result) throws Exception {
        return result.get(10, TimeUnit.SECONDS);
    }
    private CompletableFuture<MuSigV2Trade<?>> restoreTrade(MuSigV2TradeRecord record) {
        service.shutdown().join();
        service = serviceWithClock(services, grpcClient, serviceClock);
        listener = service;
        return bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.restoreTrade(service, record);
    }

}
