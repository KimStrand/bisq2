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

import java.util.Optional;

/**
 * Successful preparation of an entity, with an optional first input to consider before buffered inputs.
 * The owner checkpoints the snapshot and pending first input before processing it under the normal step rules.
 * The lane definition's factory constructs the processor from the snapshot. This is not a persistence record.
 *
 * @param <S> complete initial processor snapshot, including any processing history
 * @param <I> incoming or raised input
 */
public record Initialization<S, I>(S snapshot, Optional<I> firstInput) {
}
