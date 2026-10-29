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

import bisq.common.statemachine.FlowView;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionReadOnlyData;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationReadOnlyData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;

import java.util.Optional;

/** The protocol context's service-free API for input discarding, readiness and command admission. */
public interface MuSigReadOnlyContext {
    FlowView<MuSigTradeExecutionState, MuSigExecutionReadOnlyData> execution();

    Optional<FlowView<MuSigMediationState, MuSigMediationReadOnlyData>> mediation();
}
