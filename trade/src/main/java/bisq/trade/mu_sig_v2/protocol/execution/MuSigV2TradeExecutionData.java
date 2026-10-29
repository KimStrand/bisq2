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

import bisq.account.accounts.AccountPayload;
import bisq.common.data.ByteArray;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.contract.ContractSignatureData;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomCloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.CustomPayoutPsbt;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.SwapTxSignature;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import lombok.Builder;
import lombok.Getter;

import java.util.Optional;

/**
 * Accumulated execution data. Handlers return a new value and only change fields they learn.
 * Existing MuSig payloads contain mutable arrays and must be treated as read-only.
 */
@Getter
@Builder(toBuilder = true)
public final class MuSigV2TradeExecutionData implements PersistableProto {
    @Builder.Default
    private final Optional<PubKeyShares> myPubKeyShares = Optional.empty();
    @Builder.Default
    private final Optional<ContractSignatureData> myContractSignature = Optional.empty();
    @Builder.Default
    private final Optional<ContractSignatureData> peerContractSignature = Optional.empty();
    @Builder.Default
    private final Optional<PubKeyShares> peerPubKeyShares = Optional.empty();
    @Builder.Default
    private final Optional<NonceShares> peerNonceShares = Optional.empty();
    @Builder.Default
    private final Optional<NonceSharesMessage> myNonceShares = Optional.empty();
    @Builder.Default
    private final Optional<PartialSignaturesMessage> myPartialSignatures = Optional.empty();
    @Builder.Default
    private final Optional<PartialSignatures> peerRedactedPartialSignatures = Optional.empty();
    @Builder.Default
    private final Optional<PartialSignatures> peerPartialSignatures = Optional.empty();
    @Builder.Default
    private final Optional<DepositPsbt> myDepositPsbt = Optional.empty();
    @Builder.Default
    private final Optional<DepositPsbt> peerDepositPsbt = Optional.empty();
    @Builder.Default
    private final Optional<ByteArray> peerDepositTx = Optional.empty();
    @Builder.Default
    private final Optional<AccountPayload<?>> peerAccountPayload = Optional.empty();
    @Builder.Default
    private final Optional<SwapTxSignatureResponse> mySwapTxSignatureResponse = Optional.empty();
    @Builder.Default
    private final Optional<SwapTxSignature> peerSwapTxSignature = Optional.empty();
    @Builder.Default
    private final Optional<ByteArray> peersOutputPrvKeyShare = Optional.empty();
    @Builder.Default
    private final Optional<CloseTradeResponse> myCloseTradeResponse = Optional.empty();

    @Builder.Default
    private final Optional<CustomPayoutPsbt> myCustomPayoutPsbt = Optional.empty();
    @Builder.Default
    private final Optional<PeerCustomPayoutPsbt> peerCustomPayoutPsbt = Optional.empty();
    @Builder.Default
    private final Optional<CustomCloseTradeResponse> customCloseTradeResponse = Optional.empty();

    @Builder.Default
    private final Optional<MuSigV2FailureDetails> myFailure = Optional.empty();
    @Builder.Default
    private final Optional<MuSigV2FailureDetails> peerFailure = Optional.empty();

