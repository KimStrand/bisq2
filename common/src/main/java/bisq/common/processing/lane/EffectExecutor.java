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

import bisq.common.processing.Effect;
import bisq.common.processing.EffectFailureRule;
import bisq.common.processing.PersistableEffect;

/**
 * Executes application effects, including logs, on the lane worker after checkpoint persistence.
 *
 * @param <S> complete processor snapshot, including any processing history
 */
@FunctionalInterface
public interface EffectExecutor<S> {
    /**
     * Uses the saved current snapshot; the effect retains its original retry payload.
     * Throws if execution fails. For a saved effect, its {@link PersistableEffect#failureRule} decides what the
     * lane does next. Do not catch a failure to make an effect best effort; declare
     * {@link EffectFailureRule#BEST_EFFORT} instead.
     * Returning after a message handoff does not acknowledge delivery to the peer.
     */
    void execute(S snapshot, Effect effect);
}
