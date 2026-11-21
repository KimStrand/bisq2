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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License
 * for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.common.statemachine;

import bisq.common.processing.PersistableEffect;
import bisq.common.proto.Proto;
import bisq.common.proto.ProtoConverter;
import bisq.common.proto.ProtoResolverMap;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

/** Resolves persisted state-machine payloads. Register application types before loading persisted work. */
public class StateMachineResolver {
    private static final ProtoResolverMap<Proto> protoResolverMap = new ProtoResolverMap<>() {};

    public static <P extends Message> void addResolver(String protoTypeName,
                                                      Class<P> protoType,
                                                      ProtoConverter<P, ? extends Proto> fromProto) {
        protoResolverMap.addProtoResolver(protoTypeName, any -> {
            try {
                return fromProto.fromProto(any.unpack(protoType));
            } catch (InvalidProtocolBufferException exception) {
                throw new UnresolvableProtobufMessageException(any, exception);
            }
        });
    }

    public static Proto fromAny(Any any) throws UnresolvableProtobufException {
        return protoResolverMap.fromAny(any);
    }

    public static Event eventFromAny(Any any) throws UnresolvableProtobufException {
        try {
            return Event.class.cast(fromAny(any));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    public static PersistableEffect effectFromAny(Any any) throws UnresolvableProtobufException {
        try {
            return PersistableEffect.class.cast(fromAny(any));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
