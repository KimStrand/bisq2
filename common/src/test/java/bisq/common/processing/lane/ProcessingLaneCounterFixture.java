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

package bisq.common.processing.lane;

import bisq.common.processing.CancelScheduledInput;
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.HandleResult;
import bisq.common.processing.Input;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.Processor;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.ScheduledInput;
import bisq.common.processing.SendMessage;
import bisq.common.proto.NetworkProto;
import bisq.common.timer.MockClock;
import com.google.protobuf.StringValue;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shared parts of the lane tests: a counter as the entity, its inputs and effects, and the nested Fixture
 * which builds a lane with recording collaborators.
 */
class ProcessingLaneCounterFixture {
    static final Instant NOW = Instant.parse("2026-10-20T12:00:00Z");

    static ProcessingOutput<CounterInput> emptyOutput() {
        return new ProcessingOutput<>(List.of(), List.of());
    }

    static HandleResult<Counter, CounterInput> applied(Counter snapshot, ProcessingOutput<CounterInput> output) {
        return new HandleResult.Applied<>(new Counter(snapshot.count() + 1, snapshot.failed()), output);
    }

    static <T> T await(CompletableFuture<T> future) throws Exception {
        return future.get(5, TimeUnit.SECONDS);
    }

    static ProcessingSummary<?> awaitProcessingSummary(CompletableFuture<?> future) throws Exception {
        return assertInstanceOf(SubmissionResult.Completed.class, await(future)).processingSummary();
    }

    static Counter awaitSnapshot(CompletableFuture<?> future) throws Exception {
        return switch (await(future)) {
            case Counter counter -> counter;
            case SubmissionResult.Completed<?> completed -> switch (completed.processingSummary()) {
                case ProcessingSummary.Applied<?> applied -> assertInstanceOf(Counter.class, applied.snapshot());
                case ProcessingSummary.Deferred<?> deferred -> assertInstanceOf(Counter.class, deferred.snapshot());
                case ProcessingSummary.Ignored<?> ignored -> assertInstanceOf(Counter.class, ignored.snapshot());
                default -> throw new AssertionError("Expected a successful snapshot");
            };
            default -> throw new AssertionError("Expected a successful snapshot");
        };
    }

    static Throwable failure(CompletableFuture<?> future) {
        try {
            return switch (await(future)) {
                case SubmissionResult.Completed<?> completed -> switch (completed.processingSummary()) {
                    case ProcessingSummary.Rejected<?> rejected -> rejected.cause();
                    case ProcessingSummary.Failed<?> failed -> failed.cause();
                    default -> throw new AssertionError("Expected a failure");
                };
                case SubmissionResult.Pending<?> pending -> pending.cause();
                case SubmissionResult.NotProcessed<?> notProcessed -> notProcessed.cause();
                default -> throw new AssertionError("Expected a failure");
            };
        } catch (ExecutionException exception) {
            return exception.getCause();
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
    }

    // These inputs deliberately have no Event or Proto dependency.
    interface CounterInput extends Input {}

    interface CounterMessage extends CounterInput {}

    record Initialize(CounterInput firstInput) implements Command {}

    record InitializationMessage() implements CounterMessage {}

    record Message(String name) implements CounterMessage {}

    record Notification() implements CounterInput {}

    record Action(String name) implements CounterInput, Command {}

    record Counter(int count, boolean failed) {}

    record PublishedSnapshot(Counter snapshot,
                                     Optional<RuntimeException> persistenceFailure,
                                     Optional<RuntimeException> setupFailure) {}

    record Work(String destination, CounterInput input) {}

    interface TestEffect extends PersistableEffect {
        @Override
        default StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(toString());
        }
    }

    static SendMessage<Payload, Payload> send(String id) {
        return new SendMessage<>(new Payload(id), new Payload("peer"));
    }

    record Payload(String value) implements NetworkProto {
        @Override
        public StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(value);
        }

