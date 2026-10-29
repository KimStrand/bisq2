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

import bisq.common.processing.Effect;
import bisq.common.processing.ScheduledInput;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import com.google.protobuf.Timestamp;

import java.time.Instant;

import static com.google.common.base.Preconditions.checkArgument;

/** A one-time delivery at an absolute deadline. Its id is scoped to the owning protocol. */
public record ScheduledEvent<E extends Event>(String id, Instant triggerAt, E event) implements Effect, ScheduledInput<E> {
    public ScheduledEvent {
        checkArgument(!id.isBlank(), "Scheduled event id must not be blank");
    }

    @Override
    public E input() {
        return event;
    }

    @Override
    public bisq.common.protobuf.ScheduledEvent.Builder getBuilder(boolean serializeForHash) {
        return bisq.common.protobuf.ScheduledEvent.newBuilder()
                .setId(id)
                .setTriggerAt(Timestamp.newBuilder().setSeconds(triggerAt.getEpochSecond()).setNanos(triggerAt.getNano()))
                .setEvent(event.toEventProto(serializeForHash));
    }

    @Override
    public bisq.common.protobuf.ScheduledEvent toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static <E extends Event> ScheduledEvent<E> fromProto(bisq.common.protobuf.ScheduledEvent proto,
                                                                ProtoResolver<E> eventResolver) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasTriggerAt() && proto.hasEvent(), "Missing scheduled event data");
            checkArgument(proto.getTriggerAt().getNanos() >= 0 && proto.getTriggerAt().getNanos() < 1_000_000_000,
                    "Invalid scheduled event timestamp");
            return new ScheduledEvent<>(proto.getId(),
                    Instant.ofEpochSecond(proto.getTriggerAt().getSeconds(), proto.getTriggerAt().getNanos()),
                    Event.fromProto(proto.getEvent(), eventResolver));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
