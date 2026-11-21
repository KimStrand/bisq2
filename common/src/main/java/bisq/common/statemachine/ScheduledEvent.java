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

import bisq.common.processing.ScheduledInput;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * An event which a timer delivers once, at an absolute deadline.
 * It is persistable because the deadline is saved with the snapshot. The deadline has millisecond precision, which
 * is what the saved form keeps.
 *
 * @param <E> event family accepted and raised by the state machine
 * @param id the id of the timer, scoped to the owning protocol
 * @param deadline the absolute time at which the timer delivers the event
 * @param event the event which the timer delivers
 */
public record ScheduledEvent<E extends Event>(String id, Instant deadline, E event)
        implements ScheduledInput<E>, PersistableProto {
    public ScheduledEvent {
        checkArgument(!id.isBlank(), "Scheduled event id must not be blank");
        deadline = deadline.truncatedTo(ChronoUnit.MILLIS);
    }

    @Override
    public E input() {
        return event;
    }

    @Override
    public bisq.common.protobuf.ScheduledEvent.Builder getBuilder(boolean serializeForHash) {
        return bisq.common.protobuf.ScheduledEvent.newBuilder()
                .setId(id)
                .setDeadline(deadline.toEpochMilli())
                .setEvent(event.toEventProto(serializeForHash));
    }

    @Override
    public bisq.common.protobuf.ScheduledEvent toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static <E extends Event> ScheduledEvent<E> fromProto(bisq.common.protobuf.ScheduledEvent proto,
                                                                ProtoResolver<E> eventResolver) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasEvent(), "Missing scheduled event data");
            return new ScheduledEvent<>(proto.getId(),
                    Instant.ofEpochMilli(proto.getDeadline()),
                    Event.fromProto(proto.getEvent(), eventResolver));
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
