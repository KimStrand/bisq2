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

package bisq.common.processing;

import java.util.List;

/**
 * What handling one input produces, besides the next snapshot. Each part has its own place in the checkpoint:
 * <ul>
 * <li>effects: work outside the entity. The lane saves persistable effects until they are done and attempts
 * memory-only protocol log messages after them, including when effect execution fails.</li>
 * <li>raised inputs: inputs to handle next. The lane saves them until they are handled.</li>
 * <li>timer changes: inputs to handle at a certain time. The lane saves their deadlines until they are due or
 * cancelled.</li>
 * </ul>
 *
 * @param <I> incoming or raised input accepted by the processor
 */
public record ProcessingOutput<I>(List<Effect> effects,
                                  List<I> raisedInputs,
                                  List<TimerChange> timerChanges) {
    public ProcessingOutput {
        effects = List.copyOf(effects);
        raisedInputs = List.copyOf(raisedInputs);
        timerChanges = List.copyOf(timerChanges);
    }

    public ProcessingOutput(List<Effect> effects, List<I> raisedInputs) {
        this(effects, raisedInputs, List.of());
    }
}
