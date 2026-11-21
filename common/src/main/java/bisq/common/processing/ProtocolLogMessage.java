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

/**
 * A user-visible line that a processing step writes, for example a protocol log message in a trade chat. It is a
 * best-effort effect, kept only in memory and dispatched through the effect executor.
 * The adapter resolves local authors and writes the lines; a failed message is not retried.
 */
public sealed interface ProtocolLogMessage extends Effect {
    Content content();

    /** Displayed only locally, attributed to the supplied author. */
    record Local(Content content, String authorId) implements ProtocolLogMessage {}

    /** Displayed locally and shared with the current participants as the local user. */
    record Shared(Content content) implements ProtocolLogMessage {}

    sealed interface Content {}

    record Text(String text) implements Content {}

    /** A translation key and its already resolved arguments; the owner handles localization. */
    record Localized(String key, List<String> args) implements Content {
        public Localized {
            args = List.copyOf(args);
        }
    }
}
