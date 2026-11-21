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

package bisq.common.statemachine;

import bisq.common.processing.Command;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.ProcessingLane;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.timer.MockClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/** Two document flows exercise checkpointed input processing without trade types or services. */
class StateMachineLaneTest {
    @Test
    void ignoredInputPreservesStateAndHistoryUntilALaterValidInput() {
        var fixture = new Fixture();
        fixture.submit(new Start());
        var before = fixture.checkpoint().snapshot();
        fixture.effects.clear();

        assertInstanceOf(ProcessingSummary.Ignored.class, fixture.submit(new Increment(true)));

        assertSame(before, fixture.checkpoint().snapshot());
        assertTrue(fixture.effects.isEmpty());
        assertTrue(fixture.pending().isEmpty());
        assertEquals(Set.of(Start.class), fixture.history().get(Flow.MAIN));

        assertInstanceOf(ProcessingSummary.Applied.class, fixture.submit(new Increment()));
        assertEquals(2, fixture.document().main().data());
        assertEquals(Set.of(Start.class, Increment.class), fixture.history().get(Flow.MAIN));
        assertEquals(List.of("increment"), fixture.effects);
    }

    @Test
    void anIgnoredPendingInputDoesNotBlockTheNextInputOfTheSameType() {
        var fixture = new Fixture();
        assertInstanceOf(ProcessingSummary.Deferred.class, fixture.submit(new Increment(true)));
        assertInstanceOf(ProcessingSummary.Deferred.class, fixture.submit(new Increment()));
        assertEquals(2, fixture.pending().size());
        assertTrue(fixture.history().isEmpty());
        assertEquals(0, fixture.context.preparedHandlers);

        fixture.submit(new Start());

        assertEquals(2, fixture.document().main().data());
        assertEquals(Set.of(Start.class, Increment.class), fixture.history().get(Flow.MAIN));
        assertEquals(List.of("start", "audit", "increment"), fixture.effects);
        assertTrue(fixture.pending().isEmpty());
    }

