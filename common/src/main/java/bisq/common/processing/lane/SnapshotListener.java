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

import java.util.Optional;

/**
 * Receives the current local state and outstanding persistence and setup failures.
 * The router first passes all restored snapshots on its startup thread before queueing any recovery.
 * Later calls run on the owning lane's worker; calls for different lanes may run concurrently.
 * Failures describe this live lane and are cleared when the corresponding operation succeeds.
 * A call does not acknowledge persistence or outgoing delivery. Listener failures do not stop processing.
 * Implementations may submit inputs but must not wait for startup or work queued behind this call.
 *
 * @param <S> complete processor snapshot, including any processing history
 */
@FunctionalInterface
public interface SnapshotListener<S> {
    void onSnapshot(S snapshot,
                    Optional<RuntimeException> persistenceFailure,
                    Optional<RuntimeException> setupFailure);
}
