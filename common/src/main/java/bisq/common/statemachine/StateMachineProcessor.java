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
import bisq.common.processing.Processor;
import bisq.common.processing.StepResult;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

/**
 * Synchronous processing of one addressed flow, using the existing definitions and transition handlers.
 * The lane supplies the current document and processing history on every call and owns all pending work,
 * checkpointing and outgoing dispatch. This processor has no live snapshot or queue of its own.
 * Applications can override input validation, rejection classification and processing-failure handling.
 *
 * @param <M> complete business document
 * @param <E> event family
 * @param <F> identifier of the destination flow
 */
public class StateMachineProcessor<M, E extends Event, F>
        implements Processor<StateMachineSnapshot<M, F>, E, Delivery<F, E>> {
    private final Map<F, FlowRegistration<M, E, F>> flows;
    private final FlowHierarchy<F> hierarchy;

    public StateMachineProcessor(List<? extends FlowRegistration<M, E, F>> registrations) {
        var byId = new LinkedHashMap<F, FlowRegistration<M, E, F>>();
        for (var registration : registrations) {
            checkArgument(byId.putIfAbsent(registration.getId(), registration) == null,
                    "Duplicate flow registration: %s",
                    registration.getId());
        }
        var parents = new LinkedHashMap<F, F>();
        byId.values().forEach(registration -> registration.getParent().ifPresent(parent -> {
            checkArgument(byId.containsKey(parent),
                    "Parent %s of flow %s is not registered",
                    parent,
                    registration.getId());
            parents.put(registration.getId(), parent);
        }));
        flows = Collections.unmodifiableMap(byId);
        hierarchy = new FlowHierarchy<>(parents);
    }


    /* --------------------------------------------------------------------- */
    // Recovery, routing and step processing
    /* --------------------------------------------------------------------- */

    @Override
    public final void validateRestoration(StateMachineSnapshot<M, F> snapshot, List<Delivery<F, E>> pendingWork) {
        snapshot.processedEventTypes().keySet().forEach(this::requireFlow);
        pendingWork.forEach(delivery -> requireFlow(delivery.flow()));
    }

    @Override
    public final List<Effect> recoveryEffects(StateMachineSnapshot<M, F> snapshot) {
        return flows.values().stream()
                .filter(flow -> !isStopped(snapshot.document(), flow.getId()))
                .flatMap(flow -> flow.recoveryEffects(snapshot.document()).stream())
                .toList();
    }

    @Override
    public final List<Delivery<F, E>> route(StateMachineSnapshot<M, F> snapshot, E event) {
        return flows.values().stream()
                .filter(flow -> flow.isInterestedIn(event))
                .map(flow -> new Delivery<>(flow.getId(), event))
                // Commands reach the step so it can report rejection or an already-processed input.
                .filter(delivery -> event instanceof Command ||
                        (!isStopped(snapshot.document(), delivery.flow()) && !discard(snapshot, delivery)))
                .toList();
    }

    @Override
    public final StepResult<StateMachineSnapshot<M, F>, E> processStep(
            StateMachineSnapshot<M, F> snapshot,
            Delivery<F, E> delivery) {
        var flow = requireFlow(delivery.flow());
        boolean command = delivery.event() instanceof Command;
        if (isStopped(snapshot.document(), delivery.flow())) {
            return command
                    ? new StepResult.Rejected<>(onInputRejected(new IllegalStateException(
                            delivery.event().getClass().getSimpleName() +
                                    " is not available: processing is disabled for flow " + delivery.flow() +
                                    " or an ancestor")))
                    : new StepResult.Ignored<>();
        }
        if (discard(snapshot, delivery)) {
            return new StepResult.Ignored<>();
        }
        if (command) {
            try {
                checkState(flow.canHandle(snapshot.document(), delivery.event()),
                        "%s is not available in the current state",
                        delivery.event().getClass().getSimpleName());
                admitCommand(snapshot.document(), delivery.event());
                flow.admit(snapshot.document(), delivery.event());
            } catch (RuntimeException failure) {
                return new StepResult.Rejected<>(onInputRejected(failure));
            }
        } else if (!flow.canHandle(snapshot.document(), delivery.event())) {
            return new StepResult.Deferred<>();
        }

        Optional<StepResult.Applied<M, E>> result;
        try {
            result = apply(snapshot.document(), delivery);
        } catch (RuntimeException failure) {
            return processFailure(snapshot, delivery, failure);
        }
        if (result.isEmpty()) {
            return new StepResult.Ignored<>();
        }
        var applied = result.orElseThrow();
        return new StepResult.Applied<>(recordTransition(snapshot, delivery, applied.nextSnapshot()), applied.output());
    }


    /* --------------------------------------------------------------------- */
    // Application hooks
    /* --------------------------------------------------------------------- */

    /** Lets applications classify handler exceptions while keeping admission and coordination failures separate. */
    protected Optional<StepResult.Applied<M, E>> apply(M document, Delivery<F, E> delivery) {
        return requireFlow(delivery.flow()).apply(document, delivery.event());
    }

    /** Application admission at the command's turn, including a saved first command after recovery. */
    protected void admitCommand(M document, E event) {
    }

    protected RuntimeException onInputRejected(RuntimeException failure) {
        return failure;
    }

    /**
     * Supplies a failure event for this delivery's flow. It is handled within the same step, never broadcast or
     * queued. Its transition must establish that flow's failed state; raised reactions return to the lane.
     * An absent report, an ignored report or a failed failure handler leaves no valid failure result, so the
     * original exception escapes and the lane stops. The original input is not recorded as successfully processed.
     */
    protected Optional<E> onProcessingFailure(M document, Delivery<F, E> delivery, RuntimeException failure) {
        return Optional.empty();
    }


    /* --------------------------------------------------------------------- */
    // Failure transition and processing history
    /* --------------------------------------------------------------------- */

    private StepResult<StateMachineSnapshot<M, F>, E> processFailure(
            StateMachineSnapshot<M, F> snapshot,
            Delivery<F, E> delivery,
            RuntimeException failure) {
        try {
            E event = onProcessingFailure(snapshot.document(), delivery, failure).orElseThrow(() -> failure);
            var report = new Delivery<>(delivery.flow(), event);
            var flow = requireFlow(delivery.flow());
            checkState(!discard(snapshot, report) && flow.canHandle(snapshot.document(), event),
                    "Flow %s cannot handle its failure report", delivery.flow());
            var applied = apply(snapshot.document(), report)
                    .orElseThrow(() -> new IllegalStateException("Flow ignored its failure report: " + delivery.flow()));
            checkState(flow.isFailed(applied.nextSnapshot()),
                    "Failure report did not fail flow %s", delivery.flow());
            return new StepResult.Failed<>(recordTransition(snapshot, report, applied.nextSnapshot()),
                    applied.output(),
                    failure);
        } catch (RuntimeException | Error reportingFailure) {
            if (reportingFailure != failure) {
                failure.addSuppressed(reportingFailure);
            }
            throw failure;
        }
    }

    private StateMachineSnapshot<M, F> recordTransition(
            StateMachineSnapshot<M, F> snapshot,
            Delivery<F, E> delivery,
            M nextDocument) {
        var history = new LinkedHashMap<>(snapshot.processedEventTypes());
        var types = new HashSet<>(history.getOrDefault(delivery.flow(), Set.of()));
        types.add(delivery.event().getClass());
        history.put(delivery.flow(), types);
        return new StateMachineSnapshot<>(nextDocument, history);
    }

    private boolean discard(StateMachineSnapshot<M, F> snapshot, Delivery<F, E> delivery) {
        return snapshot.processedEventTypes().getOrDefault(delivery.flow(), Set.of())
                .contains(delivery.event().getClass()) ||
                requireFlow(delivery.flow()).discard(snapshot.document(), delivery.event(),
                        delivery.event() instanceof Command);
    }

    private boolean isStopped(M document, F flow) {
        if (!requireFlow(flow).isProcessingEnabled(document)) {
            return true;
        }
        var parent = hierarchy.parentOf(flow);
        while (parent.isPresent()) {
            F ancestor = parent.orElseThrow();
            if (!requireFlow(ancestor).isChildProcessingEnabled(document)) {
                return true;
            }
            parent = hierarchy.parentOf(ancestor);
        }
        return false;
    }

    private FlowRegistration<M, E, F> requireFlow(F id) {
        var flow = flows.get(id);
        checkArgument(flow != null, "Flow is not registered: %s", id);
        return flow;
    }
}
