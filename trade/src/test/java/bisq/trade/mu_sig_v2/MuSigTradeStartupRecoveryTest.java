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

import bisq.bonded_roles.release.AppType;
import bisq.common.observable.map.HashMapObserver;
import bisq.common.processing.Effect;
import bisq.common.processing.SendMessage;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.timer.Clock;
import bisq.common.util.StringUtils;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.persistence.PersistenceService;
import bisq.support.dispute.mu_sig.MuSigDisputeCaseDataMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.protobuf.TxConfirmationStatus;
import bisq.user.UserService;
import bisq.user.banned.BannedUserService;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.awaitSavedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.runNextRetry;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.takeNextRetry;
import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.serviceWithClock;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenValue;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Startup of the trade service with saved trades, and their recovery after a restart.
 */
class MuSigTradeStartupRecoveryTest extends MuSigTradePersistenceFixture {
    @Test
    void anInitialRecordWithoutPendingInputDoesNotInventAStartupCommand() throws Exception {
        bisq.trade.protobuf.MuSigV2Trade saved;
        seed(initialRecord(tradeAt(new Init())));
        var service = startAndAwaitRecovery(effect -> fail("Recovery must not invent a startup command"));
        try {
            var trade = savedRecord(service, tradeId).trade();
            assertInstanceOf(Init.class, trade.getExecution().state());
            saved = trade.toProto(false);
            assertEquals(saved, service.getPersistableStore().getTrades().getFirst().trade().toProto(false));
        } finally {
            service.shutdown().join();
        }

        var restoredService = startAndAwaitRecovery(effect -> fail("An unstarted trade must stay unstarted"));
        try {
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(saved, restored.toProto(false));
            assertInstanceOf(Init.class, restored.getExecution().state());
            assertInstanceOf(Init.class, restoredService.getTradeReadModel(tradeId).getExecutionState());
            assertInstanceOf(IllegalArgumentException.class, assertThrows(ExecutionException.class,
                () -> await(initializeTrade(restoredService, initializeTrade(contract, myIdentity, myAccountPayload)))).getCause());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void recoveryAndOutgoingMessagesPreserveTheSavedProtocolVersion() throws Exception {
        String savedVersion = "0.9.0";
        assertNotEquals(MuSigProtocol.VERSION, savedVersion);
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                myIdentity,
                TradeRole.SELLER_AS_TAKER,
                savedVersion,
                myAccountPayload,
                new PaymentInitiatedMessageReceived());
        seed(initialRecord(trade));
        var effects = new ArrayList<Effect>();

        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            var restored = savedRecord(service, tradeId).trade();
            assertEquals(savedVersion, restored.getProtocolVersion());

            await(service.paymentReceiptConfirmed(tradeId));
            var confirmed = savedRecord(service, tradeId).trade();

            assertInstanceOf(PaymentReceiptConfirmed.class, confirmed.getExecution().state());
            assertEquals(savedVersion, confirmed.getProtocolVersion());
            var outgoing = assertInstanceOf(SendMessage.class, effects.getFirst());
            var message = assertInstanceOf(PaymentReceivedMessage_F.class, outgoing.message());
            assertEquals(savedVersion, message.getProtocolVersion());
            assertEquals(savedVersion, saved(service).trade().getProtocolVersion());
            verify(backend, times(1)).signSwapTx(any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void cachedAndLiveMediationMessagesWaitForRestoredRecoveryAndAreNotProcessedTwice() throws Exception {
        seed(initialRecord(tradeAt(new AccountPayloadReceived())));
        var network = services.getNetworkService();
        clearInvocations(network);
        var bans = mock(BannedUserService.class);
        var users = mock(UserService.class);
        when(users.getBannedUserService()).thenReturn(bans);
        when(services.getUserService()).thenReturn(users);
        var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                tradeId,
                contract.getMediator().orElseThrow().getNetworkId());
        var listener = new AtomicReference<ConfidentialMessageService.Listener>();
        // The subscription replays a message which the network processed before the service started.
        doAnswer(invocation -> {
            listener.set(invocation.getArgument(0));
            listener.get().onMessage(opened);
            return null;
        }).when(network).subscribeToConfidentialMessages(any());
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);

        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            try {
                assertTrue(await(persistenceService.readAllPersisted()));
                assertTrue(await(service.initialize()));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                var afterReplay = whenValue(service.getTradeReadModel(tradeId).mediationStateObservable(),
                        state -> state.orElseThrow() instanceof MuSigMediationState.Open);
                assertFalse(afterReplay.isDone());

                release.countDown();
                assertInstanceOf(MuSigMediationState.Open.class,
                        await(afterReplay).orElseThrow());
                var channels = services.getChatService().getMuSigOpenTradeChannelService();
                verify(channels, times(1)).addLocalTradeLogMessage(anyString(), anyString(), any());
            } finally {
                release.countDown();
            }
        } finally {
            service.shutdown().join();
        }
        verify(network).removeConfidentialMessageListener(listener.get());
    }

