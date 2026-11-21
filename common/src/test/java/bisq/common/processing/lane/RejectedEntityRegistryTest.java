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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RejectedEntityRegistryTest {
    private static final int LIMIT = RejectedEntityRegistry.MAX_REJECTED_ENTITY_IDS;

    @Test
    void keepsTheNewestIdsAndForgetsTheOldestBeyondTheLimit() {
        var registry = filledRegistry();
        assertTrue(registry.isRejected("entity-0"));

        registry.record("entity-" + LIMIT);

        assertFalse(registry.isRejected("entity-0"));
        assertTrue(registry.isRejected("entity-1"));
        assertTrue(registry.isRejected("entity-" + LIMIT));
    }

    @Test
    void recordingAKnownIdAgainForgetsNoOtherId() {
        var registry = filledRegistry();

        registry.record("entity-5");

        assertTrue(registry.isRejected("entity-0"));
        assertTrue(registry.isRejected("entity-5"));
    }

    private static RejectedEntityRegistry filledRegistry() {
        var registry = new RejectedEntityRegistry();
        for (int i = 0; i < LIMIT; i++) {
            registry.record("entity-" + i);
        }
        return registry;
    }
}
