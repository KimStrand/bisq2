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

/**
 * One input attempt. Only {@link Deferred} keeps the input for another attempt; every other result removes it.
 * Exception causes report to callers; persisted failure details belong in the snapshot.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
public sealed interface HandleResult<S, I> {
    /** The input was applied. Save the next snapshot and output before executing effects. */
    record Applied<S, I>(S nextSnapshot, ProcessingOutput<I> output) implements HandleResult<S, I> {}

    /** The input waits for a later state and is kept for another attempt. */
    record Deferred<S, I>() implements HandleResult<S, I> {}

    /** Nothing is to be done for this input; do not record successful processing history. */
    record Ignored<S, I>() implements HandleResult<S, I> {}

    /** Reject without changing the snapshot or retaining this input. */
    record Rejected<S, I>(RuntimeException cause) implements HandleResult<S, I> {}

    /** Processing failed with a valid failure snapshot. Save it and preserve all output. */
    record Failed<S, I>(S nextSnapshot,
                        ProcessingOutput<I> output,
                        RuntimeException cause) implements HandleResult<S, I> {}
}
