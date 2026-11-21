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
import bisq.common.processing.Effect;
import bisq.common.processing.HandleResult;
import bisq.common.processing.PersistableEffect;
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.lane.Checkpoint;
import bisq.common.processing.lane.ProcessingLane;
import bisq.common.processing.lane.ProcessingLaneDefinition;
import bisq.common.processing.lane.ProcessingSummary;
import bisq.common.processing.lane.SubmissionResult.CoordinationPhase;
import bisq.common.processing.lane.SubmissionResult;
import bisq.common.timer.MockClock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

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

    @Test
    void restorationKeepsAnEarlyMessageUntilItsFlowIsReady() {
        var pendingWork = new PeerMessage(false);
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event>(
                initial(false),
                List.of(pendingWork),
                List.of(),
                List.of());
        var fixture = new LaneFixture(checkpoint);

        assertEquals(List.of(pendingWork), fixture.lastSaved().pendingInputs());
        assertEquals(0, fixture.behavior.preparedHandlers);
        assertInstanceOf(Waiting.class, fixture.lastSaved().snapshot().document().flow(Flow.EXECUTION).state());

        var result = fixture.lane.submit(new Start()).join();

        assertInstanceOf(SubmissionResult.Completed.class, result);
        assertInstanceOf(Finished.class, fixture.lastSaved().snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(1, fixture.behavior.workAttempts);
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
    }

    @Test
    void earlyMessagesDeferWhileUnavailableCommandsRejectBeforeCreatingHandlers() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var snapshot = initial(false);

        assertInstanceOf(HandleResult.Deferred.class, processor.handle(snapshot, new PeerMessage(false)));
        assertInstanceOf(HandleResult.Rejected.class, processor.handle(snapshot, new FinishCommand()));
        assertInstanceOf(HandleResult.Ignored.class, processor.handle(snapshot, new Invalid()));
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

        assertInstanceOf(ProcessingSummary.Applied.class, result.processingSummary());
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
        assertTrue(saved.pendingInputs().isEmpty());

        Event obsoleteInput = target == Flow.MEDIATION ? new ExecutionStarted() : new MediationStarted();
        for (Event ignoredInput : List.of(new Uninteresting(), obsoleteInput)) {
            var ignored = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(ignoredInput).join());

            assertInstanceOf(ProcessingSummary.Ignored.class, ignored.processingSummary());
            assertSame(saved.snapshot(), fixture.lastSaved().snapshot());
            assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
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
        var ready = applied(processor.handle(before, new Start())).nextSnapshot();
        var result = applied(processor.handle(ready, message));
        var after = result.nextSnapshot();

        assertInstanceOf(Finished.class, after.document().flow(Flow.EXECUTION).state());
        assertInstanceOf(Waiting.class, before.document().flow(Flow.EXECUTION).state());
        assertTrue(before.processedEventTypes().isEmpty());
        assertEquals(Set.of(Start.class, PeerMessage.class), after.processedEventTypes().get(Flow.EXECUTION));
        assertInstanceOf(HandleResult.Ignored.class, processor.handle(after, message));
        assertEquals(1, behavior.workAttempts);

        var restored = new TestProcessor(new Behavior());
        assertEquals(processor.handle(after, message), restored.handle(after, message));
        assertThrows(UnsupportedOperationException.class, () -> after.processedEventTypes().clear());
        assertThrows(UnsupportedOperationException.class, () -> after.processedEventTypes().get(Flow.EXECUTION).clear());
    }

    @Test
    void ignoringAnInputDoesNotPreventALaterValidInputOfTheSameType() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var before = initial(true);

        assertInstanceOf(HandleResult.Ignored.class, processor.handle(before, new PeerMessage(true)));
        assertEquals(0, behavior.workAttempts);
        assertTrue(before.processedEventTypes().isEmpty());

        var result = applied(processor.handle(before, new PeerMessage(false)));
        assertInstanceOf(Finished.class, result.nextSnapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(1, behavior.workAttempts);
    }

    @Test
    void aLaneIgnoresACopyWhoseTypeEveryInterestedFlowHandledWithoutSavingIt() {
        Map<Flow, Set<Class<?>>> history = Map.of(
                Flow.EXECUTION, Set.of(PeerMessage.class),
                Flow.MEDIATION, Set.of(PeerMessage.class),
                Flow.DECISION, Set.of(PeerMessage.class));
        var fixture = new LaneFixture(new Checkpoint<>(
                new StateMachineSnapshot<>(initial(true).document(), history),
                List.of(),
                List.of(),
                List.of()));
        var savesBefore = fixture.saved.size();

        var result = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new PeerMessage(false)).join());

        assertInstanceOf(ProcessingSummary.Ignored.class, result.processingSummary());
        assertEquals(savesBefore, fixture.saved.size());
    }

    private enum UnhandleableMessage { STOPPED_FLOW, UNREACHABLE_TYPE, NO_INTERESTED_FLOW }

    @ParameterizedTest
    @EnumSource(UnhandleableMessage.class)
    void aLaneIgnoresAMessageWhichCanNoLongerBeHandledWithoutSavingIt(UnhandleableMessage kind) {
        record Case(Document document, Event input) {
        }
        var ready = initial(true).document();
        var testCase = switch (kind) {
            // The decision flow's parent has failed, so the decision flow has stopped.
            case STOPPED_FLOW -> new Case(ready.withState(Flow.MEDIATION, new Failed()), new DecisionMessage());
            // The execution flow has finished, so no state which it can still reach handles this type.
            case UNREACHABLE_TYPE -> new Case(ready.withState(Flow.EXECUTION, new Finished()), new ExecutionMessage());
            case NO_INTERESTED_FLOW -> new Case(ready, new Uninteresting());
        };
        var fixture = new LaneFixture(new Checkpoint<>(
                new StateMachineSnapshot<>(testCase.document(), Map.of()),
                List.of(),
                List.of(),
                List.of()));
        var savesBefore = fixture.saved.size();

        var result = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(testCase.input()).join());

        assertInstanceOf(ProcessingSummary.Ignored.class, result.processingSummary());
        assertEquals(savesBefore, fixture.saved.size());
    }

    @Test
    void aMessageCanStillBeHandledUntilItsFlowHandledItsType() {
        var processor = new TestProcessor(new Behavior());
        var before = initial(false);
        var ready = new StateMachineSnapshot<>(before.document().withState(Flow.EXECUTION, new Ready()),
                Map.<Flow, Set<Class<?>>>of());
        var input = new PeerMessage(false);
        assertTrue(processor.canStillBeHandled(ready, input));

        var handled = applied(processor.handle(ready, input)).nextSnapshot();
        assertFalse(processor.canStillBeHandled(handled, input));
        assertInstanceOf(HandleResult.Ignored.class, processor.handle(handled, input));

        // No flow is interested in this input, so no flow can ever handle it.
        assertFalse(processor.canStillBeHandled(handled, new Uninteresting()));
    }

    @Test
    void aCommandCanAlwaysStillBeHandledBecauseHandlingRejectsItWithAReason() {
        var processor = new TestProcessor(new Behavior());
        // The decision flow has stopped because its parent failed.
        var parentFailed = new StateMachineSnapshot<Document, Flow>(
                initial(true).document().withState(Flow.MEDIATION, new Failed()),
                Map.of());

        assertFalse(processor.canStillBeHandled(parentFailed, new DecisionMessage()));
        assertTrue(processor.canStillBeHandled(parentFailed, new FinishDecisionCommand()));
        assertInstanceOf(HandleResult.Rejected.class, processor.handle(parentFailed, new FinishDecisionCommand()));
    }

    @Test
    void commandAdmissionFailureDoesNotEnterTheHandlerFailurePath() {
        var behavior = new Behavior();
        behavior.rejectCommands = true;
        var processor = new TestProcessor(behavior);

        assertInstanceOf(HandleResult.Rejected.class, processor.handle(initial(true), new FinishCommand()));
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

        var rejected = assertInstanceOf(ProcessingSummary.Rejected.class, unavailable.processingSummary());
        assertInstanceOf(IllegalStateException.class, rejected.cause());
        assertSame(before, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(0, fixture.behavior.mediationCommandAdmissions);
        assertEquals(0, fixture.behavior.preparedHandlers);
        assertFalse(fixture.lane.isClosed());

        fixture.lane.submit(new MediationStarted()).join();
        var ready = fixture.lastSaved().snapshot();
        assertInstanceOf(Ready.class, ready.document().flow(Flow.MEDIATION).state());
        assertInstanceOf(Waiting.class, ready.document().flow(Flow.EXECUTION).state());

        var unadmitted = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());

        rejected = assertInstanceOf(ProcessingSummary.Rejected.class, unadmitted.processingSummary());
        assertInstanceOf(IllegalArgumentException.class, rejected.cause());
        assertSame(ready, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(1, fixture.behavior.mediationCommandAdmissions);
        assertEquals(1, fixture.behavior.preparedHandlers);
        assertFalse(fixture.lane.isClosed());

        fixture.lane.submit(new ExecutionStarted()).join();
        assertInstanceOf(Ready.class, fixture.lastSaved().snapshot().document().flow(Flow.MEDIATION).state());
        assertEquals(0, fixture.behavior.workAttempts);

        var accepted = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());

        assertInstanceOf(ProcessingSummary.Applied.class, accepted.processingSummary());
        var saved = fixture.lastSaved();
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.MEDIATION).state());
        assertInstanceOf(Ready.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertEquals(Map.of(Flow.MEDIATION, Set.of(MediationStarted.class, FinishMediationCommand.class),
                        Flow.EXECUTION, Set.of(ExecutionStarted.class)),
                saved.snapshot().processedEventTypes());
        assertEquals(2, fixture.behavior.mediationCommandAdmissions);
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(0, fixture.behavior.failureReports);
        assertTrue(saved.pendingInputs().isEmpty());
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

        // A failed child leaves its parent and its siblings available.
        assertEquals(Set.of(Flow.MEDIATION),
                applied(processor.handle(childFailed, new ExecutionFinished())).nextSnapshot().processedEventTypes().keySet());
        assertEquals(Set.of(Flow.EXECUTION),
                applied(processor.handle(childFailed, new PeerMessage(false))).nextSnapshot().processedEventTypes().keySet());
        // A failed parent stops its children, but not its siblings.
        assertEquals(Set.of(Flow.EXECUTION),
                applied(processor.handle(parentFailed, new PeerMessage(false))).nextSnapshot().processedEventTypes().keySet());
        assertInstanceOf(HandleResult.Rejected.class, processor.handle(parentFailed, new FinishDecisionCommand()));
        assertInstanceOf(HandleResult.Ignored.class, processor.handle(parentFailed, new DecisionMessage()));
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
                processor.createRecoveryEffects(new StateMachineSnapshot<>(document, Map.of())));
        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1")),
                processor.createRecoveryEffects(new StateMachineSnapshot<>(
                        document.withState(Flow.MEDIATION, new Failed()), Map.of())));
        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1"), new TestEffect("recover:2")),
                processor.createRecoveryEffects(new StateMachineSnapshot<>(
                        document.withState(Flow.DECISION, new Failed()), Map.of())));
        assertTrue(processor.createRecoveryEffects(new StateMachineSnapshot<>(
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
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event>(
                snapshot,
                List.of(new DecisionMessage()),
                List.of(),
                List.of());
        var behavior = new Behavior();
        behavior.recoverWork = true;

        var fixture = new LaneFixture(checkpoint, behavior);

        assertEquals(List.of(new TestEffect("recover:0"), new TestEffect("recover:1")), fixture.effects);
        assertSame(snapshot, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var lateMessage = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new DecisionMessage()).join());
        assertInstanceOf(ProcessingSummary.Ignored.class, lateMessage.processingSummary());
        var command = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new FinishDecisionCommand()).join());
        var rejected = assertInstanceOf(ProcessingSummary.Rejected.class, command.processingSummary());
        assertInstanceOf(IllegalStateException.class, rejected.cause());
        assertSame(snapshot, fixture.lastSaved().snapshot());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var execution = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new PeerMessage(false)).join());

        assertInstanceOf(ProcessingSummary.Applied.class, execution.processingSummary());
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
        assertTrue(saved.pendingInputs().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void restoredChildFailureLeavesMediationAndExecutionAvailable() {
        var document = initial(true).document()
                .with(Flow.EXECUTION, FlowSnapshot.of(new Ready(), 1))
                .with(Flow.MEDIATION, FlowSnapshot.of(new Ready(), 2))
                .with(Flow.DECISION, FlowSnapshot.of(new Failed(), 3));
        var snapshot = new StateMachineSnapshot<>(document, Map.of(Flow.DECISION, Set.of(FailureReported.class)));
        var checkpoint = new Checkpoint<StateMachineSnapshot<Document, Flow>, Event>(
                snapshot,
                List.of(new DecisionMessage()),
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
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertTrue(fixture.lastSaved().pendingEffects().isEmpty());
        assertEquals(0, behavior.preparedHandlers);

        var execution = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new PeerMessage(false)).join());
        var mediation = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new ExecutionFinished()).join());

        assertInstanceOf(ProcessingSummary.Applied.class, execution.processingSummary());
        assertInstanceOf(ProcessingSummary.Applied.class, mediation.processingSummary());
        var saved = fixture.lastSaved();
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.EXECUTION).state());
        assertInstanceOf(Finished.class, saved.snapshot().document().flow(Flow.MEDIATION).state());
        assertEquals(2, saved.snapshot().document().flow(Flow.EXECUTION).data());
        assertEquals(3, saved.snapshot().document().flow(Flow.MEDIATION).data());
        assertSame(document.flow(Flow.DECISION), saved.snapshot().document().flow(Flow.DECISION));
        assertSame(document.flow(Flow.ROOT), saved.snapshot().document().flow(Flow.ROOT));
        assertEquals(Map.of(Flow.DECISION, Set.of(FailureReported.class),
                        Flow.EXECUTION, Set.of(PeerMessage.class), Flow.MEDIATION, Set.of(ExecutionFinished.class)),
                saved.snapshot().processedEventTypes());
        assertEquals(2, behavior.preparedHandlers);
        assertEquals(2, behavior.workAttempts);
        assertEquals(0, behavior.failureReports);
        assertEquals(recoveryEffects, fixture.effects);
        assertTrue(saved.pendingInputs().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @Test
    void failureIsRecordedOnlyInItsReceivingFlowWithinTheSameStep() {
        var behavior = new Behavior();
        behavior.failWork = true;
        var processor = new TestProcessor(behavior);
        var before = initial(true);

        var result = failed(processor.handle(before, new ExecutionFinished()));

        assertSame(behavior.workFailure, result.cause());
        assertInstanceOf(Failed.class, result.nextSnapshot().document().flow(Flow.MEDIATION).state());
        assertSame(before.document().flow(Flow.EXECUTION), result.nextSnapshot().document().flow(Flow.EXECUTION));
        assertSame(before.document().flow(Flow.DECISION), result.nextSnapshot().document().flow(Flow.DECISION));
        assertEquals(Map.of(Flow.MEDIATION, Set.of(FailureReported.class)), result.nextSnapshot().processedEventTypes());
        assertTrue(result.output().raisedInputs().isEmpty());
        assertEquals(1, behavior.workAttempts);
        assertEquals(1, behavior.failureReports);
        assertEquals(1, behavior.failureHandlerAttempts);
    }

    @Test
    void executionFailureReturnsItsParentReactionWithoutProcessingIt() {
        var behavior = new Behavior();
        behavior.failWork = true;
        var processor = new TestProcessor(behavior);

        var result = failed(processor.handle(initial(true), new PeerMessage(false)));

        assertInstanceOf(Waiting.class, result.nextSnapshot().document().flow(Flow.ROOT).state());
        assertEquals(List.of(new ChildFailed()), result.output().raisedInputs());
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
                () -> processor.handle(before, new PeerMessage(false)));

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
            assertEquals(List.of(new ExecutionFinished()),
                    saved.pendingInputs());
            observed.add(document);
        };

        var result = fixture.lane.submit(new ExecutionMessage()).join();

        var completed = assertInstanceOf(SubmissionResult.Completed.class, result);
        assertInstanceOf(ProcessingSummary.Applied.class, completed.processingSummary());
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
        assertTrue(saved.pendingInputs().isEmpty());
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
            assertEquals(List.of(new ExecutionFinished()),
                    saved.pendingInputs());
            observed.add(document);
            throw fixture.behavior.workFailure;
        };

        var completed = assertInstanceOf(SubmissionResult.Completed.class,
                fixture.lane.submit(new ExecutionMessage()).join());

        var failed = assertInstanceOf(ProcessingSummary.Failed.class, completed.processingSummary());
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
        assertTrue(saved.pendingInputs().isEmpty());
        assertTrue(saved.pendingEffects().isEmpty());

        for (Event event : List.of(new ExecutionFinished(), new DecisionMessage())) {
            var late = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(event).join());
            assertInstanceOf(ProcessingSummary.Ignored.class, late.processingSummary());
        }
        for (Event command : List.of(new FinishMediationCommand(), new FinishDecisionCommand())) {
            var late = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(command).join());
            assertInstanceOf(ProcessingSummary.Rejected.class, late.processingSummary());
        }

        assertSame(saved.snapshot(), fixture.lane.resume().join());
        assertEquals(saved, fixture.lastSaved());
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
        assertInstanceOf(ProcessingSummary.Failed.class, pending.processingSummary());
        assertEquals(0, fixture.behavior.parentReactions);
        assertTrue(fixture.effects.isEmpty());
        assertFalse(fixture.lastSaved().pendingInputs().isEmpty());

        fixture.failFailureCheckpoint = false;
        var resumed = fixture.lane.resume().join();

        assertInstanceOf(Failed.class, resumed.document().flow(Flow.ROOT).state());
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(1, fixture.behavior.failureHandlerAttempts);
        assertEquals(1, fixture.behavior.parentReactions);
        assertEquals(List.of(new TestEffect("failed-EXECUTION")), fixture.effects);
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertFalse(fixture.lane.isClosed());
    }

    @ParameterizedTest
    @EnumSource(value = FailureMode.class, names = {"MISSING", "THROWS"})
    void unrecordedFailureStopsTheLaneAndRetainsTheSavedInput(FailureMode mode) {
        var before = initial(false);
        var fixture = new LaneFixture(new Checkpoint<>(before, List.of(), List.of(), List.of()));
        var input = new ExecutionMessage();
        var pendingInput = input;
        var deferred = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(input).join());
        assertInstanceOf(ProcessingSummary.Deferred.class, deferred.processingSummary());
        assertEquals(List.of(pendingInput), fixture.lastSaved().pendingInputs());
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
        assertEquals(List.of(pendingInput), saved.pendingInputs());
        assertTrue(saved.pendingEffects().isEmpty());
        assertSame(before.document().flow(Flow.ROOT), saved.snapshot().document().flow(Flow.ROOT));
        assertSame(before.document().flow(Flow.MEDIATION), saved.snapshot().document().flow(Flow.MEDIATION));
        assertSame(before.document().flow(Flow.DECISION), saved.snapshot().document().flow(Flow.DECISION));

        var resumed = assertThrows(CompletionException.class, () -> fixture.lane.resume().join());
        var later = assertThrows(CompletionException.class, () -> fixture.lane.submit(new MediationStarted()).join());

        assertInstanceOf(RejectedExecutionException.class, resumed.getCause());
        assertInstanceOf(RejectedExecutionException.class, later.getCause());
        assertEquals(saved, fixture.lastSaved());
        assertEquals(1, fixture.behavior.workAttempts);
        assertEquals(1, fixture.behavior.failureReports);
        assertEquals(mode == FailureMode.MISSING ? 0 : 1, fixture.behavior.failureHandlerAttempts);
        assertEquals(0, fixture.behavior.parentReactions);
        assertTrue(fixture.effects.isEmpty());
    }

    @Test
    void aRoutedInputWithSeveralDestinationsIsAProgrammingError() {
        var behavior = new Behavior();
        var processor = new TestProcessor(behavior);
        var snapshot = initial(true);
        // Every flow has a rule for the failure report, which is meant only for direct delivery to the failing flow.
        var input = new FailureReported();

        assertTrue(processor.canStillBeHandled(snapshot, input));
        assertThrows(IllegalStateException.class, () -> processor.handle(snapshot, input));
        assertEquals(0, behavior.failureHandlerAttempts);
        assertEquals(0, behavior.preparedHandlers);
    }

    @Test
    void aMessageWithSeveralDestinationsIsSavedAndStopsTheLaneBeforeAnyHandlerRuns() {
        var fixture = new LaneFixture();
        var input = new FailureReported();

        assertThrows(CompletionException.class, () -> fixture.lane.submit(input).join());

        assertEquals(List.of(input), fixture.lastSaved().pendingInputs());
        assertTrue(fixture.lane.isClosed());
        assertEquals(0, fixture.behavior.failureHandlerAttempts);
        assertEquals(0, fixture.behavior.preparedHandlers);
    }

    @Test
    void applicationValidationRejectsBeforeRetentionWithoutFailingTheLane() {
        var fixture = new LaneFixture();

        var result = assertInstanceOf(SubmissionResult.Completed.class, fixture.lane.submit(new Invalid()).join());

        assertInstanceOf(ProcessingSummary.Rejected.class, result.processingSummary());
        assertEquals(1, fixture.saved.size());
        assertTrue(fixture.lastSaved().pendingInputs().isEmpty());
        assertFalse(fixture.lane.isClosed());
        assertEquals(0, fixture.behavior.failureReports);
    }

    private static <S, I> HandleResult.Applied<S, I> applied(HandleResult<S, I> result) {
        return switch (result) {
            case HandleResult.Applied<S, I> applied -> applied;
            default -> throw new AssertionError("Expected Applied, got " + result);
        };
    }

    private static <S, I> HandleResult.Failed<S, I> failed(HandleResult<S, I> result) {
        return switch (result) {
            case HandleResult.Failed<S, I> failed -> failed;
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
    private record FlowFinished(Flow flow) implements Event {}
    private record ExecutionMessage() implements Event {}
    private record ExecutionFinished() implements Event {}
    private record FailureReported() implements Event {}
    private record ChildFailed() implements Event {}
    private record Invalid() implements Event {}
    private record Uninteresting() implements Event {}
    private record TestEffect(String name) implements PersistableEffect, TestProto {}

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
        private boolean failExecution;
        private boolean failMediation;
        private boolean emitOutput;
        private boolean ignoreExecutionUntilMediationReady;
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

    private record Context(Behavior behavior, Document document) {}

    private static final class TestProcessor extends StateMachineProcessor<Document, Event, Flow> {
        private final Behavior behavior;

        private TestProcessor(Behavior behavior) {
            super(registrations(behavior));
            this.behavior = behavior;
        }

        @Override
        protected Optional<Event> onProcessingFailure(Document document,
                                                      Flow flowId,
                                                      Event input,
                                                      RuntimeException failure) {
            behavior.failureReports++;
            return behavior.failureMode == FailureMode.MISSING ? Optional.empty() : Optional.of(new FailureReported());
        }

        private static List<FlowRegistration<Document, Event, Flow>> registrations(Behavior behavior) {
            var registrations = flowRegistrations(behavior);
            for (Flow id : Flow.values()) {
                var parent = id == Flow.ROOT ? Optional.<Flow>empty() :
                        Optional.of(id == Flow.DECISION ? Flow.MEDIATION : Flow.ROOT);
                add(registrations, id, parent, behavior);
            }
            return registrations.build();
        }

        private static FlowRegistration<Document, Event, Flow> registration(Flow id,
                                                                           Optional<Flow> parent,
                                                                           Behavior behavior) {
            return add(flowRegistrations(behavior), id, parent, behavior).build().getFirst();
        }

        private static FlowRegistration.Builder<Document, Event, Flow, Document, Context> flowRegistrations(
                Behavior behavior) {
            return FlowRegistration.builder(Function.identity(), document -> {
                behavior.preparedHandlers++;
                return new Context(behavior, document);
            });
        }

        private static FlowRegistration.Builder<Document, Event, Flow, Document, Context> add(
                FlowRegistration.Builder<Document, Event, Flow, Document, Context> registrations,
                Flow id,
                Optional<Flow> parent,
                Behavior behavior) {
            return registrations.add(new Protocol(id, behavior),
                    id,
                    parent,
                    document -> document.flow(id),
                    (document, next) -> document.with(id, next));
        }
    }

    private static final class Protocol extends StateMachineDefinition<Stage, Integer, Event, Document, Context> {
        private final RuleTable<Stage, Integer, Event, Context> rules;
        private final Behavior behavior;

        private Protocol(Flow id, Behavior behavior) {
            this.behavior = behavior;
            var builder = RuleTable.<Stage, Integer, Event, Context>builder()
                    .rule(Waiting.class, FailureReported.class, failureHandler(id), Failed.class)
                    .rule(Ready.class, FailureReported.class, failureHandler(id), Failed.class);
            if (id == Flow.ROOT) {
                builder.rule(Waiting.class, ChildFailed.class, ParentFailureHandler::new, Failed.class);
            }
            // Each routed input type has exactly one destination; Start, PeerMessage and FinishCommand belong to
            // the execution flow, the other flows have their own types.
            if (id == Flow.EXECUTION) {
                builder.rule(Waiting.class, Start.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, PeerMessage.class, finishHandler(id), Finished.class)
                        .rule(Ready.class, FinishCommand.class, finishHandler(id), Finished.class)
                        .rule(Waiting.class, ExecutionStarted.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, ExecutionMessage.class, finishHandler(id), Finished.class);
            } else if (id == Flow.MEDIATION) {
                builder.rule(Waiting.class, MediationStarted.class, StartHandler::new, Ready.class)
                        .rule(Ready.class, FinishMediationCommand.class, finishHandler(id), Finished.class)
                        .rule(Ready.class, ExecutionFinished.class, finishHandler(id), Finished.class);
            } else if (id == Flow.DECISION) {
                builder.rule(Ready.class, DecisionMessage.class, finishHandler(id), Finished.class)
                        .rule(Ready.class, FinishDecisionCommand.class, finishHandler(id), Finished.class);
            }
            rules = builder.build();
        }

        private static <I extends Event> TransitionHandler.Factory<Ready, I, Finished, Integer, Context, Event>
        finishHandler(Flow id) {
            return (context, flow) -> new FinishHandler<>(id, context, flow);
        }

        private static <S extends Stage> TransitionHandler.Factory<S, FailureReported, Failed, Integer, Context, Event>
        failureHandler(Flow id) {
            return (context, flow) -> new FailureHandler<>(id, context, flow);
        }

        @Override
        public RuleTable<Stage, Integer, Event, Context> rules() {
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
        private final Flow flowId;

        private FinishHandler(Flow flowId, Context context, FlowSnapshot<Ready, Integer> flow) {
            super(context, flow);
            this.flowId = flowId;
        }

        @Override
        protected void verify(I event) {
            if (event instanceof PeerMessage message && (message.ignore() ||
                    (flowId == Flow.EXECUTION && context.behavior.ignoreExecutionUntilMediationReady &&
                            context.document.flow(Flow.MEDIATION).state() instanceof Waiting))) {
                ignoreInput();
            }
        }

        @Override
        protected Transition<Finished, Integer, Event> process(I event) {
            context.behavior.workAttempts++;
            if (context.behavior.failWork || (context.behavior.failExecution && flowId == Flow.EXECUTION) ||
                    (context.behavior.failMediation && flowId == Flow.MEDIATION)) {
                throw context.behavior.workFailure;
            }
            if (event instanceof ExecutionFinished) {
                context.behavior.beforeRaisedEvent.accept(context.document);
            }
            List<Effect> effects = new ArrayList<>();
            if (context.behavior.emitOutput) {
                effects.add(new TestEffect(flowId.name()));
            }
            List<ProtocolLogMessage> logs = context.behavior.emitOutput
                    ? List.of(new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text(flowId.name()))) : List.of();
            List<Event> raised = event instanceof ExecutionMessage ? List.of(new ExecutionFinished()) : List.of();
            if (context.behavior.emitOutput) {
                raised = List.of(new FlowFinished(flowId));
            }
            effects.addAll(logs);
            return new Transition<>(flow.to(new Finished(), data + 1),
                    effects,
                    raised);
        }
    }

    private static final class FailureHandler<S extends Stage>
            extends TransitionHandler<S, FailureReported, Failed, Integer, Context, Event> {
        private final Flow flowId;

        private FailureHandler(Flow flowId, Context context, FlowSnapshot<S, Integer> flow) {
            super(context, flow);
            this.flowId = flowId;
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
            List<Effect> effects = new ArrayList<>(List.of(new TestEffect("failed-" + flowId)));
            effects.addAll(context.behavior.emitOutput ? List.of(new ProtocolLogMessage.Shared(new ProtocolLogMessage.Text(flowId.name()))) : List.of());
            return new Transition<>(flow.to(new Failed(), data),
                    effects,
                    flowId == Flow.EXECUTION ? List.of(new ChildFailed()) : List.of());
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
        private final List<Checkpoint<StateMachineSnapshot<Document, Flow>, Event>> saved =
                new ArrayList<>();
        private final List<Effect> effects = new ArrayList<>();
        private final ProcessingLane<StateMachineSnapshot<Document, Flow>, Event> lane;
        private boolean failFailureCheckpoint;

        private LaneFixture() {
            this(new Checkpoint<>(
                    initial(true),
                    List.of(),
                    List.of(),
                    List.of()));
        }

        private LaneFixture(Checkpoint<StateMachineSnapshot<Document, Flow>, Event> checkpoint) {
            this(checkpoint, new Behavior());
        }

        private LaneFixture(Checkpoint<StateMachineSnapshot<Document, Flow>, Event> checkpoint,
                            Behavior behavior) {
            this.behavior = behavior;
            saved.add(checkpoint);
            var definition = ProcessingLaneDefinition.<StateMachineSnapshot<Document, Flow>, Event>builder(
                            Event.class,
                            snapshot -> new TestProcessor(behavior),
                            (snapshot, event) -> {
                                if (event instanceof Invalid) {
                                    throw new IllegalArgumentException("Wrong sender");
                                }
                            })
                    .build();
            lane = new ProcessingLane<>("document",
                    Optional.of(checkpoint),
                    Runnable::run,
                    mock(ScheduledExecutorService.class, org.mockito.Mockito.RETURNS_MOCKS),
                    new MockClock(),
                    definition,
                    input -> {},
                    next -> {
                        if (failFailureCheckpoint && next.snapshot().document().flow(Flow.EXECUTION).state() instanceof Failed) {
                            throw new IllegalStateException("Disk write failed");
                        }
                        saved.add(next);
                    },
                    (snapshot, effect) -> {
                        if (effect instanceof PersistableEffect) {
                            effects.add(effect);
                        }
                    },
                    (snapshot, persistenceFailure, setupFailure) -> {});
            lane.resume().join();
        }

        private Checkpoint<StateMachineSnapshot<Document, Flow>, Event> lastSaved() {
            return saved.getLast();
        }
    }
}
