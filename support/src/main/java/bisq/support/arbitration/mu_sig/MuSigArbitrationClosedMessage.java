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

package bisq.support.arbitration.mu_sig;

import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.validation.NetworkDataValidation;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.ExternalNetworkMessage;
import bisq.network.p2p.message.SenderPublicKeyProvidingPayload;
import bisq.network.p2p.services.data.storage.MetaData;
import bisq.network.p2p.services.data.storage.StoragePriority;
import bisq.network.p2p.services.data.storage.mailbox.MailboxMessage;
import com.google.protobuf.ByteString;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;

import static bisq.network.p2p.services.data.storage.MetaData.TTL_10_DAYS;
import static com.google.common.base.Preconditions.checkArgument;

/**
 * The arbitrator sends this message to both traders when it has closed the arbitration case of their trade. It always
 * carries the result and the signature of the arbitrator for it.
 */
@Slf4j
@Getter
@ToString
@EqualsAndHashCode
public final class MuSigArbitrationClosedMessage implements MailboxMessage, ExternalNetworkMessage, SenderPublicKeyProvidingPayload {
    public static final MetaData META_DATA = new MetaData(TTL_10_DAYS, StoragePriority.HIGH, MuSigArbitrationClosedMessage.class.getSimpleName());
    private final String id;
    private final String tradeId;
    private final NetworkId senderNetworkId;
    private final MuSigArbitrationResult muSigArbitrationResult;
    private final byte[] arbitrationResultSignature;

    public MuSigArbitrationClosedMessage(String id,
                                    String tradeId,
                                    NetworkId senderNetworkId,
                                    MuSigArbitrationResult muSigArbitrationResult,
                                    byte[] arbitrationResultSignature) {
        this.id = id;
        this.tradeId = tradeId;
        this.senderNetworkId = senderNetworkId;
        this.muSigArbitrationResult = muSigArbitrationResult;
        this.arbitrationResultSignature = arbitrationResultSignature.clone();
        verify();
    }

    @Override
    public void verify() {
        NetworkDataValidation.validateId(id);
        NetworkDataValidation.validateTradeId(tradeId);
        NetworkDataValidation.validateECSignature(arbitrationResultSignature);
    }

    @Override
    public bisq.support.protobuf.MuSigArbitrationClosedMessage.Builder getValueBuilder(boolean serializeForHash) {
        return bisq.support.protobuf.MuSigArbitrationClosedMessage.newBuilder()
                .setId(id)
                .setTradeId(tradeId)
                .setSenderNetworkId(senderNetworkId.toProto(serializeForHash))
                .setMuSigArbitrationResult(muSigArbitrationResult.toProto(serializeForHash))
                .setArbitrationResultSignature(ByteString.copyFrom(arbitrationResultSignature));
    }

    public static MuSigArbitrationClosedMessage fromProto(bisq.support.protobuf.MuSigArbitrationClosedMessage proto)
            throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasMuSigArbitrationResult(), "The arbitration result is missing");
            return new MuSigArbitrationClosedMessage(
                    proto.getId(),
                    proto.getTradeId(),
                    NetworkId.fromProto(proto.getSenderNetworkId()),
                    MuSigArbitrationResult.fromProto(proto.getMuSigArbitrationResult()),
                    proto.getArbitrationResultSignature().toByteArray()
            );
        } catch (RuntimeException e) {
            throw new UnresolvableProtobufException(e);
        }
    }

    public static ProtoResolver<ExternalNetworkMessage> getNetworkMessageResolver() {
        return any -> {
            try {
                bisq.support.protobuf.MuSigArbitrationClosedMessage proto =
                        any.unpack(bisq.support.protobuf.MuSigArbitrationClosedMessage.class);
                return MuSigArbitrationClosedMessage.fromProto(proto);
            } catch (InvalidProtocolBufferException e) {
                throw new UnresolvableProtobufMessageException(e);
            }
        };
    }

    @Override
    public MetaData getMetaData() {
        return META_DATA;
    }

    @Override
    public PublicKey getSenderPublicKey() {
        return senderNetworkId.getPubKey().getPublicKey();
    }

    public byte[] getArbitrationResultSignature() {
        return arbitrationResultSignature.clone();
    }
}
