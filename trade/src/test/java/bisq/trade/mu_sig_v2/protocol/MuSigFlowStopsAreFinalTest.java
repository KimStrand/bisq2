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

import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.MuSigBuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.MuSigBuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.MuSigSellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.MuSigSellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import org.junit.jupiter.api.Test;

import static bisq.trade.mu_sig_v2.protocol.RuleTableTestSupport.isTerminal;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The lane drops a message for a stopped flow without saving it (StateMachineProcessor.canStillBeHandled). That is
 * safe only while stopping is final. These are the flow definitions which MuSigTradeProcessor registers; a new flow
 * belongs here as well.
 */
class MuSigFlowStopsAreFinalTest {
    @Test
    void theFailureStatesOfEveryFlowAreFinal() {
        var failed = new MuSigTradeExecutionState.Failed();
        var failedAtPeer = new MuSigTradeExecutionState.FailedAtPeer();
        var buyerAsMaker = new MuSigBuyerAsMakerProtocol();
        var buyerAsTaker = new MuSigBuyerAsTakerProtocol();
        var sellerAsMaker = new MuSigSellerAsMakerProtocol();
        var sellerAsTaker = new MuSigSellerAsTakerProtocol();
        assertTrue(buyerAsMaker.isFailed(failed) && buyerAsMaker.isFailed(failedAtPeer));
        assertTrue(isTerminal(buyerAsMaker.rules(), failed) && isTerminal(buyerAsMaker.rules(), failedAtPeer));
        assertTrue(isTerminal(buyerAsTaker.rules(), failed) && isTerminal(buyerAsTaker.rules(), failedAtPeer));
        assertTrue(isTerminal(sellerAsMaker.rules(), failed) && isTerminal(sellerAsMaker.rules(), failedAtPeer));
        assertTrue(isTerminal(sellerAsTaker.rules(), failed) && isTerminal(sellerAsTaker.rules(), failedAtPeer));

        var mediation = new MuSigMediationProtocol();
        var mediationFailed = new MuSigMediationState.Failed();
        assertTrue(mediation.isFailed(mediationFailed) && isTerminal(mediation.rules(), mediationFailed));

        var decisionFailed = new MuSigMediationDecisionState.Failed();
        var myDecision = new MuSigMyMediationDecisionProtocol();
        var peerDecision = new MuSigPeerMediationDecisionProtocol();
        assertTrue(myDecision.isFailed(decisionFailed) && isTerminal(myDecision.rules(), decisionFailed));
        assertTrue(peerDecision.isFailed(decisionFailed) && isTerminal(peerDecision.rules(), decisionFailed));
    }

    @Test
    void theTradeNeverReturnsToActiveAndArchivingIsFinal() {
        var trade = new MuSigTradeProtocol();
        // The child flows run only while the trade is Active, and the trade itself stops when it is Historical.
        assertFalse(trade.isChildProcessingEnabled(new MuSigTradeState.Failed()));
        assertFalse(trade.isProcessingEnabled(new MuSigTradeState.Historical()));

        assertTrue(trade.rules().getRules().stream()
                .noneMatch(rule -> MuSigTradeState.Active.class.equals(rule.to())));
        assertTrue(isTerminal(trade.rules(), new MuSigTradeState.Historical()));
    }
}
