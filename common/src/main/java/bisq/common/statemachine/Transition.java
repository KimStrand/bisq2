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
import bisq.common.processing.TimerChange;

import java.util.List;

/**
 * The result of a handler: the flow's next state and data, with the effects, the raised events and the timer
 * changes.
 *
 * @param <T> target state
 * @param <D> flow data
 * @param <E> event family accepted and raised by the state machine
 */
public record Transition<T, D, E>(FlowSnapshot<T, D> next, ProcessingOutput<E> output) {
    /** @param timerChanges the timers which the transition starts, replaces or cancels; see {@link TimerChange} */
    public Transition(FlowSnapshot<T, D> next,
                      List<Effect> effects,
                      List<E> raisedEvents,
                      List<TimerChange> timerChanges) {
        this(next, new ProcessingOutput<>(effects, raisedEvents, timerChanges));
    }

    public Transition(FlowSnapshot<T, D> next, List<Effect> effects, List<E> raisedEvents) {
        this(next, effects, raisedEvents, List.of());
    }

    public Transition(FlowSnapshot<T, D> next, List<Effect> effects) {
        this(next, effects, List.of());
    }
}