    @Override
    public bisq.trade.protobuf.MuSigV2TradeExecutionData.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigV2TradeExecutionData.newBuilder();
        myPubKeyShares.ifPresent(value -> builder.setMyPubKeyShares(value.toProto(serializeForHash)));
        myContractSignature.ifPresent(value -> builder.setMyContractSignature(value.toProto(serializeForHash)));
        peerContractSignature.ifPresent(value -> builder.setPeerContractSignature(value.toProto(serializeForHash)));
        peerPubKeyShares.ifPresent(value -> builder.setPeerPubKeyShares(value.toProto(serializeForHash)));
        peerNonceShares.ifPresent(value -> builder.setPeerNonceShares(value.toProto(serializeForHash)));
        myNonceShares.ifPresent(value -> builder.setMyNonceShares(value.toProto(serializeForHash)));
        myPartialSignatures.ifPresent(value -> builder.setMyPartialSignatures(value.toProto(serializeForHash)));
        peerRedactedPartialSignatures.ifPresent(value -> builder.setPeerRedactedPartialSignatures(value.toProto(serializeForHash)));
        peerPartialSignatures.ifPresent(value -> builder.setPeerPartialSignatures(value.toProto(serializeForHash)));
        myDepositPsbt.ifPresent(value -> builder.setMyDepositPsbt(value.toProto(serializeForHash)));
        peerDepositPsbt.ifPresent(value -> builder.setPeerDepositPsbt(value.toProto(serializeForHash)));
        peerDepositTx.ifPresent(value -> builder.setPeerDepositTx(value.toProto(serializeForHash)));
        peerAccountPayload.ifPresent(value -> builder.setPeerAccountPayload(value.toProto(serializeForHash)));
        mySwapTxSignatureResponse.ifPresent(value -> builder.setMySwapTxSignatureResponse(value.toProto(serializeForHash)));
        peerSwapTxSignature.ifPresent(value -> builder.setPeerSwapTxSignature(value.toProto(serializeForHash)));
        peersOutputPrvKeyShare.ifPresent(value -> builder.setPeersOutputPrvKeyShare(value.toProto(serializeForHash)));
        myCloseTradeResponse.ifPresent(value -> builder.setMyCloseTradeResponse(value.toProto(serializeForHash)));
        myCustomPayoutPsbt.ifPresent(value -> builder.setMyCustomPayoutPsbt(value.toProto(serializeForHash)));
        peerCustomPayoutPsbt.ifPresent(value -> builder.setPeerCustomPayoutPsbt(value.toProto(serializeForHash)));
        customCloseTradeResponse.ifPresent(value -> builder.setCustomCloseTradeResponse(value.toProto(serializeForHash)));
        myFailure.ifPresent(value -> builder.setMyFailure(value.toProto(serializeForHash)));
        peerFailure.ifPresent(value -> builder.setPeerFailure(value.toProto(serializeForHash)));
        return builder;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeExecutionData toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2TradeExecutionData fromProto(bisq.trade.protobuf.MuSigV2TradeExecutionData proto) throws UnresolvableProtobufException {
        try {
            var builder = MuSigV2TradeExecutionData.builder();
            if (proto.hasMyPubKeyShares()) {
                builder.myPubKeyShares(Optional.of(PubKeyShares.fromProto(proto.getMyPubKeyShares())));
            }
            if (proto.hasMyContractSignature()) {
                builder.myContractSignature(Optional.of(ContractSignatureData.fromProto(proto.getMyContractSignature())));
            }
            if (proto.hasPeerContractSignature()) {
                builder.peerContractSignature(Optional.of(ContractSignatureData.fromProto(proto.getPeerContractSignature())));
            }
            if (proto.hasPeerPubKeyShares()) {
                builder.peerPubKeyShares(Optional.of(PubKeyShares.fromProto(proto.getPeerPubKeyShares())));
            }
            if (proto.hasPeerNonceShares()) {
                builder.peerNonceShares(Optional.of(NonceShares.fromProto(proto.getPeerNonceShares())));
            }
            if (proto.hasMyNonceShares()) {
                builder.myNonceShares(Optional.of(NonceSharesMessage.fromProto(proto.getMyNonceShares())));
            }
            if (proto.hasMyPartialSignatures()) {
                builder.myPartialSignatures(Optional.of(PartialSignaturesMessage.fromProto(proto.getMyPartialSignatures())));
            }
            if (proto.hasPeerRedactedPartialSignatures()) {
                builder.peerRedactedPartialSignatures(Optional.of(PartialSignatures.fromProto(proto.getPeerRedactedPartialSignatures())));
            }
            if (proto.hasPeerPartialSignatures()) {
                builder.peerPartialSignatures(Optional.of(PartialSignatures.fromProto(proto.getPeerPartialSignatures())));
            }
            if (proto.hasMyDepositPsbt()) {
                builder.myDepositPsbt(Optional.of(DepositPsbt.fromProto(proto.getMyDepositPsbt())));
            }
            if (proto.hasPeerDepositPsbt()) {
                builder.peerDepositPsbt(Optional.of(DepositPsbt.fromProto(proto.getPeerDepositPsbt())));
            }
            if (proto.hasPeerDepositTx()) {
                builder.peerDepositTx(Optional.of(ByteArray.fromProto(proto.getPeerDepositTx())));
            }
            if (proto.hasPeerAccountPayload()) {
                builder.peerAccountPayload(Optional.of(AccountPayload.fromProto(proto.getPeerAccountPayload())));
            }
            if (proto.hasMySwapTxSignatureResponse()) {
                builder.mySwapTxSignatureResponse(Optional.of(SwapTxSignatureResponse.fromProto(proto.getMySwapTxSignatureResponse())));
            }
            if (proto.hasPeerSwapTxSignature()) {
                builder.peerSwapTxSignature(Optional.of(SwapTxSignature.fromProto(proto.getPeerSwapTxSignature())));
            }
            if (proto.hasPeersOutputPrvKeyShare()) {
                builder.peersOutputPrvKeyShare(Optional.of(ByteArray.fromProto(proto.getPeersOutputPrvKeyShare())));
            }
            if (proto.hasMyCloseTradeResponse()) {
                builder.myCloseTradeResponse(Optional.of(CloseTradeResponse.fromProto(proto.getMyCloseTradeResponse())));
            }
            if (proto.hasMyCustomPayoutPsbt()) {
                builder.myCustomPayoutPsbt(Optional.of(CustomPayoutPsbt.fromProto(proto.getMyCustomPayoutPsbt())));
            }
            if (proto.hasPeerCustomPayoutPsbt()) {
                builder.peerCustomPayoutPsbt(Optional.of(PeerCustomPayoutPsbt.fromProto(proto.getPeerCustomPayoutPsbt())));
            }
            if (proto.hasCustomCloseTradeResponse()) {
                builder.customCloseTradeResponse(Optional.of(CustomCloseTradeResponse.fromProto(proto.getCustomCloseTradeResponse())));
            }
            if (proto.hasMyFailure()) {
                builder.myFailure(Optional.of(MuSigV2FailureDetails.fromProto(proto.getMyFailure())));
            }
            if (proto.hasPeerFailure()) {
                builder.peerFailure(Optional.of(MuSigV2FailureDetails.fromProto(proto.getPeerFailure())));
            }
            return builder.build();
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
