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

package bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker;

import bisq.common.processing.Effect;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.RuleTable;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
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
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositConfirmationSkippedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.blockchain.DepositTxConfirmedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.buyer.BuyersCloseTradeTimeoutEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.buyer.PaymentInitiatedEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.buyer.PaymentReceivedMessage_F_Handler;
import bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler;
import bisq.trade.mu_sig_v2.protocol.execution.taker.SendAccountPayloadMessage_Handler;

import java.util.List;

/**
 * Buyer-as-taker execution, including normal and mediated closure.
 * Buyer forced closure remains a subsequent implementation step.
 */
public final class BuyerAsTakerProtocol extends MuSigExecutionProtocol<BuyerAsTakerState> {
    private static final RuleTable<BuyerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<BuyerAsTakerState, MuSigV2TradeExecutionData, Event, MuSigProtocolContext>builder()
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
                            PaymentInitiatedEvent.class,
                            PaymentInitiatedEventHandler::new,
                            PaymentInitiated.class)

                    .rule(PaymentInitiated.class,
                            BuyersCloseTradeTimeoutEvent.class,
                            BuyersCloseTradeTimeoutEventHandler::new,
                            PaymentInitiated.class)

                    .rule(PaymentInitiated.class,
                            PaymentReceivedMessage_F.class,
                            PaymentReceivedMessage_F_Handler::new,
                            TradeClosed.class)

                    .rule(DepositTxConfirmed.class,
                            MediationResultByMyselfAcceptedEvent.class,
                            MediationResultByMyselfAcceptedEventHandler::new,
                            CustomPayoutSigned.class)

                    .rule(PaymentInitiated.class,
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
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends BuyerAsTakerState, MuSigV2TradeExecutionData> flow) {
        return flow.state() instanceof AccountPayloadReceived ? List.of(new ObserveDepositTxConfirmation()) : List.of();
    }

    public static RuleTable<BuyerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> transitions() {
        return RULES;
    }

    @Override
    protected RuleTable<BuyerAsTakerState, MuSigV2TradeExecutionData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
