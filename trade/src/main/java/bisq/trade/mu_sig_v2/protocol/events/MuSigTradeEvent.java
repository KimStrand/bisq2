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

import bisq.common.proto.UnresolvableProtobufException;
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
import bisq.trade.protocol.v2.events.TradeEvent;
import lombok.EqualsAndHashCode;

/** A local MuSig V2 input, including caller requests, notifications and raised facts. */
@EqualsAndHashCode(callSuper = true)
public abstract class MuSigTradeEvent extends TradeEvent {
    @Override
    public final bisq.trade.protobuf.TradeEvent.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.TradeEvent.newBuilder()
                .setMuSigTradeEvent(getMuSigTradeEventBuilder(serializeForHash));
    }

    protected abstract bisq.trade.protobuf.MuSigTradeEvent.Builder getMuSigTradeEventBuilder(boolean serializeForHash);

    public static MuSigTradeEvent fromProto(bisq.trade.protobuf.MuSigTradeEvent proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getEventCase()) {
                case TAKEOFFER -> new MuSigTakeOfferEvent();
                case DEPOSITTXCONFIRMED -> new DepositTxConfirmedEvent();
                case DEPOSITCONFIRMATIONSKIPPED -> new DepositConfirmationSkippedEvent();
                case PAYMENTINITIATED -> new PaymentInitiatedEvent();
                case PAYMENTRECEIPTCONFIRMED -> new PaymentReceiptConfirmedEvent();
                case SELLERSCLOSETRADETIMEOUT -> new SellersCloseTradeTimeoutEvent();
                case BUYERSCLOSETRADETIMEOUT -> new BuyersCloseTradeTimeoutEvent();
                case MEDIATIONRESULTACCEPTED -> new MediationResultAcceptedEvent();
                case MEDIATIONRESULTREJECTED -> new MediationResultRejectedEvent();
                case MEDIATIONREQUESTED -> new MediationRequestedEvent();
                case TRADEARCHIVED -> new TradeArchivedEvent();
                case EXECUTIONFAILED -> new ExecutionFailedEvent();
                case MEDIATIONOPENED -> MediationOpenedEvent.fromProto(proto.getMediationOpened());
                case MEDIATIONCLOSED -> MediationClosedEvent.fromProto(proto.getMediationClosed());
                case MEDIATIONPAYMENTDETAILSREQUESTED -> MediationPaymentDetailsRequestedEvent.fromProto(proto.getMediationPaymentDetailsRequested());
                case MEDIATIONRESULTBYMYSELFACCEPTED -> MediationResultByMyselfAcceptedEvent.fromProto(proto.getMediationResultByMyselfAccepted());
                case MEDIATIONRESULTBYPEERACCEPTED -> MediationResultByPeerAcceptedEvent.fromProto(proto.getMediationResultByPeerAccepted());
                case PROCESSINGFAILED -> MuSigProcessingFailedEvent.fromProto(proto.getProcessingFailed());
                case EVENT_NOT_SET -> throw new IllegalArgumentException("Missing or unknown persisted trade event");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
