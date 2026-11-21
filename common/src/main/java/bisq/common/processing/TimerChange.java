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
 * A change of the owner's timers, which a processor returns next to its effects and its raised inputs: the start of
 * a timer or its cancellation. The lane saves the deadlines with the producing checkpoint. Its timers follow the
 * saved deadlines, also after a restart, so a timer is never pending work.
 */
public sealed interface TimerChange permits ScheduledInput, CancelScheduledInput {
    /** The id of the timer, scoped to the owning entity. */
    String id();
}