    @Test
    void initializationReturnsBeforeRecoveryFinishesAndNewInputsWaitBehindIt() throws Exception {
        var other = buyerTradeAt(new DepositTxConfirmed());
        seed(initialRecord(tradeAt(new AccountPayloadReceived())), initialRecord(other));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(15, TimeUnit.SECONDS));
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effect -> fail("No peer send is expected"));
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        var models = new ConcurrentLinkedQueue<MuSigTradeReadModel>();
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String id, MuSigTradeReadModel readModel) {
                models.add(readModel);
            }
        });
        try {
            try {
                assertTrue(await(persistenceService.readAllPersisted()));
                var initialization = service.initialize();
                assertTrue(initialization.isDone());
                assertTrue(await(initialization));
                assertTrue(entered.await(15, TimeUnit.SECONDS));
                assertEquals(Set.of(tradeId, other.getId()), models.stream()
                        .map(MuSigTradeReadModel::getId)
                        .collect(Collectors.toSet()));
                assertSame(service.getTradeReadModel(tradeId), service.getTradeReadModels().get(tradeId));

                var recovery = service.getRecoveryFuture();
                assertFalse(recovery.isDone());
                // Repeated initialization and cancellation of an observer do not restart or cancel recovery.
                assertTrue(await(service.initialize()));
                assertEquals(2, models.size());
                assertTrue(service.getRecoveryFuture().cancel(false));
                assertFalse(recovery.isDone());

                var confirmation = service.onDepositTxConfirmed(tradeId);
                assertFalse(confirmation.isDone());
                assertEquals(other.toProto(false), savedRecord(service, other.getId()).trade().toProto(false));

                release.countDown();
                await(recovery);
                await(confirmation);
                assertInstanceOf(DepositTxConfirmed.class, service.getTradeReadModel(tradeId).getExecutionState());
                verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
            } finally {
                release.countDown();
            }
        } finally {
            pin.unbind();
            service.shutdown().join();
        }
    }

    @Test
    void aShutdownDuringStartupIsNotLoggedAsAFailedRecovery() throws Exception {
        seed(initialRecord(tradeAt(new DepositTxConfirmed())));
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effect -> fail("No peer send is expected"));
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        // Startup publishes the read model of the saved trade before it queues the recovery of that trade.
        var pin = service.getTradeReadModels().addObserver(new HashMapObserver<>() {
            @Override
            public void put(String id, MuSigTradeReadModel readModel) {
                service.shutdown();
            }
        });
        var serviceLog = captureServiceLog();
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertInstanceOf(RejectedExecutionException.class,
                    assertThrows(ExecutionException.class, () -> await(service.initialize())).getCause());
            assertInstanceOf(RejectedExecutionException.class, bisq.common.util.ExceptionUtil.getRootCause(
                    assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture()))));
            assertTrue(recoveryWarnings(serviceLog).isEmpty());
        } finally {
            release(serviceLog);
            pin.unbind();
            service.shutdown().join();
        }
    }

    @Test
    void failedOutgoingRecoveryAllowsMediationAndOtherTradesToProgress() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed());
        var expectedTrade = trade.withMediation(FlowSnapshot.of(new MuSigMediationState.Open(),
                trade.getMediation().orElseThrow().data()));
        var other = buyerTradeAt(new DepositTxConfirmed());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var message = new PaymentReceivedMessage_F(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                myIdentity.getNetworkId(),
                contract.getMaker().getNetworkId(),
                signature);
        var pendingSend = new SendMessage<>(message, message.getReceiver());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(),
                List.of(),
                List.of(pendingSend)),
                initialRecord(other));
        var failure = new IllegalStateException("Transport unavailable during recovery");
        var attempted = new ConcurrentLinkedQueue<Effect>();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        mockNetworkSend(effect -> {
            attempted.add(effect);
            if (((SendMessage<?, ?>) effect).message() instanceof MuSigTradeMessage messageSent &&
                    messageSent.getTradeId().equals(tradeId)) {
                throw failure;
            }
        });
        retries.clear();
        var service = serviceWithClock(services, grpcClient, contract::getTakeOfferDate, retries);
        MuSigV2TradeRecord savedBeforeRestart;
        var serviceLog = captureServiceLog();
        try {
            assertTrue(await(persistenceService.readAllPersisted()));
            assertTrue(await(service.initialize()));
            assertSame(failure,
                    assertThrows(ExecutionException.class, () -> await(service.getRecoveryFuture())).getCause());
            // Nobody has to ask for the recovery result: the service logs the failure itself.
            var warnings = recoveryWarnings(serviceLog);
            assertEquals(1, warnings.size());
            assertSame(failure, bisq.common.util.ExceptionUtil.getRootCause(
                    ((ThrowableProxy) warnings.getFirst().getThrowableProxy()).getThrowable()));
            assertTrue(await(service.initialize()));

            var opened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                    tradeId,
                    contract.getMediator().orElseThrow().getNetworkId());
            var reportedFailure = assertThrows(ExecutionException.class,
                    () -> await(submitMessage(service, opened))).getCause();
            assertSame(failure, reportedFailure);
            runNextRetry(retries);
            takeNextRetry(retries); // A failed automatic attempt schedules another retry.
            var committed = savedRecord(service, tradeId);
            assertEquals(expectedTrade.toProto(false), committed.trade().toProto(false));
            assertTrue(committed.pendingInputs().isEmpty());
            assertEquals(Set.of(MuSigMediationOpenedMessage.class), committed.processedEventTypes().get(Flow.MEDIATION));
            assertEquals(List.of(pendingSend, pendingSend, pendingSend), List.copyOf(attempted));
            assertEquals(2, committed.pendingEffects().size());
            assertEquals(pendingSend, committed.pendingEffects().getFirst());
            var reportSend = assertInstanceOf(SendMessage.class, committed.pendingEffects().getLast());
            var report = assertInstanceOf(MuSigDisputeCaseDataMessage.class, reportSend.message());
            assertEquals(tradeId, report.getTradeId());
            assertEquals(contract.getMediator().orElseThrow().getNetworkId(), reportSend.receiver());
            verifyNoInteractions(backend);

            var otherOpened = new MuSigMediationOpenedMessage(StringUtils.createUid(),
                    other.getId(),
                    contract.getMediator().orElseThrow().getNetworkId());
            await(submitMessage(service, otherOpened));
            assertInstanceOf(MuSigMediationState.Open.class,
                    savedRecord(service, otherOpened.getTradeId()).trade().getMediation().orElseThrow().state());
            savedBeforeRestart = service.getPersistence().read().orElseThrow().getTrades().stream()
                    .filter(record -> record.trade().getId().equals(tradeId)).findFirst().orElseThrow();
            assertEquals(committed.toProto(false), savedBeforeRestart.toProto(false));
        } finally {
            release(serviceLog);
            service.shutdown().join();
        }

        var sent = new ArrayList<Effect>();
        var restoredService = startAndAwaitRecovery(effect -> sent.add(effect));
        try {
            assertEquals(savedBeforeRestart.pendingEffects(), sent);
            var restored = savedRecord(restoredService, tradeId).trade();
            assertEquals(expectedTrade.toProto(false), restored.toProto(false));
            assertTrue(savedRecord(restoredService, tradeId).pendingEffects().isEmpty());
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void diskRestartResumesDepositObservationOnceAndDoesNotRestartItAfterConfirmation() throws Exception {
        var original = tradeAt(new AccountPayloadReceived());
        seed(initialRecord(original));
        var service = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {

            assertTrue(Files.isRegularFile(service.getPersistence().getStorePath()));
        } finally {
            service.shutdown().join();
        }
        clearInvocations(asyncBackend, backend);
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> observer = invocation.getArgument(1);
            observer.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var restoredService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            // Recovery queued the callback; await its published result separately.
            await(whenExecutionState(restoredService, tradeId, DepositTxConfirmed.class));
            var restored = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(DepositTxConfirmed.class, restored.getExecution().state());
            assertEquals(contract.toProto(false), restored.getContract().toProto(false));
            assertEquals(myIdentity.toProto(false), restored.getMyIdentity().toProto(false));
            assertEquals(TradeRole.SELLER_AS_TAKER, restored.getMyRole());
            assertEquals("1.0.0", restored.getProtocolVersion());
            assertEquals(myAccountPayload.toProto(false), restored.getMyAccountPayload().toProto(false));
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(DepositTxConfirmedEvent.class));
            verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
        clearInvocations(asyncBackend);
        var restartedService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            assertInstanceOf(DepositTxConfirmed.class, savedRecord(restartedService, tradeId).trade().getExecution().state());
            verifyNoInteractions(asyncBackend);
        } finally {
            restartedService.shutdown().join();
        }
    }

    @Test
    void unacknowledgedObservationIsRecoveredOnceEvenWithAnImmediateConfirmation() throws Exception {
        seed(new MuSigV2TradeRecord(tradeAt(new AccountPayloadReceived()),
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ObserveDepositTxConfirmation())));
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> observer = invocation.getArgument(1);
            observer.onNext(TxConfirmationStatus.newBuilder().setNumConfirmations(1).build());
            return null;
        }).when(asyncBackend).subscribeTxConfirmationStatus(any(), any());

        var service = startAndAwaitRecovery(effect -> {});
        try {
            assertInstanceOf(DepositTxConfirmed.class,
                    await(whenExecutionState(service, tradeId, DepositTxConfirmed.class)));
            assertTrue(saved(service).pendingEffects().isEmpty());
            verify(asyncBackend, times(1)).subscribeTxConfirmationStatus(any(), any());
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void aSavedObservationRequestIsDiscardedWhenConfirmationIsAlreadyRecorded() throws Exception {
        var trade = tradeAt(new DepositTxConfirmed());
        seed(new MuSigV2TradeRecord(trade,
                Map.of(),
                List.of(),
                List.of(),
                List.of(new ObserveDepositTxConfirmation())));

        var service = startAndAwaitRecovery(effect -> fail("No outgoing work is needed"));
        try {
            assertEquals(trade.toProto(false), savedRecord(service, tradeId).trade().toProto(false));
            assertTrue(saved(service).pendingEffects().isEmpty());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void earlyPeerMessageSurvivesRestartAndRunsAfterDepositConfirmation() throws Exception {
        var early = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                contract.getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                new byte[]{7});
        var initial = tradeAt(new AccountPayloadReceived()).withExecution(FlowSnapshot.of(new AccountPayloadReceived(),
                MuSigV2TradeExecutionData.builder().peerRedactedPartialSignatures(Optional.of(
                        PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance()))).build()));
        seed(initialRecord(initial));
        var service = startAndAwaitRecovery(effect -> {});
        try {

            await(submitMessage(service, early));
            assertInstanceOf(AccountPayloadReceived.class, savedRecord(service, early.getTradeId()).trade().getExecution().state());
            assertEquals(1, saved(service).pendingInputs().size());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> {});
        try {
            assertInstanceOf(AccountPayloadReceived.class,
                    await(whenExecutionState(restoredService, tradeId, AccountPayloadReceived.class)));
            assertEquals(early.getId(),
                    ((PaymentInitiatedMessage_E) saved(restoredService).pendingInputs().getFirst()).getId());
            await(restoredService.onDepositTxConfirmed(tradeId));
            var advanced = savedRecord(restoredService, tradeId).trade();
            assertInstanceOf(PaymentInitiatedMessageReceived.class, advanced.getExecution().state());
            assertTrue(saved(restoredService).pendingInputs().isEmpty());
            assertTrue(saved(restoredService).processedEventTypes().getOrDefault(Flow.EXECUTION,
                    Set.of()).contains(PaymentInitiatedMessage_E.class));
            await(submitMessage(restoredService, early));
            assertSame(advanced, savedRecord(restoredService, early.getTradeId()).trade());
        } finally {
            restoredService.shutdown().join();
        }
        var restartedService = startAndAwaitRecovery(effect -> {});
        try {
            var restored = savedRecord(restartedService, tradeId).trade();
            assertTrue(saved(restartedService).processedEventTypes().getOrDefault(Flow.EXECUTION, Set.of()).containsAll(
                    Set.of(DepositTxConfirmedEvent.class, PaymentInitiatedMessage_E.class)));
            var duplicate = new PaymentInitiatedMessage_E(StringUtils.createUid(),
                    tradeId,
                    "1.0.0",
                    contract.getMaker().getNetworkId(),
                    myIdentity.getNetworkId(),
                    new byte[]{7});
            await(submitMessage(restartedService, duplicate));
            assertSame(restored, savedRecord(restartedService, duplicate.getTradeId()).trade());
            await(restartedService.onDepositTxConfirmed(tradeId));
            assertSame(restored, savedRecord(restartedService, tradeId).trade());
        } finally {
            restartedService.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aResumedTradeFailureDoesNotPreventOtherTradesFromStarting(boolean backendFails) throws Exception {
        var record = pendingCustomSettlement(TX_ID);
        var other = buyerTradeAt(new PaymentInitiated());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                other.getId(),
                "1.0.0",
                other.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(record, new MuSigV2TradeRecord(other,
                Map.of(),
                List.of(confirmation),
                List.of(),
                List.of()));
        if (backendFails) {
            when(backend.customCloseTrade(any())).thenThrow(new IllegalStateException("Backend unavailable"));
        } else {
            when(backend.customCloseTrade(any())).thenReturn(bisq.trade.protobuf.CustomCloseTradeResponse.getDefaultInstance());
        }
        String expectedError = backendFails ? "Backend unavailable" : "Custom payout transaction must not be empty";

        for (int restart = 0; restart < 3; restart++) {
            var effects = new ConcurrentLinkedQueue<Effect>();
            var service = startAndAwaitRecovery(effect -> effects.add(effect));
            try {
                var failed = savedRecord(service, tradeId).trade();
                assertInstanceOf(Failed.class, failed.getExecution().state());
                assertSame(failed, savedRecord(service, tradeId).trade());
                assertEquals(record.trade().toProto(false).getMediation(),
                        failed.toProto(false).getMediation());
                assertEquals(record.trade().getExecution().data().getMyCustomPayoutPsbt(),
                        failed.getExecution().data().getMyCustomPayoutPsbt());
                assertTrue(failed.getExecution().data().getPeerCustomPayoutPsbt().isEmpty());
                assertFalse(savedRecord(service,
                        tradeId).processedEventTypes().getOrDefault(Flow.EXECUTION,
                        Set.of()).contains(MediationResultByPeerAcceptedEvent.class));
                assertEquals(expectedError, failed.getExecution().data().getMyFailure().orElseThrow().errorMessage());
                var closed = savedRecord(service, other.getId()).trade();
                assertInstanceOf(TradeClosed.class, closed.getExecution().state());
                assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
                var saved = service.getPersistence().read().orElseThrow().getTrades();
                assertEquals(2, saved.size());
                for (var savedRecord : saved) {
                    assertTrue(savedRecord.pendingInputs().isEmpty());
                    assertTrue(savedRecord.scheduledEvents().isEmpty());
                    assertTrue(savedRecord.pendingEffects().isEmpty());
                    assertEquals(savedRecord(service, savedRecord.trade().getId()).trade().toProto(false),
                            savedRecord.trade().toProto(false));
                }
                var messageTypes = effects.stream().map(effect -> ((SendMessage<?, ?>) effect).message().getClass())
                        .collect(Collectors.toSet());
                assertEquals(restart == 0 ? Set.of(MuSigReportErrorMessage.class, CooperativeClosureMessage_G.class) : Set.of(),
                        messageTypes);
                assertEquals(restart == 0 ? 2 : 0, effects.size());
                verify(backend, times(1)).customCloseTrade(any());
                verify(backend, times(1)).closeTrade(any());
            } finally {
                service.shutdown().join();
            }
        }
    }

    @Test
    void overdueTimeoutRunsAfterRestartAndItsCancellationIsPersisted() throws Exception {
        var trade = tradeAt(new PaymentReceiptConfirmed());
        var scheduled = new ScheduledEvent<Event>(CLOSE_TRADE_TIMEOUT_ID,
                Instant.parse("2100-01-01T00:00:00.123456789Z"),
                new SellersCloseTradeTimeoutEvent());
        Clock clock = () -> scheduled.deadline().plusSeconds(1).toEpochMilli();
        seed(new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(scheduled), List.of()));
        var service = startAndAwaitRecovery(effect -> {}, clock);
        try {
            // Await the backend callback explicitly: the timer thread may enqueue after a snapshot query.
            verify(backend, timeout(10_000).times(1)).closeTrade(any());
            // The lane saves the closed trade after the backend call has returned, so wait for the saved record.
            var closed = awaitSavedRecord(service, tradeId,
                    record -> record.trade().getExecution().state() instanceof SellerForceClosedTrade).trade();
            assertInstanceOf(SellerForceClosedTrade.class, closed.getExecution().state());
            assertEquals(Optional.of(clock.now()), closed.getExecution().data().getTradeCompletedDate());
            assertTrue(saved(service).scheduledEvents().isEmpty());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> {}, clock);
        try {
            assertInstanceOf(SellerForceClosedTrade.class,
                    await(whenExecutionState(restoredService, tradeId, SellerForceClosedTrade.class)));
            assertEquals(Optional.of(clock.now()),
                    savedRecord(restoredService, tradeId).trade().getExecution().data().getTradeCompletedDate());
            verify(backend, times(1)).closeTrade(any());
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void buyerRetainsEarlyConfirmationAcrossRestartAndClosesAfterANewPaymentCommand() throws Exception {
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        var signature = SwapTxSignature.from(new SwapTxSignatureResponse(new byte[]{7}, new byte[]{8}));
        var confirmation = new PaymentReceivedMessage_F(StringUtils.createUid(),
                buyer.getId(),
                "1.0.0",
                buyer.getContract().getMaker().getNetworkId(),
                myIdentity.getNetworkId(),
                signature);
        seed(new MuSigV2TradeRecord(buyer,
                Map.of(),
                List.of(confirmation),
                List.of(),
                List.of()));
        when(backend.getPartialSignatures(any())).thenReturn(bisq.trade.protobuf.PartialSignaturesMessage.newBuilder()
                .setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{6})).build());
        var effects = new ArrayList<Effect>();
        var service = startAndAwaitRecovery(effect -> effects.add(effect));
        try {
            assertInstanceOf(DepositTxConfirmed.class,
                    await(whenExecutionState(service, buyer.getId(), DepositTxConfirmed.class)));
            verify(backend, never()).closeTrade(any());
            await(service.paymentInitiated(buyer.getId()));
            var closed = savedRecord(service, buyer.getId()).trade();
            assertInstanceOf(TradeClosed.class, closed.getExecution().state());
            assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            assertInstanceOf(PaymentInitiatedMessage_E.class,
                    ((SendMessage<?, ?>) effects.getFirst()).message());
            assertInstanceOf(CooperativeClosureMessage_G.class,
                    ((SendMessage<?, ?>) effects.getLast()).message());
            assertTrue(saved(service).pendingInputs().isEmpty());
            assertTrue(saved(service).scheduledEvents().isEmpty());
        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> fail("No completed send should replay"));
        try {
            var closed = savedRecord(restoredService, buyer.getId()).trade();
            assertInstanceOf(TradeClosed.class, closed.getExecution().state());
            assertEquals(signature, closed.getExecution().data().getPeerSwapTxSignature().orElseThrow());
            await(restoredService.paymentInitiated(buyer.getId()));
            assertSame(closed, savedRecord(restoredService, buyer.getId()).trade());
            await(submitMessage(restoredService, confirmation));
            assertSame(closed, savedRecord(restoredService, confirmation.getTradeId()).trade());
        } finally {
            restoredService.shutdown().join();
        }
        verify(backend, times(1)).getPartialSignatures(any());
        verify(backend, times(1)).closeTrade(any());
    }

    private static ListAppender<ILoggingEvent> captureServiceLog() {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(MuSigTradeService.class)).addAppender(appender);
        return appender;
    }

    private static void release(ListAppender<ILoggingEvent> appender) {
        ((Logger) LoggerFactory.getLogger(MuSigTradeService.class)).detachAppender(appender);
        appender.stop();
    }

    private static List<ILoggingEvent> recoveryWarnings(ListAppender<ILoggingEvent> appender) {
        // The appender adds an event while it holds its own lock.
        synchronized (appender) {
            return appender.list.stream()
                    .filter(event -> event.getLevel() == Level.WARN)
                    .filter(event -> event.getFormattedMessage().startsWith("Startup recovery"))
                    .toList();
        }
    }
}
