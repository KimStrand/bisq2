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

package bisq.trade.mu_sig_v2;

import bisq.account.accounts.AccountPayload;
import bisq.common.monetary.Monetary;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.trade.TradeRole;
import bisq.trade.TradeV2;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.MuSigV2TradeData;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;

import java.util.concurrent.CompletableFuture;

public final class MuSigV2TradeTestFactory {
    private MuSigV2TradeTestFactory() {
    }

    /** Supplies fixture terms through the same initialization command as the application. */
    public static InitializeTrade initializeTrade(MuSigContract contract,
                                                  Identity myIdentity,
                                                  AccountPayload<?> myAccountPayload) {
        var market = contract.getOffer().getMarket();
        return new InitializeTrade(contract.getTakeOfferDate(),
                myIdentity,
                contract.getOffer(),
                Monetary.from(contract.getBaseSideAmount(), market.getBaseCurrencyCode()),
                Monetary.from(contract.getQuoteSideAmount(), market.getQuoteCurrencyCode()),
                contract.getNonBtcSidePaymentMethodSpec(),
                myAccountPayload,
                contract.getMediator(),
                contract.getArbitrator(),
                contract.getMarketPrice());
    }

    /** Reads the fixture's saved document after the public initialization operation completes. */
    public static CompletableFuture<MuSigV2Trade<?>> initializeTrade(MuSigTradeService service, InitializeTrade command) {
        String id = TradeV2.createId(command.getOffer().getId(),
                command.getTakerIdentity().getNetworkId().getId(),
                command.getTakeOfferDate());
        return service.initializeTrade(id, command)
                .thenApply(ignored -> MuSigTradeServiceTestSupport.savedRecord(service, id).trade());
    }

    /** Copies the fixture's current terms into a plain contract for peer messages. */
    public static MuSigContract copyContract(MuSigContract contract) {
        return new MuSigContract(contract.getTakeOfferDate(),
                contract.getOffer(),
                contract.getTaker().getNetworkId(),
                contract.getBaseSideAmount(),
                contract.getQuoteSideAmount(),
                contract.getNonBtcSidePaymentMethodSpec(),
                contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                contract.getMediator(),
                contract.getArbitrator(),
                contract.getPriceSpec(),
                contract.getMarketPrice());
    }

    public static <S extends MuSigTradeExecutionState> MuSigV2Trade<S> tradeAt(
            MuSigContract contract,
            Identity myIdentity,
            TradeRole myRole,
            String protocolVersion,
            AccountPayload<?> myAccountPayload,
            S state) {
        var initial = MuSigV2Trade.create(contract, myIdentity, myRole, myAccountPayload);
        return new MuSigV2Trade<>(initial.getId(),
                initial.getState(),
                new MuSigV2TradeData(contract,
                        myIdentity,
                        myRole,
                        protocolVersion,
                        myAccountPayload),
                new MuSigV2TradeExecution<>(state, initial.getExecution().data()),
                initial.getMediation());
    }
}
