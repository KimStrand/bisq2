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
import bisq.common.processing.LogEntry;
import bisq.common.processing.ProcessingOutput;

import java.util.List;

/** A typed next flow snapshot with outgoing work, narration and raised events for the coordinating owner. */
public class Transition<S, D, E> {
    private final FlowSnapshot<S, D> next;
    private final ProcessingOutput<E> output;

    public Transition(FlowSnapshot<S, D> next, ProcessingOutput<E> output) {
        this.next = next;
        this.output = output;
    }

    public Transition(FlowSnapshot<S, D> next,
                      List<Effect> effects,
                      List<LogEntry> logs,
                      List<E> raisedEvents) {
        this(next, new ProcessingOutput<>(effects, logs, raisedEvents));
    }

    public Transition(FlowSnapshot<S, D> next, List<Effect> effects, List<LogEntry> logs) {
        this(next, effects, logs, List.of());
    }

    public Transition(FlowSnapshot<S, D> next, List<Effect> effects) {
        this(next, effects, List.of(), List.of());
    }

    public final FlowSnapshot<S, D> next() {
        return next;
    }

    public final ProcessingOutput<E> output() {
        return output;
    }
}
