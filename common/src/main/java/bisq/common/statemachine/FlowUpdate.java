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

import bisq.common.processing.ProcessingOutput;

/**
 * The next document and the output after one flow applied an input. It is not a snapshot: the processor adds the
 * processing history.
 *
 * @param <M> complete business document
 * @param <E> event family accepted and raised by the state machine
 * @param nextDocument the document after the flow applied the input
 * @param output the effects, the raised events and the timer changes of that step
 */
public record FlowUpdate<M, E>(M nextDocument, ProcessingOutput<E> output) {}
