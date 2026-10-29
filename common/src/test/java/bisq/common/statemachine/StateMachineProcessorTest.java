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
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.PendingWork;
import bisq.common.processing.ProcessingLane;
import bisq.common.processing.ProcessingLaneDefinition;
import bisq.common.processing.ProcessingResult;
import bisq.common.processing.StepResult;
import bisq.common.processing.SubmissionResult;
import bisq.common.processing.SubmissionResult.CoordinationPhase;
import bisq.common.timer.MockClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class StateMachineProcessorTest {
    @Test
    void duplicateFlowIdsAreRejected() {
        var behavior = new Behavior();
        var first = TestProcessor.registration(Flow.ROOT, Optional.empty(), behavior);
        var duplicate = TestProcessor.registration(Flow.ROOT, Optional.empty(), behavior);

        assertThrows(IllegalArgumentException.class,
                () -> new StateMachineProcessor<>(List.of(first, duplicate)));
    }

    @Test
    void aMissingParentIsRejected() {
        var child = TestProcessor.registration(Flow.DECISION, Optional.of(Flow.MEDIATION), new Behavior());

        assertThrows(IllegalArgumentException.class, () -> new StateMachineProcessor<>(List.of(child)));
    }

    @Test
    void cyclicParentRelationshipsAreRejected() {
        var behavior = new Behavior();
        var first = TestProcessor.registration(Flow.EXECUTION, Optional.of(Flow.MEDIATION), behavior);
        var second = TestProcessor.registration(Flow.MEDIATION, Optional.of(Flow.EXECUTION), behavior);

        assertThrows(IllegalArgumentException.class,
                () -> new StateMachineProcessor<>(List.of(first, second)));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void restorationRejectsUnregisteredFlowsBeforeSetupOrOutgoingWork(boolean unknownHistory) {
        var behavior = new Behavior();
        behavior.recoverWork = true;
        var execution = TestProcessor.registration(Flow.EXECUTION, Optional.empty(), behavior);
        var snapshot = new StateMachineSnapshot<Document, Flow>(
                new Document(Map.of(Flow.EXECUTION, FlowSnapshot.of(new Ready(), 0))),
                Map.of(unknownHistory ? Flow.MEDIATION : Flow.EXECUTION, Set.of(Start.class)));
        var pendingWork = new ArrayList<PendingWork<Delivery<Flow, Event>>>();
        pendingWork.add(new PendingWork<>(new Delivery<>(Flow.EXECUTION, new PeerMessage(false)), false));
        if (!unknownHistory) {
            pendingWork.add(new PendingWork<>(new Delivery<>(Flow.MEDIATION, new PeerMessage(false)), false));
        }
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>(
                snapshot,
                pendingWork,
                List.of(new TestEffect("saved-message")),
                List.of(new ScheduledEvent<>("deadline", Instant.EPOCH, new PeerMessage(false))));
        var operations = new ArrayList<String>();
        var timers = mock(ScheduledExecutorService.class);
        var definition = ProcessingLaneDefinition.<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>builder(
                        Event.class,
                        restored -> new StateMachineProcessor<>(List.of(execution)))
                .setupWith(restored -> operations.add("setup"))
                .build();

        var failure = assertThrows(IllegalArgumentException.class,
                () -> new ProcessingLane<>("document",
                        Optional.of(checkpoint),
                        Runnable::run,
                        timers,
                        new MockClock(),
                        definition,
                        next -> operations.add("save"),
                        (restored, effect) -> operations.add("effect"),
                        entry -> operations.add("log"),
                        (restored, persistenceFailure, setupFailure) -> operations.add("publish")));

        assertEquals("Flow is not registered: MEDIATION", failure.getMessage());
        assertTrue(operations.isEmpty());
        assertEquals(0, behavior.preparedHandlers);
        assertEquals(0, behavior.workAttempts);
        verifyNoInteractions(timers);
    }

    @Test
    void restorationKeepsAnEarlyMessageUntilItsFlowIsReady() {
        var pendingWork = new PendingWork<Delivery<Flow, Event>>(
                new Delivery<>(Flow.EXECUTION, new PeerMessage(false)), false);
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>(
                initial(false),
                List.of(pendingWork),
                List.of(),
                List.of());
        var fixture = new LaneFixture(checkpoint);

        assertEquals(List.of(pendingWork), fixture.lastSaved().pendingWork());
        assertEquals(0, fixture.behavior.preparedHandlers);
        assertInstanceOf(Waiting.class, fixture.lastSaved().snapshot().document().flow(Flow.EXECUTION).state());

        var result = fixture.lane.submit(new Start()).join();

        assertInstanceOf(SubmissionResult.Completed.class, result);
        assertInstanceOf(Finished.class, fixture.lastSaved().snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(1, fixture.behavior.workAttempts);
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
    }

    @Test
    void earlyMessagesDeferWhileUnavailableCommandsRejectBeforeCreatingHandlers() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var snapshot = initial(false);

        assertEquals(List.of(Flow.EXECUTION, Flow.MEDIATION, Flow.DECISION),
                processor.route(snapshot, new PeerMessage(false)).stream().map(Delivery::flow).toList());
        assertInstanceOf(StepResult.Deferred.class,
                processor.processStep(snapshot, new Delivery<>(Flow.EXECUTION, new PeerMessage(false))));
        assertInstanceOf(StepResult.Rejected.class,
                processor.processStep(snapshot, new Delivery<>(Flow.EXECUTION, new FinishCommand())));
        assertTrue(processor.route(snapshot, new Invalid()).isEmpty());
        assertEquals(0, behavior.preparedHandlers);
        assertEquals(0, behavior.failureReports);
        assertTrue(snapshot.processedEventTypes().isEmpty());
    }

    @ParameterizedTest
    @EnumSource(value = Flow.class, names = {"EXECUTION", "MEDIATION", "DECISION"})
    void laneRoutesInputsOnlyToInterestedFlows(Flow target) {
        var document = initial(true).document();
        if (target != Flow.DECISION) {
            document = document.withState(target, new Waiting());
        }
        Event input = switch (target) {
            case EXECUTION -> new ExecutionStarted();
            case MEDIATION -> new MediationStarted();
            case DECISION -> new DecisionMessage();
            default -> throw new IllegalArgumentException("Unexpected target: " + target);
        };
        var fixture = new LaneFixture(new Checkpoint<>(
                new StateMachineSnapshot<>(document, Map.of()),
                List.of(),
                List.of(),
                List.of()));

        var result = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(input).join());

        assertInstanceOf(ProcessingResult.Applied.class, result.processingResult());
        var saved = fixture.lastSaved();
        Class<? extends Stage> expectedState = target == Flow.DECISION ? Finished.class : Ready.class;
        assertInstanceOf(expectedState, saved.snapshot().document().flow(target).state());
        assertEquals(target == Flow.DECISION ? 1 : 0, saved.snapshot().document().flow(target).data());
        assertEquals(Map.of(target, Set.of(input.getClass())), saved.snapshot().processedEventTypes());
        for (Flow flow : Flow.values()) {
            if (flow != target) {
                assertSame(document.flow(flow), saved.snapshot().document().flow(flow));
            }
        }
        assertTrue(saved.pendingWork().isEmpty());

        Event obsoleteInput = target == Flow.MEDIATION ? new ExecutionStarted() : new MediationStarted();
        for (Event ignoredInput : List.of(new Uninteresting(), obsoleteInput)) {
            var ignored = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(ignoredInput).join());

            assertInstanceOf(ProcessingResult.Ignored.class, ignored.processingResult());
            assertSame(saved.snapshot(), fixture.lastSaved().snapshot());
            assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        }
        assertEquals(1, fixture.behavior.preparedHandlers);
        assertEquals(0, fixture.behavior.failureReports);
        assertTrue(fixture.effects.isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void processingHistoryIsReturnedWithTheDocumentAndRecheckedPerFlow() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var before = initial(false);
        var message = new PeerMessage(false);
        var retained = processor.route(before, message);
        var ready = applied(processor.processStep(before, new Delivery<>(Flow.EXECUTION, new Start()))).nextSnapshot();
        var result = applied(processor.processStep(ready, retained.getFirst()));
        var after = result.nextSnapshot();

        assertInstanceOf(Finished.class, after.document().flow(Flow.EXECUTION).state());
        assertInstanceOf(Waiting.class, before.document().flow(Flow.EXECUTION).state());
        assertTrue(before.processedEventTypes().isEmpty());
        assertEquals(Set.of(Start.class, PeerMessage.class), after.processedEventTypes().get(Flow.EXECUTION));
        assertEquals(List.of(Flow.MEDIATION, Flow.DECISION),
                processor.route(after, message).stream().map(Delivery::flow).toList());
        assertInstanceOf(StepResult.Ignored.class, processor.processStep(after, retained.getFirst()));
        assertEquals(1, behavior.workAttempts);

        var restored = new TestProcessor(new Behavior());
        assertEquals(processor.route(after, message), restored.route(after, message));
        assertThrows(UnsupportedOperationException.class, () -> after.processedEventTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> after.processedEventTypes().get(Flow.EXECUTION).clear());
    }

    @Test
    void ignoringAnInputDoesNotPreventALaterValidInputOfTheSameType() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var before = initial(true);

        assertInstanceOf(StepResult.Ignored.class,
                processor.processStep(before, new Delivery<>(Flow.EXECUTION, new PeerMessage(true))));
        assertEquals(0, behavior.workAttempts);
        assertTrue(before.processedEventTypes().isEmpty());

        var result = applied(processor.processStep(before, new Delivery<>(Flow.EXECUTION, new PeerMessage(false))));
        assertInstanceOf(Finished.class, result.nextSnapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(1, behavior.workAttempts);
    }

    @Test
    void commandAdmissionFailureDoesNotEnterTheHandlerFailurePath() {
        var behavior = new Behavior();
        behavior.rejectCommands = true;
        var processor = new TestProcessor(behavior);

        assertInstanceOf(StepResult.Rejected.class,
                processor.processStep(initial(true), new Delivery<>(Flow.EXECUTION, new FinishCommand())));
        assertEquals(0, behavior.preparedHandlers);
        assertEquals(0, behavior.failureReports);
    }

    @Test
    void rejectedCommandsCanSucceedAfterReadinessAndAdmissionConditionsChange() {
        var fixture = new LaneFixture(new Checkpoint<>(
                initial(false),
                List.of(),
                List.of(),
                List.of()));
        var command = new FinishMediationCommand();
        var before = fixture.lastSaved().snapshot();

        var unavailable = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());

        var rejected = assertInstanceOf(ProcessingResult.Rejected.class, unavailable.processingResult());
        assertInstanceOf(IllegalStateException.class, rejected.cause());
        assertSame(before, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(0, fixture.behavior.mediationCommandAdmissions);
        assertEquals(0, fixture.behavior.preparedHandlers);
        assertFalse(fixture.lane.isClosed());

        fixture.lane.submit(new MediationStarted()).join();
        var ready = fixture.lastSaved().snapshot();
        assertInstanceOf(Ready.class, ready.document().flow(Flow.MEDIATION).state());
        assertInstanceOf(Waiting.class, ready.document().flow(Flow.EXECUTION).state());

        var unadmitted = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());

        rejected = assertInstanceOf(ProcessingResult.Rejected.class, unadmitted.processingResult());
        assertInstanceOf(IllegalArgumentException.class, rejected.cause());
        assertSame(ready, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(1, fixture.behavior.mediationCommandAdmissions);
        assertEquals(1, fixture.behavior.preparedHandlers);
        assertFalse(fixture.lane.isClosed());

        fixture.lane.submit(new ExecutionStarted()).join();
        assertInstanceOf(Ready.class, fixture.lastSaved().snapshot().document().flow(Flow.MEDIATION).state());
        assertEquals(0, fixture.behavior.workAttempts);

        var accepted = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());

        assertInstanceOf(ProcessingResult.Applied.class, accepted.processingResult());
        var saved = fixture.lastSaved();
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.MEDIATION).state());
        assertInstanceOf(Ready.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(Map.of(Flow.MEDIATION, Set.of(MediationStarted.class, FinishMediationCommand.class),
                        Flow.EXECUTION, Set.of(ExecutionStarted.class)),
                saved.snapshot().processedEventTypes());
        assertEquals(2, fixture.behavior.mediationCommandAdmissions);
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(0, fixture.behavior.failureReports);
        assertTrue(saved.pendingWork().isEmpty());
        assertTrue(fixture.effects.isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void failedParentsStopChildrenWhileFailedChildrenLeaveTheirParentAndSiblingsAvailable() {
        var processor = new TestProcessor(new Behavior());
        var before = initial(true);
        var childFailed = new StateMachineSnapshot<Document, Flow>(
                before.document().withState(Flow.DECISION, new Failed()),
                Map.of());
        var parentFailed = new StateMachineSnapshot<Document, Flow>(
                before.document().withState(Flow.MEDIATION, new Failed()),
                Map.of());

        assertEquals(List.of(Flow.EXECUTION, Flow.MEDIATION),
                processor.route(childFailed, new PeerMessage(false)).stream().map(Delivery::flow).toList());
        assertEquals(List.of(Flow.EXECUTION),
                processor.route(parentFailed, new PeerMessage(false)).stream().map(Delivery::flow).toList());
        assertInstanceOf(StepResult.Rejected.class,
                processor.processStep(parentFailed, new Delivery<>(Flow.DECISION, new FinishCommand())));
        assertInstanceOf(StepResult.Ignored.class,
                processor.processStep(parentFailed, new Delivery<>(Flow.DECISION, new PeerMessage(false))));
    }

    @Test
    void recoveryUsesTheSuppliedSnapshotAndSkipsStoppedFlowsAndTheirChildren() {
        var behavior = new Behavior();
        behavior.recoverWork = true;
        var processor = new TestProcessor(behavior);
        var document = initial(true).document();
        for (Flow id : Flow.values()) {
            document = document.with(id, FlowSnapshot.of(document.flow(id).state(), id.ordinal()));
        }

        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1"),
                        new TestEffect("recover:2"), new TestEffect("recover:3")),
                processor.recoveryEffects(new StateMachineSnapshot<>(document, Map.of())));
        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1")),
                processor.recoveryEffects(new StateMachineSnapshot<>(
                        document.withState(Flow.MEDIATION, new Failed()), Map.of())));
        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1"), new TestEffect("recover:2")),
                processor.recoveryEffects(new StateMachineSnapshot<>(
                        document.withState(Flow.DECISION, new Failed()), Map.of())));
        assertTrue(processor.recoveryEffects(new StateMachineSnapshot<>(
                document.withState(Flow.ROOT, new Failed()), Map.of())).isEmpty());
        assertEquals(0, behavior.preparedHandlers);
        assertEquals(0, behavior.workAttempts);
    }

    @Test
    void restoredParentFailureStopsChildWorkWhileExecutionContinues() {
        var document = initial(true).document()
                .with(Flow.EXECUTION, FlowSnapshot.of(new Ready(), 1))
                .with(Flow.MEDIATION, FlowSnapshot.of(new Failed(), 2))
                .with(Flow.DECISION, FlowSnapshot.of(new Ready(), 3));
        var snapshot = new StateMachineSnapshot<>(document,
                Map.of(Flow.MEDIATION, Set.of(FailureReported.class), Flow.DECISION, Set.of(Start.class)));
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>(
                snapshot,
                List.of(new PendingWork<>(new Delivery<>(Flow.DECISION, new DecisionMessage()), false)),
                List.of(),
                List.of());
        var behavior = new Behavior();
        behavior.recoverWork = true;

        var fixture = new LaneFixture(checkpoint, behavior);

        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1")), fixture.effects);
        assertSame(snapshot, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var lateMessage = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new DecisionMessage()).join());
        assertInstanceOf(ProcessingResult.Ignored.class, lateMessage.processingResult());
        var command = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new FinishDecisionCommand()).join());
        var rejected = assertInstanceOf(ProcessingResult.Rejected.class, command.processingResult());
        assertInstanceOf(IllegalStateException.class, rejected.cause());
        assertSame(snapshot, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var execution = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new PeerMessage(false)).join());

        assertInstanceOf(ProcessingResult.Applied.class, execution.processingResult());
        var saved = fixture.lastSaved();
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(2, saved.snapshot().document().flow(Flow.EXECUTION).data());
        assertSame(document.flow(Flow.MEDIATION), saved.snapshot().document().flow(Flow.MEDIATION));
        assertSame(document.flow(Flow.DECISION), saved.snapshot().document().flow(Flow.DECISION));
        assertSame(document.flow(Flow.ROOT), saved.snapshot().document().flow(Flow.ROOT));
        assertEquals(Map.of(Flow.MEDIATION, Set.of(FailureReported.class),
                        Flow.DECISION, Set.of(Start.class), Flow.EXECUTION, Set.of(PeerMessage.class)),
                saved.snapshot().processedEventTypes());
        assertEquals(1, behavior.preparedHandlers);
        assertEquals(1, behavior.workAttempts);
        assertEquals(0, behavior.failureReports);
        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1")), fixture.effects);
        assertTrue(saved.pendingWork().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void restoredChildFailureLeavesMediationAndExecutionAvailable() {
        var document = initial(true).document()
                .with(Flow.EXECUTION, FlowSnapshot.of(new Ready(), 1))
                .with(Flow.MEDIATION, FlowSnapshot.of(new Ready(), 2))
                .with(Flow.DECISION, FlowSnapshot.of(new Failed(), 3));
        var snapshot = new StateMachineSnapshot<>(document, Map.of(Flow.DECISION, Set.of(FailureReported.class)));
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>(
                snapshot,
                List.of(new PendingWork<>(new Delivery<>(Flow.DECISION, new DecisionMessage()), false)),
                List.of(),
                List.of());
        var behavior = new Behavior();
        behavior.recoverWork = true;

        var fixture = new LaneFixture(checkpoint, behavior);

        var recoveryEffects = List.of(new TestEffect("recover:0"),
                new TestEffect("recover:1"),
                new TestEffect("recover:2"));
        assertEquals(recoveryEffects, fixture.effects);
        assertSame(snapshot, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var result = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new PeerMessage(false)).join());

        assertInstanceOf(ProcessingResult.Applied.class, result.processingResult());
        var saved = fixture.lastSaved();
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.MEDIATION).state());
        assertEquals(2, saved.snapshot().document().flow(Flow.EXECUTION).data());
        assertEquals(3, saved.snapshot().document().flow(Flow.MEDIATION).data());
        assertSame(document.flow(Flow.DECISION), saved.snapshot().document().flow(Flow.DECISION));
        assertSame(document.flow(Flow.ROOT), saved.snapshot().document().flow(Flow.ROOT));
        assertEquals(Map.of(Flow.DECISION, Set.of(FailureReported.class),
                        Flow.EXECUTION, Set.of(PeerMessage.class), Flow.MEDIATION, Set.of(PeerMessage.class)),
                saved.snapshot().processedEventTypes());
        assertEquals(2, behavior.preparedHandlers);
        assertEquals(2, behavior.workAttempts);
        assertEquals(0, behavior.failureReports);
        assertEquals(recoveryEffects, fixture.effects);
        assertTrue(saved.pendingWork().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void failureIsRecordedOnlyInItsReceivingFlowWithinTheSameStep() {
        var behavior = new Behavior();
        behavior.failWork = true;
        var processor = new TestProcessor(behavior);
        var before = initial(true);

        var result = failed(processor.processStep(before, new Delivery<>(Flow.MEDIATION, new PeerMessage(false))));

        assertSame(behavior.workFailure, result.cause());
        assertInstanceOf(Failed.class, result.nextSnapshot().document().flow(Flow.MEDIATION).state());
        assertSame(before.document().flow(Flow.EXECUTION), result.nextSnapshot().document().flow(Flow.EXECUTION));
        assertSame(before.document().flow(Flow.DECISION), result.nextSnapshot().document().flow(Flow.DECISION));
        assertEquals(Map.of(Flow.MEDIATION, Set.of(FailureReported.class)), result.nextSnapshot().processedEventTypes());
        assertTrue(result.output().raisedEvents().isEmpty());
        assertEquals(1, behavior.workAttempts);
        assertEquals(1, behavior.failureReports);
        assertEquals(1, behavior.failureHandlerAttempts);
    }

    @Test
    void executionFailureReturnsItsParentReactionWithoutProcessingIt() {
        var behavior = new Behavior();
        behavior.failWork = true;
        var processor = new TestProcessor(behavior);

        var result = failed(processor.processStep(initial(true),
                new Delivery<>(Flow.EXECUTION, new PeerMessage(false))));

        assertInstanceOf(Waiting.class, result.nextSnapshot().document().flow(Flow.ROOT).state());
        assertEquals(List.of(new ChildFailed()), result.output().raisedEvents());
        assertEquals(0, behavior.parentReactions);
    }

    @ParameterizedTest
    @EnumSource(value = FailureMode.class, names = {"MISSING", "IGNORED", "THROWS", "NON_FAILED"})
    void unsuccessfulFailureReportingThrowsTheOriginalFailureWithoutRecursing(FailureMode mode) {
        var behavior = new Behavior();
        behavior.failWork = true;
        behavior.failureMode = mode;
        var processor = new TestProcessor(behavior);
        var before = initial(true);

        var thrown = assertThrows(RuntimeException.class,
                () -> processor.processStep(before, new Delivery<>(Flow.EXECUTION, new PeerMessage(false))));

        assertSame(behavior.workFailure, thrown);
        assertEquals(1, behavior.failureReports);
        assertTrue(behavior.failureHandlerAttempts <= 1);
        assertInstanceOf(Ready.class, before.document().flow(Flow.EXECUTION).state());
        assertTrue(before.processedEventTypes().isEmpty());
        if (mode == FailureMode.THROWS) {
            assertEquals(List.of(behavior.reportFailure), List.of(thrown.getSuppressed()));
        }
    }

    @Test
    void raisedEventsSeeTheSavedProducerAndSaveTheirOwnTransition() {
        var fixture = new LaneFixture();
        var before = fixture.lastSaved().snapshot().document();
        var observed = new ArrayList<Document>();
        fixture.behavior.beforeRaisedEvent = document -> {
            var saved = fixture.lastSaved();
            assertSame(saved.snapshot().document(), document);
            assertInstanceOf(Finished.class, document.flow(Flow.EXECUTION).state());
            assertEquals(1, document.flow(Flow.EXECUTION).data());
            assertSame(before.flow(Flow.MEDIATION), document.flow(Flow.MEDIATION));
            assertEquals(Map.of(Flow.EXECUTION, Set.of(ExecutionMessage.class)),
                    saved.snapshot().processedEventTypes());
            assertEquals(List.of(new PendingWork<>(new Delivery<>(Flow.MEDIATION, new ExecutionFinished()), false)),
                    saved.pendingWork());
            observed.add(document);
        };

        var result = fixture.lane.submit(new ExecutionMessage()).join();

        var completed = assertInstanceOf(SubmissionResult.Completed.class, result);
        assertInstanceOf(ProcessingResult.Applied.class, completed.processingResult());
        assertEquals(1, observed.size());
        var saved = fixture.lastSaved();
        var document = saved.snapshot().document();
        assertSame(observed.getFirst().flow(Flow.EXECUTION), document.flow(Flow.EXECUTION));
        assertInstanceOf(Finished.class, document.flow(Flow.MEDIATION).state());
        assertEquals(1, document.flow(Flow.MEDIATION).data());
        assertEquals(Map.of(Flow.EXECUTION, Set.of(ExecutionMessage.class),
                        Flow.MEDIATION, Set.of(ExecutionFinished.class)),
                saved.snapshot().processedEventTypes());
        assertSame(before.flow(Flow.ROOT), document.flow(Flow.ROOT));
        assertSame(before.flow(Flow.DECISION), document.flow(Flow.DECISION));
        assertEquals(2, fixture.behavior.workAttempts);
        assertTrue(saved.pendingWork().isEmpty());
    }

    @Test
    void raisedEventFailurePreservesTheSavedProducerAndStopsOnlyTheReceivingBranch() {
        var fixture = new LaneFixture();
        var before = fixture.lastSaved().snapshot().document();
        var observed = new ArrayList<Document>();
        fixture.behavior.beforeRaisedEvent = document -> {
            var saved = fixture.lastSaved();
            assertSame(saved.snapshot().document(), document);
            assertInstanceOf(Finished.class, document.flow(Flow.EXECUTION).state());
            assertEquals(1, document.flow(Flow.EXECUTION).data());
            assertSame(before.flow(Flow.MEDIATION), document.flow(Flow.MEDIATION));
            assertEquals(Map.of(Flow.EXECUTION, Set.of(ExecutionMessage.class)),
                    saved.snapshot().processedEventTypes());
            assertEquals(List.of(new PendingWork<>(new Delivery<>(Flow.MEDIATION, new ExecutionFinished()), false)),
                    saved.pendingWork());
            observed.add(document);
            throw fixture.behavior.workFailure;
        };

        var completed = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new ExecutionMessage()).join());

        var failed = assertInstanceOf(ProcessingResult.Failed.class, completed.processingResult());
        assertSame(fixture.behavior.workFailure, failed.cause());
        assertEquals(1, observed.size());
        var saved = fixture.lastSaved();
        var document = saved.snapshot().document();
        assertSame(saved.snapshot(), failed.snapshot());
        assertSame(observed.getFirst().flow(Flow.EXECUTION), document.flow(Flow.EXECUTION));
        assertInstanceOf(Failed.class, document.flow(Flow.MEDIATION).state());
        assertEquals(0, document.flow(Flow.MEDIATION).data());
        assertEquals(Map.of(Flow.EXECUTION, Set.of(ExecutionMessage.class),
                        Flow.MEDIATION, Set.of(FailureReported.class)),
                saved.snapshot().processedEventTypes());
        assertSame(before.flow(Flow.ROOT), document.flow(Flow.ROOT));
        assertSame(before.flow(Flow.DECISION), document.flow(Flow.DECISION));
        assertTrue(saved.pendingWork().isEmpty());
        assertTrue(saved.pendingEffects().isEmpty());

        for (Event event : List.of(new ExecutionFinished(), new DecisionMessage())) {
            var late = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(event).join());
            assertInstanceOf(ProcessingResult.Ignored.class, late.processingResult());
        }
        for (Event command : List.of(new FinishMediationCommand(), new FinishDecisionCommand())) {
            var late = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());
            assertInstanceOf(ProcessingResult.Rejected.class, late.processingResult());
        }

        assertSame(saved.snapshot(), fixture.lane.resume().join());
        assertSame(saved, fixture.lastSaved());
        assertEquals(3, fixture.behavior.preparedHandlers);
        assertEquals(2, fixture.behavior.workAttempts);
        assertEquals(1, fixture.behavior.failureReports);
        assertEquals(1, fixture.behavior.failureHandlerAttempts);
        assertEquals(0, fixture.behavior.parentReactions);
        assertEquals(0, fixture.behavior.mediationCommandAdmissions);
        assertEquals(List.of(new TestEffect("failed-MEDIATION")), fixture.effects);
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void laneSavesTheFailureBeforeParentReactionAndSaveRetryDoesNotRepeatTheHandlers() {
        var fixture = new LaneFixture();
        fixture.behavior.failWork = true;
        fixture.failFailureCheckpoint = true;
        fixture.behavior.beforeParentReaction = () -> {
            var savedDocument = fixture.lastSaved().snapshot().document();
            assertInstanceOf(Failed.class, savedDocument.flow(Flow.EXECUTION).state());
            assertInstanceOf(Waiting.class, savedDocument.flow(Flow.ROOT).state());
        };

        var submission = fixture.lane.submit(new PeerMessage(false)).join();

        var pending = assertInstanceOf(SubmissionResult.Pending.class, submission);
        assertEquals(CoordinationPhase.PERSISTENCE, pending.phase());
        assertInstanceOf(ProcessingResult.Failed.class, pending.processingResult());
        assertEquals(0, fixture.behavior.parentReactions);
        assertTrue(fixture.effects.isEmpty());
        assertFalse(fixture.lastSaved().pendingWork().isEmpty());

        fixture.failFailureCheckpoint = false;
        var resumed = fixture.lane.resume().join();

        assertInstanceOf(Failed.class, resumed.document().flow(Flow.ROOT).state());
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(1, fixture.behavior.failureHandlerAttempts);
        assertEquals(1, fixture.behavior.parentReactions);
        assertEquals(List.of(new TestEffect("failed-EXECUTION")), fixture.effects);
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @ParameterizedTest
    @EnumSource(value = FailureMode.class, names = {"MISSING", "THROWS"})
    void unrecordedFailureStopsTheLaneAndRetainsTheSavedInput(FailureMode mode) {
        var before = initial(false);
        var fixture = new LaneFixture(new Checkpoint<>(before, List.of(), List.of(), List.of()));
        var input = new ExecutionMessage();
        var pendingInput = new PendingWork<Delivery<Flow, Event>>(new Delivery<>(Flow.EXECUTION, input), false);
        var deferred = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(input).join());
        assertInstanceOf(ProcessingResult.Deferred.class, deferred.processingResult());
        assertEquals(List.of(pendingInput), fixture.lastSaved().pendingWork());
        assertEquals(0, fixture.behavior.preparedHandlers);
        fixture.behavior.failWork = true;
        fixture.behavior.failureMode = mode;

        var thrown = assertThrows(CompletionException.class,
                () -> fixture.lane.submit(new ExecutionStarted()).join());

        assertSame(fixture.behavior.workFailure, thrown.getCause());
        assertTrue(fixture.lane.isClosed());
        var saved = fixture.lastSaved();
        assertInstanceOf(Ready.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(Map.of(Flow.EXECUTION, Set.of(ExecutionStarted.class)), saved.snapshot().processedEventTypes());
        assertEquals(List.of(pendingInput), saved.pendingWork());
        assertTrue(saved.pendingEffects().isEmpty());
        assertSame(before.document().flow(Flow.ROOT), saved.snapshot().document().flow(Flow.ROOT));
        assertSame(before.document().flow(Flow.MEDIATION), saved.snapshot().document().flow(Flow.MEDIATION));
        assertSame(before.document().flow(Flow.DECISION), saved.snapshot().document().flow(Flow.DECISION));

        var resumed = assertThrows(CompletionException.class, () -> fixture.lane.resume().join());
        var later = assertThrows(CompletionException.class, () -> fixture.lane.submit(new MediationStarted()).join());

        assertInstanceOf(RejectedExecutionException.class, resumed.getCause());
        assertInstanceOf(RejectedExecutionException.class, later.getCause());
        assertSame(saved, fixture.lastSaved());
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(1, fixture.behavior.failureReports);
        assertEquals(mode == FailureMode.MISSING ? 0 : 1, fixture.behavior.failureHandlerAttempts);
        assertEquals(0, fixture.behavior.parentReactions);
        assertTrue(fixture.effects.isEmpty());
    }

    @Test
    void applicationValidationRejectsBeforeRetentionWithoutFailingTheLane() {
        var fixture = new LaneFixture();

        var result = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(new Invalid()).join());

        assertInstanceOf(ProcessingResult.Rejected.class, result.processingResult());
        assertEquals(1, fixture.saved.size());
        assertTrue(fixture.lastSaved().pendingWork().isEmpty());
        assertFalse(fixture.lane.isClosed());
        assertEquals(0, fixture.behavior.failureReports);
    }

    private static <S, I> StepResult.Applied<S, I> applied(StepResult<S, I> result) {
        return switch (result) {
            case StepResult.Applied<S, I> applied -> applied;
            default -> throw new AssertionError("Expected Applied, got " + result);
        };
    }

    private static <S, I> StepResult.Failed<S, I> failed(StepResult<S, I> result) {
        return switch (result) {
            case StepResult.Failed<S, I> failed -> failed;
            default -> throw new AssertionError("Expected Failed, got " + result);
        };
    }

    private static StateMachineSnapshot<Document, Flow> initial(boolean ready) {
        var flows = new LinkedHashMap<Flow, FlowSnapshot<? extends Stage, Integer>>();
        for (Flow flow : Flow.values()) {
            Stage state = flow == Flow.ROOT || !ready ? new Waiting() : new Ready();
            flows.put(flow, FlowSnapshot.of(state, 0));
        }
        return new StateMachineSnapshot<>(new Document(flows), Map.of());
    }

    private enum Flow { ROOT, EXECUTION, MEDIATION, DECISION }
    private enum FailureMode { VALID, MISSING, IGNORED, THROWS, NON_FAILED }
    private interface Stage {}
    private record Waiting() implements Stage {}
    private record Ready() implements Stage {}
    private record Finished() implements Stage {}
    private record Failed() implements Stage {}
    private interface Event extends bisq.common.statemachine.Event, TestProto {}
    private record Start() implements Event, Command {}
    private record FinishCommand() implements Event, Command {}
    private record FinishMediationCommand() implements Event, Command {}
    private record FinishDecisionCommand() implements Event, Command {}
    private record MediationStarted() implements Event {}
    private record ExecutionStarted() implements Event {}
    private record DecisionMessage() implements Event {}
    private record PeerMessage(boolean ignore) implements Event {}
    private record ExecutionMessage() implements Event {}
    private record ExecutionFinished() implements Event {}
    private record FailureReported() implements Event {}
    private record ChildFailed() implements Event {}
    private record Invalid() implements Event {}
    private record Uninteresting() implements Event {}
    private record TestEffect(String name) implements Effect, TestProto {}

    private record Document(Map<Flow, FlowSnapshot<? extends Stage, Integer>> flows) {
        private Document {
            flows = Map.copyOf(flows);
        }

        private FlowSnapshot<? extends Stage, Integer> flow(Flow id) {
            return flows.get(id);
        }

        private Document with(Flow id, FlowSnapshot<? extends Stage, Integer> next) {
            var updated = new LinkedHashMap<>(flows);
            updated.put(id, next);
            return new Document(updated);
        }

        private Document withState(Flow id, Stage state) {
            return with(id, FlowSnapshot.of(state, flow(id).data()));
        }
    }

    private static final class Behavior {
        private final RuntimeException workFailure = new IllegalStateException("Backend failed");
        private final RuntimeException reportFailure = new IllegalStateException("Failure handler failed");
        private boolean failWork;
        private boolean recoverWork;
        private boolean rejectCommands;
        private FailureMode failureMode = FailureMode.VALID;
        private int preparedHandlers;
        private int workAttempts;
        private int failureReports;
        private int failureHandlerAttempts;
        private int parentReactions;
        private int mediationCommandAdmissions;
        private Runnable beforeParentReaction = () -> {};
        private Consumer<Document> beforeRaisedEvent = document -> {};
    }

    private record Context(Flow flow, Behavior behavior, Document document) {}

    private static final class TestProcessor extends StateMachineProcessor<Document, Event, Flow> {
        private final Behavior behavior;

        private TestProcessor(Behavior behavior) {
            super(registrations(behavior));
            this.behavior = behavior;
        }

        @Override
        public void validateInput(StateMachineSnapshot<Document, Flow> snapshot, Event event) {
            if (event instanceof Invalid) {
                throw new IllegalArgumentException("Wrong sender");
            }
        }

        @Override
        protected Optional<Event> onProcessingFailure(Document document,
                                                      Delivery<Flow, Event> delivery,
                                                      RuntimeException failure) {
            behavior.failureReports++;
            return behavior.failureMode == FailureMode.MISSING ? Optional.empty() : Optional.of(new FailureReported());
        }

        private static List<FlowRegistration<Document, Event, Flow>> registrations(Behavior behavior) {
            var registrations = new ArrayList<FlowRegistration<Document, Event, Flow>>();
            for (Flow id : Flow.values()) {
                var parent = id == Flow.ROOT ? Optional.<Flow>empty() :
                        Optional.of(id == Flow.DECISION ? Flow.MEDIATION : Flow.ROOT);
                registrations.add(registration(id, parent, behavior));
            }
            return registrations;
        }

        private static FlowRegistration<Document, Event, Flow> registration(Flow id,
                                                                           Optional<Flow> parent,
                                                                           Behavior behavior) {
            return new Protocol(id, behavior).bind(id,
                    parent,
                    document -> document.flow(id),
                    (document, next) -> document.with(id, next),
                    Function.identity(),
                    (document, event) -> {
                        behavior.preparedHandlers++;
                        return new Context(id, behavior, document);
                    });
        }
    }

    private static final class Protocol extends StateMachineDefinition<Stage, Integer, Event, Document, Context> {
        private final RuleTable<Stage, Integer, Event, Context> rules;
        private final Behavior behavior;

        private Protocol(Flow id, Behavior behavior) {
            this.behavior = behavior;
            var builder = RuleTable.<Stage, Integer, Event, Context>builder()
                    .rule(Waiting.class, FailureReported.class, FailureHandler::new, Failed.class)
                    .rule(Ready.class, FailureReported.class, FailureHandler::new, Failed.class);
            if (id == Flow.ROOT) {
                builder.rule(Waiting.class, ChildFailed.class, ParentFailureHandler::new, Failed.class);
            } else {
                builder.rule(Waiting.class, Start.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, PeerMessage.class, FinishHandler::new, Finished.class)
                        .rule(Ready.class, FinishCommand.class, FinishHandler::new, Finished.class);
            }
            if (id == Flow.EXECUTION) {
                builder.rule(Waiting.class, ExecutionStarted.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, ExecutionMessage.class, FinishHandler::new, Finished.class);
            } else if (id == Flow.MEDIATION) {
                builder.rule(Waiting.class, MediationStarted.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, FinishMediationCommand.class, FinishHandler::new, Finished.class)
                        .rule(Ready.class, ExecutionFinished.class, FinishHandler::new, Finished.class);
            } else if (id == Flow.DECISION) {
                builder.rule(Ready.class, DecisionMessage.class, FinishHandler::new, Finished.class)
                        .rule(Ready.class, FinishDecisionCommand.class, FinishHandler::new, Finished.class);
            }
            rules = builder.build();
        }

        @Override
        protected RuleTable<Stage, Integer, Event, Context> rules() {
            return rules;
        }

        @Override
        protected List<Effect> recoveryEffects(FlowSnapshot<? extends Stage, Integer> flow) {
            return behavior.recoverWork ? List.of(new TestEffect("recover:" + flow.data())) : List.of();
        }

        @Override
        public boolean isFailed(Stage state) {
            return state instanceof Failed && behavior.failureMode != FailureMode.NON_FAILED;
        }

        @Override
        public void admit(Document document, FlowSnapshot<? extends Stage, Integer> flow, Event event) {
            if (behavior.rejectCommands) {
                throw new IllegalStateException("Command guard rejected");
            }
            if (event instanceof FinishMediationCommand) {
                behavior.mediationCommandAdmissions++;
                if (!(document.flow(Flow.EXECUTION).state() instanceof Ready)) {
                    throw new IllegalArgumentException("Execution must be ready before this command");
                }
            }
        }
    }

    private static final class StartHandler<I extends Event>
            extends TransitionHandler<Waiting, I, Ready, Integer, Context, Event> {
        private StartHandler(Context context, FlowSnapshot<Waiting, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected Transition<Ready, Integer, Event> process(I event) {
            return new Transition<>(flow.to(new Ready(), data), List.of());
        }
    }

    private static final class FinishHandler<I extends Event>
            extends TransitionHandler<Ready, I, Finished, Integer, Context, Event> {
        private FinishHandler(Context context, FlowSnapshot<Ready, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected void verify(I event) {
            if (event instanceof PeerMessage message && message.ignore()) {
                ignoreInput();
            }
        }

        @Override
        protected Transition<Finished, Integer, Event> process(I event) {
            context.behavior.workAttempts++;
            if (context.behavior.failWork) {
                throw context.behavior.workFailure;
            }
            if (event instanceof ExecutionFinished) {
                context.behavior.beforeRaisedEvent.accept(context.document);
            }
            return new Transition<>(flow.to(new Finished(), data + 1),
                    List.of(),
                    List.of(),
                    event instanceof ExecutionMessage ? List.of(new ExecutionFinished()) : List.of());
        }
    }

    private static final class FailureHandler<S extends Stage>
            extends TransitionHandler<S, FailureReported, Failed, Integer, Context, Event> {
        private FailureHandler(Context context, FlowSnapshot<S, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected void verify(FailureReported event) {
            if (context.behavior.failureMode == FailureMode.IGNORED) {
                ignoreInput();
            }
        }

        @Override
        protected Transition<Failed, Integer, Event> process(FailureReported event) {
            context.behavior.failureHandlerAttempts++;
            if (context.behavior.failureMode == FailureMode.THROWS) {
                throw context.behavior.reportFailure;
            }
            return new Transition<>(flow.to(new Failed(), data),
                    List.of(new TestEffect("failed-" + context.flow)),
                    List.of(),
                    context.flow == Flow.EXECUTION ? List.of(new ChildFailed()) : List.of());
        }
    }

    private static final class ParentFailureHandler
            extends TransitionHandler<Waiting, ChildFailed, Failed, Integer, Context, Event> {
        private ParentFailureHandler(Context context, FlowSnapshot<Waiting, Integer> flow) {
            super(context, flow);
        }

        @Override
        protected Transition<Failed, Integer, Event> process(ChildFailed event) {
            context.behavior.beforeParentReaction.run();
            context.behavior.parentReactions++;
            return new Transition<>(flow.to(new Failed(), data), List.of());
        }
    }

    private static final class LaneFixture {
        private final Behavior behavior;
        private final List<Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>> saved =
                new ArrayList<>();
        private final List<Effect> effects = new ArrayList<>();
        private final ProcessingLane<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>> lane;
        private boolean failFailureCheckpoint;

        private LaneFixture() {
            this(new Checkpoint<>(
                    initial(true),
                    List.of(),
                    List.of(),
                    List.of()));
        }

        private LaneFixture(Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>> checkpoint) {
            this(checkpoint, new Behavior());
        }

        private LaneFixture(Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>> checkpoint,
                            Behavior behavior) {
            this.behavior = behavior;
            saved.add(checkpoint);
            var definition = ProcessingLaneDefinition.<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>>builder(
                            Event.class,
                            snapshot -> new TestProcessor(behavior))
                    .build();
            lane = new ProcessingLane<>("document",
                    Optional.of(checkpoint),
                    Runnable::run,
                    mock(ScheduledExecutorService.class),
                    new MockClock(),
                    definition,
                    next -> {
                        if (failFailureCheckpoint && next.snapshot().document().flow(Flow.EXECUTION).state() instanceof Failed) {
                            throw new IllegalStateException("Disk write failed");
                        }
                        saved.add(next);
                    },
                    (snapshot, effect) -> effects.add(effect),
                    entry -> {},
                    (snapshot, persistenceFailure, setupFailure) -> {});
            lane.resume().join();
        }

        private Checkpoint<StateMachineSnapshot<Document, Flow>, Event, Delivery<Flow, Event>> lastSaved() {
            return saved.getLast();
        }
    }
}
