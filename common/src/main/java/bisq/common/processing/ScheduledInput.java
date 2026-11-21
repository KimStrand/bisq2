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

import java.time.Instant;

/**
 * Starts a timer, or replaces the timer with the same id: the lane saves the deadline and delivers the input when it
 * is due.
 *
 * @param <I> incoming or raised input accepted by the processor
 */
public non-sealed interface ScheduledInput<I> extends TimerChange {
    Instant deadline();

    I input();
}
