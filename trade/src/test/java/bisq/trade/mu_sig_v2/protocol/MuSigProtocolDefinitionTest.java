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

import bisq.common.statemachine.Event;
import bisq.common.statemachine.RuleTable;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.BuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMyMediationDecisionProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigPeerMediationDecisionProtocol;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuSigProtocolDefinitionTest {
    @Test
    void muSigDefinitionsRejectCyclesByDefault() {
        var builder = RuleTable.<BuyerAsTakerState, MuSigV2TradeExecutionData, Event, MuSigProtocolContext>builder()
                .rule(Init.class, MuSigTakeOfferEvent.class, MuSigTakeOfferEventHandler::new, TradeInitialized.class)
                .rule(TradeInitialized.class, DepositTxConfirmedEvent.class, (context, flow) -> {
                    throw new AssertionError("A cyclic definition must fail before handler creation");
                }, Init.class);

        assertThrows(IllegalArgumentException.class, builder::build);
    }

    @Test
    void currentExecutionAndMediationDefinitionsAreAcyclic() {
        assertAll(
                () -> assertDoesNotThrow(() -> {
                    new MuSigProtocol();
                }),
                () -> assertDoesNotThrow(BuyerAsMakerProtocol::new),
                () -> assertDoesNotThrow(BuyerAsTakerProtocol::new),
                () -> assertDoesNotThrow(SellerAsMakerProtocol::new),
                () -> assertDoesNotThrow(SellerAsTakerProtocol::new),
                () -> assertDoesNotThrow(MuSigMediationProtocol::new),
                () -> assertDoesNotThrow(MuSigMyMediationDecisionProtocol::new),
                () -> assertDoesNotThrow(MuSigPeerMediationDecisionProtocol::new));
    }

    @Test
    void onlyAnInitialDecisionHasAcceptanceAndRejectionTransitions() {
        var mine = new MuSigMyMediationDecisionProtocol();
        var peer = new MuSigPeerMediationDecisionProtocol();
        var initial = new MuSigMediationDecisionState.Init();
        assertTrue(mine.canHandle(initial, MediationResultAcceptedEvent.class));
        assertTrue(mine.canHandle(initial, MediationResultRejectedEvent.class));
        assertTrue(peer.canHandle(initial, MuSigCustomPayoutPsbtMessage.class));
        assertTrue(peer.canHandle(initial, MuSigMediationResultRejectionMessage.class));
        List<MuSigMediationDecisionState> terminalStates = List.of(new MuSigMediationDecisionState.Accepted(),
                new MuSigMediationDecisionState.Rejected());
        for (var state : terminalStates) {
            assertTrue(mine.isTerminal(state));
            assertTrue(peer.isTerminal(state));
            assertFalse(mine.canHandle(state, MediationResultAcceptedEvent.class));
            assertFalse(mine.canHandle(state, MediationResultRejectedEvent.class));
            assertFalse(peer.canHandle(state, MuSigCustomPayoutPsbtMessage.class));
            assertFalse(peer.canHandle(state, MuSigMediationResultRejectionMessage.class));
        }
    }
}
