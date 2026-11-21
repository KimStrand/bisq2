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

package bisq.common.processing.lane;

import bisq.common.processing.PersistableEffect;
import bisq.common.processing.ScheduledInput;

import java.util.List;

/**
 * One owner's snapshot and unfinished inputs, effects and deadlines, saved together.
 * Input destinations are selected by the processor on each attempt, never stored by the lane.
 * Lists are copied; the snapshot and contained values must remain stable after construction.
 * Live processors, setup completion, caller futures and best-effort logs are not persisted.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 * @param snapshot the snapshot of the owner
 * @param pendingInputs the inputs which wait for handling
 * @param pendingEffects the effects which are not done yet
 * @param scheduledInputs the timers with their deadlines
 */
public record Checkpoint<S, I>(S snapshot,
                               List<I> pendingInputs,
                               List<PersistableEffect> pendingEffects,
                               List<ScheduledInput<I>> scheduledInputs) {
    public Checkpoint {
        pendingInputs = List.copyOf(pendingInputs);
        pendingEffects = List.copyOf(pendingEffects);
        scheduledInputs = List.copyOf(scheduledInputs);
    }
}
