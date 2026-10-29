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

import bisq.account.accounts.AccountPayload;
import bisq.common.statemachine.FlowSnapshot;
import bisq.contract.Party;
import bisq.contract.Role;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationDecisionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2Mediation;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecision;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationDecisionData;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Optional;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MuSigV2TradeTest {
    @ParameterizedTest
    @EnumSource(TradeRole.class)
    void creationInitializesEveryConfiguredFlow(TradeRole role) {
        var fixedData = tradeFor(role, "local");
        var contract = fixedData.getContract();
        when(contract.getMediator()).thenReturn(Optional.of(mock(UserProfile.class)));

        var trade = MuSigV2Trade.create(contract,
                fixedData.getMyIdentity(),
                role,
                fixedData.getMyAccountPayload());

        assertEquals(MuSigProtocol.VERSION, trade.getProtocolVersion());
        assertInstanceOf(MuSigTradeExecutionState.Init.class, trade.getExecution().state());
        var mediation = trade.getMediation().orElseThrow();
        assertInstanceOf(MuSigMediationState.Init.class, mediation.state());
        assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.myDecision().state());
        assertInstanceOf(MuSigMediationDecisionState.Init.class, mediation.peerDecision().state());
        assertEquals(new MuSigTradeState.Active(), trade.getState());

        when(contract.getMediator()).thenReturn(Optional.empty());
        var withoutMediator = MuSigV2Trade.create(contract,
                fixedData.getMyIdentity(),
                role,
                fixedData.getMyAccountPayload());
        assertTrue(withoutMediator.getMediation().isEmpty());
    }

    @Test
    void bothPartiesDeriveTheIdFromTheAgreedTaker() {
        var taker = tradeFor(TradeRole.SELLER_AS_TAKER, "taker");
        var maker = tradeFor(TradeRole.BUYER_AS_MAKER, "maker");

        assertEquals(TradeV2.createId("offer", "taker", 100L), taker.getId());
        assertEquals(taker.getId(), maker.getId());
    }

    @Test
    void updatesPreserveTheSuppliedIdTradeStateAndFixedDataAcrossBothFlows() {
        var initial = tradeFor(TradeRole.SELLER_AS_TAKER, "taker");
        String suppliedId = TradeV2.createId("earlier-offer", "taker", 99L);
        MuSigV2Trade<Init> trade = new MuSigV2Trade<>(suppliedId,
                new MuSigTradeState.Historical(),
                new MuSigV2TradeData(initial.getContract(),
                        initial.getMyIdentity(),
                        initial.getMyRole(),
                        initial.getProtocolVersion(),
                        initial.getMyAccountPayload()),
                initial.getExecution(),
                initial.getMediation());
        assertNotEquals(initial.getId(), trade.getId());

        var afterExecution = trade.withExecution(trade.getExecution().to(new Init(),
                MuSigV2TradeExecutionData.builder().build()));
        var afterMediation = afterExecution.withMediation(
                FlowSnapshot.of(new MuSigMediationState.Init(), MuSigV2MediationData.initial()));

        assertEquals(suppliedId, afterExecution.getId());
        assertEquals(suppliedId, afterMediation.getId());
        assertEquals(suppliedId.substring(0, 8), afterMediation.getShortId());
        assertEquals(new MuSigTradeState.Historical(), afterExecution.getState());
        assertEquals(new MuSigTradeState.Historical(), afterMediation.getState());
        assertFixedDataUnchanged(trade, afterExecution);
        assertFixedDataUnchanged(trade, afterMediation);
    }

    @Test
    void parentTransitionsPreserveBothFlowsAndLeaveThePreviousSnapshotUnchanged() {
        var initial = tradeFor(TradeRole.SELLER_AS_TAKER, "taker")
                .withMediation(FlowSnapshot.of(new MuSigMediationState.Init(), MuSigV2MediationData.initial()));

        var next = new MuSigV2Trade<>(initial.getId(),
                new MuSigTradeState.Historical(),
                initial.data(),
                new MuSigV2TradeExecution<>(new MuSigTradeExecutionState.TradeClosed(),
                        initial.getExecution().data()),
                Optional.empty());
        var historical = initial.withProtocol(next);

        assertEquals(new MuSigTradeState.Active(), initial.getState());
        assertEquals(new MuSigTradeState.Historical(), historical.getState());
        assertEquals(next.state(), historical.state());
        assertSame(initial.data(), historical.data());
        assertEquals(initial.getId(), historical.getId());
        assertFixedDataUnchanged(initial, historical);
        assertSame(initial.getExecution(), historical.getExecution());
        assertSame(initial.getMediation().orElseThrow(), historical.getMediation().orElseThrow());
    }

    @Test
    void aCaseTransitionCannotReplaceTheDecisionsEvenWhenItsSnapshotContainsDifferentChildren() {
        var trade = tradeFor(TradeRole.SELLER_AS_TAKER, "taker");
        var before = trade.getMediation().orElseThrow();
        var proposed = new MuSigV2Mediation<>(new MuSigMediationState.Closed(),
                before.data(),
                new MuSigV2MediationDecision<>(new MuSigMediationDecisionState.Accepted(),
                        MuSigV2MediationDecisionData.initial()),
                new MuSigV2MediationDecision<>(new MuSigMediationDecisionState.Rejected(),
                        MuSigV2MediationDecisionData.initial()));

        var updated = trade.withMediation(proposed);
        var after = updated.getMediation().orElseThrow();

        assertEquals(proposed.state(), after.state());
        assertSame(proposed.data(), after.data());
        assertSame(before.myDecision(), after.myDecision());
        assertSame(before.peerDecision(), after.peerDecision());
        assertSame(trade.data(), updated.data());
        assertSame(trade.state(), updated.state());
        assertSame(trade.getExecution(), updated.getExecution());
    }

    @Test
    void decisionWritersPreserveTheirParentAndTheOtherDecision() {
        var trade = tradeFor(TradeRole.SELLER_AS_TAKER, "taker");
        var before = trade.getMediation().orElseThrow();
        var accepted = new MuSigV2MediationDecision<>(new MuSigMediationDecisionState.Accepted(),
                MuSigV2MediationDecisionData.initial());
        var afterMine = trade.withMyMediationDecision(accepted);
        var afterPeer = afterMine.withPeerMediationDecision(accepted);

        assertEquals(accepted, afterMine.getMediation().orElseThrow().myDecision());
        assertSame(before.peerDecision(), afterMine.getMediation().orElseThrow().peerDecision());
        assertSame(afterMine.getMediation().orElseThrow().myDecision(),
                afterPeer.getMediation().orElseThrow().myDecision());
        assertEquals(accepted, afterPeer.getMediation().orElseThrow().peerDecision());
        for (var updated : List.of(afterMine, afterPeer)) {
            var mediation = updated.getMediation().orElseThrow();
            assertSame(before.state(), mediation.state());
            assertSame(before.data(), mediation.data());
            assertSame(trade.state(), updated.state());
            assertSame(trade.data(), updated.data());
            assertSame(trade.getExecution(), updated.getExecution());
        }
    }

    private void assertFixedDataUnchanged(MuSigV2Trade<?> before, MuSigV2Trade<?> after) {
        assertSame(before.getContract(), after.getContract());
        assertSame(before.getMyIdentity(), after.getMyIdentity());
        assertEquals(before.getMyRole(), after.getMyRole());
        assertEquals(before.getProtocolVersion(), after.getProtocolVersion());
        assertSame(before.getMyAccountPayload(), after.getMyAccountPayload());
    }

    private MuSigV2Trade<Init> tradeFor(TradeRole role, String localId) {
        MuSigOffer offer = mock(MuSigOffer.class);
        when(offer.getId()).thenReturn("offer");
        NetworkId takerNetworkId = mock(NetworkId.class);
        when(takerNetworkId.getId()).thenReturn("taker");
        MuSigContract contract = mock(MuSigContract.class);
        when(contract.getOffer()).thenReturn(offer);
        when(contract.getMediator()).thenReturn(Optional.of(mock(UserProfile.class)));
        when(contract.getTaker()).thenReturn(new Party(Role.TAKER, takerNetworkId));
        when(contract.getTakeOfferDate()).thenReturn(100L);
        NetworkId localNetworkId = mock(NetworkId.class);
        when(localNetworkId.getId()).thenReturn(localId);
        Identity identity = mock(Identity.class);
        when(identity.getNetworkId()).thenReturn(localNetworkId);
        return MuSigV2TradeTestFactory.tradeAt(contract,
                identity,
                role,
                "1.0.0",
                mock(AccountPayload.class),
                new Init());
    }
}
