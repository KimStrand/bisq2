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
import bisq.common.processing.Processor;

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
 * Synchronous processing of one input by its destination flow. Each input type that this processor routes has at
 * most one registered destination; flows pass facts to each other as raised inputs. A failure report is not routed:
 * it goes directly to the failing flow.
 * The lane supplies the current document and processing history on every call and owns all pending work,
 * checkpointing and outgoing dispatch. This processor has no live snapshot or queue of its own.
 * Applications can override how rejections and processing failures are classified, and how a processing failure
 * is reported. Command admission belongs to each flow's {@code admit}.
 *
 * @param <M> complete business document
 * @param <E> event family accepted and raised by the state machine
 * @param <F> flow identifier
 */
public class StateMachineProcessor<M, E extends Event, F>
        implements Processor<StateMachineSnapshot<M, F>, E> {
    private final Map<F, FlowRegistration<M, E, F>> flows;
    private final FlowHierarchy<F> hierarchy;

    public StateMachineProcessor(List<? extends FlowRegistration<M, E, F>> registrations) {
        LinkedHashMap<F, FlowRegistration<M, E, F>> byId = new LinkedHashMap<F, FlowRegistration<M, E, F>>();
        for (FlowRegistration<M, E, F> registration : registrations) {
            checkArgument(byId.putIfAbsent(registration.getId(), registration) == null,
                    "Duplicate flow registration: %s",
                    registration.getId());
        }
        LinkedHashMap<F, F> parents = new LinkedHashMap<F, F>();
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
    // Recovery and whole-input processing
    /* --------------------------------------------------------------------- */

    @Override
    public final List<Effect> createRecoveryEffects(StateMachineSnapshot<M, F> snapshot) {
        return flows.values().stream()
                .filter(flow -> !isStopped(snapshot.document(), flow))
                .flatMap(flow -> flow.recoveryEffects(snapshot.document()).stream())
                .toList();
    }

    /**
     * True for a command, which {@link #handle} rejects with a reason instead of ignoring it. For a message, false if
     * no flow is interested in it, if its flow has stopped, or if a final discard rule applies
     * ({@link #isFinallyDiscarded}). None of these can change back: interest depends only on the type and stopping is
     * final. The flow's own rule ({@link FlowRegistration#shouldDiscard}) is not used, because it need not be final.
     * <p>
     * This method must not throw: the lane calls it before saving a message. For several destinations, which is a
     * programming error, it returns true, so the lane saves the message and {@link #handle} stops the lane before any
     * handler runs.
     */
    @Override
    public final boolean canStillBeHandled(StateMachineSnapshot<M, F> snapshot, E input) {
        if (input instanceof Command) {
            return true;
        }
        List<FlowRegistration<M, E, F>> destinations = findDestinations(input);
        if (destinations.size() != 1) {
            return !destinations.isEmpty();
        }
        FlowRegistration<M, E, F> flow = destinations.getFirst();
        return !isStopped(snapshot.document(), flow) && !isFinallyDiscarded(snapshot, flow, input);
    }

    /**
     * Processes one input attempt in this order: find the destination flow, check whether the flow has stopped,
     * apply the discard rules, check readiness, run command admission, apply the handler and record the input's
     * type as processed.
     * <p>
     * Without a destination or with a stopped flow, a command is rejected and a message is ignored. While the flow
     * is not ready, a command is rejected and a message is deferred. Several destinations are a programming error:
     * this method throws, and the lane stops before any handler runs.
     */
    @Override
    public final HandleResult<StateMachineSnapshot<M, F>, E> handle(StateMachineSnapshot<M, F> snapshot, E input) {
        List<FlowRegistration<M, E, F>> destinations = findDestinations(input);
        if (destinations.size() > 1) {
            throw new IllegalStateException("Input " + input.getClass().getSimpleName() +
                    " has several destinations " + destinations.stream().map(FlowRegistration::getId).toList() +
                    "; each routed input type may have at most one");
        }
        if (destinations.isEmpty()) {
            return rejectCommandOrIgnoreMessage(input,
                    "No destination for command " + input.getClass().getSimpleName());
        }
        FlowRegistration<M, E, F> flow = destinations.getFirst();
        M document = snapshot.document();

        if (isStopped(document, flow)) {
            return rejectCommandOrIgnoreMessage(input, input.getClass().getSimpleName() +
                    " is not available: processing is disabled for flow " + flow.getId() + " or an ancestor");
        }

        if (discard(snapshot, flow, input)) {
            return new HandleResult.Ignored<>();
        }

        if (input instanceof Command) {
            try {
                checkState(flow.canHandle(document, input),
                        "%s is not available in the current state",
                        input.getClass().getSimpleName());
                flow.admit(document, input);
            } catch (RuntimeException failure) {
                return new HandleResult.Rejected<>(onInputRejected(failure));
            }
        } else if (!flow.canHandle(document, input)) {
            return new HandleResult.Deferred<>();
        }

        Optional<FlowUpdate<M, E>> result;
        try {
            result = flow.apply(document, input);
        } catch (RuntimeException failure) {
            return processFailure(snapshot, flow, input, classifyProcessingFailure(failure));
        }
        if (result.isEmpty()) {
            return new HandleResult.Ignored<>();
        }
        FlowUpdate<M, E> update = result.orElseThrow();
        return new HandleResult.Applied<>(recordTransition(snapshot, flow, input, update.nextDocument()),
                update.output());
    }

    private List<FlowRegistration<M, E, F>> findDestinations(E input) {
        return flows.values().stream()
                .filter(flow -> flow.isInterestedIn(input))
                .toList();
    }

    private HandleResult<StateMachineSnapshot<M, F>, E> rejectCommandOrIgnoreMessage(E input, String reason) {
        return input instanceof Command
                ? new HandleResult.Rejected<>(onInputRejected(new IllegalStateException(reason)))
                : new HandleResult.Ignored<>();
    }


    /* --------------------------------------------------------------------- */
    // Application hooks
    /* --------------------------------------------------------------------- */

    /**
     * Lets applications classify an exception thrown while a flow applies an input, before failure handling and
     * before callers see it. Rejections before applying use {@link #onInputRejected} instead.
     */
    protected RuntimeException classifyProcessingFailure(RuntimeException failure) {
        return failure;
    }

    protected RuntimeException onInputRejected(RuntimeException failure) {
        return failure;
    }

    /**
     * Supplies a failure event for the flow which failed to apply the input. It is handled within the same handle
     * call, never broadcast or queued. Its transition must establish that flow's failed state; raised reactions
     * return to the lane. An absent report, an ignored report or a failed failure handler leaves no valid failure
     * result, so the original exception escapes and the lane stops. The original input is not recorded as
     * successfully processed.
     *
     * @param failure the exception which {@link #classifyProcessingFailure} returned
     */
    protected Optional<E> onProcessingFailure(M document, F flowId, E input, RuntimeException failure) {
        return Optional.empty();
    }


    /* --------------------------------------------------------------------- */
    // Failure transition and processing history
    /* --------------------------------------------------------------------- */

    private HandleResult<StateMachineSnapshot<M, F>, E> processFailure(
            StateMachineSnapshot<M, F> snapshot,
            FlowRegistration<M, E, F> flow,
            E input,
            RuntimeException failure) {
        try {
            E report = onProcessingFailure(snapshot.document(), flow.getId(), input, failure)
                    .orElseThrow(() -> failure);
            checkState(!discard(snapshot, flow, report) && flow.canHandle(snapshot.document(), report),
                    "Flow %s cannot handle its failure report", flow.getId());
            FlowUpdate<M, E> update = flow.apply(snapshot.document(), report)
                    .orElseThrow(() -> new IllegalStateException("Flow ignored its failure report: " + flow.getId()));
            checkState(flow.isFailed(update.nextDocument()),
                    "Failure report did not fail flow %s", flow.getId());
            return new HandleResult.Failed<>(recordTransition(snapshot, flow, report, update.nextDocument()),
                    update.output(),
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
            FlowRegistration<M, E, F> flow,
            E event,
            M nextDocument) {
        LinkedHashMap<F, Set<Class<?>>> history = new LinkedHashMap<>(snapshot.processedEventTypes());
        HashSet<Class<?>> types = new HashSet<>(history.getOrDefault(flow.getId(), Set.of()));
        types.add(event.getClass());
        history.put(flow.getId(), types);
        return new StateMachineSnapshot<>(nextDocument, history);
    }

    private boolean discard(StateMachineSnapshot<M, F> snapshot, FlowRegistration<M, E, F> flow, E event) {
        return isFinallyDiscarded(snapshot, flow, event) || flow.shouldDiscard(snapshot.document(), event);
    }

    /**
     * The final discard rules: the flow already processed the input's type, or the input is a message which no state
     * the flow can still reach handles. Neither can change back, because processing history only grows and reachable
     * states only shrink. {@link #canStillBeHandled} may therefore use them before a message is saved.
     */
    private boolean isFinallyDiscarded(StateMachineSnapshot<M, F> snapshot, FlowRegistration<M, E, F> flow, E event) {
        return hasProcessedType(snapshot, flow, event) ||
                (!(event instanceof Command) && !flow.canReach(snapshot.document(), event));
    }

    private boolean hasProcessedType(StateMachineSnapshot<M, F> snapshot, FlowRegistration<M, E, F> flow, E event) {
        return snapshot.processedEventTypes().getOrDefault(flow.getId(), Set.of()).contains(event.getClass());
    }

    private boolean isStopped(M document, FlowRegistration<M, E, F> flow) {
        if (!flow.isProcessingEnabled(document)) {
            return true;
        }
        Optional<F> parent = hierarchy.parentOf(flow.getId());
        while (parent.isPresent()) {
            FlowRegistration<M, E, F> ancestor = requireFlow(parent.orElseThrow());
            if (!ancestor.isChildProcessingEnabled(document)) {
                return true;
            }
            parent = hierarchy.parentOf(ancestor.getId());
        }
        return false;
    }

    private FlowRegistration<M, E, F> requireFlow(F id) {
        FlowRegistration<M, E, F> flow = flows.get(id);
        checkArgument(flow != null, "Flow is not registered: %s", id);
        return flow;
    }
}