    @Test
    void ignoringARaisedInputPreservesTheProducerAndAllowsALaterValidInput() {
        var fixture = new Fixture();
        fixture.context.ignoreAudit = true;
        fixture.context.duringAudit = () -> fail("An ignored input must not be processed");

        fixture.submit(new Start());

        assertInstanceOf(Ready.class, fixture.document().main().state());
        assertInstanceOf(Idle.class, fixture.document().audit().state());
        assertTrue(fixture.history().getOrDefault(Flow.AUDIT, Set.of()).isEmpty());
        assertEquals(List.of("start"), fixture.effects);
        assertTrue(fixture.pending().isEmpty());

        fixture.context.ignoreAudit = false;
        fixture.context.duringAudit = () -> {};
        fixture.submit(new Audit());
        assertInstanceOf(Ready.class, fixture.document().audit().state());
        assertEquals(List.of("start", "audit"), fixture.effects);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aRaisedFailureDoesNotLoseUnrelatedWaitingOrRaisedWork(boolean raised) {
        var fixture = new Fixture();
        if (raised) {
            fixture.context.startEvents = List.of(new Audit(), new FinishedMessage());
        } else {
            fixture.submit(new FinishedMessage());
            assertEquals(1, fixture.pending().size());
        }
        fixture.context.failAudit = true;

        assertInstanceOf(ProcessingSummary.Failed.class, fixture.submit(new Start()));

        assertInstanceOf(Finished.class, fixture.document().main().state());
        assertInstanceOf(Failed.class, fixture.document().audit().state());
        assertEquals(List.of("start", "auditFailed", "finish"), fixture.effects);
        assertEquals(Set.of(AuditFailed.class), fixture.history().get(Flow.AUDIT));
        assertEquals(1, fixture.context.auditAttempts);
        assertTrue(fixture.pending().isEmpty());
        fixture.lane.resume().join();
        assertInstanceOf(ProcessingSummary.Ignored.class, fixture.submit(new Audit()));
        assertEquals(1, fixture.context.auditAttempts);
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void anUnrecordedErrorStopsTheLaneAndRetainsTheLastCheckpoint() {
        var fixture = new Fixture();
        var failure = new AssertionError("Processing failed");
        fixture.context.duringAudit = () -> { throw failure; };

        assertSame(failure, assertThrows(CompletionException.class, () -> fixture.submit(new Start())).getCause());

        var saved = fixture.checkpoint();
        assertInstanceOf(Ready.class, saved.snapshot().document().main().state());
        assertInstanceOf(Idle.class, saved.snapshot().document().audit().state());
        assertEquals(List.of(new Audit()), fixture.pending());
        assertEquals(List.of("start"), fixture.effects);
        assertTrue(fixture.lane.isClosed());
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, () -> fixture.lane.resume().join()).getCause());
        assertInstanceOf(RejectedExecutionException.class,
                assertThrows(CompletionException.class, () -> fixture.submit(new Increment())).getCause());
        assertSame(saved, fixture.checkpoint());
        assertEquals(1, fixture.context.auditAttempts);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void processedTypesAreIgnoredWithoutPreparingHandlersIncludingAfterRestoration(boolean restore) {
        var original = new Fixture();
        original.submit(new Start());
        original.submit(new Increment());
        var fixture = restore ? new Fixture(original.checkpoint()) : original;
        var before = fixture.checkpoint().snapshot();
        int prepared = fixture.context.preparedHandlers;
        int effects = fixture.effects.size();

        assertInstanceOf(ProcessingSummary.Ignored.class, fixture.submit(new Increment()));
        assertInstanceOf(ProcessingSummary.Ignored.class, fixture.submit(new Start()));

        assertSame(before, fixture.checkpoint().snapshot());
        assertEquals(prepared, fixture.context.preparedHandlers);
        assertEquals(effects, fixture.effects.size());
        assertTrue(fixture.pending().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> fixture.history().clear());
        assertThrows(UnsupportedOperationException.class, () -> fixture.history().get(Flow.MAIN).clear());
    }

    @Test
    void definitionsEvaluateRulesWithoutOwningProcessingHistory() {
        var definition = new MainProtocol();
        var context = new Context();
        var before = FlowSnapshot.of(new Idle(), 0);
        var started = definition.rules().apply(context, before, new Start()).orElseThrow().next();
        var incremented = definition.rules().apply(context, started, new Increment()).orElseThrow().next();
        var repeated = definition.rules().apply(context, incremented, new Increment()).orElseThrow().next();
        assertEquals(3, repeated.data());
    }

    @Test
    void deferredInputUsesFreshSiblingReadinessBeforePreparingItsHandler() {
        var fixture = new Fixture();
        fixture.context.startEvents = List.of();
        var before = fixture.document();

        assertInstanceOf(ProcessingSummary.Deferred.class, fixture.submit(new Audit()));

        assertSame(before, fixture.document());
        assertEquals(0, fixture.context.preparedHandlers);
        assertEquals(List.of(new Audit()), fixture.pending());

        fixture.submit(new Start());

        assertInstanceOf(Ready.class, fixture.document().audit().state());
        assertEquals(2, fixture.context.preparedHandlers);
        assertEquals(List.of("start", "audit"), fixture.effects);
        assertTrue(fixture.pending().isEmpty());
    }

    private interface Stage {}

    private record Idle() implements Stage {}

    private record Ready() implements Stage {}

    private record Finished() implements Stage {}

    private record Failed() implements Stage {}

    private interface Event extends bisq.common.statemachine.Event, TestProto {}

    private record Start() implements Event, Command {}

    private record Audit() implements Event {}

    private record AuditFailed() implements Event {}

    private record Increment(boolean ignore) implements Event {
        private Increment() {
            this(false);
        }
    }

    private record FinishedMessage() implements Event {}

    private enum Flow { MAIN, AUDIT }

    private record Document(FlowSnapshot<? extends Stage, Integer> main,
                            FlowSnapshot<? extends Stage, Integer> audit) {}

    private record TestEffect(String name) implements PersistableEffect, TestProto {}

    private static final class Context {
        private int preparedHandlers;
        private Document document;
        private List<Event> startEvents = List.of(new Audit());
        private boolean failAudit;
        private boolean ignoreAudit;
        private int auditAttempts;
        private Runnable duringAudit = () -> {};

        private Context prepare(Document document) {
            preparedHandlers++;
            this.document = document;
            return this;
        }
    }

    private static final class Fixture {
        private final Context context = new Context();
        private final List<String> effects = new ArrayList<>();
        private final List<Checkpoint<StateMachineSnapshot<Document, Flow>, Event>> saved =
                new ArrayList<>();
        private final ProcessingLane<StateMachineSnapshot<Document, Flow>, Event> lane;

        private Fixture() {
            this(new Checkpoint<>(
                    new StateMachineSnapshot<>(new Document(FlowSnapshot.of(new Idle(), 0), FlowSnapshot.of(new Idle(), 0)),
                            Map.of()),
                    List.of(),
                    List.of(),
                    List.of()));
        }

        private Fixture(Checkpoint<StateMachineSnapshot<Document, Flow>, Event> checkpoint) {
            var flows = FlowRegistration.<Document, Event, Flow, Document, Context>builder(Function.identity(),
                            context::prepare)
                    .add(new MainProtocol(),
                            Flow.MAIN,
                            Optional.empty(),
                            Document::main,
                            (document, next) -> new Document(next, document.audit()))
                    .add(new AuditProtocol(),
                            Flow.AUDIT,
                            Optional.empty(),
                            Document::audit,
                            (document, next) -> new Document(document.main(), next))
                    .build();
            var definition = ProcessingLaneDefinition.<StateMachineSnapshot<Document, Flow>, Event>builder(
                    Event.class,
                    snapshot -> new StateMachineProcessor<>(flows) {
                        @Override
                        protected Optional<Event> onProcessingFailure(Document document,
                                                                      Flow flowId,
                                                                      Event input,
                                                                      RuntimeException failure) {
                            return input instanceof Audit ? Optional.of(new AuditFailed()) : Optional.empty();
                        }
                    },
                    (ignoredSnapshot, ignoredInput) -> {}).build();
            saved.add(checkpoint);
            lane = new ProcessingLane<>("document",
                    Optional.of(checkpoint),
                    Runnable::run,
                    mock(ScheduledExecutorService.class, org.mockito.Mockito.RETURNS_MOCKS),
                    new MockClock(),
                    definition,
                    input -> {},
                    saved::add,
                    (snapshot, effect) -> effects.add(((TestEffect) effect).name()),
                    (snapshot, persistenceFailure, setupFailure) -> {});
            lane.resume().join();
        }

        private ProcessingSummary<StateMachineSnapshot<Document, Flow>> submit(Event event) {
            return switch (lane.submit(event).join()) {
                case SubmissionResult.Completed<StateMachineSnapshot<Document, Flow>> completed -> completed.processingSummary();
                default -> throw new AssertionError("Expected completed coordination");
            };
        }

        private Checkpoint<StateMachineSnapshot<Document, Flow>, Event> checkpoint() {
            return saved.getLast();
        }

        private Document document() {
            return checkpoint().snapshot().document();
        }

        private Map<Flow, Set<Class<?>>> history() {
            return checkpoint().snapshot().processedEventTypes();
        }

        private List<Event> pending() {
            return checkpoint().pendingInputs();
        }
    }

    private static final class MainProtocol extends StateMachineDefinition<Stage, Integer, Event, Document, Context> {
        private static final RuleTable<Stage, Integer, Event, Context> RULES =
                RuleTable.<Stage, Integer, Event, Context>builder()
                        .rule(Idle.class, Start.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, Increment.class, IncrementHandler::new, Ready.class)
                        .rule(Ready.class, FinishedMessage.class, FinishHandler::new, Finished.class)
                        .build();

        @Override
        public boolean isFailed(Stage state) {
            return false;
        }

        @Override
        public RuleTable<Stage, Integer, Event, Context> rules() {
            return RULES;
        }
    }

    private static final class AuditProtocol extends StateMachineDefinition<Stage, Integer, Event, Document, Context> {
        private static final RuleTable<Stage, Integer, Event, Context> RULES =
                RuleTable.<Stage, Integer, Event, Context>builder()
                        .rule(Idle.class, Audit.class, AuditHandler::new, Ready.class)
                        .rule(Idle.class, AuditFailed.class, AuditFailedHandler::new, Failed.class)
                        .build();

        @Override
        public boolean isFailed(Stage state) {
            return state instanceof Failed;
        }

        @Override
        protected boolean isReady(Document context, FlowSnapshot<? extends Stage, Integer> flow, Event event) {
            return event instanceof AuditFailed || context.main().state() instanceof Ready;
        }

        @Override
        public RuleTable<Stage, Integer, Event, Context> rules() {
            return RULES;
        }
    }

    private static final class StartHandler extends TransitionHandler<Idle, Start, Ready, Integer, Context, Event> {
        private StartHandler(Context context, FlowSnapshot<Idle, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected Transition<Ready, Integer, Event> process(Start event) {
            return new Transition<>(flow.to(new Ready(), data + 1),
                    List.of(new TestEffect("start")),
                    context.startEvents);
        }
    }

    private static final class AuditHandler extends TransitionHandler<Idle, Audit, Ready, Integer, Context, Event> {
        private AuditHandler(Context context, FlowSnapshot<Idle, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected void verify(Audit event) {
            if (context.ignoreAudit) {
                ignoreInput();
            }
        }

        @Override
        protected Transition<Ready, Integer, Event> process(Audit event) {
            // The handler context is prepared from the current document, after the main flow became ready.
            assertInstanceOf(Ready.class, context.document.main().state());
            context.auditAttempts++;
            context.duringAudit.run();
            if (context.failAudit) {
                throw new IllegalStateException("Audit failed");
            }
            return new Transition<>(flow.to(new Ready(), data + 1), List.of(new TestEffect("audit")));
        }
    }

    private static final class AuditFailedHandler extends TransitionHandler<Idle, AuditFailed, Failed, Integer, Context, Event> {
        private AuditFailedHandler(Context context, FlowSnapshot<Idle, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected Transition<Failed, Integer, Event> process(AuditFailed event) {
            return new Transition<>(flow.to(new Failed(), data), List.of(new TestEffect("auditFailed")));
        }
    }

    private static final class IncrementHandler extends TransitionHandler<Ready, Increment, Ready, Integer, Context, Event> {
        private IncrementHandler(Context context, FlowSnapshot<Ready, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected void verify(Increment event) {
            if (event.ignore()) {
                ignoreInput();
            }
        }

        @Override
        protected Transition<Ready, Integer, Event> process(Increment event) {
            if (event.ignore()) {
                fail("An ignored input must not be processed");
            }
            return new Transition<>(flow.to(new Ready(), data + 1), List.of(new TestEffect("increment")));
        }
    }

    private static final class FinishHandler<E extends Event> extends TransitionHandler<Ready, E, Finished, Integer, Context, Event> {
        private FinishHandler(Context context, FlowSnapshot<Ready, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected Transition<Finished, Integer, Event> process(E event) {
            return new Transition<>(flow.to(new Finished(), data), List.of(new TestEffect("finish")));
        }
    }
}
