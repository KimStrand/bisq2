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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;

import bisq.common.processing.Effect;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.CustomPayoutFinalizationHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MediationResultByMyselfAcceptedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigProcessingFailedHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigReportErrorMessageHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositConfirmationSkippedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositTxConfirmedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.CooperativeClosureMessage_G_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.PaymentInitiatedMessage_E_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.PaymentReceiptConfirmedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.SellersCloseTradeTimeoutEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.taker.SendAccountPayloadMessage_Handler;

import java.util.List;

/**
 * Seller-as-taker execution, including normal, mediated and forced closure. Each rule names its source stage, the input it accepts,
 * the handler that runs and the stage it reaches. The target is checked against the handler's
 * declared return type, and the handler's own source and event types are checked against
 * the rule arguments, so a rule that disagrees with its handler does not compile.
 * <p>
 * Inputs reuse MuSig events and messages, plus the facts raised by mediation. The stages are
 * restricted by this role's sealed state interface. The MuSig owner drives both flows.
 * <p>
 * Reopening mediation is not covered.
 */
public final class SellerAsTakerProtocol extends MuSigExecutionProtocol<SellerAsTakerState> {
    private static final RuleTable<SellerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<SellerAsTakerState, MuSigV2TradeExecutionData, Event, MuSigProtocolContext>builder()
                    .rule(Init.class,
                            MuSigTakeOfferEvent.class,
                            MuSigTakeOfferEventHandler::new,
                            TradeInitialized.class)

                    .rule(TradeInitialized.class,
                            SetupTradeMessage_B.class,
                            SetupTradeMessage_B_Handler::new,
                            NonceSharesAndPartialSignaturesCreated.class)

                    .rule(NonceSharesAndPartialSignaturesCreated.class,
                            SetupTradeMessage_D.class,
                            SetupTradeMessage_D_Handler::new,
                            DepositTxSignedAndPublished.class)

                    .rule(DepositTxSignedAndPublished.class,
                            SendAccountPayloadMessage.class,
                            SendAccountPayloadMessage_Handler::new,
                            AccountPayloadReceived.class)

                    .rule(AccountPayloadReceived.class,
                            DepositTxConfirmedEvent.class,
                            DepositTxConfirmedEventHandler::new,
                            DepositTxConfirmed.class)
                    .rule(AccountPayloadReceived.class,
                            DepositConfirmationSkippedEvent.class,
                            DepositConfirmationSkippedEventHandler::new,
                            DepositTxConfirmed.class)

                    .rule(DepositTxConfirmed.class,
                            PaymentInitiatedMessage_E.class,
                            PaymentInitiatedMessage_E_Handler::new,
                            PaymentInitiatedMessageReceived.class)

                    .rule(PaymentInitiatedMessageReceived.class,
                            PaymentReceiptConfirmedEvent.class,
                            PaymentReceiptConfirmedEventHandler::new,
                            PaymentReceiptConfirmed.class)

                    .rule(PaymentReceiptConfirmed.class,
                            CooperativeClosureMessage_G.class,
                            CooperativeClosureMessage_G_Handler::new,
                            TradeClosed.class)

                    .rule(PaymentReceiptConfirmed.class,
                            SellersCloseTradeTimeoutEvent.class,
                            SellersCloseTradeTimeoutEventHandler::new,
                            SellerForceClosedTrade.class)

                    .rule(DepositTxConfirmed.class,
                            MediationResultByMyselfAcceptedEvent.class,
                            MediationResultByMyselfAcceptedEventHandler::new,
                            CustomPayoutSigned.class)

                    .rule(PaymentInitiatedMessageReceived.class,
                            MediationResultByMyselfAcceptedEvent.class,
                            MediationResultByMyselfAcceptedEventHandler::new,
                            CustomPayoutSigned.class)

                    .rule(CustomPayoutSigned.class,
                            MediationResultByPeerAcceptedEvent.class,
                            CustomPayoutFinalizationHandler::new,
                            CustomPayoutClosedTrade.class)

                    .fromAny(MuSigProcessingFailedEvent.class,
                            MuSigProcessingFailedHandler::new,
                            Failed.class)
                    .fromAny(MuSigReportErrorMessage.class,
                            MuSigReportErrorMessageHandler::new,
                            FailedAtPeer.class)
                    .build();

    @Override
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends SellerAsTakerState, MuSigV2TradeExecutionData> flow) {
        return flow.state() instanceof AccountPayloadReceived ? List.of(new ObserveDepositTxConfirmation()) : List.of();
    }

    /**
     * Exposed for the table checks; a running protocol uses the inherited instance method.
     */
    public static RuleTable<SellerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> transitions() {
        return RULES;
    }

    @Override
    protected RuleTable<SellerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
