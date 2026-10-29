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

import java.util.List;

/** Outgoing work, narration and follow-up events produced by a processing step. */
public record ProcessingOutput<E>(List<Effect> effects,
                                  List<LogEntry> logs,
                                  List<E> raisedEvents) {
    public ProcessingOutput {
        effects = List.copyOf(effects);
        logs = List.copyOf(logs);
        raisedEvents = List.copyOf(raisedEvents);
    }
}
