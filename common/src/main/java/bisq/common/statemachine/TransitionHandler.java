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

import java.util.Optional;

/**
 * Validates one input and produces a replacement flow. A new handler is created per transition.
 *
 * @param <I> concrete incoming event type
 * @param <E> event family accepted and raised by the state machine
 */
public abstract class TransitionHandler<S, I extends E, T, D, C, E> {
    protected final C context;
    protected final FlowSnapshot<S, D> flow;
    protected final D data;
    private boolean ignored;

    protected TransitionHandler(C context, FlowSnapshot<S, D> flow) {
        this.context = context;
        this.flow = flow;
        data = flow.data();
    }

    public final Optional<Transition<T, D, E>> handle(I event) {
        verify(event);
        if (ignored) {
            return Optional.empty();
        }
        return Optional.of(process(event));
    }

    protected void verify(I event) {
    }

    /** Called during verification to consume this input without processing it or recording its type. */
    protected final void ignoreInput() {
        ignored = true;
    }

    protected abstract Transition<T, D, E> process(I event);

    @FunctionalInterface
    public interface Factory<S, I extends E, T, D, C, E> {
        TransitionHandler<S, I, T, D, C, E> create(C context, FlowSnapshot<S, D> flow);
    }
}
