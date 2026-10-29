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
 * The outcome of one addressed processing step.
 * Exception causes report failures to callers; persisted failure details belong in the snapshot.
 */
public sealed interface StepResult<S, I> {
    /** The step succeeded; checkpoint the next snapshot and pending output before continuing. */
    record Applied<S, I>(S nextSnapshot, ProcessingOutput<I> output) implements StepResult<S, I> {}

    /** Relevant work may become applicable later; retain it without changing the snapshot. */
    record Deferred<S, I>() implements StepResult<S, I> {}

    /** Consume duplicate or irrelevant work without changing the snapshot or processing history. */
    record Ignored<S, I>() implements StepResult<S, I> {}

    /** Reject the request without changing the snapshot or clearing other retained work. */
    record Rejected<S, I>(RuntimeException cause) implements StepResult<S, I> {}

    /**
     * Processing failed with a valid next snapshot. The processor decides which flow or entity failed.
     * Checkpoint this snapshot and pending output, process internal reactions, then report the cause.
     */
    record Failed<S, I>(S nextSnapshot,
                        ProcessingOutput<I> output,
                        RuntimeException cause) implements StepResult<S, I> {}
}
