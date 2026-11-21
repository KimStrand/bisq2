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

/**
 * A flow's state and selected data from one snapshot. Both values must stay tied to that snapshot.
 *
 * @param <S> flow state
 * @param <D> read-only data interface of the domain; returned values must not allow mutation of the owned data
 * @param state the state of the flow
 * @param data the selected data of the flow
 */
public record FlowView<S, D>(S state, D data) {
}
