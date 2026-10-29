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

import bisq.common.processing.Effect;
import bisq.common.processing.StepResult;
import lombok.Getter;

import java.util.List;
import java.util.Optional;

/**
 * A flow definition bound to its part of the owner's document. Implementations keep the flow's state and
 * data types together when reading and replacing its snapshot. The processor treats every registration alike.
 */
@Getter
public abstract class FlowRegistration<M, E, F> {
    private final F id;
    private final Optional<F> parent;

    protected FlowRegistration(F id, Optional<F> parent) {
        this.id = id;
        this.parent = parent;
    }

    public abstract boolean isInterestedIn(E event);

    public abstract boolean isFailed(M snapshot);

    /** Controls this flow's own inputs and recovery; failure stops processing by default. */
    public boolean isProcessingEnabled(M snapshot) {
        return !isFailed(snapshot);
    }

    /**
     * Controls descendants independently of this flow's own transitions. Disabled descendants discard
     * messages and reject commands; temporary waiting belongs in their readiness checks instead.
     * By default, a failed or disabled flow also disables its descendants.
     */
    public boolean isChildProcessingEnabled(M snapshot) {
        return !isFailed(snapshot) && isProcessingEnabled(snapshot);
    }

    public abstract boolean discard(M snapshot, E event, boolean command);

    public abstract boolean canHandle(M snapshot, E event);

    public void admit(M snapshot, E event) {
    }

    public abstract Optional<StepResult.Applied<M, E>> apply(M snapshot, E event);

    public List<Effect> recoveryEffects(M snapshot) {
        return List.of();
    }
}
