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

package bisq.common.statemachine;

import bisq.common.processing.Input;
import bisq.common.proto.Proto;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import com.google.protobuf.Any;

import static com.google.common.base.Preconditions.checkArgument;

/** A state-machine input whose domain type supplies its protobuf representation. */
public interface Event extends Input, Proto {
    default bisq.common.protobuf.Event toEventProto(boolean serializeForHash) {
        return bisq.common.protobuf.Event.newBuilder()
                .setPayload(Any.pack(toProto(serializeForHash)))
                .build();
    }

    static <E extends Event> E fromProto(bisq.common.protobuf.Event proto, ProtoResolver<E> resolver) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasPayload(), "Missing persisted event payload");
            return resolver.fromAny(proto.getPayload());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
