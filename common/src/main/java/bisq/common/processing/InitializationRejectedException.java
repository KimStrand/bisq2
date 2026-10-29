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

import lombok.Getter;

/**
 * An initializer's final rejection of a validated entity ID. The router remembers that ID for the registry's
 * lifetime. Unverified inputs and interrupted or failed initialization must not use this signal.
 * Callers still receive the original application rejection.
 */
@Getter
public final class InitializationRejectedException extends RuntimeException {
    private final RuntimeException rejection;

    public InitializationRejectedException(RuntimeException rejection) {
        super(rejection.getMessage(), rejection);
        this.rejection = rejection;
    }
}
