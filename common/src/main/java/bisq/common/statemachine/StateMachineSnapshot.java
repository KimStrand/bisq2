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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The complete input and result of a state-machine processor step. Processing history stays separate from
 * the business document, but both are checkpointed together. The supplied document must remain stable.
 * Pending inputs, outgoing effects and deadlines belong to the lane's checkpoint instead.
 */
public record StateMachineSnapshot<M, F>(M document, Map<F, Set<Class<?>>> processedEventTypes) {
    public StateMachineSnapshot {
        var history = new LinkedHashMap<F, Set<Class<?>>>();
        processedEventTypes.forEach((flow, types) -> history.put(flow, Set.copyOf(types)));
        processedEventTypes = Collections.unmodifiableMap(history);
    }
}
