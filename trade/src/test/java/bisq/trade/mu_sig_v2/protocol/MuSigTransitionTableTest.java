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

import bisq.common.statemachine.RuleTable.Rule;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.BuyerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SellerAsMakerProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Checks each table against its sealed stage hierarchy, so adding a stage without a reachable rule fails.
 * Events reuse existing MuSig classes, so there is no equivalent sealed-hierarchy check for unused events.
 */
class MuSigTransitionTableTest {
    @ParameterizedTest
    @EnumSource(TradeRole.class)
    void everyDeclaredStateIsReachableAndOnlyExpectedStatesAreTerminal(TradeRole role) {
        Class<? extends MuSigTradeExecutionState> stateFamily = switch (role) {
            case BUYER_AS_MAKER -> BuyerAsMakerState.class;
            case BUYER_AS_TAKER -> BuyerAsTakerState.class;
            case SELLER_AS_MAKER -> SellerAsMakerState.class;
            case SELLER_AS_TAKER -> SellerAsTakerState.class;
        };
        var rules = switch (role) {
            case BUYER_AS_MAKER -> BuyerAsMakerProtocol.transitions().getRules();
            case BUYER_AS_TAKER -> BuyerAsTakerProtocol.transitions().getRules();
            case SELLER_AS_MAKER -> SellerAsMakerProtocol.transitions().getRules();
            case SELLER_AS_TAKER -> SellerAsTakerProtocol.transitions().getRules();
        };
        Set<Class<?>> declared = Set.of(stateFamily.getPermittedSubclasses());
        Set<Class<?>> reachable = new HashSet<>();
        ArrayDeque<Class<?>> pending = new ArrayDeque<>();
        pending.add(Init.class);
        while (!pending.isEmpty()) {
            Class<?> state = pending.removeFirst();
            if (reachable.add(state)) {
                rules.stream()
                        .filter(rule -> rule.from().equals(state))
                        .map(Rule::to)
                        .forEach(pending::addLast);
            }
        }
        assertEquals(declared, reachable, "Unreachable or undeclared states");

        Set<Class<?>> withOutgoing = rules.stream()
                .map(Rule::from)
                .collect(Collectors.toSet());
        Set<Class<?>> terminalStates = declared.stream()
                .filter(state -> !withOutgoing.contains(state))
                .collect(Collectors.toSet());
        Set<Class<?>> expectedTerminals = new HashSet<>(Set.of(
                Failed.class,
                FailedAtPeer.class,
                TradeClosed.class,
                CustomPayoutClosedTrade.class));
        if (role.isSeller()) {
            expectedTerminals.add(SellerForceClosedTrade.class);
        }
        assertEquals(expectedTerminals, terminalStates, "Unexpected terminal states");

        assertEquals(List.of(CustomPayoutClosedTrade.class), rules.stream()
                .filter(rule -> rule.from().equals(CustomPayoutSigned.class) && rule.on().equals(MediationResultByPeerAcceptedEvent.class))
                .map(Rule::to).toList(), "Peer acceptance must finalize the signed custom payout directly");
    }

    @Test
    void theSellerAsTakerHierarchyIsTheOneWeExpect() {
        Class<?>[] states = SellerAsTakerState.class.getPermittedSubclasses();
        assertEquals(14, states.length, Arrays.toString(states));
        assertEquals(31, SellerAsTakerProtocol.transitions().getRules().size());
    }
}
