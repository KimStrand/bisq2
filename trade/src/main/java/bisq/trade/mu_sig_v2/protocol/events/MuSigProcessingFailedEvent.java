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
import bisq.common.util.ExceptionUtil;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

/** Internal failure report; network error notifications use the existing trade message. */
@ToString(callSuper = true)
@Getter
@EqualsAndHashCode(callSuper = true)
public final class MuSigProcessingFailedEvent extends MuSigTradeEvent {
    private final TradeProtocolFailure tradeProtocolFailure;
    private final String errorMessage;
    private final String stackTrace;

    public MuSigProcessingFailedEvent(TradeProtocolFailure tradeProtocolFailure,
                                      String errorMessage,
                                      String stackTrace) {
        this.tradeProtocolFailure = tradeProtocolFailure;
        this.errorMessage = errorMessage;
        this.stackTrace = stackTrace;
    }

    public static MuSigProcessingFailedEvent fromException(MuSigProtocolException exception) {
        return new MuSigProcessingFailedEvent(exception.getTradeProtocolFailure(),
                ExceptionUtil.getRootCauseMessage(exception),
                ExceptionUtil.getSafeStackTraceAsString(exception));
    }

    @Override
    protected bisq.trade.protobuf.MuSigTradeEvent.Builder getMuSigTradeEventBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigTradeEvent.newBuilder()
                .setProcessingFailed(bisq.trade.protobuf.MuSigProcessingFailedEvent.newBuilder()
                        .setTradeProtocolFailure(tradeProtocolFailure.toProtoEnum())
                        .setErrorMessage(errorMessage)
                        .setStackTrace(stackTrace));
    }

    public static MuSigProcessingFailedEvent fromProto(bisq.trade.protobuf.MuSigProcessingFailedEvent proto) throws UnresolvableProtobufException {
        try {
            return new MuSigProcessingFailedEvent(TradeProtocolFailure.fromProto(proto.getTradeProtocolFailure()),
                    proto.getErrorMessage(),
                    proto.getStackTrace());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
