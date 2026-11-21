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

package bisq.trade.mu_sig_v2.protocol.execution;

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.trade.mu_sig_v2.protocol.MuSigState;

/**
 * Execution positions shared where roles reach the same milestone. Each role's sealed
 * interface restricts its allowed states; accumulated data lives in {@link MuSigV2TradeExecutionData}.
 */
public sealed interface MuSigTradeExecutionState extends MuSigState, PersistableProto
        permits MuSigBuyerAsMakerState, MuSigBuyerAsTakerState, MuSigSellerAsMakerState, MuSigSellerAsTakerState {

    record Init() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The maker's key shares, contract signature and nonce shares are available. */
    record TradeInitializedAndNonceSharesCreated() implements MuSigBuyerAsMakerState, MuSigSellerAsMakerState {}

    /** The maker has signed the deposit; the taker must still publish it. */
    record PartialSignaturesCreatedAndDepositTxSigned() implements MuSigBuyerAsMakerState, MuSigSellerAsMakerState {}

    /** Local key shares and the contract signature are available. */
    record TradeInitialized() implements MuSigBuyerAsTakerState, MuSigSellerAsTakerState {}

    record NonceSharesAndPartialSignaturesCreated() implements MuSigBuyerAsTakerState, MuSigSellerAsTakerState {}

    record DepositTxSignedAndPublished() implements MuSigBuyerAsTakerState, MuSigSellerAsTakerState {}

    /** The peer's account matched the contract; we watch for deposit confirmation. */
    record AccountPayloadReceived() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    record DepositTxConfirmed() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The buyer released the withheld swap partial signature through message E. */
    record PaymentInitiated() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState {}

    /** The seller received the buyer's released swap partial signature. */
    record PaymentInitiatedMessageReceived() implements MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The seller confirmed receipt of payment and released the swap transaction signature. */
    record PaymentReceiptConfirmed() implements MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** Local acceptance signed a custom payout; normal settlement is no longer available. */
    record CustomPayoutSigned() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** Both parties signed the same custom payout and the backend closed the trade. Terminal. */
    record CustomPayoutClosedTrade() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The seller force-closed after the peer did not cooperate and the timeout fired. Terminal. */
    record SellerForceClosedTrade() implements MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** Local cooperative settlement completed. Terminal. */
    record TradeClosed() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** A local handler failed. Terminal under the V1-compatible policy. */
    record Failed() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The peer reported failure. Terminal under the V1-compatible policy. */
    record FailedAtPeer() implements MuSigBuyerAsMakerState, MuSigBuyerAsTakerState,
            MuSigSellerAsMakerState, MuSigSellerAsTakerState {}

    /** The trade was settled: by cooperative closure, by a custom payout or by the seller's forced closure. */
    default boolean isClosed() {
        return this instanceof TradeClosed || this instanceof CustomPayoutClosedTrade ||
                this instanceof SellerForceClosedTrade;
    }

    /** A local handler failed, or the peer reported a failure. */
    default boolean isFailed() {
        return this instanceof Failed || this instanceof FailedAtPeer;
    }

    @Override
    default bisq.trade.protobuf.MuSigTradeExecutionState.Builder getBuilder(boolean serializeForHash) {
        bisq.trade.protobuf.MuSigTradeExecutionState.Builder builder =
                bisq.trade.protobuf.MuSigTradeExecutionState.newBuilder();
        return switch (this) {
            case Failed ignored -> builder.setFailed(
                    bisq.trade.protobuf.MuSigTradeExecutionState.Failed.getDefaultInstance());
            case FailedAtPeer ignored -> builder.setFailedAtPeer(
                    bisq.trade.protobuf.MuSigTradeExecutionState.FailedAtPeer.getDefaultInstance());
            case Init ignored -> builder.setInit(
                    bisq.trade.protobuf.MuSigTradeExecutionState.Init.getDefaultInstance());
            case TradeInitializedAndNonceSharesCreated ignored -> builder.setTradeInitializedAndNonceSharesCreated(
                    bisq.trade.protobuf.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated.getDefaultInstance());
            case PartialSignaturesCreatedAndDepositTxSigned ignored -> builder.setPartialSignaturesCreatedAndDepositTxSigned(
                    bisq.trade.protobuf.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned.getDefaultInstance());
            case TradeInitialized ignored -> builder.setTradeInitialized(
                    bisq.trade.protobuf.MuSigTradeExecutionState.TradeInitialized.getDefaultInstance());
            case NonceSharesAndPartialSignaturesCreated ignored -> builder.setNonceSharesAndPartialSignaturesCreated(
                    bisq.trade.protobuf.MuSigTradeExecutionState.NonceSharesAndPartialSignaturesCreated
                            .getDefaultInstance());
            case DepositTxSignedAndPublished ignored -> builder.setDepositTxSignedAndPublished(
                    bisq.trade.protobuf.MuSigTradeExecutionState.DepositTxSignedAndPublished.getDefaultInstance());
            case AccountPayloadReceived ignored -> builder.setAccountPayloadReceived(
                    bisq.trade.protobuf.MuSigTradeExecutionState.AccountPayloadReceived.getDefaultInstance());
            case DepositTxConfirmed ignored -> builder.setDepositTxConfirmed(
                    bisq.trade.protobuf.MuSigTradeExecutionState.DepositTxConfirmed.getDefaultInstance());
            case PaymentInitiated ignored -> builder.setPaymentInitiated(
                    bisq.trade.protobuf.MuSigTradeExecutionState.PaymentInitiated.getDefaultInstance());
            case PaymentInitiatedMessageReceived ignored -> builder.setPaymentInitiatedMessageReceived(
                    bisq.trade.protobuf.MuSigTradeExecutionState.PaymentInitiatedMessageReceived.getDefaultInstance());
            case PaymentReceiptConfirmed ignored -> builder.setPaymentReceiptConfirmed(
                    bisq.trade.protobuf.MuSigTradeExecutionState.PaymentReceiptConfirmed.getDefaultInstance());
            case CustomPayoutSigned ignored -> builder.setCustomPayoutSigned(
                    bisq.trade.protobuf.MuSigTradeExecutionState.CustomPayoutSigned.getDefaultInstance());
            case CustomPayoutClosedTrade ignored -> builder.setCustomPayoutClosedTrade(
                    bisq.trade.protobuf.MuSigTradeExecutionState.CustomPayoutClosedTrade.getDefaultInstance());
            case SellerForceClosedTrade ignored -> builder.setSellerForceClosedTrade(
                    bisq.trade.protobuf.MuSigTradeExecutionState.SellerForceClosedTrade.getDefaultInstance());
            case TradeClosed ignored -> builder.setTradeClosed(
                    bisq.trade.protobuf.MuSigTradeExecutionState.TradeClosed.getDefaultInstance());
        };
    }

    @Override
    default bisq.trade.protobuf.MuSigTradeExecutionState toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    static MuSigTradeExecutionState fromProto(bisq.trade.protobuf.MuSigTradeExecutionState proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getMessageCase()) {
                case FAILED -> new Failed();
                case FAILEDATPEER -> new FailedAtPeer();
                case INIT -> new Init();
                case TRADEINITIALIZEDANDNONCESHARESCREATED -> new TradeInitializedAndNonceSharesCreated();
                case PARTIALSIGNATURESCREATEDANDDEPOSITTXSIGNED -> new PartialSignaturesCreatedAndDepositTxSigned();
                case TRADEINITIALIZED -> new TradeInitialized();
                case NONCESHARESANDPARTIALSIGNATURESCREATED -> new NonceSharesAndPartialSignaturesCreated();
                case DEPOSITTXSIGNEDANDPUBLISHED -> new DepositTxSignedAndPublished();
                case ACCOUNTPAYLOADRECEIVED -> new AccountPayloadReceived();
                case DEPOSITTXCONFIRMED -> new DepositTxConfirmed();
                case PAYMENTINITIATED -> new PaymentInitiated();
                case PAYMENTINITIATEDMESSAGERECEIVED -> new PaymentInitiatedMessageReceived();
                case PAYMENTRECEIPTCONFIRMED -> new PaymentReceiptConfirmed();
                case CUSTOMPAYOUTSIGNED -> new CustomPayoutSigned();
                case CUSTOMPAYOUTCLOSEDTRADE -> new CustomPayoutClosedTrade();
                case SELLERFORCECLOSEDTRADE -> new SellerForceClosedTrade();
                case TRADECLOSED -> new TradeClosed();
                case MESSAGE_NOT_SET -> throw new IllegalArgumentException("Missing or unknown execution state");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
