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

import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;

import java.util.function.Function;

import static com.google.common.base.Preconditions.checkArgument;

/** An input and its destination. The owning domain supplies the stable representation of its flow identifiers. */
public record Delivery<F, E extends Event>(F flow, E event) {
    public bisq.common.protobuf.Delivery toProto(boolean serializeForHash, Function<? super F, String> flowWriter) {
        return bisq.common.protobuf.Delivery.newBuilder()
                .setFlow(flowWriter.apply(flow))
                .setEvent(event.toEventProto(serializeForHash))
                .build();
    }

    public static <F, E extends Event> Delivery<F, E> fromProto(bisq.common.protobuf.Delivery proto,
                                                                Function<String, F> flowReader,
                                                                ProtoResolver<E> eventResolver) throws UnresolvableProtobufException {
        try {
            checkArgument(!proto.getFlow().isBlank() && proto.hasEvent(), "Missing pending input destination or event");
            return new Delivery<>(flowReader.apply(proto.getFlow()), Event.fromProto(proto.getEvent(), eventResolver));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
