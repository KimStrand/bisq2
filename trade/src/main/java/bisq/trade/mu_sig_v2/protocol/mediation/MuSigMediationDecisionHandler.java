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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;

import static com.google.common.base.Preconditions.checkState;

@Slf4j
abstract class MuSigMediationDecisionHandler<S extends MuSigMediationDecisionState, E extends Event,
        T extends MuSigMediationDecisionState>
        extends MuSigTransitionHandler<S, E, T, MuSigV2MediationDecisionData> {

    MuSigMediationDecisionHandler(MuSigProtocolContext context,
                                  FlowSnapshot<S, MuSigV2MediationDecisionData> flow) {
        super(context, flow);
    }

    MuSigMediationResult payoutResult() {
        MuSigMediationResult result = context.mediation().orElseThrow().data().signedResult().orElseThrow().result();
        checkState(result.getMediationPayoutDistributionType() != MediationPayoutDistributionType.NO_PAYOUT,
                "The mediator did not propose a payout");
        return result;
    }

    void verifyPeerDecision(MuSigTradeMessage message, byte[] resultHash) {
        MuSigMediationResult result = context.mediation().orElseThrow().data().signedResult().orElseThrow().result();
        if (result.getMediationPayoutDistributionType() == MediationPayoutDistributionType.NO_PAYOUT) {
            log.warn("Trade {}: Ignoring {} because the mediator did not propose a payout",
                    tradeId,
                    message.getClass().getSimpleName());
            ignoreInput();
            return;
        }
        if (!Arrays.equals(resultHash, MuSigMediationResultService.getMediationResultHash(result))) {
            log.warn("Trade {}: Ignoring {} because the mediation result hash does not match",
                    tradeId,
                    message.getClass().getSimpleName());
            ignoreInput();
            return;
        }
    }
}
