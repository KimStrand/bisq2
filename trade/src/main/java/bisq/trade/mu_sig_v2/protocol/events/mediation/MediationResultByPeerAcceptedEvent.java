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

import bisq.common.proto.UnresolvableProtobufException;
import bisq.trade.mu_sig.PeerCustomPayoutPsbt;
import bisq.trade.mu_sig_v2.protocol.events.MuSigTradeEvent;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@ToString(callSuper = true)
@Getter
@EqualsAndHashCode(callSuper = true)
public final class MediationResultByPeerAcceptedEvent extends MuSigTradeEvent implements InternalEvent {
    private final PeerCustomPayoutPsbt psbt;

    public MediationResultByPeerAcceptedEvent(PeerCustomPayoutPsbt psbt) {
        this.psbt = psbt;
    }

    @Override
    protected bisq.trade.protobuf.MuSigTradeEvent.Builder getMuSigTradeEventBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigTradeEvent.newBuilder()
                .setMediationResultByPeerAccepted(bisq.trade.protobuf.MediationResultByPeerAcceptedEvent.newBuilder()
                        .setPsbt(psbt.toProto(serializeForHash)));
    }

    public static MediationResultByPeerAcceptedEvent fromProto(bisq.trade.protobuf.MediationResultByPeerAcceptedEvent proto) throws UnresolvableProtobufException {
        try {
            return new MediationResultByPeerAcceptedEvent(PeerCustomPayoutPsbt.fromProto(proto.getPsbt()));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
