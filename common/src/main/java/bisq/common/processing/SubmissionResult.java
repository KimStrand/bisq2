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
 * The result of one submitted input and its immediately processable raised events.
 * Completed and pending results contain a {@link ProcessingResult}, which alone makes no durability guarantee.
 * A pending result identifies the coordination work that must be retried, without resubmitting the input.
 * Results describe this attempt; they are neither persisted nor updated after a later retry.
 * Unexpected processor failures and closed-lane submissions still complete the future exceptionally.
 */
public sealed interface SubmissionResult<S> {
    /**
     * Required checkpoints, setup and immediate outgoing handoffs finished for this attempt.
     * A submission rejected before reaching a lane requires none of that work.
     * The processing result may be deferred, rejected or failed. Completion does not confirm peer delivery.
     */
    record Completed<S>(ProcessingResult<S> processingResult) implements SubmissionResult<S> {}

    /**
     * Coordination work remains pending in the given phase. The processing result describes what the input
     * already did; retry retained coordination work without resubmitting that input.
     */
    record Pending<S>(ProcessingResult<S> processingResult,
                      CoordinationPhase phase,
                      RuntimeException cause) implements SubmissionResult<S> {}

    /** Earlier coordination work prevented admitting this command. It was neither run nor retained. */
    record NotProcessed<S>(RuntimeException cause) implements SubmissionResult<S> {}

    enum CoordinationPhase {
        /** The calculated snapshot or pending work is not yet saved. Retry the checkpoint, not the handler. */
        PERSISTENCE,

        /** The initial checkpoint is saved, but idempotent application setup must finish before processing. */
        SETUP,

        /**
         * The checkpoint is saved, but outgoing work must be retried. Message handoff failures allow processing
         * to continue, so an applied input can report an older message still pending in this lane.
         * Timer and other required effect failures pause further processing until retried successfully.
         */
        OUTGOING_WORK
    }
}
