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
import bisq.common.observable.Pin;
import bisq.common.observable.map.ReadOnlyObservableMap;
import bisq.common.processing.Input;
import bisq.common.processing.Processor;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.Initialization;
import bisq.common.processing.lane.LaneRouter;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.RejectedEntityRegistry;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.threading.ExecutorFactory;
import bisq.common.timer.Clock;
import bisq.common.timer.SystemClock;
import bisq.common.util.StringUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.NetworkService;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.network.p2p.services.confidential.ConfidentialMessageService;
import bisq.persistence.DbSubDirectory;
import bisq.persistence.Persistence;
import bisq.persistence.PersistenceClient;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRestrictedException;
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
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.user.UserService;
import com.google.common.annotations.VisibleForTesting;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
 * a fixed worker pool; lane work waits while all workers are busy. Application adapters handle chat, network
 * delivery and backend subscriptions.
 * <p>
 * Startup registers every saved lane, then the router publishes their snapshots before queueing recovery
 * and admitting network inputs. Initialization does not wait for recovery. Shutdown removes the listener
 * and drains lanes before stopping the executors.
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
    // Keeps the earlier maximum concurrency; this is not a measured optimum.
    @VisibleForTesting
    static final int WORKER_POOL_SIZE = 5;

    // Service dependencies
    private final ServiceProvider serviceProvider;
    @Getter
    private final MusigGrpcClient musigGrpcClient;
    private final NetworkService networkService;
    private final Clock clock;

    // Application adapters
    private final MuSigTradeChatService tradeChatService;
    private final MuSigDepositTxObserver depositTxObserver;

    // Processing lanes
    private final ExecutorService executor;
    private final ScheduledExecutorService timerExecutor;
    private final LaneRouter<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> router;
    // Retain across future activations; only routers inspect and update this memory.
    private final RejectedEntityRegistry rejectedEntities = new RejectedEntityRegistry();

    // Persistence
    private final Object persistenceLock = new Object();
    @Getter
    private final MuSigV2TradeStore persistableStore = new MuSigV2TradeStore();
    @Getter
    private final Persistence<MuSigV2TradeStore> persistence;

    // Read models
    private final MuSigTradeReadModelStore readModelStore = new MuSigTradeReadModelStore();

    // Service lifecycle
    private final Object lock = new Object();
    private final CompletableFuture<Boolean> initialization = new CompletableFuture<>();
    private final CompletableFuture<Boolean> stopped = new CompletableFuture<>();
    private boolean initialized;
    private boolean closed;
    private CompletableFuture<Void> recovery = CompletableFuture.completedFuture(null);
    private Optional<Pin> alertsPin = Optional.empty();


    /* --------------------------------------------------------------------- */
    // Construction
    /* --------------------------------------------------------------------- */

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
        this.clock = clock;
        executor = createWorkerPool();

        persistence = serviceProvider.getPersistenceService().getOrCreatePersistence(
                this,
                DbSubDirectory.PRIVATE,
                "MuSigV2TradeStore",
                persistableStore);

        networkService = serviceProvider.getNetworkService();
        UserService userService = serviceProvider.getUserService();
        MuSigTradeMessageSender messageSender =
                new MuSigTradeMessageSender(networkService, userService.getBannedUserService());
        tradeChatService = new MuSigTradeChatService(
                serviceProvider.getChatService().getMuSigOpenTradeChannelService(),
                userService.getUserIdentityService(),
                userService.getUserProfileService());

        depositTxObserver = new MuSigDepositTxObserver(musigGrpcClient::getAsyncStub);
        MuSigTradeEffectHandler effectHandler = new MuSigTradeEffectHandler(
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

        MuSigInputPolicy inputPolicy = new MuSigInputPolicy(serviceProvider, appType);
        MuSigTradeInitializer tradeInitializer = new MuSigTradeInitializer(
                serviceProvider,
                inputPolicy,
                messageSender);

        ProcessingLaneDefinition<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> laneDefinition =
                createLaneDefinition(tradeInitializer);
        MuSigTakenOfferRemoval takenOfferRemoval =
                new MuSigTakenOfferRemoval(serviceProvider.getOfferService().getMuSigOfferService(),
                        serviceProvider.getSettingsService());
        MuSigPeerContactAddition peerContactAddition =
                new MuSigPeerContactAddition(serviceProvider.getSettingsService(), userService);
        MuSigTradeUpdateListener updateListener =
                new MuSigTradeUpdateListener(readModelStore, takenOfferRemoval, peerContactAddition);

        router = new LaneRouter<>(
                inputPolicy,
                laneDefinition,
                executor,
                timerExecutor,
                clock,
                this::saveCheckpoint,
                effectHandler,
                updateListener,
                rejectedEntities);
    }

    /**
     * While all workers are busy, a lane's worker request waits in the executor queue. A rejected request would
     * close its lane permanently. The queue has no capacity limit, but it holds at most one request per lane.
     */
    @VisibleForTesting
    static ExecutorService createWorkerPool() {
        return ExecutorFactory.newFixedThreadPool("MuSigTradeServiceV2", WORKER_POOL_SIZE);
    }


    /* --------------------------------------------------------------------- */
    // Service lifecycle
    /* --------------------------------------------------------------------- */

    @Override
    public CompletableFuture<Boolean> initialize() {
        synchronized (lock) {
            if (closed) {
                return CompletableFuture.failedFuture(new RejectedExecutionException("Trade service is closed"));
            }
            if (initialized) {
                return initialization.copy();
            }
            log.info("initialize");
            Map<String, Checkpoint<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event>> checkpoints =
                    persistableStore.getTrades().stream()
                            .collect(Collectors.toMap(record -> record.trade().getId(),
                                    MuSigV2TradeRecord::toCheckpoint));
            router.restore(checkpoints);
            initialized = true;
            recovery = new CompletableFuture<>();
        }
        try {
            router.start().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    recovery.complete(null);
                } else {
                    logLaneFailure("Startup recovery of the saved MuSig V2 trades failed", failure);
                    recovery.completeExceptionally(failure);
                }
            });
            synchronized (lock) {
                requireRunning();
                // Replays the messages which the network processed before, each exactly once and each on its own,
                // then delivers new ones (startup-replay.md). Holding the lock keeps a concurrent shutdown from running
                // between the check and the subscription. The network calls onMessage outside its own lock.
                networkService.subscribeToConfidentialMessages(this);
                // A trade restriction pauses inputs which the lanes keep. After a change of the alerts, the lanes
                // continue with the inputs which are allowed now. The registration also calls the observer once.
                alertsPin = Optional.of(serviceProvider.getBondedRolesService().getAlertService()
                        .getAuthorizedAlertDataSet()
                        .addObserver(this::resumePausedInputs));
            }
            initialization.complete(true);
        } catch (RuntimeException failure) {
            recovery.completeExceptionally(failure);
            initialization.completeExceptionally(failure);
        }
        return initialization.copy();
    }

    /**
     * Completes after accepted work, including initialization, has drained and both executors have terminated.
     */
    @Override
    public CompletableFuture<Boolean> shutdown() {
        synchronized (lock) {
            if (closed) {
                return stopped.copy();
            }
            closed = true;
            networkService.removeConfidentialMessageListener(this);
            alertsPin.ifPresent(Pin::unbind);
            alertsPin = Optional.empty();
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

    /**
     * Observes startup recovery separately from initialization, without allowing callers to cancel it.
     */
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
            } else if (message instanceof MuSigMediationOpenedMessage opened) {
                tradeId = opened.getTradeId();
                source = "Mediation message";
                result = submit(tradeId, opened);
            } else if (message instanceof MuSigMediationClosedMessage closed) {
                tradeId = closed.getTradeId();
                source = "Mediation message";
                result = submit(tradeId, closed);
            } else if (message instanceof MuSigDisputeCasePaymentDetailsRequest request) {
                tradeId = request.getTradeId();
                source = "Payment details request";
                result = submit(tradeId, request);
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

    /**
     * The deposit observer reports the first confirmation of the trade's deposit transaction.
     */
    @VisibleForTesting
    public CompletableFuture<Void> onDepositTxConfirmed(String tradeId) {
        return submit(tradeId, new DepositTxConfirmedEvent());
    }

    private void resumePausedInputs() {
        router.resumePausedInputs().whenComplete((ignored, failure) -> {
            if (failure != null) {
                logLaneFailure("Continuing the paused inputs of the MuSig V2 trades failed", failure);
            }
        });
    }

    private void onDepositTxObservationFailed(String tradeId, Throwable failure) {
        log.warn("Deposit observation failed for trade {}", StringUtils.sanitizeForLog(tradeId), failure);
    }


    /* --------------------------------------------------------------------- */
    // Commands
    /* --------------------------------------------------------------------- */

    /**
     * Submits the taker's initializer under the trade ID which follows from the command. Completion includes setup
     * and the first local protocol step. A trade restriction which arrives during the setup pauses that step: the
     * future still completes, and the step runs when the restriction ends.
     */
    public CompletableFuture<Void> initializeTrade(InitializeTrade command) {
        return submit(command.getTradeId(), command);
    }

    /**
     * Development-only command; backend confirmation remains an independently deferrable notification.
     */
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

    /**
     * Stops business processing while retaining the trade record and any committed outgoing messages.
     */
    public CompletableFuture<Void> archiveTrade(String tradeId) {
        return submit(tradeId, new TradeArchivedEvent());
    }


    /* --------------------------------------------------------------------- */
    // Read models
    /* --------------------------------------------------------------------- */

    /**
     * Returns the same observable model for the lifetime of a registered trade.
     */
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
     * The initializer validates the request and returns the initial trade without starting it.
     * The lane saves that trade and its first input before calling repeatable chat setup.
     * Restoration selects the processor from the saved role.
     * Creating this definition does not initialize trades, run setup or construct processors.
     */
    private ProcessingLaneDefinition<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> createLaneDefinition(
            MuSigTradeInitializer tradeInitializer) {
        MuSigTradeInputValidator inputValidator =
                new MuSigTradeInputValidator(
                        serviceProvider.getUserService().getBannedUserService(),
                        serviceProvider.getChatService().getMuSigOpenTradeChannelService());
        return ProcessingLaneDefinition
                .builder(Event.class,
                        this::createProcessor,
                        inputValidator)
                .initializeOn(InitializeTrade.class,
                        command -> new Initialization<>(
                                new StateMachineSnapshot<>(tradeInitializer.initializeForTaker(command), Map.of()),
                                new MuSigTakeOfferEvent()))
                .initializeOn(SetupTradeMessage_A.class,
                        message -> new Initialization<>(
                                new StateMachineSnapshot<>(tradeInitializer.initializeForMaker(message), Map.of()),
                                message))
                .stashUntilInitialized(MuSigTradeMessage.class,
                        MuSigMediationOpenedMessage.class,
                        MuSigMediationClosedMessage.class,
                        MuSigDisputeCasePaymentDetailsRequest.class)
                .setupWith(snapshot -> setupTrade(snapshot.document()))
                .build();
    }

    /** Creates the processor of a new or a restored trade. The role of the trade selects the protocol. */
    private Processor<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> createProcessor(
            StateMachineSnapshot<MuSigV2Trade<?>, Flow> snapshot) {
        MuSigTradeProcessor<?> processor =
                MuSigTradeProcessor.create(serviceProvider, musigGrpcClient, snapshot.document(), clock);
        // A trade's execution state always matches its role, and the factory selects the processor by role.
        // The lane keeps that role and replaces documents only through that processor's typed flow writers.
        @SuppressWarnings("unchecked")
        Processor<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> laneProcessor =
                (Processor<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event>) (Processor<?, ?>) processor;
        return laneProcessor;
    }


    /* --------------------------------------------------------------------- */
    // Trade setup
    /* --------------------------------------------------------------------- */

    /**
     * Required setup for new and restored trades; the lane publishes failures and successful recovery.
     */
    private void setupTrade(MuSigV2Trade<?> trade) {
        try {
            MuSigContract contract = trade.getContract();
            tradeChatService.ensureChannel(trade.getId(),
                    trade.getMyIdentity().getId(),
                    trade.getPeerParty().getNetworkId().getId(),
                    contract.getMediator(),
                    contract.getArbitrator());
        } catch (RuntimeException failure) {
            log.warn("Setup failed for trade {}", StringUtils.sanitizeForLog(trade.getId()), failure);
            throw failure;
        }
    }


    /* --------------------------------------------------------------------- */
    // Persistence
    /* --------------------------------------------------------------------- */

    @Override
    public Optional<MuSigV2TradeStore> readPersisted() {
        synchronized (lock) {
            checkState(!initialized && !closed, "Read persisted trades before initializing the service");
            Optional<MuSigV2TradeStore> restored = persistence.read();
            restored.ifPresent(persistableStore::applyPersisted);
            return restored;
        }
    }

    @Override
    public CompletableFuture<Boolean> persist() {
        synchronized (lock) {
            synchronized (persistenceLock) {
                return persistence.persistAsync(persistableStore.getClone()).thenApply(ignored -> true);
            }
        }
    }

    /**
     * Returns only after the store was written; a failed save throws. Only the lanes call this.
     */
    private void saveCheckpoint(Checkpoint<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event> checkpoint) {
        MuSigV2TradeRecord record = MuSigV2TradeRecord.fromCheckpoint(checkpoint);
        synchronized (persistenceLock) {
            MuSigV2TradeStore candidate = persistableStore.getClone();
            candidate.put(record);
            persistence.persistAsync(candidate).join();
            // Publish only after saving succeeds so other trades cannot save a failed candidate.
            persistableStore.put(record);
        }
    }


    /* --------------------------------------------------------------------- */
    // Submission
    /* --------------------------------------------------------------------- */

    @VisibleForTesting
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

    /**
     * Report processing and coordination failures without exposing the lane's internal document.
     */
    private void checkSubmissionResult(SubmissionResult<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> result) {
        ProcessingSummary<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> processing = switch (result) {
            case SubmissionResult.Completed(var value) -> value;
            case SubmissionResult.Pending(var ignored, var phase, var cause) -> throw cause;
            case SubmissionResult.NotProcessed(var cause) -> throw cause;
        };
        switch (processing) {
            case ProcessingSummary.Applied(var ignored) -> {
            }
            case ProcessingSummary.Deferred(var ignored) -> {
            }
            case ProcessingSummary.Ignored(var ignored) -> {
            }
            case ProcessingSummary.Rejected(var cause) -> throw cause;
            case ProcessingSummary.Failed(var ignored, var cause) -> throw cause;
        }
    }

    // No production code waits for the recovery of the saved trades or for the continuation of paused inputs, so
    // the service reports their failure itself.
    private void logLaneFailure(String message, Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (isRejectedByShutdown(cause)) {
            return;
        }
        log.warn(message, failure);
    }

    private void logSubmissionFailure(String source, String tradeId, Throwable failure) {
        Throwable cause = failure instanceof CompletionException ? failure.getCause() : failure;
        if (isRejectedByShutdown(cause)) {
            return;
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

    // A shutdown rejects the work which is still waiting. That is expected and needs no log line.
    private boolean isRejectedByShutdown(Throwable cause) {
        synchronized (lock) {
            return closed && cause instanceof RejectedExecutionException;
        }
    }

    private void requireRunning() {
        if (closed) {
            throw new RejectedExecutionException("Trade service is closed");
        }
        checkState(initialized, "Trade service is not initialized");
    }
}
