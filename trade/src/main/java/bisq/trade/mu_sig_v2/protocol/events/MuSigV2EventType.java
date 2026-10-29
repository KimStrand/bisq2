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

package bisq.trade.mu_sig_v2.protocol.events;

import bisq.common.proto.ProtoEnum;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationClosedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationOpenedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationPaymentDetailsRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import lombok.Getter;

/** Stable persisted identities for successfully processed MuSig input types. */
@Getter
public enum MuSigV2EventType implements ProtoEnum {
    TAKE_OFFER(MuSigTakeOfferEvent.class),
    SETUP_A(SetupTradeMessage_A.class),
    SETUP_B(SetupTradeMessage_B.class),
    SETUP_C(SetupTradeMessage_C.class),
    SETUP_D(SetupTradeMessage_D.class),
    ACCOUNT_PAYLOAD_AND_DEPOSIT(SendAccountPayloadAndDepositTxMessage.class),
    ACCOUNT_PAYLOAD(SendAccountPayloadMessage.class),
    PAYMENT_INITIATED(PaymentInitiatedMessage_E.class),
    PAYMENT_RECEIVED(PaymentReceivedMessage_F.class),
    COOPERATIVE_CLOSURE(CooperativeClosureMessage_G.class),
    DEPOSIT_CONFIRMED(DepositTxConfirmedEvent.class),
    DEPOSIT_CONFIRMATION_SKIPPED(DepositConfirmationSkippedEvent.class),
    PAYMENT_RECEIPT_CONFIRMED(PaymentReceiptConfirmedEvent.class),
    CLOSE_TIMEOUT(SellersCloseTradeTimeoutEvent.class),
    BUYER_CLOSE_TIMEOUT(BuyersCloseTradeTimeoutEvent.class),
    MEDIATION_REQUESTED(MediationRequestedEvent.class),
    MEDIATION_OPENED(MediationOpenedEvent.class),
    MEDIATION_CLOSED(MediationClosedEvent.class),
    MEDIATION_PAYMENT_DETAILS_REQUESTED(MediationPaymentDetailsRequestedEvent.class),
    MEDIATION_RESULT_ACCEPTED(MediationResultAcceptedEvent.class),
    MEDIATION_RESULT_REJECTED(MediationResultRejectedEvent.class),
    MEDIATION_RESULT_BY_MYSELF_ACCEPTED(MediationResultByMyselfAcceptedEvent.class),
    MEDIATION_RESULT_BY_PEER_ACCEPTED(MediationResultByPeerAcceptedEvent.class),
    PEER_CUSTOM_PAYOUT(MuSigCustomPayoutPsbtMessage.class),
    PEER_RESULT_REJECTION(MuSigMediationResultRejectionMessage.class),
    PAYMENT_INITIATED_EVENT(PaymentInitiatedEvent.class),
    PROCESSING_FAILED(MuSigProcessingFailedEvent.class),
    REPORT_ERROR(MuSigReportErrorMessage.class),
    TRADE_ARCHIVED(TradeArchivedEvent.class),
    EXECUTION_FAILED(ExecutionFailedEvent.class);

    private final Class<? extends Event> eventClass;

    MuSigV2EventType(Class<? extends Event> eventClass) {
        this.eventClass = eventClass;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2EventType toProtoEnum() {
        return bisq.trade.protobuf.MuSigV2EventType.valueOf(name());
    }

    public static MuSigV2EventType fromProto(bisq.trade.protobuf.MuSigV2EventType proto) throws UnresolvableProtobufException {
        try {
            return valueOf(proto.name());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    public static MuSigV2EventType fromEventClass(Class<?> eventClass) {
        for (MuSigV2EventType type : values()) {
            if (type.eventClass.equals(eventClass)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported persisted event type: " + eventClass);
    }
}
