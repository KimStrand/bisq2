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
 * Observes current local state and outstanding persistence and setup failures on the lane worker.
 * Failures describe this live lane and are cleared when the corresponding operation succeeds.
 * Publication does not acknowledge persistence or outgoing delivery. Implementations must not block processing.
 */
@FunctionalInterface
public interface SnapshotPublisher<S> {
    void publish(S snapshot,
                 Optional<RuntimeException> persistenceFailure,
                 Optional<RuntimeException> setupFailure);
}
