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
import bisq.common.application.Service;
import bisq.common.observable.map.ReadOnlyObservableMap;
import bisq.common.processing.Initialization;
import bisq.common.processing.Input;
import bisq.common.processing.LaneRouter;
import bisq.common.processing.ProcessingLaneDefinition;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.Processor;
import bisq.common.processing.RejectedEntityRegistry;
import bisq.common.processing.SubmissionResult;
import bisq.common.statemachine.Delivery;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.threading.AbortPolicyWithLogging;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.Clock;
import bisq.common.timer.SystemClock;
import bisq.common.util.StringUtils;
import bisq.network.NetworkService;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceClient;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.MediationCaseState;
import bisq.support.mediation.mu_sig.MuSigMediationStateChangeMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRestrictedException;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeProcessor;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.user.contact_list.ContactReason;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkState;

/**
 * Application API and network listener for V2 trades. A LaneRouter routes each trade ID to one ProcessingLane,
 * which owns initialization, serial processing, checkpoints, pending work and timers. Different trades share
 * the bounded worker pool. Application adapters handle chat, network delivery and backend subscriptions.
 * <p>
 * Startup registers every saved lane before queueing recovery and admitting network inputs. Initialization
 * does not wait for recovery. Shutdown removes the listener and drains lanes before stopping the executors.
 * Early peer messages wait in memory until initialization; initialized pending inputs are checkpointed.
 * Handlers and future continuations must never wait for work submitted to the same lane or worker pool.
 * <p>
 * The V2 application setup initializes the supplied client before this service and closes it after shutdown.
 * Commands and backend notifications return completion-only futures; callers observe trade state through the read model.
 * An exceptional future may accompany an applied transition with pending coordination; it does not imply rollback.
 */
