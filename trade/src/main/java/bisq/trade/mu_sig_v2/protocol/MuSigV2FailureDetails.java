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

package bisq.trade.mu_sig_v2.protocol;

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.trade.exceptions.TradeProtocolFailure;

public record MuSigV2FailureDetails(TradeProtocolFailure tradeProtocolFailure,
                                    String errorMessage,
                                    String errorStackTrace) implements PersistableProto {
    @Override
    public bisq.trade.protobuf.MuSigV2FailureDetails.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigV2FailureDetails.newBuilder()
                .setTradeProtocolFailure(tradeProtocolFailure.toProtoEnum())
                .setErrorMessage(errorMessage)
                .setErrorStackTrace(errorStackTrace);
    }

    @Override
    public bisq.trade.protobuf.MuSigV2FailureDetails toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2FailureDetails fromProto(bisq.trade.protobuf.MuSigV2FailureDetails proto) throws UnresolvableProtobufException {
        try {
            return new MuSigV2FailureDetails(TradeProtocolFailure.fromProto(proto.getTradeProtocolFailure()),
                    proto.getErrorMessage(),
                    proto.getErrorStackTrace());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
