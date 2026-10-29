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

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.contract.mu_sig.MuSigContract;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.mu_sig.MuSigMediationPayoutResolver;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import com.google.protobuf.ByteString;

import java.security.GeneralSecurityException;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

/** Business data owned by the mediation case. Decision snapshots belong to its containing document. */
public record MuSigV2MediationData(Optional<SignedResult> signedResult,
                                   Optional<MuSigV2FailureDetails> failure)
        implements PersistableProto {
    public static MuSigV2MediationData initial() {
        return new MuSigV2MediationData(Optional.empty(),
                Optional.empty());
    }

    @Override
    public bisq.trade.protobuf.MuSigV2MediationData.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigV2MediationData.newBuilder();
        signedResult.ifPresent(result -> builder.setResult(result.result().toProto(serializeForHash))
                .setSignature(ByteString.copyFrom(result.signature())));
        failure.ifPresent(details -> builder.setFailure(details.toProto(serializeForHash)));
        return builder;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2MediationData toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2MediationData fromProto(bisq.trade.protobuf.MuSigV2MediationData proto) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasResult() == proto.hasSignature(), "Incomplete signed mediation result");
            Optional<SignedResult> result = proto.hasResult()
                    ? Optional.of(new SignedResult(MuSigMediationResult.fromProto(proto.getResult()),
                            proto.getSignature().toByteArray()))
                    : Optional.empty();
            return new MuSigV2MediationData(result,
                    proto.hasFailure() ? Optional.of(MuSigV2FailureDetails.fromProto(proto.getFailure())) : Optional.empty());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    public MuSigV2MediationData withResult(SignedResult result) {
        return new MuSigV2MediationData(Optional.of(result), failure);
    }

    public MuSigV2MediationData withFailure(MuSigV2FailureDetails details) {
        return new MuSigV2MediationData(signedResult, Optional.of(details));
    }

    public record SignedResult(MuSigMediationResult result, byte[] signature) {
        public SignedResult {
            signature = signature.clone();
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }

        public void verify(MuSigContract contract) {
            try {
                checkArgument(MuSigMediationResultService.verifyMediationResult(result,
                                signature,
                                contract,
                                contract.getMediator().orElseThrow().getPublicKey()),
                        "Invalid mediator signature or contract hash");
            } catch (GeneralSecurityException exception) {
                throw new IllegalArgumentException("Could not verify the mediator signature", exception);
            }
            if (result.getMediationPayoutDistributionType() != MediationPayoutDistributionType.NO_PAYOUT) {
                var payoutContext = MuSigMediationPayoutResolver.createPayoutContext(contract).orElseThrow();
                MuSigMediationPayoutResolver.checkPayoutAmounts(result.getMediationPayoutDistributionType(),
                        payoutContext,
                        result.getProposedBuyerPayoutAmount(),
                        result.getProposedSellerPayoutAmount(),
                        result.getPayoutAdjustmentPercentage());
            }
        }
    }
}
