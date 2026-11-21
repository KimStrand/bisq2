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

import bisq.common.processing.Input;

/**
 * The application's rules for the inputs of its lanes, evaluated without access to a live snapshot: which arriving
 * inputs are accepted, and which pending inputs are paused.
 */
@FunctionalInterface
public interface InputPolicy {
    /**
     * Throws if the application does not accept this arriving input. The router calls this on the submitting
     * thread, outside its lock, before buffering or lane creation.
     * Implementations must be short, thread-safe and read-only. Throwing a {@link RuntimeException} rejects
     * only this input without retaining it or blocking the entity ID. The router does not repeat this check
     * when releasing buffered inputs, replaying retained work or resuming a lane.
     */
    void check(Input input);

    /**
     * True while the lane must not start the handler of this pending input, for example while the application does
     * not allow its handling. The lane asks on its worker each time before it starts the handler of a pending input.
     * A paused input stays pending and saved, and the lane still handles its other inputs. The application calls
     * {@link LaneRouter#resumePausedInputs()} when the reason may have ended.
     * Implementations must be short, thread-safe and read-only. They name only inputs which the lane keeps, such as
     * a saved message, a first input, a raised input or the input of a timer: a paused command would stay pending
     * after its submission. By default no input is paused.
     */
    default boolean isPaused(Input input) {
        return false;
    }
}
