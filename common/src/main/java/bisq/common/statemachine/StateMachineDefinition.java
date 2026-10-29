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

import bisq.common.processing.Effect;
import bisq.common.processing.ProcessingOutput;
import bisq.common.processing.StepResult;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * A stateless flow definition. The owner supplies a snapshot and receives its replacement.
 * The processor records successfully processed event types and discards subsequent inputs of those types per flow.
 * Every transition produces effects, logs and raised events through {@link ProcessingOutput}.
 *
 * @param <S> flow state family
 * @param <D> accumulated flow data
 * @param <E> accepted event family
 * @param <A> current read-only context for input policies
 * @param <C> handler context, prepared from the current snapshot
 */
public abstract class StateMachineDefinition<S, D, E, A, C> {
    protected abstract RuleTable<S, D, E, C> rules();

    public final boolean isInterestedIn(E event) {
        return rules().isInterestedIn(event);
    }

    public final boolean canHandle(S state, E event) {
        return rules().canHandle(state, event);
    }

    public final boolean canHandle(S state, Class<? extends E> eventType) {
        return rules().canHandle(state, eventType);
    }

    public final boolean canReach(S state, E event) {
        return rules().canReach(state, event);
    }

    public final boolean isTerminal(S state) {
        return rules().isTerminal(state);
    }

    public final Optional<Transition<? extends S, D, E>> apply(C context, FlowSnapshot<? extends S, D> flow, E event) {
        return rules().apply(context, flow, event);
    }


    /* --------------------------------------------------------------------- */
    // Flow policies
    /* --------------------------------------------------------------------- */

    public abstract boolean isFailed(S state);

    public boolean isProcessingEnabled(S state) {
        return !isFailed(state);
    }

    public boolean isChildProcessingEnabled(S state) {
        return !isFailed(state) && isProcessingEnabled(state);
    }

    /** Domain rules supplement the processor's successful event-type deduplication. */
    public final boolean discard(A context, FlowSnapshot<? extends S, D> flow, E event, boolean command) {
        return (!command && !canReach(flow.state(), event)) || shouldDiscard(context, flow, event, command);
    }

    protected boolean shouldDiscard(A context, FlowSnapshot<? extends S, D> flow, E event, boolean command) {
        return false;
    }

    /**
     * Reports readiness without changing a flow or performing work. The processor rejects an unavailable command
     * or defers a still-relevant message or raised event. The lane retains deferred work for later.
     * Payload verification stays in handlers.
     */
    public boolean canHandle(A context, FlowSnapshot<? extends S, D> flow, E event) {
        return canHandle(flow.state(), event);
    }

    /**
     * Called by the owner for commands after checking readiness, before handler processing.
     * Implementations may reject by throwing, but must not perform work or change a flow's data.
     */
    public void admit(A context, FlowSnapshot<? extends S, D> flow, E event) {
    }

    /** Describes effects for the lane to re-establish work required by a restored flow without replaying its handlers. */
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends S, D> flow) {
        return List.of();
    }


    /* --------------------------------------------------------------------- */
    // Document binding
    /* --------------------------------------------------------------------- */

    /**
     * Binds this definition to a typed part of the complete document, without sharing writable sibling models.
     * Input policies receive fresh views; handler context is prepared only when applying a ready, admitted input.
     */
    public final <M, F> FlowRegistration<M, E, F> bind(
            F id,
            Optional<F> parent,
            Function<M, FlowSnapshot<? extends S, D>> reader,
            BiFunction<M, FlowSnapshot<? extends S, D>, M> writer,
            Function<M, A> admissionContext,
            BiFunction<M, E, C> handlerContext) {
        return new FlowRegistration<>(id, parent) {
            @Override
            public boolean isInterestedIn(E event) {
                return StateMachineDefinition.this.isInterestedIn(event);
            }

            @Override
            public boolean isFailed(M snapshot) {
                return StateMachineDefinition.this.isFailed(reader.apply(snapshot).state());
            }

            @Override
            public boolean isProcessingEnabled(M snapshot) {
                return StateMachineDefinition.this.isProcessingEnabled(reader.apply(snapshot).state());
            }

            @Override
            public boolean isChildProcessingEnabled(M snapshot) {
                return StateMachineDefinition.this.isChildProcessingEnabled(reader.apply(snapshot).state());
            }

            @Override
            public boolean discard(M snapshot, E event, boolean command) {
                return StateMachineDefinition.this.discard(admissionContext.apply(snapshot),
                        reader.apply(snapshot),
                        event,
                        command);
            }

            @Override
            public boolean canHandle(M snapshot, E event) {
                return StateMachineDefinition.this.canHandle(admissionContext.apply(snapshot),
                        reader.apply(snapshot),
                        event);
            }

            @Override
            public void admit(M snapshot, E event) {
                StateMachineDefinition.this.admit(admissionContext.apply(snapshot), reader.apply(snapshot), event);
            }

            @Override
            public Optional<StepResult.Applied<M, E>> apply(M snapshot, E event) {
                return StateMachineDefinition.this.apply(handlerContext.apply(snapshot, event),
                        reader.apply(snapshot),
                        event)
                        .map(transition -> new StepResult.Applied<>(writer.apply(snapshot, transition.next()),
                                transition.output()));
            }

            @Override
            public List<Effect> recoveryEffects(M snapshot) {
                return StateMachineDefinition.this.recoveryEffects(reader.apply(snapshot));
            }
        };
    }
}
