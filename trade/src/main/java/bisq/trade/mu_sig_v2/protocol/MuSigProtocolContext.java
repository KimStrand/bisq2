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
import bisq.common.market.Market;
import bisq.common.statemachine.FlowView;
import bisq.common.timer.Clock;
import bisq.contract.Party;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionReadOnlyData;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationReadOnlyData;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;

import java.util.Optional;

/** Handler dependencies, fixed trade data and read-only flow views tied to one coordinated snapshot. */
public record MuSigProtocolContext(ServiceProvider serviceProvider,
                                   MusigGrpcClient musigGrpcClient,
                                   String tradeId,
                                   MuSigContract contract,
                                   Identity myIdentity,
                                   TradeRole myRole,
                                   String protocolVersion,
                                   AccountPayload<?> myAccountPayload,
                                   Clock clock,
                                   FlowView<MuSigTradeExecutionState, MuSigExecutionReadOnlyData> execution,
                                   Optional<FlowView<MuSigMediationState, MuSigMediationReadOnlyData>> mediation)
        implements MuSigReadOnlyContext {
    public MuSigProtocolContext(ServiceProvider serviceProvider, MusigGrpcClient musigGrpcClient,
                                MuSigV2Trade<?> trade, Clock clock) {
        this(serviceProvider,
                musigGrpcClient,
                trade.getId(),
                trade.getContract(),
                trade.getMyIdentity(),
                trade.getMyRole(),
                trade.getProtocolVersion(),
                trade.getMyAccountPayload(),
                clock,
                MuSigExecutionProtocol.readOnlyView(trade.getExecution()),
                trade.getMediation().map(MuSigMediationProtocol::readOnlyView));
    }

    public NetworkId myNetworkId() {
        return myIdentity.getNetworkId();
    }

    public NetworkId peerNetworkId() {
        return peerParty().getNetworkId();
    }

    public Party peerParty() {
        return myRole.isMaker() ? contract.getTaker() : contract.getMaker();
    }

    public String offerId() {
        return contract.getOffer().getId();
    }

    /** The Bitcoin leg of the market, which is what MuSig locks. */
    public long tradeAmount() {
        Market market = contract.getOffer().getMarket();
        if (market.getBaseCurrencyCode().equals("BTC")) {
            return contract.getBaseSideAmount();
        }
        if (market.getQuoteCurrencyCode().equals("BTC")) {
            return contract.getQuoteSideAmount();
        }
        throw new UnsupportedOperationException(
                "The extended protocol version without Bitcoin on any market leg is not yet supported");
    }
}
