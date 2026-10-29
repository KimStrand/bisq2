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

package bisq.trade.mu_sig_v2.protocol.execution.buyer;

import bisq.common.statemachine.CancelScheduledEvent;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigTransitionHandler;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol.CLOSE_TRADE_TIMEOUT_ID;

/** Consumes the timeout without closing the trade; a later seller confirmation remains processable. */
@Slf4j
public final class BuyersCloseTradeTimeoutEventHandler
        extends MuSigTransitionHandler<PaymentInitiated, BuyersCloseTradeTimeoutEvent, PaymentInitiated,
        MuSigV2TradeExecutionData> {
    public BuyersCloseTradeTimeoutEventHandler(MuSigProtocolContext context,
                                               FlowSnapshot<PaymentInitiated, MuSigV2TradeExecutionData> flow) {
        super(context, flow);
    }

    @Override
    protected Transition<PaymentInitiated, MuSigV2TradeExecutionData, Event> process(BuyersCloseTradeTimeoutEvent event) {
        // TODO Obtain and validate the seller's swap transaction, then perform backend recovery before
        // marking the trade force-closed. See "Define and implement buyer recovery" in ../TODO.md.
        log.warn("Buyer closing timeout reached for trade {}. Recovery is not implemented; the trade remains open.",
                StringUtils.sanitizeForLog(tradeId));
        return new Transition<>(flow, List.of(new CancelScheduledEvent(CLOSE_TRADE_TIMEOUT_ID)));
    }
}
