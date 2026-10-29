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

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory final initialization rejections for one entity namespace. Retain the same registry across router
 * activations. Entries are not evicted: forgetting an ID would allow its rejected initialization to run again.
 */
public final class RejectedEntityRegistry {
    private final Set<String> rejectedEntityIds = ConcurrentHashMap.newKeySet();

    boolean isRejected(String entityId) {
        return rejectedEntityIds.contains(entityId);
    }

    void record(String entityId) {
        rejectedEntityIds.add(entityId);
    }
}
