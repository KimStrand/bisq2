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

import bisq.common.processing.Checkpoint;
import bisq.common.processing.Effect;
import bisq.common.processing.PendingWork;
import bisq.common.processing.ProcessingLane;
import bisq.common.processing.ProcessingLaneDefinition;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.StepResult.Applied;
import bisq.common.processing.SubmissionResult;
import bisq.common.timer.MockClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class StateMachineHierarchyTest {
    @Test
    void cyclesAreRejectedWhenTheHierarchyIsDeclared() {
        assertThrows(IllegalArgumentException.class, () -> new FlowHierarchy<>(Map.of(Flow.PARENT, Flow.PARENT)));
        assertThrows(IllegalArgumentException.class, () -> new FlowHierarchy<>(Map.of(
                Flow.CHILD,
                Flow.PARENT,
                Flow.PARENT,
                Flow.GRANDCHILD,
                Flow.GRANDCHILD,
                Flow.CHILD)));
    }

    @Test
    void changingTheSourceMapCannotChangeTheHierarchy() {
        var parents = new EnumMap<Flow, Flow>(Flow.class);
        parents.put(Flow.CHILD, Flow.PARENT);
        var hierarchy = new FlowHierarchy<>(parents);
        parents.put(Flow.CHILD, Flow.CHILD);

        assertEquals(Optional.of(Flow.PARENT), hierarchy.parentOf(Flow.CHILD));
        assertTrue(hierarchy.parentOf(Flow.PARENT).isEmpty());
        assertTrue(hierarchy.parentOf(Flow.OTHER_ROOT).isEmpty());
    }

    @Test
    void aFailedParentStopsEveryDescendantAndPreservesTheirSnapshots() {
        var fixture = new Fixture();
        var before = fixture.getSnapshot();
        fixture.submit(new Fail(Flow.PARENT));
        for (Flow flow : List.of(Flow.PARENT, Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            fixture.submit(new Enable(flow));
            fixture.submit(new Message(flow));
        }
        fixture.submit(new Message(Flow.OTHER_ROOT));

        assertEquals(State.FAILED, fixture.getSnapshot().flow(Flow.PARENT).state());
        for (Flow child : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            assertSame(before.flow(child), fixture.getSnapshot().flow(child));
        }
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertEquals(List.of(Flow.PARENT, Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void aFailedChildLeavesItsParentSiblingAndOtherRootRunning() {
        var fixture = new Fixture();
        var grandchild = fixture.getSnapshot().flow(Flow.GRANDCHILD);
        fixture.submit(new Fail(Flow.CHILD));
        fixture.submit(new Message(Flow.CHILD));
        fixture.submit(new Message(Flow.GRANDCHILD));
        for (Flow flow : List.of(Flow.PARENT, Flow.SIBLING, Flow.OTHER_ROOT)) {
            fixture.submit(new Message(flow));
            assertEquals(State.READY, fixture.getSnapshot().flow(flow).state());
            assertEquals(1, fixture.getSnapshot().flow(flow).data());
        }

        assertEquals(State.FAILED, fixture.getSnapshot().flow(Flow.CHILD).state());
        assertSame(grandchild, fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(List.of(Flow.CHILD, Flow.PARENT, Flow.SIBLING, Flow.OTHER_ROOT), fixture.processed);
    }

    @Test
    void stoppedCommandsAreRejectedBeforeDomainAdmissionEvenIfTheDomainWouldDiscardThem() {
        var fixture = new Fixture();
        fixture.submit(new Fail(Flow.PARENT));
        fixture.context.discardCommands = true;
        var failed = fixture.getSnapshot();
        int admissions = fixture.context.admissions;
        for (Flow flow : List.of(Flow.PARENT, Flow.CHILD, Flow.GRANDCHILD)) {
            assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Command(flow)));
        }

        assertEquals(3, fixture.rejections);
        assertEquals(admissions, fixture.context.admissions);
        assertSame(failed, fixture.getSnapshot());
        assertEquals(List.of(Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void parentFailurePrunesQueuedDescendantMessagesButPreservesOtherWaitingWork() {
        var document = Document.initial();
        for (Flow flow : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD, Flow.OTHER_ROOT)) {
            document = document.withState(flow, State.WAITING);
        }
        var fixture = new Fixture(document, List.of());
        for (Flow flow : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD, Flow.OTHER_ROOT)) {
            fixture.submit(new Message(flow));
        }
        assertEquals(4, fixture.getPendingEvents().size());

        fixture.submit(new Fail(Flow.PARENT));

        assertEquals(List.of(new Delivery<>(Flow.OTHER_ROOT, new Message(Flow.OTHER_ROOT))),
                fixture.getPendingEvents());
        fixture.submit(new Enable(Flow.OTHER_ROOT));
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertSame(document.flow(Flow.CHILD), fixture.getSnapshot().flow(Flow.CHILD));
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void raisedEventsObserveTheParentsCommittedFailure() {
        var fixture = new Fixture();
        var child = fixture.getSnapshot().flow(Flow.CHILD);

        fixture.submit(new Raise(Flow.OTHER_ROOT, List.of(new Fail(Flow.PARENT),
                new Message(Flow.CHILD),
                new Message(Flow.OTHER_ROOT))));

        assertSame(child, fixture.getSnapshot().flow(Flow.CHILD));
        assertEquals(State.FAILED, fixture.getSnapshot().flow(Flow.PARENT).state());
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertEquals(List.of(Flow.OTHER_ROOT, Flow.PARENT, Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void aLaterParentFailurePreservesAnEarlierRaisedChildTransition() {
        var fixture = new Fixture();
        var child = fixture.getSnapshot().flow(Flow.CHILD);

        fixture.submit(new Raise(Flow.OTHER_ROOT, List.of(new Message(Flow.CHILD), new Fail(Flow.PARENT))));

        assertEquals(child.data() + 1, fixture.getSnapshot().flow(Flow.CHILD).data());
        assertEquals(List.of(Flow.OTHER_ROOT, Flow.CHILD, Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void restoredParentFailureStopsQueuedChildrenWithoutPersistingASecondStoppedFlag() {
        var document = Document.initial().withState(Flow.PARENT, State.FAILED);
        var fixture = new Fixture(document, List.of(
                new Delivery<>(Flow.GRANDCHILD, new Message(Flow.GRANDCHILD)),
                new Delivery<>(Flow.OTHER_ROOT, new Message(Flow.OTHER_ROOT))));

        fixture.resume();

        assertSame(document.flow(Flow.GRANDCHILD), fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(List.of(Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void anInputThatFailsItsFlowIsNotRequeued() {
        var fixture = new Fixture(Document.initial().withState(Flow.CHILD, State.WAITING), List.of());
        fixture.submit(new Crash(Flow.CHILD));
        assertEquals(1, fixture.getPendingEvents().size());

        assertInstanceOf(ProcessingResult.Applied.class, fixture.submit(new Enable(Flow.CHILD)));

        assertEquals(State.FAILED, fixture.getSnapshot().flow(Flow.CHILD).state());
        assertEquals(State.READY, fixture.getSnapshot().flow(Flow.PARENT).state());
        assertTrue(fixture.getPendingEvents().isEmpty());
        fixture.submit(new Message(Flow.SIBLING));
        assertEquals(1, fixture.getSnapshot().flow(Flow.SIBLING).data());
    }

    @Test
    void aSavedFlowFailureDuringRecoveryAllowsUnrelatedSavedWorkToContinue() {
        var document = Document.initial();
        var fixture = new Fixture(document, List.of(
                new Delivery<>(Flow.CHILD, new Crash(Flow.CHILD)),
                new Delivery<>(Flow.GRANDCHILD, new Message(Flow.GRANDCHILD)),
                new Delivery<>(Flow.OTHER_ROOT, new Message(Flow.OTHER_ROOT))));

        fixture.resume();

        assertEquals(State.FAILED, fixture.getSnapshot().flow(Flow.CHILD).state());
        assertSame(document.flow(Flow.PARENT), fixture.getSnapshot().flow(Flow.PARENT));
        assertSame(document.flow(Flow.GRANDCHILD), fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertEquals(List.of(Flow.CHILD, Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());

        fixture.submit(new Message(Flow.SIBLING));
        assertEquals(1, fixture.getSnapshot().flow(Flow.SIBLING).data());
    }

    @Test
    void aClosedParentDoesNotStopItsChildren() {
        var fixture = new Fixture(Document.initial().withState(Flow.PARENT, State.CLOSED), List.of());
        fixture.submit(new Command(Flow.CHILD));
        assertEquals(1, fixture.getSnapshot().flow(Flow.CHILD).data());
        assertEquals(State.CLOSED, fixture.getSnapshot().flow(Flow.PARENT).state());
        assertEquals(1, fixture.context.admissions);
    }

    @Test
    void aFailedParentCanArchiveWhileItsDescendantsRemainStopped() {
        var fixture = Fixture.withLifecycleParent(Document.initial(), List.of());
        fixture.submit(new Command(Flow.CHILD));
        fixture.submit(new Fail(Flow.PARENT));
        var failed = fixture.getSnapshot();
        assertEquals(State.FAILED, failed.flow(Flow.PARENT).state());

        for (Flow flow : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            fixture.submit(new Message(flow));
            assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Command(flow)));
        }
        assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Command(Flow.PARENT)));
        assertSame(failed, fixture.getSnapshot());

        fixture.submit(new Archive(Flow.PARENT));

        assertEquals(State.HISTORICAL, fixture.getSnapshot().flow(Flow.PARENT).state());
        for (Flow child : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            assertSame(failed.flow(child), fixture.getSnapshot().flow(child));
        }
        assertEquals(List.of(Flow.CHILD, Flow.PARENT, Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void enablingParentLifecycleTransitionsDoesNotReenableItsChildrenByDefault() {
        var context = new Context();
        var parent = new TestFlow(Flow.PARENT, Optional.empty(), context) {
            @Override
            public boolean isProcessingEnabled(Document snapshot) {
                return snapshot.flow(getId()).state() != State.HISTORICAL;
            }

            @Override
            public boolean canHandle(Document snapshot, Event event) {
                return event instanceof Archive || super.canHandle(snapshot, event);
            }
        };
        var fixture = new Fixture(Document.initial(), List.of(), context, parent);
        fixture.submit(new Fail(Flow.PARENT));
        var failed = fixture.getSnapshot();

        fixture.submit(new Message(Flow.CHILD));
        fixture.submit(new Message(Flow.GRANDCHILD));
        assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Command(Flow.CHILD)));
        fixture.submit(new Archive(Flow.PARENT));

        assertSame(failed.flow(Flow.CHILD), fixture.getSnapshot().flow(Flow.CHILD));
        assertSame(failed.flow(Flow.GRANDCHILD), fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(State.HISTORICAL, fixture.getSnapshot().flow(Flow.PARENT).state());
        assertEquals(List.of(Flow.PARENT, Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"READY", "FAILED"})
    void archivingStopsTheParentAndAllDescendantsWithoutChangingTheirSnapshots(State initialState) {
        var fixture = Fixture.withLifecycleParent(Document.initial().withState(Flow.PARENT, initialState), List.of());
        fixture.submit(new Archive(Flow.PARENT));
        var historical = fixture.getSnapshot();

        for (Flow flow : List.of(Flow.PARENT, Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            fixture.submit(new Message(flow));
            assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Command(flow)));
        }
        assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Archive(Flow.PARENT)));

        assertSame(historical, fixture.getSnapshot());
        assertEquals(List.of(Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
        fixture.submit(new Message(Flow.OTHER_ROOT));
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
    }

    @Test
    void disablingChildrenDiscardsTheirQueuedMessagesAndPreservesOtherWaitingWork() {
        var document = Document.initial();
        for (Flow flow : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD, Flow.OTHER_ROOT)) {
            document = document.withState(flow, State.WAITING);
        }
        var fixture = Fixture.withLifecycleParent(document, List.of());
        for (Flow flow : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD, Flow.OTHER_ROOT)) {
            fixture.submit(new Message(flow));
        }
        assertEquals(4, fixture.getPendingEvents().size());

        fixture.submit(new Archive(Flow.PARENT));

        assertEquals(List.of(new Delivery<>(Flow.OTHER_ROOT, new Message(Flow.OTHER_ROOT))),
                fixture.getPendingEvents());
        for (Flow child : List.of(Flow.CHILD, Flow.SIBLING, Flow.GRANDCHILD)) {
            assertSame(document.flow(child), fixture.getSnapshot().flow(child));
        }
        fixture.submit(new Enable(Flow.OTHER_ROOT));
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void raisedEventsUseTheParentsCommittedChildPolicy() {
        var fixture = Fixture.withLifecycleParent(Document.initial(), List.of());
        var grandchild = fixture.getSnapshot().flow(Flow.GRANDCHILD);

        fixture.submit(new Raise(Flow.OTHER_ROOT, List.of(new Archive(Flow.PARENT),
                new Message(Flow.GRANDCHILD),
                new Message(Flow.OTHER_ROOT))));

        assertSame(grandchild, fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(State.HISTORICAL, fixture.getSnapshot().flow(Flow.PARENT).state());
        assertEquals(1, fixture.getSnapshot().flow(Flow.OTHER_ROOT).data());
        assertEquals(List.of(Flow.OTHER_ROOT, Flow.PARENT, Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @Test
    void disablingChildrenPreservesAnEarlierRaisedChildTransition() {
        var fixture = Fixture.withLifecycleParent(Document.initial(), List.of());
        var grandchild = fixture.getSnapshot().flow(Flow.GRANDCHILD);

        fixture.submit(new Raise(Flow.OTHER_ROOT, List.of(new Message(Flow.GRANDCHILD), new Archive(Flow.PARENT))));

        assertEquals(grandchild.data() + 1, fixture.getSnapshot().flow(Flow.GRANDCHILD).data());
        assertEquals(List.of(Flow.OTHER_ROOT, Flow.GRANDCHILD, Flow.PARENT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = State.class, names = {"FAILED", "HISTORICAL"})
    void restoredParentPolicyControlsRecoveryAndQueuedChildren(State state) {
        var document = Document.initial().withState(Flow.PARENT, state);
        var fixture = Fixture.withLifecycleParent(document, List.of(
                new Delivery<>(Flow.CHILD, new Message(Flow.CHILD)),
                new Delivery<>(Flow.GRANDCHILD, new Message(Flow.GRANDCHILD)),
                new Delivery<>(Flow.OTHER_ROOT, new Message(Flow.OTHER_ROOT))));

        fixture.resume();

        assertEquals(state == State.FAILED
                        ? List.of(new Recover(Flow.PARENT), new Recover(Flow.OTHER_ROOT))
                        : List.of(new Recover(Flow.OTHER_ROOT)),
                fixture.context.recovered);
        assertSame(document.flow(Flow.CHILD), fixture.getSnapshot().flow(Flow.CHILD));
        assertSame(document.flow(Flow.GRANDCHILD), fixture.getSnapshot().flow(Flow.GRANDCHILD));
        assertEquals(List.of(Flow.OTHER_ROOT), fixture.processed);
        assertTrue(fixture.getPendingEvents().isEmpty());
        if (state == State.FAILED) {
            fixture.submit(new Archive(Flow.PARENT));
        } else {
            assertInstanceOf(ProcessingResult.Rejected.class, fixture.submit(new Archive(Flow.PARENT)));
        }
        assertEquals(State.HISTORICAL, fixture.getSnapshot().flow(Flow.PARENT).state());
    }

    private enum Flow { PARENT, CHILD, SIBLING, GRANDCHILD, OTHER_ROOT }

    private enum State { WAITING, READY, CLOSED, FAILED, HISTORICAL }

    private interface Event extends bisq.common.statemachine.Event, TestProto {
        Flow flow();
    }

    private record Message(Flow flow) implements Event {}

    private record Command(Flow flow) implements Event, bisq.common.processing.Command {}

    private record Archive(Flow flow) implements Event, bisq.common.processing.Command {}

    private record Fail(Flow flow) implements Event {}

    private record Enable(Flow flow) implements Event {}

    private record Crash(Flow flow) implements Event {}

    private record Raise(Flow flow, List<Event> events) implements Event {}

    private record Recover(Flow flow) implements Effect, TestProto {}

    private record Document(Map<Flow, FlowSnapshot<State, Integer>> flows) {
        private Document {
            flows = Map.copyOf(flows);
        }

        private static Document initial() {
            var flows = new EnumMap<Flow, FlowSnapshot<State, Integer>>(Flow.class);
            for (Flow flow : Flow.values()) {
                flows.put(flow, FlowSnapshot.of(State.READY, 0));
            }
            return new Document(flows);
        }

        private FlowSnapshot<State, Integer> flow(Flow flow) {
            return flows.get(flow);
        }

        private Document withState(Flow flow, State state) {
            return withFlow(flow, flow(flow).to(state, flow(flow).data()));
        }

        private Document withFlow(Flow flow, FlowSnapshot<State, Integer> next) {
            var updated = new EnumMap<>(flows);
            updated.put(flow, next);
            return new Document(updated);
        }
    }

    private record Processed(Flow flow) implements Effect, TestProto {}

    private static final class Context {
        private final List<Effect> recovered = new ArrayList<>();
        private boolean discardCommands;
        private int admissions;
    }

    private static final class Fixture {
        private final Context context;
        private final List<Flow> processed = new ArrayList<>();
        private final List<Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>> saved =
                new ArrayList<>();
        private final ProcessingLane<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>> lane;
        private int rejections;

        private Fixture() {
            this(Document.initial(), List.of());
        }

        private Fixture(Document document, List<Delivery<Flow, Event>> pending) {
            this(document, pending, new Context());
        }

        private Fixture(Document document, List<Delivery<Flow, Event>> pending, Context context) {
            this(document, pending, context, new TestFlow(Flow.PARENT, Optional.empty(), context));
        }

        private Fixture(Document document, List<Delivery<Flow, Event>> pending, Context context, TestFlow parent) {
            this.context = context;
            var flows = List.of(parent,
                    new TestFlow(Flow.CHILD, Optional.of(Flow.PARENT), context),
                    new TestFlow(Flow.SIBLING, Optional.of(Flow.PARENT), context),
                    new TestFlow(Flow.GRANDCHILD, Optional.of(Flow.CHILD), context),
                    new TestFlow(Flow.OTHER_ROOT, Optional.empty(), context));
            var definition = ProcessingLaneDefinition.<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>builder(
                    Event.class,
                    snapshot -> new StateMachineProcessor<>(flows) {
                        @Override
                        protected RuntimeException onInputRejected(RuntimeException failure) {
                            rejections++;
                            return failure;
                        }

                        @Override
                        protected Optional<Event> onProcessingFailure(Document document,
                                                                      Delivery<Flow, Event> delivery,
                                                                      RuntimeException failure) {
                            return Optional.of(new Fail(delivery.flow()));
                        }
                    }).build();
            var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>(
                    new StateMachineSnapshot<>(document, Map.of()),
                    pending.stream().map(delivery -> new PendingWork<>(delivery, false)).toList(),
                    List.of(),
                    List.of());
            saved.add(checkpoint);
            lane = new ProcessingLane<>("hierarchy",
                    Optional.of(checkpoint),
                    Runnable::run,
                    mock(ScheduledExecutorService.class),
                    new MockClock(),
                    definition,
                    saved::add,
                    (snapshot, effect) -> {
                        if (effect instanceof Processed processedFlow) {
                            processed.add(processedFlow.flow());
                        } else {
                            context.recovered.add(effect);
                        }
                    },
                    entry -> {},
                    (snapshot, persistenceFailure, setupFailure) -> {});
        }

        private static Fixture withLifecycleParent(Document document, List<Delivery<Flow, Event>> pending) {
            var context = new Context();
            return new Fixture(document, pending, context, new LifecycleParent(context));
        }

        private ProcessingResult<StateMachineSnapshot<Document, Flow>> submit(Event event) {
            return switch (lane.submit(event).join()) {
                case SubmissionResult.Completed<StateMachineSnapshot<Document, Flow>> completed -> completed.processingResult();
                default -> throw new AssertionError("Expected completed coordination");
            };
        }

        private void resume() {
            lane.resume().join();
        }

        private Document getSnapshot() {
            return saved.getLast().snapshot().document();
        }

        private List<Delivery<Flow, Event>> getPendingEvents() {
            return saved.getLast().pendingWork().stream().map(PendingWork::value).toList();
        }
    }

    private static class TestFlow extends FlowRegistration<Document, Event, Flow> {
        private final Context context;

        private TestFlow(Flow id, Optional<Flow> parent, Context context) {
            super(id, parent);
            this.context = context;
        }

        @Override
        public boolean isInterestedIn(Event event) {
            return event.flow() == getId();
        }

        @Override
        public boolean isFailed(Document snapshot) {
            return snapshot.flow(getId()).state() == State.FAILED;
        }

        @Override
        public boolean discard(Document snapshot, Event event, boolean command) {
            return context.discardCommands && command;
        }

        @Override
        public boolean canHandle(Document snapshot, Event event) {
            return event instanceof Fail || event instanceof Enable || snapshot.flow(getId()).state() == State.READY;
        }

        @Override
        public void admit(Document snapshot, Event event) {
            context.admissions++;
        }

        @Override
        public Optional<Applied<Document, Event>> apply(Document snapshot, Event event) {
            var flow = getId();
            if (event instanceof Crash) {
                throw new IllegalStateException("Processing failed");
            }
            var output = new ProcessingOutput<Event>(List.of(new Processed(flow)),
                    List.of(),
                    event instanceof Raise raised ? raised.events() : List.of());
            if (event instanceof Fail) {
                return Optional.of(new Applied<>(snapshot.withState(flow, State.FAILED), output));
            }
            if (event instanceof Enable) {
                return Optional.of(new Applied<>(snapshot.withState(flow, State.READY), output));
            }
            if (event instanceof Archive) {
                return Optional.of(new Applied<>(snapshot.withState(flow, State.HISTORICAL), output));
            }
            if (event instanceof Raise) {
                return Optional.of(new Applied<>(snapshot, output));
            }
            var current = snapshot.flow(flow);
            return Optional.of(new Applied<>(snapshot.withFlow(flow, current.to(current.state(), current.data() + 1)),
                    output));
        }

        @Override
        public List<Effect> recoveryEffects(Document snapshot) {
            return List.of(new Recover(getId()));
        }
    }

    private static final class LifecycleParent extends TestFlow {
        private LifecycleParent(Context context) {
            super(Flow.PARENT, Optional.empty(), context);
        }

        @Override
        public boolean isProcessingEnabled(Document snapshot) {
            return snapshot.flow(getId()).state() != State.HISTORICAL;
        }

        @Override
        public boolean isChildProcessingEnabled(Document snapshot) {
            return snapshot.flow(getId()).state() == State.READY;
        }

        @Override
        public boolean canHandle(Document snapshot, Event event) {
            State state = snapshot.flow(getId()).state();
            if (event instanceof Archive) {
                return state == State.READY || state == State.FAILED;
            }
            return state == State.READY && super.canHandle(snapshot, event);
        }

        @Override
        public boolean discard(Document snapshot, Event event, boolean command) {
            return super.discard(snapshot, event, command) || (!command && !canHandle(snapshot, event));
        }
    }
}
