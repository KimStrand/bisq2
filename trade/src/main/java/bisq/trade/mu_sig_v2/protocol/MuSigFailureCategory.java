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

package bisq.trade.mu_sig_v2.protocol;

/** Java-side failure classification; the initial processing-failure policy matches V1. */
public enum MuSigFailureCategory {
    /** Admission rejected an input before any handler ran. The trade is unchanged. */
    INPUT_REJECTED,
    /** An operation failed. This does not imply that retrying it is safe. */
    OPERATION_FAILED,
    /** Processing found an inconsistency or an unexpected handler failure. */
    PROTOCOL_FAILED
}
