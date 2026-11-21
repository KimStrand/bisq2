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
 * Synchronous business processing for one entity. The lane owns retention, persistence and effect execution.
 * The processor factory must reject snapshots whose saved history is incompatible with its definitions.
 * A message is every submitted input that is not a {@link Command}. Most messages come from the network; local
 * observations, such as a blockchain confirmation, take the same path.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
public interface Processor<S, I extends Input> {
    /**
     * False if {@link #handle} would ignore this message in the snapshot and in every later one, for example because
     * the message was already handled. The lane asks only about messages, never about commands. It asks with the last
     * saved snapshot, after the lane definition's input validation and before it retains the message, and for false it
     * neither retains nor saves the message. Return false only if the answer cannot change back, so that dropping the
     * message cannot lose work. Must not modify the snapshot or perform backend work.
     */
    default boolean canStillBeHandled(S snapshot, I input) {
        return true;
    }

    /**
     * Handles one input using the latest working snapshot and returns the next snapshot and output; raised inputs
     * are handled only after this result is saved. An unexpected exception without a valid failure snapshot stops
     * the lane instead of retrying potentially unfinished handler work.
     * <p>
     * Return Applied when the input was applied, Failed when handling failed with a valid failure snapshot,
     * Deferred when the input waits for a later state, Rejected for an unavailable command and Ignored when nothing
     * is to be done. Only Deferred keeps the input for another attempt.
     * <p>
     * Commands never return Deferred. Ordinary commands are not retained
     * for later execution; unavailable commands must be rejected during this call.
     */
    HandleResult<S, I> handle(S snapshot, I input);

    /**
     * Describes ongoing work needed after restart, without modifying the snapshot or executing effects.
     * The lane saves these effects before execution. Live retries reuse them; saved timer deadlines are
     * restored separately and must not be recalculated from the restart time.
     */
    default List<Effect> createRecoveryEffects(S snapshot) {
        return List.of();
    }
}
