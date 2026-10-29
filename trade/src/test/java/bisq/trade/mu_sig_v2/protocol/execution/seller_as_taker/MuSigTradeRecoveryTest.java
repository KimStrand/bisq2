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

import bisq.common.processing.Effect;
import bisq.network.SendMessageResult;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig_v2.MuSigTradeService;
import bisq.trade.mu_sig_v2.MuSigV2TradeRecord;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeLaneFixture;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.MuSigV2TradeData;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.protobuf.SubscribeTxConfirmationStatusRequest;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.profile.UserProfile;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.restoreTrade;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.resumeLane;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigTradeRecoveryTest extends SellerAsTakerFixture {
    private final ConcurrentLinkedQueue<Effect> restoredEffects = new ConcurrentLinkedQueue<>();
    private final MuSigTradeService service = newService();

    @AfterEach
    void close() {
        service.shutdown().join();
    }

    @Test
    void restoringPreservesBothFlowsAndRestartsOnlyTheRequiredObservation() throws Exception {
        var mediatorNetworkId = networkId("mediator-profile");
        UserProfile mediator = mock(UserProfile.class);
        when(mediator.getNetworkId()).thenReturn(mediatorNetworkId);
        when(mediator.toProto(true)).thenReturn(bisq.user.protobuf.UserProfile.getDefaultInstance());
        when(muSigContract.getMediator()).thenReturn(Optional.of(mediator));
        MuSigV2TradeRecord record;
        try (var original = afterDepositPublication()) {
            original.handle(accountPayloadMessage(peerAccountPayload));
            original.handle(new MediationOpenedEvent(new MuSigMediationStateChangeMessage(uid(),
                    TRADE_ID,
                    mediatorNetworkId,
                    MediationCaseState.OPEN,
                    Optional.empty(),
                    Optional.empty())));
            record = original.getSavedRecord();
        }
        MuSigV2Trade<?> snapshot = record.trade();
        clearInvocations(blockingStub(), services.getContractService());

        assertSame(snapshot, await(restoreTrade(service, record)));
        assertSame(snapshot, savedRecord(service, TRADE_ID).trade());
        assertTrue(record.processedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(SendAccountPayloadMessage.class));
        assertInstanceOf(MuSigMediationState.Open.class, snapshot.getMediation().orElseThrow().state());
        assertTrue(record.processedEventTypes().getOrDefault(Flow.MEDIATION,
                Set.of()).contains(MediationOpenedEvent.class));
        await(submitMessage(service, setupMessage()));
        assertSame(snapshot, savedRecord(service, TRADE_ID).trade());
        assertTrue(restoredEffects.isEmpty());
        verify(services.getChatService().getMuSigOpenTradeChannelService(), never()).sendTradeLogMessage(anyString(), any());
        verifyNoInteractions(blockingStub(), services.getContractService());

        assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(service, initializeTrade(muSigContract, myIdentity, myAccountPayload)))).getCause());
        assertSame(snapshot, savedRecord(service, TRADE_ID).trade());
        verify(grpcClient.getAsyncStub(), times(1))
                .subscribeTxConfirmationStatus(argThat(request -> request.getTradeId().equals(TRADE_ID)), any());
    }

    @Test
    void immediateConfirmationIsQueuedAfterResumeAndKeepsTheRestoredDocumentIntact() throws Exception {
        var record = waitingForDeposit();
        var snapshot = record.trade();
        clearInvocations(blockingStub(), services.getContractService());
        var stub = grpcClient.getAsyncStub();
        var beforeConfirmation = new AtomicReference<MuSigV2Trade<?>>();
        var afterConfirmation = new AtomicReference<CompletableFuture<MuSigTradeExecutionState>>();
        doAnswer(invocation -> {
            SubscribeTxConfirmationStatusRequest request = invocation.getArgument(0);
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            beforeConfirmation.set(savedRecord(service, request.getTradeId()).trade());
            responses.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            afterConfirmation.set(whenExecutionState(service, request.getTradeId(), DepositTxConfirmed.class));
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        assertSame(snapshot, await(restoreTrade(service, record)));
        assertSame(snapshot, beforeConfirmation.get());
        assertInstanceOf(DepositTxConfirmed.class, await(afterConfirmation.get()));
        var confirmed = savedRecord(service, TRADE_ID).trade();
        assertSame(snapshot.getContract(), confirmed.getContract());
        assertTrue(savedRecord(service,
                TRADE_ID).processedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).containsAll(record.processedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of())));
        assertTrue(savedRecord(service,
                TRADE_ID).processedEventTypes().getOrDefault(Flow.EXECUTION,
                Set.of()).contains(DepositTxConfirmedEvent.class));
        assertTrue(restoredEffects.isEmpty());
        verifyNoInteractions(blockingStub(), services.getContractService());
    }

    @Test
    void newInputsWaitUntilTheQueuedResumeOperationFinishes() throws Exception {
        var record = waitingForDeposit();
        var snapshot = record.trade();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var stub = grpcClient.getAsyncStub();
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(10, TimeUnit.SECONDS));
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        var restored = restoreTrade(service, record);
        try {
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            var confirmation = service.onDepositTxConfirmed(TRADE_ID);
            assertFalse(restored.isDone());
            assertFalse(confirmation.isDone());
            release.countDown();

            assertSame(snapshot, await(restored));
            await(confirmation);
            assertInstanceOf(DepositTxConfirmed.class, service.getTradeReadModel(TRADE_ID).getExecutionState());
        } finally {
            release.countDown();
        }
    }

    @Test
    void earlierConfirmedAndClosedTradesDoNotStartObservationOrReplayHandlers() throws Exception {
        List<MuSigV2TradeRecord> records;
        try (var original = afterDepositPublication()) {
            var published = original.getSavedRecord();
            original.handle(accountPayloadMessage(peerAccountPayload));
            original.handle(new DepositTxConfirmedEvent());
            var confirmed = original.getSavedRecord();
            runSettlement(original::handle);
            var closed = original.getSavedRecord();
            records = List.of(published, confirmed, closed);
        }
        clearInvocations(blockingStub(), services.getContractService());

        for (var record : records) {
            var snapshot = record.trade();
            var restoredService = newService();
            try {
                assertSame(snapshot, await(restoreTrade(restoredService, record)));
            } finally {
                restoredService.shutdown().join();
            }
        }
        assertTrue(restoredEffects.isEmpty());
        verify(services.getChatService().getMuSigOpenTradeChannelService(), never()).sendTradeLogMessage(anyString(), any());
        verifyNoInteractions(blockingStub(),
                services.getContractService(),
                grpcClient.getAsyncStub());
    }

    @Test
    void aFailedSubscriptionStartupPausesRecoveryAndRetainsInputsUntilRetrySucceeds() throws Exception {
        var record = waitingForDeposit();
        var snapshot = record.trade();
        var stub = grpcClient.getAsyncStub();
        var failure = new IllegalStateException("backend unavailable");
        doThrow(failure).when(stub).subscribeTxConfirmationStatus(any(), any());
        clearInvocations(blockingStub(), services.getContractService());

        assertSame(failure, assertThrows(ExecutionException.class,
                () -> await(restoreTrade(service, record))).getCause());
        assertSame(snapshot, savedRecord(service, TRADE_ID).trade());
        assertTrue(savedRecord(service, TRADE_ID).pendingEffects().stream()
                .anyMatch(ObserveDepositTxConfirmation.class::isInstance));
        assertSame(failure, assertThrows(ExecutionException.class,
                () -> await(service.onDepositTxConfirmed(TRADE_ID))).getCause());
        assertSame(snapshot, savedRecord(service, TRADE_ID).trade());
        assertTrue(savedRecord(service, TRADE_ID).pendingInputs().stream()
                .anyMatch(input -> input.event() instanceof DepositTxConfirmedEvent));

        doAnswer(invocation -> null).when(stub).subscribeTxConfirmationStatus(any(), any());

        await(resumeLane(service, TRADE_ID));
        assertInstanceOf(DepositTxConfirmed.class, savedRecord(service, TRADE_ID).trade().getExecution().state());
        assertTrue(savedRecord(service, TRADE_ID).pendingInputs().isEmpty());
        assertTrue(savedRecord(service, TRADE_ID).pendingEffects().isEmpty());
        verifyNoInteractions(blockingStub(), services.getContractService());
    }

    @Test
    void restorationRequiresAMatchingStateFamilyAndAnOpenService() throws Exception {
        var snapshot = tradeAt(new Init());
        var record = MuSigV2TradeRecord.initial(snapshot);
        var makerWithTakerState = new MuSigV2Trade<>(TRADE_ID,
                snapshot.getState(),
                new MuSigV2TradeData(muSigContract,
                        myIdentity,
                        TradeRole.SELLER_AS_MAKER,
                        "1.0.0",
                        myAccountPayload),
                tradeAt(new TradeInitialized()).getExecution(),
                snapshot.getMediation());
        assertFailure(IllegalArgumentException.class,
                restoreTrade(service, MuSigV2TradeRecord.initial(makerWithTakerState)));
        var wrongRoleState = new MuSigV2Trade<>(TRADE_ID,
                snapshot.getState(),
                new MuSigV2TradeData(muSigContract,
                        myIdentity,
                        TradeRole.BUYER_AS_TAKER,
                        "1.0.0",
                        myAccountPayload),
                tradeAt(new PaymentReceiptConfirmed()).getExecution(),
                snapshot.getMediation());
        assertFailure(IllegalArgumentException.class,
                restoreTrade(service, MuSigV2TradeRecord.initial(wrongRoleState)));
        assertSame(snapshot, await(restoreTrade(service, record)));
        assertTrue(restoredEffects.isEmpty());
        verifyNoInteractions(blockingStub());
        service.shutdown().get(10, TimeUnit.SECONDS);
        assertFailure(RejectedExecutionException.class, restoreTrade(service, record));
    }

    private MuSigTradeService newService() {
        var networkService = services.getNetworkService();
        doAnswer(invocation -> {
            restoredEffects.add(new Effect.SendMessage<>(invocation.getArgument(0), invocation.getArgument(1)));
            return CompletableFuture.completedFuture(new SendMessageResult());
        }).when(networkService).confidentialSend(any(), any(), any());
        return serviceWithClock(services, grpcClient, serviceClock);
    }

    private MuSigV2TradeRecord waitingForDeposit() {
        try (var original = afterDepositPublication()) {
            original.handle(accountPayloadMessage(peerAccountPayload));
            return original.getSavedRecord();
        }
    }

    private MuSigTradeLaneFixture<SellerAsTakerState> afterDepositPublication() {
        var original = new MuSigTradeLaneFixture<>(services, grpcClient,
                tradeAt(new Init()),
                SellerAsTakerState.class,
                new SellerAsTakerProtocol(),
                effects::add,
                logs::add,
                clock);
        original.handle(new MuSigTakeOfferEvent());
        original.handle(setupMessage());
        original.handle(depositSetupMessage());
        return original;
    }

    private static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(10, TimeUnit.SECONDS);
    }

    private static void assertFailure(Class<? extends Throwable> type, CompletableFuture<?> future) {
        assertInstanceOf(type,
                assertThrows(ExecutionException.class, () -> future.get(10, TimeUnit.SECONDS)).getCause());
    }
}
