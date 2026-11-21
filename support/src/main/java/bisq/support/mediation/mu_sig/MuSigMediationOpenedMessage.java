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
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.common.statemachine.Event;
import bisq.common.validation.NetworkDataValidation;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.ExternalNetworkMessage;
import bisq.network.p2p.message.SenderPublicKeyProvidingPayload;
import bisq.network.p2p.services.data.storage.MetaData;
import bisq.network.p2p.services.data.storage.StoragePriority;
import bisq.network.p2p.services.data.storage.mailbox.MailboxMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.security.PublicKey;

import static bisq.network.p2p.services.data.storage.MetaData.TTL_10_DAYS;

/**
 * The mediator sends this message to both traders when it has opened the mediation case of their trade.
 */
@Slf4j
@Getter
@ToString
@EqualsAndHashCode
public final class MuSigMediationOpenedMessage implements MailboxMessage, ExternalNetworkMessage,
        SenderPublicKeyProvidingPayload, Event {
    public static final MetaData META_DATA = new MetaData(TTL_10_DAYS, StoragePriority.HIGH, MuSigMediationOpenedMessage.class.getSimpleName());
    private final String id;
    private final String tradeId;
    private final NetworkId senderNetworkId;

    public MuSigMediationOpenedMessage(String id, String tradeId, NetworkId senderNetworkId) {
        this.id = id;
        this.tradeId = tradeId;
        this.senderNetworkId = senderNetworkId;
        verify();
    }

    @Override
    public void verify() {
        NetworkDataValidation.validateId(id);
        NetworkDataValidation.validateTradeId(tradeId);
    }

    @Override
    public bisq.support.protobuf.MuSigMediationOpenedMessage.Builder getValueBuilder(boolean serializeForHash) {
        return bisq.support.protobuf.MuSigMediationOpenedMessage.newBuilder()
                .setId(id)
                .setTradeId(tradeId)
                .setSenderNetworkId(senderNetworkId.toProto(serializeForHash));
    }

    public static MuSigMediationOpenedMessage fromProto(bisq.support.protobuf.MuSigMediationOpenedMessage proto)
            throws UnresolvableProtobufException {
        try {
            return new MuSigMediationOpenedMessage(
                    proto.getId(),
                    proto.getTradeId(),
                    NetworkId.fromProto(proto.getSenderNetworkId())
            );
        } catch (RuntimeException e) {
            throw new UnresolvableProtobufException(e);
        }
    }

    public static ProtoResolver<ExternalNetworkMessage> getNetworkMessageResolver() {
        return any -> {
            try {
                bisq.support.protobuf.MuSigMediationOpenedMessage proto =
                        any.unpack(bisq.support.protobuf.MuSigMediationOpenedMessage.class);
                return MuSigMediationOpenedMessage.fromProto(proto);
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
}
