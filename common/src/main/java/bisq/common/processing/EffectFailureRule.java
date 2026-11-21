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

/**
 * What the lane does when executing a saved effect fails. Each saved effect declares its rule with
 * {@link PersistableEffect#failureRule}. A log line has no rule: the lane logs its failure and continues.
 */
public enum EffectFailureRule {
    /**
     * Processing waits: the entity handles no further inputs until a retry of this effect succeeds. New messages are
     * still saved. Use it for work the entity depends on, such as an observation.
     */
    BARRIER,

    /**
     * Handing an outgoing message to the network failed. Later handoffs wait behind it, in order; other effects and
     * inputs continue, and the lane retries the handoff.
     */
    HANDOFF,

    /** The failure is logged and the effect is dropped. Processing continues, and nothing is retried. */
    BEST_EFFORT
}
