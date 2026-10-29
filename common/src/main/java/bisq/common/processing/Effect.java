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

package bisq.common.processing;

import bisq.common.proto.NetworkProto;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.Proto;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import com.google.protobuf.Any;

import static com.google.common.base.Preconditions.checkArgument;

/** Outgoing work described by a processor and performed after its producing step commits. */
public interface Effect extends PersistableProto {
    default bisq.common.protobuf.Effect toEffectProto(boolean serializeForHash) {
        return bisq.common.protobuf.Effect.newBuilder()
                .setPayload(Any.pack(toProto(serializeForHash)))
                .build();
    }

    static Effect fromProto(bisq.common.protobuf.Effect proto, ProtoResolver<? extends Effect> resolver) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasPayload(), "Missing persisted effect payload");
            return resolver.fromAny(proto.getPayload());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    /**
     * Whether recovery replays a saved pending request. Work reconstructed from the current processor snapshot
     * returns false; the processor supplies any still-required work during recovery instead.
     */
    default boolean replayOnRestart() {
        return true;
    }

    /** A prepared payload and its destination; the adapter supplies the sending identity. */
    record SendMessage<M extends NetworkProto, R extends Proto>(M message, R receiver) implements Effect {
        @Override
        public bisq.common.protobuf.SendMessage.Builder getBuilder(boolean serializeForHash) {
            return bisq.common.protobuf.SendMessage.newBuilder()
                    .setMessage(Any.pack(message.toProto(serializeForHash)))
                    .setReceiver(Any.pack(receiver.toProto(serializeForHash)));
        }

        @Override
        public bisq.common.protobuf.SendMessage toProto(boolean serializeForHash) {
            return unsafeToProto(serializeForHash);
        }

        public static <M extends NetworkProto, R extends Proto> SendMessage<M, R> fromProto(
                bisq.common.protobuf.SendMessage proto,
                ProtoResolver<M> messageResolver,
                ProtoResolver<R> receiverResolver) throws UnresolvableProtobufException {
            try {
                checkArgument(proto.hasMessage() && proto.hasReceiver(), "Missing outgoing message or receiver");
                return new SendMessage<>(messageResolver.fromAny(proto.getMessage()),
                        receiverResolver.fromAny(proto.getReceiver()));
            } catch (RuntimeException exception) {
                throw new UnresolvableProtobufException(exception);
            }
        }
    }
}
