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

package bisq.trade.mu_sig_v2.protocol.events.mediation;

import bisq.common.data.ByteArray;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.trade.mu_sig_v2.protocol.events.InternalEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigTradeEvent;
import com.google.protobuf.ByteString;
import lombok.EqualsAndHashCode;
import lombok.ToString;

/**
 * The local trader accepted the mediation result which has this hash. The message which tells the peer about the
 * acceptance names the result in the same way. Execution signs the payout only if the stored result has this hash.
 */
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public final class MediationResultByMyselfAcceptedEvent extends MuSigTradeEvent implements InternalEvent {
    private final ByteArray mediationResultHash;

    public MediationResultByMyselfAcceptedEvent(ByteArray mediationResultHash) {
        this.mediationResultHash = new ByteArray(mediationResultHash.getBytes().clone());
    }

    public ByteArray getMediationResultHash() {
        return new ByteArray(mediationResultHash.getBytes().clone());
    }

    @Override
    protected bisq.trade.protobuf.MuSigTradeEvent.Builder getMuSigTradeEventBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigTradeEvent.newBuilder()
                .setMediationResultByMyselfAccepted(bisq.trade.protobuf.MediationResultByMyselfAcceptedEvent.newBuilder()
                        .setMediationResultHash(ByteString.copyFrom(mediationResultHash.getBytes())));
    }

    public static MediationResultByMyselfAcceptedEvent fromProto(bisq.trade.protobuf.MediationResultByMyselfAcceptedEvent proto) throws UnresolvableProtobufException {
        try {
            return new MediationResultByMyselfAcceptedEvent(new ByteArray(proto.getMediationResultHash().toByteArray()));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
