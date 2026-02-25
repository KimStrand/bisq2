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

package bisq.support.mediation.mu_sig;

import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.validation.NetworkDataValidation;
import bisq.network.p2p.message.ExternalNetworkMessage;
import bisq.network.p2p.services.data.storage.MetaData;
import bisq.network.p2p.services.data.storage.mailbox.MailboxMessage;
import bisq.support.mediation.MediationCaseState;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

import static bisq.network.p2p.services.data.storage.MetaData.HIGH_PRIORITY;
import static bisq.network.p2p.services.data.storage.MetaData.TTL_10_DAYS;

@Slf4j
@Getter
@ToString
@EqualsAndHashCode
public final class MuSigMediationStateMessage implements MailboxMessage, ExternalNetworkMessage {
    private transient final MetaData metaData = new MetaData(TTL_10_DAYS, HIGH_PRIORITY, getClass().getSimpleName());
    private final String tradeId;
    private final MediationCaseState mediationCaseState;
    private final Optional<MuSigMediationResult> muSigMediationResult;

    public MuSigMediationStateMessage(String tradeId,
                                      MediationCaseState mediationCaseState,
                                      Optional<MuSigMediationResult> muSigMediationResult) {
        this.tradeId = tradeId;
        this.mediationCaseState = mediationCaseState;
        this.muSigMediationResult = muSigMediationResult;

        verify();
    }

    @Override
    public void verify() {
        NetworkDataValidation.validateTradeId(tradeId);
        if (mediationCaseState == MediationCaseState.CLOSED && muSigMediationResult.isEmpty()) {
            throw new IllegalArgumentException("Closed mediation case state must contain MuSigMediationResult.");
        }
    }

    @Override
    public bisq.support.protobuf.MuSigMediationStateMessage.Builder getValueBuilder(boolean serializeForHash) {
        bisq.support.protobuf.MuSigMediationStateMessage.Builder builder = bisq.support.protobuf.MuSigMediationStateMessage.newBuilder()
                .setTradeId(tradeId)
                .setMediationCaseState(mediationCaseState.toProtoEnum());
        muSigMediationResult.ifPresent(result -> builder.setMuSigMediationResult(result.toProto(serializeForHash)));
        return builder;
    }

    public static MuSigMediationStateMessage fromProto(bisq.support.protobuf.MuSigMediationStateMessage proto) {
        return new MuSigMediationStateMessage(
                proto.getTradeId(),
                MediationCaseState.fromProto(proto.getMediationCaseState()),
                proto.hasMuSigMediationResult()
                        ? Optional.of(MuSigMediationResult.fromProto(proto.getMuSigMediationResult()))
                        : Optional.empty()
        );
    }

    public static ProtoResolver<ExternalNetworkMessage> getNetworkMessageResolver() {
        return any -> {
            try {
                bisq.support.protobuf.MuSigMediationStateMessage proto = any.unpack(bisq.support.protobuf.MuSigMediationStateMessage.class);
                return MuSigMediationStateMessage.fromProto(proto);
            } catch (InvalidProtocolBufferException e) {
                throw new UnresolvableProtobufMessageException(e);
            }
        };
    }

    @Override
    public double getCostFactor() {
        return getCostFactor(0.1, 0.2);
    }
}
