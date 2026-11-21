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

import bisq.common.processing.Effect;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.InputValidator;
import bisq.common.processing.lane.ProcessingLane;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.timer.Clock;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig_v2.MuSigV2TradeRecord;
import bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;

import bisq.user.banned.BannedUserService;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;

/**
 * Runs the real lane and processor on the calling test thread. Checkpoints stay in memory and the timer
 * executor is mocked; tests submit timeout events explicitly. No queue or processing rules are implemented here.
 * Snapshot access is only for assertions after synchronous submissions, not an application query API.
 */
public final class MuSigTradeLaneFixture<S extends MuSigTradeExecutionState> implements AutoCloseable {
    private final InputValidator<StateMachineSnapshot<? extends MuSigV2Trade<?>, Flow>, Event> inputValidator;
    private final ProcessingLane<StateMachineSnapshot<MuSigV2Trade<S>, Flow>, Event> lane;
    private StateMachineSnapshot<MuSigV2Trade<S>, Flow> snapshot;
    private MuSigV2TradeRecord savedRecord;

    public static <S extends MuSigTradeExecutionState> MuSigTradeProcessor<S> createProcessor(
            ServiceProvider services,
            MusigGrpcClient grpcClient,
            MuSigV2Trade<?> trade,
            Class<S> stateFamily,
            MuSigExecutionProtocol<S> execution,
            Clock clock) {
        return MuSigTradeProcessor.assemble(services, grpcClient, trade, execution, clock);
    }

    public MuSigTradeLaneFixture(ServiceProvider services,
                                 MusigGrpcClient grpcClient,
                                 MuSigV2Trade<S> trade,
                                 Class<S> stateFamily,
                                 MuSigExecutionProtocol<S> execution,
                                 Consumer<Effect> effects,
                                 Consumer<ProtocolLogMessage> logs,
                                 Clock clock) {
        this(services,
                grpcClient,
                initialRecord(trade),
                stateFamily,
                execution,
                effects,
                logs,
                clock);
    }

    /** Restores and resumes the complete saved record, including work that has not finished yet. */
    public MuSigTradeLaneFixture(ServiceProvider services,
                                 MusigGrpcClient grpcClient,
                                 MuSigV2TradeRecord record,
                                 Class<S> stateFamily,
                                 MuSigExecutionProtocol<S> execution,
                                 Consumer<Effect> effects,
                                 Consumer<ProtocolLogMessage> logs,
                                 Clock clock) {
        inputValidator = MuSigV2TradeTestFactory.inputValidator(mock(BannedUserService.class),
                services.getChatService().getMuSigOpenTradeChannelService());
        var processor = createProcessor(services, grpcClient, record.trade(), stateFamily, execution, clock);
        var restored = record.toCheckpoint();
        // A trade's execution state always matches its role, so the processor chosen for that role fits it.
        @SuppressWarnings("unchecked")
        var trade = (MuSigV2Trade<S>) restored.snapshot().document();
        snapshot = new StateMachineSnapshot<>(trade,
                restored.snapshot().processedEventTypes());
        var checkpoint = new Checkpoint<StateMachineSnapshot<MuSigV2Trade<S>, Flow>, Event>(
                snapshot,
                restored.pendingInputs(),
                restored.pendingEffects(),
                restored.scheduledInputs());
        savedRecord = record;
        var definition = ProcessingLaneDefinition
                .<StateMachineSnapshot<MuSigV2Trade<S>, Flow>, Event>builder(Event.class,
                        ignored -> processor,
                        inputValidator)
                .build();
        lane = new ProcessingLane<>(record.trade().getId(),
                Optional.of(checkpoint),
                Runnable::run,
                mock(ScheduledExecutorService.class, RETURNS_DEEP_STUBS),
                clock,
                definition,
                input -> {},
                saved -> savedRecord = MuSigV2TradeRecord.fromCheckpoint(saved),
                (current, effect) -> {
                    if (effect instanceof ProtocolLogMessage entry) {
                        logs.accept(entry);
                    } else {
                        effects.accept(effect);
                    }
                },
                (current, persistenceFailure, setupFailure) -> snapshot = current);
        lane.resume().join();
    }

    /** Callbacks enqueue here and must not wait for the returned future inside the current lane step. */
    public CompletableFuture<SubmissionResult<StateMachineSnapshot<MuSigV2Trade<S>, Flow>>> submit(Event event) {
        return lane.submit(event);
    }

    /** Synchronous test entry point; use submit from callbacks. Failures retain their original cause. */
    public ProcessingSummary<?> handle(Event event) {
        var result = submit(event).join();
        return switch (result) {
            case SubmissionResult.Completed<?> completed -> switch (completed.processingSummary()) {
                case ProcessingSummary.Rejected<?> rejected -> throw rejected.cause();
                case ProcessingSummary.Failed<?> failed -> throw failed.cause();
                default -> completed.processingSummary();
            };
            case SubmissionResult.Pending<?> pending -> throw pending.cause();
            case SubmissionResult.NotProcessed<?> pending -> throw pending.cause();
        };
    }

    public void resume() {
        lane.resume().join();
    }

    public MuSigV2Trade<S> getTrade() {
        return snapshot.document();
    }

    public S getExecutionState() {
        return getTrade().getExecution().state();
    }

    public Map<Flow, Set<Class<?>>> getProcessedEventTypes() {
        return snapshot.processedEventTypes();
    }

    public MuSigV2TradeRecord getSavedRecord() {
        return savedRecord;
    }

    @Override
    public void close() {
        lane.shutdown().join();
    }
}
