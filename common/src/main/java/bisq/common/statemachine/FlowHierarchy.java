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

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Immutable parent relationships between flows. A flow without a declared parent is a root.
 * The processor enforces each ancestor's child-processing policy without changing descendant snapshots.
 */
public final class FlowHierarchy<F> {
    private final Map<F, F> parentByFlow;

    public FlowHierarchy(Map<F, F> parentByFlow) {
        this.parentByFlow = Map.copyOf(parentByFlow);
        for (F flow : this.parentByFlow.keySet()) {
            var visited = new HashSet<F>();
            F ancestor = flow;
            while (this.parentByFlow.containsKey(ancestor)) {
                checkArgument(visited.add(ancestor), "Cyclic flow hierarchy at %s", ancestor);
                ancestor = this.parentByFlow.get(ancestor);
            }
        }
    }

    public Optional<F> parentOf(F flow) {
        return Optional.ofNullable(parentByFlow.get(flow));
    }
}
