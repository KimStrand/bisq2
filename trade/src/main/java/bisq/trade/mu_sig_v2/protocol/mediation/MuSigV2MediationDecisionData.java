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
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;

import java.util.Optional;

/** A decision keeps its own processing failure independently of the case and the other party's decision. */
public record MuSigV2MediationDecisionData(Optional<MuSigV2FailureDetails> failure) implements PersistableProto {
    public static MuSigV2MediationDecisionData initial() {
        return new MuSigV2MediationDecisionData(Optional.empty());
    }

    @Override
    public bisq.trade.protobuf.MuSigV2MediationDecisionData.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigV2MediationDecisionData.newBuilder();
        failure.ifPresent(details -> builder.setFailure(details.toProto(serializeForHash)));
        return builder;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2MediationDecisionData toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2MediationDecisionData fromProto(bisq.trade.protobuf.MuSigV2MediationDecisionData proto) throws UnresolvableProtobufException {
        try {
            return new MuSigV2MediationDecisionData(proto.hasFailure()
                    ? Optional.of(MuSigV2FailureDetails.fromProto(proto.getFailure())) : Optional.empty());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    public MuSigV2MediationDecisionData withFailure(MuSigV2FailureDetails details) {
        return new MuSigV2MediationDecisionData(Optional.of(details));
    }
}