        @Override
        public void verify() {}
    }

    record Observation(String id) implements TestEffect {
        @Override
        public boolean replayOnRestart() {
            return false;
        }
    }

    record Timer(String id, Instant deadline, CounterInput input) implements ScheduledInput<CounterInput> {}

    record CancelTimer(String id) implements CancelScheduledInput {}

    record TimerTask(Runnable task, long delayNanos, ScheduledFuture<?> future) {}

    static final class ManualExecutor implements Executor {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        @Override
        public void execute(Runnable command) {
            tasks.addLast(command);
        }

        void runAll() {
            Runnable task;
            while ((task = tasks.pollFirst()) != null) {
                task.run();
            }
        }
    }

    static final class Fixture {
        final ManualExecutor executor = new ManualExecutor();
        final ScheduledExecutorService timers = mock(ScheduledExecutorService.class, org.mockito.Mockito.RETURNS_MOCKS);
        final MockClock clock = new MockClock();
        final List<TimerTask> timerTasks = new ArrayList<>();
        final List<TimerTask> retryTasks = new ArrayList<>();
        final List<Checkpoint<Counter, CounterInput>> saved = new ArrayList<>();
        final List<Effect> executed = new ArrayList<>();
        final List<Counter> effectSnapshots = new ArrayList<>();
        final List<Work> attempted = new ArrayList<>();
        final List<PublishedSnapshot> published = new ArrayList<>();
        // Declared before the lane, whose construction uses it.
        final InputPolicy inputPolicy = new InputPolicy() {
            @Override
            public void check(Input input) {
            }

            @Override
            public boolean isPaused(Input input) {
                return input instanceof CounterInput counterInput && pauseRule.test(counterInput);
            }
        };
        final ProcessingLane<Counter, CounterInput> lane = createLane(executor);
        BiFunction<Counter, CounterInput, List<Work>> routes = (snapshot, input) -> List.of(new Work("counter", input));
        BiFunction<Counter, Work, HandleResult<Counter, CounterInput>> handler = (snapshot, work) -> applied(snapshot, emptyOutput());
        Function<Counter, List<Effect>> recoveryEffects = snapshot -> List.of();
        BiConsumer<Counter, CounterInput> onValidate = (snapshot, input) -> {};
        BiPredicate<Counter, CounterInput> stillHandleable = (snapshot, input) -> true;
        Predicate<CounterInput> pauseRule = input -> false;
        Consumer<Input> onPrepare = input -> {};
        Consumer<Counter> onCreateProcessor = snapshot -> {};
        boolean initializerReturnsNull;
        boolean processorFactoryReturnsNull;
        Consumer<Counter> onSetup = snapshot -> {};
        Consumer<Checkpoint<Counter, CounterInput>> onSave = checkpoint -> {};
        Consumer<Effect> onEffect = effect -> {};
        Consumer<ProtocolLogMessage> onLog = entry -> {};
        SnapshotListener<Counter> onSnapshot = (snapshot, persistenceFailure, setupFailure) -> {};
        Runnable onSchedule = () -> {};

        Fixture() {
            clock.setTime(NOW.toEpochMilli());
            when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.SECONDS)))
                    .thenAnswer(invocation -> {
                        var future = mock(ScheduledFuture.class);
                        retryTasks.add(new TimerTask(invocation.getArgument(0), invocation.getArgument(1), future));
                        return future;
                    });
            when(timers.schedule(any(Runnable.class), anyLong(), eq(TimeUnit.NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        onSchedule.run();
                        var future = mock(ScheduledFuture.class);
                        timerTasks.add(new TimerTask(invocation.getArgument(0), invocation.getArgument(1), future));
                        return future;
                    });
        }

        ProcessingLane<Counter, CounterInput> createLane(Executor worker) {
            return createLane(worker, false);
        }

        ProcessingLane<Counter, CounterInput> createLane(Executor worker, boolean withSetup) {
            return new ProcessingLane<>("counter",
                    Optional.empty(),
                    worker,
                    timers,
                    clock,
                    createDefinition(withSetup),
                    inputPolicy,
                    this::saveCheckpoint,
                    this::executeEffect,
                    this::publishSnapshot);
        }

        ProcessingLane<Counter, CounterInput> restoreLane(Checkpoint<Counter, CounterInput> checkpoint,
                                                                       Executor worker) {
            return new ProcessingLane<>("counter",
                    Optional.of(checkpoint),
                    worker,
                    timers,
                    clock,
                    createDefinition(true),
                    inputPolicy,
                    this::saveCheckpoint,
                    this::executeEffect,
                    this::publishSnapshot);
        }

        ProcessingLaneDefinition<Counter, CounterInput> createDefinition(boolean withSetup) {
            var builder = ProcessingLaneDefinition.<Counter, CounterInput>builder(
                            CounterInput.class,
                            this::createProcessor,
                            (snapshot, input) -> onValidate.accept(snapshot, input))
                    .initializeOn(Initialize.class, input -> prepare(input, input.firstInput()))
                    .initializeOn(InitializationMessage.class, input -> prepare(input, input))
                    .stashUntilInitialized(CounterMessage.class);
            if (withSetup) {
                builder.setupWith(snapshot -> onSetup.accept(snapshot));
            }
            return builder.build();
        }

        void saveCheckpoint(Checkpoint<Counter, CounterInput> checkpoint) {
            onSave.accept(checkpoint);
            saved.add(checkpoint);
        }

        void executeEffect(Counter snapshot, Effect effect) {
            if (effect instanceof ProtocolLogMessage entry) {
                onLog.accept(entry);
            } else {
                effectSnapshots.add(snapshot);
                onEffect.accept(effect);
                executed.add(effect);
            }
        }

        void publishSnapshot(Counter snapshot,
                                     Optional<RuntimeException> persistenceFailure,
                                     Optional<RuntimeException> setupFailure) {
            published.add(new PublishedSnapshot(snapshot, persistenceFailure, setupFailure));
            onSnapshot.onSnapshot(snapshot, persistenceFailure, setupFailure);
        }

        Initialization<Counter, CounterInput> prepare(Input input, CounterInput firstInput) {
            onPrepare.accept(input);
            if (initializerReturnsNull) {
                return null;
            }
            return new Initialization<>(new Counter(0, false), firstInput);
        }

        Processor<Counter, CounterInput> createProcessor(Counter initialSnapshot) {
            onCreateProcessor.accept(initialSnapshot);
            if (processorFactoryReturnsNull) {
                return null;
            }
            return new Processor<>() {
                @Override
                public List<Effect> createRecoveryEffects(Counter snapshot) {
                    return recoveryEffects.apply(snapshot);
                }

                @Override
                public boolean canStillBeHandled(Counter snapshot, CounterInput input) {
                    return stillHandleable.test(snapshot, input);
                }

                @Override
                public HandleResult<Counter, CounterInput> handle(Counter snapshot, CounterInput input) {
                    var work = routes.apply(snapshot, input);
                    if (work.isEmpty()) {
                        return input instanceof Command
                                ? new HandleResult.Rejected<>(new IllegalStateException("No destination for command " + input))
                                : new HandleResult.Ignored<>();
                    }
                    if (work.size() != 1) {
                        throw new AssertionError("Multiple-flow behavior belongs in StateMachineProcessorTest");
                    }
                    attempted.add(work.getFirst());
                    return handler.apply(snapshot, work.getFirst());
                }
            };
        }

        void initialize() {
            initialize(lane);
        }

        void initialize(ProcessingLane<Counter, CounterInput> lane) {
            // Keep initialization separate from the counter operations measured by each test.
            var first = new Action("initialize");
            var previousHandler = handler;
            handler = (snapshot, work) -> new HandleResult.Applied<>(snapshot, emptyOutput());
            try {
                var initialized = lane.submit(new Initialize(first));
                executor.runAll();
                assertDoesNotThrow(() -> assertEquals(new Counter(0, false), awaitSnapshot(initialized)));
                assertEquals(List.of(first), attemptedInputs());
                attempted.clear();
            } finally {
                handler = previousHandler;
            }
        }

        void startTimer(Timer timer) throws Exception {
            initialize();
            handler = (snapshot, work) -> applied(snapshot,
                    new ProcessingOutput<>(List.of(), List.of(), List.of(timer)));
            var started = lane.submit(new Action("start-timer"));
            executor.runAll();
            assertEquals(new Counter(1, false), awaitSnapshot(started));
            handler = (snapshot, work) -> applied(snapshot, emptyOutput());
        }

        Checkpoint<Counter, CounterInput> lastSaved() {
            return saved.getLast();
        }

        List<CounterInput> attemptedInputs() {
            return attempted.stream().map(Work::input).toList();
        }
    }
}
