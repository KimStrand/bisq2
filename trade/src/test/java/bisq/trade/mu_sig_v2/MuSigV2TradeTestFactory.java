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
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.data.ByteArray;
import bisq.common.monetary.Monetary;
import bisq.common.processing.lane.InputValidator;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.util.StringUtils;
import bisq.contract.TradeId;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.network.identity.NetworkId;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.MuSigV2TradeData;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;
import bisq.user.banned.BannedUserService;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class MuSigV2TradeTestFactory {
    /** The completion date of a trade which {@link #tradeAt} creates in a closed state. */
    public static final long TRADE_COMPLETED_DATE = 1_750_000_600_000L;

    private MuSigV2TradeTestFactory() {
    }

    /** The input validator of a trade's lane, for tests of other packages; the class is package-private. */
    public static InputValidator<StateMachineSnapshot<? extends MuSigV2Trade<?>, Flow>, Event> inputValidator(
            BannedUserService bannedUserService,
            MuSigOpenTradeChannelService tradeChannelService) {
        return new MuSigTradeInputValidator(bannedUserService, tradeChannelService);
    }

    /** A closing message whose result belongs to no contract and has no valid signature. */
    public static MuSigMediationClosedMessage unverifiedMediationClosedMessage(String tradeId, NetworkId mediator) {
        MuSigMediationResult result = new MuSigMediationResult(new byte[20],
                MediationResultReason.OTHER,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(100_000L),
                Optional.of(100_000L),
                Optional.empty(),
                Optional.empty());
        return new MuSigMediationClosedMessage(StringUtils.createUid(), tradeId, mediator, result, new byte[70]);
    }

    /** The raised event which says that the local trader accepted this mediation result. */
    public static MediationResultByMyselfAcceptedEvent acceptedByMyself(MuSigMediationResult result) {
        return new MediationResultByMyselfAcceptedEvent(
                new ByteArray(MuSigMediationResultService.getMediationResultHash(result)));
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
        // Derived here independently of the command, so the lookup also checks the ID under which the trade is saved.
        String id = TradeId.create(command.getOffer().getId(),
                command.getTakerIdentity().getNetworkId().getId(),
                command.getTakeOfferDate());
        return service.initializeTrade(command)
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
        var executionData = initial.getExecution().data();
        if (state.isClosed()) {
            executionData = executionData.toBuilder()
                    .tradeCompletedDate(Optional.of(TRADE_COMPLETED_DATE))
                    .build();
        }
        return new MuSigV2Trade<>(initial.getId(),
                initial.getState(),
                new MuSigV2TradeData(contract,
                        myIdentity,
                        myRole,
                        protocolVersion,
                        myAccountPayload),
                new MuSigV2TradeExecution<>(state, executionData),
                initial.getMediation());
    }
}
