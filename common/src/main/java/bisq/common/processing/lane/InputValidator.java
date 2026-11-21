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

/**
 * Decides whether a submitted input may enter the lane of its entity, for example by its sender, receiver and protocol
 * version. Each lane definition has one; a lane whose inputs need no such check passes an empty validator explicitly.
 * {@link InputPolicy} decides before the lane exists and without a snapshot; this validator decides in the lane, with
 * the snapshot of the entity.
 *
 * @param <S> complete processor snapshot, including any processing history
 * @param <I> incoming or raised input accepted by the processor
 */
@FunctionalInterface
public interface InputValidator<S, I> {
    /**
     * The lane calls this before it keeps an input: for a message with the last saved snapshot, for a command with the
     * current snapshot. Throwing a {@link RuntimeException} rejects only this input. Implementations must not modify
     * the snapshot or perform backend work. Whether a command fits the current state of the entity belongs to the
     * processor's handling.
     */
    void validate(S snapshot, I input);
}
