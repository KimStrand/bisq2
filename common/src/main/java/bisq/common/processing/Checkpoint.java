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
 * One owner's processor snapshot and unfinished work, saved together by the application's persistence adapter.
 * Lists are copied; the supplied snapshot and contained values must remain stable after construction.
 * Pending work retains whether its input was a command, even when its addressed value does not expose that input.
 * Processor instances, setup completion, submission futures and best-effort logs are not part of a checkpoint.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input
 * @param <W> one addressed piece of pending work
 */
public record Checkpoint<S, I, W>(S snapshot,
                                  List<PendingWork<W>> pendingWork,
                                  List<Effect> pendingEffects,
                                  List<ScheduledInput<I>> scheduledInputs) {
    public Checkpoint {
        pendingWork = List.copyOf(pendingWork);
        pendingEffects = List.copyOf(pendingEffects);
        scheduledInputs = List.copyOf(scheduledInputs);
    }
}
