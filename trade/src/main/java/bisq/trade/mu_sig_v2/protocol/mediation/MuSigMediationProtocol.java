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

package bisq.trade.mu_sig_v2.protocol.mediation;

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.FlowView;
import bisq.common.statemachine.RuleTable;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxSignedAndPublished;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Closed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Failed;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Init;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Open;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState.Requested;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkState;

/**
 * Owns the case lifecycle and signed result. The two decision protocols continue after case closure.
 */
public final class MuSigMediationProtocol
        extends MuSigProtocol<MuSigMediationState, MuSigV2MediationData> {
    private static final RuleTable<MuSigMediationState, MuSigV2MediationData, Event,
            MuSigProtocolContext> RULES =
            RuleTable.<MuSigMediationState, MuSigV2MediationData, Event, MuSigProtocolContext>builder()
                    .rule(Init.class,
                            MediationRequestedEvent.class,
                            MediationRequestedEventHandler::new,
                            Requested.class)

                    .rule(Init.class,
                            MuSigMediationOpenedMessage.class,
                            MuSigMediationOpenedMessageHandler::new,
                            Open.class)

                    .rule(Requested.class,
                            MuSigMediationOpenedMessage.class,
                            MuSigMediationOpenedMessageHandler::new,
                            Open.class)

                    .rule(Open.class,
                            MuSigMediationClosedMessage.class,
                            MuSigMediationClosedMessageHandler::new,
                            Closed.class)

                    .rule(Requested.class,
                            MuSigDisputeCasePaymentDetailsRequest.class,
                            MuSigDisputeCasePaymentDetailsRequestHandler::new,
                            Requested.class)

                    .rule(Open.class,
                            MuSigDisputeCasePaymentDetailsRequest.class,
                            MuSigDisputeCasePaymentDetailsRequestHandler::new,
                            Open.class)

                    .rule(Closed.class,
                            MuSigDisputeCasePaymentDetailsRequest.class,
                            MuSigDisputeCasePaymentDetailsRequestHandler::new,
                            Closed.class)

                    .fromAny(MuSigProcessingFailedEvent.class,
                            MediationProcessingFailedHandler::new,
                            Failed.class)
                    .build();

    @Override
    public boolean isFailed(MuSigMediationState state) {
        return state instanceof Failed;
    }

    @Override
    public void admit(MuSigReadOnlyContext context,
                      FlowSnapshot<? extends MuSigMediationState, MuSigV2MediationData> flow,
                      Event event) {
        if (event instanceof MediationRequestedEvent) {
            MuSigTradeExecutionState executionState = context.execution().state();
            checkInitializedAndUnresolved(executionState);
            checkState(allowsRequestOrRejection(executionState),
                    "Mediation requires the deposit transaction to be available");
        }
    }

    static void checkInitializedAndUnresolved(MuSigTradeExecutionState state) {
        checkState(!(state instanceof MuSigTradeExecutionState.Init) && !state.isClosed() && !state.isFailed(),
                "Mediation commands require an initialized, unresolved trade");
    }

    static boolean allowsRequestOrRejection(MuSigTradeExecutionState state) {
        return state instanceof DepositTxSignedAndPublished || state instanceof AccountPayloadReceived ||
                state instanceof DepositTxConfirmed || state instanceof PaymentInitiated ||
                state instanceof PaymentInitiatedMessageReceived || state instanceof PaymentReceiptConfirmed;
    }

    public static FlowView<MuSigMediationState, MuSigMediationReadOnlyData> readOnlyView(
            MuSigV2Mediation<? extends MuSigMediationState> flow) {
        MuSigMediationReadOnlyData data = new MuSigMediationReadOnlyData() {
            @Override
            public Optional<MuSigV2MediationData.SignedResult> signedResult() {
                return flow.data().signedResult();
            }

            @Override
            public MuSigMediationDecisionState myDecisionState() {
                return flow.myDecision().state();
            }

            @Override
            public MuSigMediationDecisionState peerDecisionState() {
                return flow.peerDecision().state();
            }
        };
        return new FlowView<>(flow.state(), data);
    }

    @Override
    public RuleTable<MuSigMediationState, MuSigV2MediationData, Event,
            MuSigProtocolContext> rules() {
        return RULES;
    }
}
