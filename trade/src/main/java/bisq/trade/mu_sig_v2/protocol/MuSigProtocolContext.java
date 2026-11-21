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
import bisq.common.timer.Clock;
import bisq.contract.Party;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig.grpc.MusigGrpcClient;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;

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
                                   MuSigReadOnlyContext flows) {
    public <S extends MuSigTradeExecutionState> MuSigProtocolContext(ServiceProvider serviceProvider,
                                                                     MusigGrpcClient musigGrpcClient,
                                                                     MuSigV2Trade<S> trade,
                                                                     MuSigExecutionProtocol<S> executionProtocol,
                                                                     Clock clock) {
        this(serviceProvider,
                musigGrpcClient,
                trade.getId(),
                trade.getContract(),
                trade.getMyIdentity(),
                trade.getMyRole(),
                trade.getProtocolVersion(),
                trade.getMyAccountPayload(),
                clock,
                MuSigReadOnlyContext.of(trade, executionProtocol));
    }

    /**
     * The network ID under which this node sends its messages of the trade: its network ID in the contract, which is
     * the one the peer stored for it (rule 3 of docs/specifications/user/identity-by-key.md). The identity of the
     * trade has the same key, but its addresses can be newer, for example when the maker added a transport after it
     * published the offer.
     */
    public NetworkId myNetworkId() {
        return MuSigV2Trade.myParty(contract, myRole).getNetworkId();
    }

    public NetworkId peerNetworkId() {
        return peerParty().getNetworkId();
    }

    public Party peerParty() {
        return MuSigV2Trade.peerParty(contract, myRole);
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
