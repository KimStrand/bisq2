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

import java.util.LinkedHashSet;

/**
 * In-memory final initialization rejections for one entity namespace. Retain the same registry across router
 * activations. The registry keeps the newest {@link #MAX_REJECTED_ENTITY_IDS} IDs and forgets the oldest beyond that.
 * A forgotten ID behaves as after a restart, which forgets every ID: a copy of its request runs its initialization
 * again.
 */
public final class RejectedEntityRegistry {
    // A sender can create any number of new entity IDs, so the registry keeps only the newest ones.
    static final int MAX_REJECTED_ENTITY_IDS = 1_000;

    private final Object lock = new Object();
    private final LinkedHashSet<String> rejectedEntityIds = new LinkedHashSet<>();

    boolean isRejected(String entityId) {
        synchronized (lock) {
            return rejectedEntityIds.contains(entityId);
        }
    }

    void record(String entityId) {
        synchronized (lock) {
            if (rejectedEntityIds.add(entityId) && rejectedEntityIds.size() > MAX_REJECTED_ENTITY_IDS) {
                rejectedEntityIds.removeFirst();
            }
        }
    }
}