@Slf4j
public final class MuSigTradeService implements Service, PersistenceClient<MuSigV2TradeStore>,
        ConfidentialMessageService.Listener {
    private final Object lock = new Object();
    private final Object persistenceLock = new Object();
    @Getter
    private final MuSigV2TradeStore persistableStore = new MuSigV2TradeStore();
    @Getter
    private final Persistence<MuSigV2TradeStore> persistence;
    private final ServiceProvider serviceProvider;
    @Getter
    private final MusigGrpcClient musigGrpcClient;
    private final NetworkService networkService;
    private final MuSigTradeChatService tradeChatService;
    // Retain across future activations; only routers inspect and update this memory.
    private final RejectedEntityRegistry rejectedEntities = new RejectedEntityRegistry();
    private final MuSigDepositTxObserver depositTxObserver;
    private final ExecutorService executor;
    private final ScheduledExecutorService timerExecutor;
    private final LaneRouter<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>> router;
    private final MuSigTradeReadModelStore readModelStore = new MuSigTradeReadModelStore();
    private final CompletableFuture<Boolean> stopped = new CompletableFuture<>();
    private final CompletableFuture<Boolean> initialization = new CompletableFuture<>();
    private boolean initialized;
    private boolean closed;
    private Optional<RuntimeException> restoreFailure = Optional.empty();
    private CompletableFuture<Void> recovery = CompletableFuture.completedFuture(null);

    public MuSigTradeService(ServiceProvider serviceProvider,
                             MusigGrpcClient musigGrpcClient,
                             AppType appType) {
        this(serviceProvider,
                musigGrpcClient,
                appType,
                new SystemClock(),
                ExecutorFactory.newSingleThreadScheduledExecutor("MuSigTradeServiceV2-timers"));
    }

    @VisibleForTesting
    MuSigTradeService(ServiceProvider serviceProvider,
                      MusigGrpcClient musigGrpcClient,
                      AppType appType,
                      Clock clock,
                      ScheduledExecutorService timerExecutor) {
        this.timerExecutor = timerExecutor;
        this.serviceProvider = serviceProvider;
        this.musigGrpcClient = musigGrpcClient;
        var submissionPolicy = new MuSigSubmissionPolicy(serviceProvider, appType);
        networkService = serviceProvider.getNetworkService();
        var userService = serviceProvider.getUserService();
        var messageSender = new MuSigTradeMessageSender(networkService, userService.getBannedUserService());
        tradeChatService = new MuSigTradeChatService(serviceProvider.getChatService().getMuSigOpenTradeChannelService(),
                userService.getUserIdentityService(),
                userService.getUserProfileService());
        String name = "MuSigTradeServiceV2";
        int maxPoolSize = 5;
        int queueCapacity = 100;
        executor = ExecutorFactory.boundedCachedPool(name,
                2,
                maxPoolSize,
                30,
                queueCapacity,
                new AbortPolicyWithLogging(name, queueCapacity, maxPoolSize));
        persistence = serviceProvider.getPersistenceService().getOrCreatePersistence(
                this,
                DbSubDirectory.PRIVATE,
                "MuSigV2TradeStore",
                persistableStore);
        depositTxObserver = new MuSigDepositTxObserver(
                musigGrpcClient::getAsyncStub);
        var effectHandler = new MuSigTradeEffectHandler(serviceProvider.getIdentityService(),
                serviceProvider.getOfferService().getMuSigOfferService(),
                messageSender,
                tradeChatService,
                depositTxObserver,
                tradeId -> onDepositTxConfirmed(tradeId)
                        .whenComplete((ignored, failure) -> {
                            if (failure != null) {
                                logSubmissionFailure("Backend callback", tradeId, failure);
                            }
                        }),
                this::onDepositTxObservationFailed);
        var admission = new MuSigTradeAdmission(serviceProvider, submissionPolicy, effectHandler);
        var laneDefinition = createLaneDefinition(admission, submissionPolicy, clock);
        router = new LaneRouter<>(laneDefinition,
                executor,
                timerExecutor,
                clock,
                checkpoint -> persistCheckpointAndWait(MuSigV2TradeRecord.fromCheckpoint(checkpoint)),
                (snapshot, effect) -> effectHandler.handle(snapshot.document(), effect),
                tradeChatService::postLog,
                (snapshot, persistenceFailure, setupFailure) -> readModelStore.upsert(snapshot.document(),
                        persistenceFailure,
                        setupFailure),
                rejectedEntities);
    }


    /* --------------------------------------------------------------------- */
    // Service lifecycle
    /* --------------------------------------------------------------------- */

    @Override
    public CompletableFuture<Boolean> initialize() {
        List<MuSigV2TradeRecord> records;
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Trade service is closed"));
            }
            if (restoreFailure.isPresent()) {
                return CompletableFuture.failedFuture(restoreFailure.get());
            }
            if (initialized) {
                return initialization.copy();
            }
            log.info("initialize");
            records = persistableStore.getTrades();
            var checkpoints = records.stream()
                    .collect(Collectors.toMap(record -> record.trade().getId(), MuSigV2TradeRecord::toCheckpoint));
            router.restore(checkpoints);
            initialized = true;
            recovery = new CompletableFuture<>();
        }
        // Observers may submit inputs. The router buffers them until every recovery has been queued.
        records.forEach(record -> readModelStore.upsert(record.trade(), Optional.empty(), Optional.empty()));
        try {
            router.start().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    recovery.complete(null);
                } else {
                    recovery.completeExceptionally(failure);
                }
            });
            List<EnvelopePayloadMessage> cachedMessages;
            synchronized (lock) {
                requireRunning();
                // Subscribe before reading cached messages so concurrent delivery cannot fall between them.
                networkService.addConfidentialMessageListener(this);
                cachedMessages = networkService.getConfidentialMessageServices().stream()
                        .flatMap(service -> service.getProcessedEnvelopePayloadMessages().stream())
                        .toList();
            }
            cachedMessages.forEach(this::onMessage);
            initialization.complete(true);
        } catch (RuntimeException failure) {
            recovery.completeExceptionally(failure);
            initialization.completeExceptionally(failure);
        }
        return initialization.copy();
    }

    /** Completes after accepted work, including initialization, has drained and both executors have terminated. */
    @Override
    public CompletableFuture<Boolean> shutdown() {
        synchronized (lock) {
            if (closed) {
                return stopped.copy();
            }
            closed = true;
            networkService.removeConfidentialMessageListener(this);
        }
        log.info("shutdown");
        router.shutdown()
                // A lane may finish on a trade worker, which cannot wait for its own termination.
                .whenCompleteAsync((ignored, failure) -> {
                    depositTxObserver.close();
                    timerExecutor.shutdownNow();
                    executor.close();
                    timerExecutor.close();
                })
                .whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        stopped.complete(true);
                    } else {
                        stopped.completeExceptionally(failure);
                    }
                });
        return stopped.copy();
    }

    /** Observes startup recovery separately from initialization, without allowing callers to cancel it. */
    public CompletableFuture<Void> getRecoveryFuture() {
        synchronized (lock) {
            checkState(initialized, "Trade service is not initialized");
            return recovery.copy();
        }
    }


    /* --------------------------------------------------------------------- */
    // Messages and callbacks
    /* --------------------------------------------------------------------- */

    @Override
    public void onMessage(EnvelopePayloadMessage message) {
        try {
            String tradeId;
            String source;
            CompletableFuture<Void> result;
            if (message instanceof MuSigTradeMessage tradeMessage) {
                tradeId = tradeMessage.getTradeId();
                source = "Trade message";
                result = submit(tradeId, tradeMessage);
            } else if (message instanceof MuSigMediationStateChangeMessage mediationMessage) {
                tradeId = mediationMessage.getTradeId();
                source = "Mediation message";
                if (mediationMessage.getMediationCaseState() == MediationCaseState.OPEN) {
                    result = submit(tradeId, new MediationOpenedEvent(mediationMessage));
                } else if (mediationMessage.getMediationCaseState() == MediationCaseState.CLOSED) {
                    result = submit(tradeId, new MediationClosedEvent(mediationMessage));
                } else {
                    // Reopening a second case is not modeled; preserve the existing network-side ignore policy.
                    return;
                }
            } else if (message instanceof MuSigDisputeCasePaymentDetailsRequest request) {
                tradeId = request.getTradeId();
                source = "Payment details request";
                result = submit(tradeId, new MediationPaymentDetailsRequestedEvent(request));
            } else {
                return;
            }
            result.whenComplete((ignored, failure) -> {
                if (failure != null) {
                    logSubmissionFailure(source, tradeId, failure);
                }
            });
        } catch (RuntimeException exception) {
            log.warn("Could not submit incoming MuSig message", exception);
        }
    }

    public CompletableFuture<Void> onDepositTxConfirmed(String tradeId) {
        return submit(tradeId, new DepositTxConfirmedEvent());
    }

    private void onDepositTxObservationFailed(String tradeId, Throwable failure) {
        log.warn("Deposit observation failed for trade {}", StringUtils.sanitizeForLog(tradeId), failure);
    }


    /* --------------------------------------------------------------------- */
    // Commands
    /* --------------------------------------------------------------------- */

    /** Submits the taker's initializer. Completion includes setup and the first local protocol step. */
    public CompletableFuture<Void> initializeTrade(String tradeId, InitializeTrade command) {
        String expectedId = TradeV2.createId(command.getOffer().getId(),
                command.getTakerIdentity().getNetworkId().getId(),
                command.getTakeOfferDate());
        if (!tradeId.equals(expectedId)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Wrong trade ID for preparation"));
        }
        return submit(tradeId, command);
    }

    /** Development-only command; backend confirmation remains an independently deferrable notification. */
    public CompletableFuture<Void> skipWaitForConfirmation(String tradeId) {
        return submit(tradeId, new DepositConfirmationSkippedEvent());
    }

    public CompletableFuture<Void> paymentInitiated(String tradeId) {
        return submit(tradeId, new PaymentInitiatedEvent());
    }

    public CompletableFuture<Void> paymentReceiptConfirmed(String tradeId) {
        return submit(tradeId, new PaymentReceiptConfirmedEvent());
    }

    public CompletableFuture<Void> requestMediation(String tradeId) {
        return submit(tradeId, new MediationRequestedEvent());
    }

    public CompletableFuture<Void> acceptMediationResult(String tradeId) {
        return submit(tradeId, new MediationResultAcceptedEvent());
    }

    public CompletableFuture<Void> rejectMediationResult(String tradeId) {
        return submit(tradeId, new MediationResultRejectedEvent());
    }

    /** Stops business processing while retaining the trade record and any committed outgoing messages. */
    public CompletableFuture<Void> archiveTrade(String tradeId) {
        return submit(tradeId, new TradeArchivedEvent());
    }


    /* --------------------------------------------------------------------- */
    // Read models
    /* --------------------------------------------------------------------- */

    /** Returns the same observable model for the lifetime of a registered trade. */
    public MuSigTradeReadModel getTradeReadModel(String tradeId) {
        return readModelStore.get(tradeId);
    }

    /**
     * Observers receive existing models when subscribing and new models for taker, maker and restored trades.
     * Later state changes use each model's observables. Notifications run on the publishing thread and do not
     * acknowledge persistence; UI consumers must dispatch to the UI thread and must not block processing.
     */
    public ReadOnlyObservableMap<String, MuSigTradeReadModel> getTradeReadModels() {
        return readModelStore.getAll();
    }


    /* --------------------------------------------------------------------- */
    // Lane configuration
    /* --------------------------------------------------------------------- */

    /**
     * Admission validates the request and returns the initial trade without starting it.
     * The lane saves that trade and its first input before calling repeatable chat setup.
     * Automatic contact creation runs only for new trades. Restoration selects the processor from the saved role.
     * Creating this definition does not admit trades, run setup or construct processors.
     */
    private ProcessingLaneDefinition<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>> createLaneDefinition(
            MuSigTradeAdmission admission,
            MuSigSubmissionPolicy submissionPolicy,
            Clock clock) {
        return ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>>builder(Event.class,
                        snapshot -> {
                            var processor = MuSigTradeProcessor.create(serviceProvider, musigGrpcClient, snapshot.document(), clock);
                            // The factory checks the execution state family. The lane keeps that role
                            // and replaces documents only through that processor's typed flow writers.
                            @SuppressWarnings("unchecked")
                            var laneProcessor = (Processor<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>>)
                                    (Processor<?, ?, ?>) processor;
                            return laneProcessor;
                        })
                .checkSubmissionWith(submissionPolicy::check)
                .initializeOn(InitializeTrade.class,
                        command -> new Initialization<>(
                                new StateMachineSnapshot<>(admission.admitTaker(command), Map.of()),
                                Optional.of(new MuSigTakeOfferEvent())))
                .initializeOn(SetupTradeMessage_A.class,
                        message -> new Initialization<>(
                                new StateMachineSnapshot<>(admission.admitMaker(message), Map.of()),
                                Optional.of(message)))
                .stashUntilInitialized(MuSigTradeMessage.class,
                        MediationOpenedEvent.class,
                        MediationClosedEvent.class,
                        MediationPaymentDetailsRequestedEvent.class)
                .setupWith(snapshot -> setupTrade(snapshot.document()))
                .onInitialized(snapshot -> maybeAddPeerToContactList(snapshot.document()))
                .build();
    }


    /* --------------------------------------------------------------------- */
    // Trade setup
    /* --------------------------------------------------------------------- */

    /** Required setup for new and restored trades; the lane publishes failures and successful recovery. */
    private void setupTrade(MuSigV2Trade<?> trade) {
        try {
            var contract = trade.getContract();
            var peerParty = trade.getMyRole().isMaker() ? contract.getTaker() : contract.getMaker();
            tradeChatService.ensureChannel(trade.getId(),
                    trade.getMyIdentity().getId(),
                    peerParty.getNetworkId().getId(),
                    contract.getMediator(),
                    contract.getArbitrator());
        } catch (RuntimeException failure) {
            log.warn("Setup failed for trade {}", StringUtils.sanitizeForLog(trade.getId()), failure);
            throw failure;
        }
    }

    private void maybeAddPeerToContactList(MuSigV2Trade<?> trade) {
        try {
            if (serviceProvider.getSettingsService().getDoAutoAddToContactList()) {
                var userService = serviceProvider.getUserService();
                var userProfileService = userService.getUserProfileService();
                var contract = trade.getContract();
                var peerParty = trade.getMyRole().isMaker() ? contract.getTaker() : contract.getMaker();
                var peersProfile = userProfileService.findUserProfile(peerParty.getNetworkId().getId());
                var myProfile = userProfileService.findUserProfile(trade.getMyIdentity().getId());
                if (peersProfile.isPresent() && myProfile.isPresent()) {
                    userService.getContactListService().addContactListEntry(peersProfile.get(),
                            myProfile.get(),
                            ContactReason.MUSIG_TRADE);
                }
            }
        } catch (RuntimeException exception) {
            log.warn("Adding the peer to the contact list failed for tradeId={}. The trade is unaffected.",
                    StringUtils.sanitizeForLog(trade.getId()), exception);
        }
    }


    /* --------------------------------------------------------------------- */
    // Persistence
    /* --------------------------------------------------------------------- */

    @Override
    public Optional<MuSigV2TradeStore> readPersisted() {
        synchronized (lock) {
            checkState(!initialized && !closed, "Read persisted trades before initializing the service");
            try {
                var restored = persistence.readOrThrow();
                restored.ifPresent(persistableStore::applyPersisted);
                restoreFailure = Optional.empty();
                return restored;
            } catch (RuntimeException failure) {
                // PersistenceService reports false rather than propagating client read failures.
                restoreFailure = Optional.of(failure);
                throw failure;
            }
        }
    }

    @Override
    public CompletableFuture<Boolean> persist() {
        synchronized (lock) {
            if (restoreFailure.isPresent()) {
                return CompletableFuture.failedFuture(restoreFailure.get());
            }
            synchronized (persistenceLock) {
                return persistence.persistAsync(persistableStore.getClone()).thenApply(ignored -> true);
            }
        }
    }

    private void persistCheckpointAndWait(MuSigV2TradeRecord record) {
        synchronized (persistenceLock) {
            var candidate = persistableStore.getClone();
            candidate.put(record);
            persistence.persistAsync(candidate).join();
            // Publish only after saving succeeds so other trades cannot save a failed candidate.
            persistableStore.put(record);
        }
    }


    /* --------------------------------------------------------------------- */
    // Submission
    /* --------------------------------------------------------------------- */

    CompletableFuture<Void> submit(String tradeId, Input input) {
        synchronized (lock) {
            try {
                requireRunning();
            } catch (RuntimeException failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        return router.submit(tradeId, input).thenAccept(this::checkSubmissionResult);
    }

    /** Report processing and coordination failures without exposing the lane's internal document. */
    private void checkSubmissionResult(SubmissionResult<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> result) {
        var processing = switch (result) {
            case SubmissionResult.Completed(var value) -> value;
            case SubmissionResult.Pending(var ignored, var phase, var cause) -> throw cause;
            case SubmissionResult.NotProcessed(var cause) -> throw cause;
        };
        switch (processing) {
            case ProcessingResult.Applied(var ignored) -> {}
            case ProcessingResult.Deferred(var ignored) -> {}
            case ProcessingResult.Ignored(var ignored) -> {}
            case ProcessingResult.Rejected(var cause) -> throw cause;
            case ProcessingResult.Failed(var ignored, var cause) -> throw cause;
        }
    }

    private void logSubmissionFailure(String source, String tradeId, Throwable failure) {
        var cause = failure instanceof CompletionException ? failure.getCause() : failure;
        synchronized (lock) {
            if (closed && cause instanceof RejectedExecutionException) {
                return;
            }
        }
        if (cause instanceof TradeRestrictedException ||
                cause instanceof MuSigProtocolException protocolException &&
                        protocolException.getCategory() == MuSigFailureCategory.INPUT_REJECTED) {
            log.warn("{} rejected for trade {}: {}", source,
                    StringUtils.sanitizeForLog(tradeId), StringUtils.sanitizeForLog(cause.getMessage()));
        } else {
            log.warn("{} failed for trade {}", source, StringUtils.sanitizeForLog(tradeId), failure);
        }
    }

    private void requireRunning() {
        if (closed) {
            throw new RejectedExecutionException("Trade service is closed");
        }
        checkState(initialized, "Trade service is not initialized");
    }


    /* --------------------------------------------------------------------- */
    // Test access
    /* --------------------------------------------------------------------- */

    @VisibleForTesting
    LaneRouter<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>> getRouter() {
        return router;
    }
}
