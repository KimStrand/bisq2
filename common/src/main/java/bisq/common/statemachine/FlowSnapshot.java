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

package bisq.common.statemachine;

/** One flow's position and accumulated data, replaced together. */
public interface FlowSnapshot<S, D> {
    S state();

    D data();

    /** A state/data value for a transition; the flow binding installs its concrete domain snapshot. */
    static <S, D> FlowSnapshot<S, D> of(S state, D data) {
        record Value<S, D>(S state, D data) implements FlowSnapshot<S, D> {
        }
        return new Value<>(state, data);
    }

    default <T> FlowSnapshot<T, D> to(T nextState, D nextData) {
        return of(nextState, nextData);
    }
}
