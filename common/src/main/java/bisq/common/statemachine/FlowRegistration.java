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
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * What the processor needs from one flow, expressed for the complete document. A registration hides the flow's own
 * state and data types, so the processor can treat flows of different types alike. {@link Builder} creates the
 * registration for a {@link StateMachineDefinition}, which defines the flow's rules and their contracts.
 *
 * @param <M> complete business document
 * @param <E> event family accepted and raised by the state machine
 * @param <F> flow identifier
 */
@Getter
public abstract class FlowRegistration<M, E, F> {
    private final F id;
    private final Optional<F> parent;

    protected FlowRegistration(F id, Optional<F> parent) {
        this.id = id;
        this.parent = parent;
    }

    /** Starts the registrations of one processor; see {@link Builder}. */
    public static <M, E, F, A, C> Builder<M, E, F, A, C> builder(Function<M, A> checkContext,
                                                               Function<M, C> handlerContext) {
        return new Builder<>(checkContext, handlerContext);
    }

    public abstract boolean isInterestedIn(E event);

    public abstract boolean isFailed(M document);

    /** See {@link StateMachineDefinition#isProcessingEnabled}. */
    public abstract boolean isProcessingEnabled(M document);

    /** See {@link StateMachineDefinition#isChildProcessingEnabled}. */
    public abstract boolean isChildProcessingEnabled(M document);

    /**
     * Whether a state which this flow can still reach handles the event. Once false, the answer must stay false for
     * every later document, because the lane drops such a message without saving it.
     */
    public abstract boolean canReach(M document, E event);

    /** See {@link StateMachineDefinition#shouldDiscard}. */
    public abstract boolean shouldDiscard(M document, E event);

    /**
     * Whether the flow can apply the event now: its rule table has a rule for the current state and the event, and
     * the flow's own readiness rule agrees ({@link StateMachineDefinition#isReady}).
     */
    public abstract boolean canHandle(M document, E event);

    /** See {@link StateMachineDefinition#admit}. */
    public abstract void admit(M document, E event);

    public abstract Optional<FlowUpdate<M, E>> apply(M document, E event);

    /** See {@link StateMachineDefinition#recoveryEffects}. */
    public abstract List<Effect> recoveryEffects(M document);

    /**
     * Registers the flows of one processor. All flows of a processor share one check context and one handler
     * context, so {@link FlowRegistration#builder} takes both once.
     * <p>
     * Each flow is bound to a typed part of the complete document, without sharing writable sibling models. Checks
     * receive a fresh check context for every call. The handler context is prepared only when a ready, admitted
     * input is applied.
     *
     * @param <M> complete business document
     * @param <E> event family accepted and raised by the state machine
     * @param <F> flow identifier
     * @param <A> check context, shared by all flows
     * @param <C> handler context, shared by all flows
     */
    public static final class Builder<M, E, F, A, C> {
        private final Function<M, A> checkContext;
        private final Function<M, C> handlerContext;
        private final List<FlowRegistration<M, E, F>> registrations = new ArrayList<>();

        private Builder(Function<M, A> checkContext, Function<M, C> handlerContext) {
            this.checkContext = checkContext;
            this.handlerContext = handlerContext;
        }

        /**
         * @param reader returns the flow's part of the document
         * @param writer returns a document in which only the flow's part is replaced
         */
        public <S, D> Builder<M, E, F, A, C> add(StateMachineDefinition<S, D, E, A, C> definition,
                                                 F id,
                                                 Optional<F> parent,
                                                 Function<M, FlowSnapshot<? extends S, D>> reader,
                                                 BiFunction<M, FlowSnapshot<? extends S, D>, M> writer) {
            registrations.add(bind(definition, id, parent, reader, writer));
            return this;
        }

        /** Returns one registration per added flow, in the order of the calls to {@link #add}. */
        public List<FlowRegistration<M, E, F>> build() {
            return List.copyOf(registrations);
        }

        /** The registration converts between the document and the flow's part of it and forwards to the definition. */
        private <S, D> FlowRegistration<M, E, F> bind(StateMachineDefinition<S, D, E, A, C> definition,
                                                      F id,
                                                      Optional<F> parent,
                                                      Function<M, FlowSnapshot<? extends S, D>> reader,
                                                      BiFunction<M, FlowSnapshot<? extends S, D>, M> writer) {
            return new FlowRegistration<>(id, parent) {
                @Override
                public boolean isInterestedIn(E event) {
                    return definition.rules().isInterestedIn(event);
                }

                @Override
                public boolean isFailed(M document) {
                    return definition.isFailed(reader.apply(document).state());
                }

                @Override
                public boolean isProcessingEnabled(M document) {
                    return definition.isProcessingEnabled(reader.apply(document).state());
                }

                @Override
                public boolean isChildProcessingEnabled(M document) {
                    return definition.isChildProcessingEnabled(reader.apply(document).state());
                }

                @Override
                public boolean canReach(M document, E event) {
                    return definition.rules().canReach(reader.apply(document).state(), event);
                }

                @Override
                public boolean shouldDiscard(M document, E event) {
                    return definition.shouldDiscard(checkContext.apply(document), reader.apply(document), event);
                }

                @Override
                public boolean canHandle(M document, E event) {
                    FlowSnapshot<? extends S, D> flow = reader.apply(document);
                    return definition.rules().canHandle(flow.state(), event) &&
                            definition.isReady(checkContext.apply(document), flow, event);
                }

                @Override
                public void admit(M document, E event) {
                    definition.admit(checkContext.apply(document), reader.apply(document), event);
                }

                @Override
                public Optional<FlowUpdate<M, E>> apply(M document, E event) {
                    return definition.rules().apply(handlerContext.apply(document), reader.apply(document), event)
                            .map(transition -> new FlowUpdate<>(writer.apply(document, transition.next()),
                                    transition.output()));
                }

                @Override
                public List<Effect> recoveryEffects(M document) {
                    return definition.recoveryEffects(reader.apply(document));
                }
            };
        }
    }
}
