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

import bisq.common.processing.HandleResult;

/**
 * Summary of processing one submitted input and its immediately processable raised inputs.
 * Router admission can reject before a lane exists; the lane derives other results from retained work and
 * {@link HandleResult} values. It describes progress without guaranteeing durability; {@link SubmissionResult}
 * reports whether coordination completed or remains pending.
 *
 * @param <S> complete processor snapshot, including any processing history
 */
public sealed interface ProcessingSummary<S> {
    /** At least one handle call applied changes, and none of this submission's processing work remains pending. */
    record Applied<S>(S snapshot) implements ProcessingSummary<S> {}

    /** Some processing work remains pending; other handle calls of this submission may already have applied changes. */
    record Deferred<S>(S snapshot) implements ProcessingSummary<S> {}

    /** No destination applied or retained the input. Successful processing history is unchanged. */
    record Ignored<S>(S snapshot) implements ProcessingSummary<S> {}

    /** No handle call applied changes, and none of this submission's work is retained. */
    record Rejected<S>(RuntimeException cause) implements ProcessingSummary<S> {}

    /**
     * A handle call failed, or a rejection prevented completing a partially applied or retained submission.
     * The snapshot determines which flows failed; this result does not itself fail the whole entity.
     */
    record Failed<S>(S snapshot, RuntimeException cause) implements ProcessingSummary<S> {}
}
