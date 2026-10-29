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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker;

import bisq.common.processing.Effect;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.execution.CustomPayoutFinalizationHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MediationResultByMyselfAcceptedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigProcessingFailedHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigReportErrorMessageHandler;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositConfirmationSkippedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositTxConfirmedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.maker.SendAccountPayloadAndDepositTxMessage_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.maker.SetupTradeMessage_A_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.CooperativeClosureMessage_G_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.PaymentInitiatedMessage_E_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.PaymentReceiptConfirmedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.seller.SellersCloseTradeTimeoutEventHandler;

import java.util.List;

/** Seller-as-maker execution, sharing normal, mediated and forced settlement with seller-as-taker. */
public final class SellerAsMakerProtocol extends MuSigExecutionProtocol<SellerAsMakerState> {
    private static final RuleTable<SellerAsMakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<SellerAsMakerState, MuSigV2TradeExecutionData, Event, MuSigProtocolContext>builder()
                    .rule(Init.class,
                            SetupTradeMessage_A.class,
                            SetupTradeMessage_A_Handler::new,
                            TradeInitializedAndNonceSharesCreated.class)

                    .rule(TradeInitializedAndNonceSharesCreated.class,
                            SetupTradeMessage_C.class,
                            SetupTradeMessage_C_Handler::new,
                            PartialSignaturesCreatedAndDepositTxSigned.class)

                    .rule(PartialSignaturesCreatedAndDepositTxSigned.class,
                            SendAccountPayloadAndDepositTxMessage.class,
                            SendAccountPayloadAndDepositTxMessage_Handler::new,
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
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends SellerAsMakerState, MuSigV2TradeExecutionData> flow) {
        return flow.state() instanceof AccountPayloadReceived ? List.of(new ObserveDepositTxConfirmation()) : List.of();
    }

    public static RuleTable<SellerAsMakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> transitions() {
        return RULES;
    }

    @Override
    protected RuleTable<SellerAsMakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
