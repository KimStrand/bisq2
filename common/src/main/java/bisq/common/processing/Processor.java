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
 * Routes inputs within an entity and evaluates one addressed step at a time.
 * The owner coordinates checkpoints and outgoing work between steps.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input
 * @param <W> one addressed piece of work; a simple processor can use its input directly
 */
public interface Processor<S, I, W> {
    /**
     * Checks that a restored snapshot and its pending work are compatible with this processor.
     * The lane calls this during reconstruction, before setup, recovery or dispatch. Throwing rejects restoration.
     * Must not modify saved data, perform backend work or reject work merely because it is not ready yet.
     */
    default void validateRestoration(S snapshot, List<W> pendingWork) {
    }

    /**
     * Describes ongoing work required by a restored snapshot, without changing it or executing effects.
     * The lane calls this after setup, replaces pending effects that do not replay on restart, and saves
     * the resulting work before dispatch. Live retries reuse that work without calling this method again.
     * Saved timer deadlines are restored separately; they must not be recalculated from the restart time.
     */
    default List<Effect> recoveryEffects(S snapshot) {
        return List.of();
    }

    /**
     * Checks an external input's identity, origin and supported protocol before retaining or processing it.
     * Throwing rejects this input without failing the owner. Must not modify the snapshot or perform backend work.
     * State-dependent readiness and payload verification remain in {@link #processStep}.
     */
    default void validateInput(S snapshot, I input) {
    }

    /**
     * Selects ordered destinations without modifying the snapshot or performing backend work.
     * Non-command routing omits uninterested or permanently stopped/unreachable destinations, and already-processed
     * types where the processor uses successful-type history. Commands may retain interested destinations so the
     * step can distinguish rejection from an already-processed input. Simple processors can accept repeated types.
     * Includes relevant work that may become applicable later; {@link #processStep} decides applicability.
     * External non-command inputs are routed against the last saved snapshot so an unsaved transition cannot
     * cause permanent discard. A newly prepared owner uses its initial snapshot until its first checkpoint.
     */
    List<W> route(S snapshot, I input);

    /**
     * Evaluates one destination. Unavailable commands are rejected, never deferred.
     * Deferral must be decided before modifying the snapshot or performing backend work.
     * Recheck successful processing history and relevance against this snapshot: earlier work may have advanced
     * the owner since routing. Retaining an input does not mark its type as successfully processed.
     *
     * A handled processing failure returns {@link StepResult.Failed} with a valid failure snapshot.
     * An unexpected exception supplies no such snapshot and requires the owner to stop processing.
     */
    StepResult<S, I> processStep(S snapshot, W work);
}
