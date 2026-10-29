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

import bisq.common.data.StringLongPair;
import bisq.common.processing.Effect;
import bisq.common.proto.NetworkProto;
import bisq.common.proto.ProtobufUtils;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;
import com.google.protobuf.StringValue;
import com.google.protobuf.Timestamp;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateMachinePersistenceTest {
    @Test
    void sharedResolverRestoresNestedWorkAndChecksPayloadCategories() throws Exception {
        StateMachineResolver.addResolver("google.protobuf.StringValue", StringValue.class,
                proto -> new TestEvent(proto.getValue()));
        for (Effect effect : new Effect[]{
                new ScheduledEvent<>("timeout", Instant.EPOCH, new TestEvent("expired")),
                new CancelScheduledEvent("timeout")}) {
            var proto = bisq.common.protobuf.Effect.parseFrom(effect.toEffectProto(false).toByteArray());
            assertEquals(effect, Effect.fromProto(proto, StateMachineResolver::effectFromAny));
        }

        assertThrows(UnresolvableProtobufException.class, () -> StateMachineResolver.eventFromAny(
                Any.pack(new CancelScheduledEvent("timeout").toProto(false))));
        assertThrows(UnresolvableProtobufException.class, () -> StateMachineResolver.effectFromAny(
                Any.pack(new TestEvent("expired").toProto(false))));
        var unknown = Any.newBuilder().setTypeUrl("type.googleapis.com/unknown.Payload").build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> StateMachineResolver.fromAny(unknown));
    }

    @Test
    void inputsAndFlowIdentifiersRoundTripWithoutADependencyOnTheTradeModule() throws Exception {
        var input = new Delivery<>(42, new TestEvent("received"));
        var proto = bisq.common.protobuf.Delivery.parseFrom(input.toProto(false, Object::toString).toByteArray());

        assertEquals("42", proto.getFlow());
        assertEquals(input, Delivery.fromProto(proto, Integer::valueOf, StateMachinePersistenceTest::eventFromAny));
    }

    @Test
    void aScheduledEventPreservesItsIdDeadlineAndPayload() throws Exception {
        var deadline = Instant.parse("2030-01-01T00:00:00.123456789Z");
        var scheduled = new ScheduledEvent<>("timeout", deadline, new TestEvent("expired"));
        var proto = bisq.common.protobuf.ScheduledEvent.parseFrom(scheduled.toProto(false).toByteArray());

        assertEquals(scheduled, ScheduledEvent.fromProto(proto, StateMachinePersistenceTest::eventFromAny));
        assertEquals(123_456_789, proto.getTriggerAt().getNanos());
    }

    @Test
    void sendingRetainsTheOriginalMessageAndRecipient() throws Exception {
        var send = new Effect.SendMessage<>(new TestMessage("original-message"), new StringLongPair("recipient", 7L));
        var proto = bisq.common.protobuf.SendMessage.parseFrom(send.toProto(false).toByteArray());

        assertEquals(send, Effect.SendMessage.fromProto(proto,
                any -> new TestMessage(unpack(any, StringValue.class).getValue()),
                any -> StringLongPair.fromProto(unpack(any, bisq.common.protobuf.StringLongPair.class))));
    }

    @Test
    void theEffectEnvelopeSupportsSharedAndDomainEffects() throws Exception {
        for (Effect effect : new Effect[]{
                new Effect.SendMessage<>(new TestMessage("message"), new StringLongPair("recipient", 7L)),
                new ScheduledEvent<>("timeout", Instant.EPOCH, new TestEvent("expired")),
                new CancelScheduledEvent("timeout"),
                new CustomEffect("observe")}) {
            var proto = bisq.common.protobuf.Effect.parseFrom(effect.toEffectProto(false).toByteArray());

            assertTrue(proto.hasPayload());
            assertEquals(effect, Effect.fromProto(proto, StateMachinePersistenceTest::effectFromAny));
        }
    }

    @Test
    void missingAndUnknownPayloadsAreRejected() {
        assertThrows(UnresolvableProtobufException.class, () -> Event.fromProto(
                bisq.common.protobuf.Event.getDefaultInstance(),
                StateMachinePersistenceTest::eventFromAny));
        assertThrows(UnresolvableProtobufException.class, () -> Effect.fromProto(
                bisq.common.protobuf.Effect.getDefaultInstance(),
                StateMachinePersistenceTest::effectFromAny));
        var unknown = Any.newBuilder().setTypeUrl("type.googleapis.com/unknown.Payload").build();
        assertThrows(UnresolvableProtobufMessageException.class, () -> Event.fromProto(
                bisq.common.protobuf.Event.newBuilder().setPayload(unknown).build(),
                StateMachinePersistenceTest::eventFromAny));
        assertThrows(UnresolvableProtobufMessageException.class, () -> Effect.fromProto(
                bisq.common.protobuf.Effect.newBuilder().setPayload(unknown).build(),
                StateMachinePersistenceTest::effectFromAny));
    }

    @Test
    void incompleteDeliveriesAndOutgoingMessagesAreRejected() {
        var input = new Delivery<>(42, new TestEvent("received")).toProto(false, Object::toString);
        assertThrows(UnresolvableProtobufException.class, () -> Delivery.fromProto(input.toBuilder().clearFlow().build(),
                Integer::valueOf,
                StateMachinePersistenceTest::eventFromAny));
        assertThrows(UnresolvableProtobufException.class, () -> Delivery.fromProto(input.toBuilder().clearEvent().build(),
                Integer::valueOf,
                StateMachinePersistenceTest::eventFromAny));
        var send = new Effect.SendMessage<>(new TestMessage("message"), new StringLongPair("recipient", 7L)).toProto(false);
        for (var incomplete : new bisq.common.protobuf.SendMessage[]{
                send.toBuilder().clearMessage().build(), send.toBuilder().clearReceiver().build()}) {
            assertThrows(UnresolvableProtobufException.class, () -> Effect.SendMessage.fromProto(incomplete,
                    any -> new TestMessage(unpack(any, StringValue.class).getValue()),
                    any -> StringLongPair.fromProto(unpack(any, bisq.common.protobuf.StringLongPair.class))));
        }
    }

    @Test
    void invalidScheduledEventsAreRejected() {
        var scheduled = new ScheduledEvent<>("timeout", Instant.EPOCH, new TestEvent("expired")).toProto(false);
        for (var invalid : new bisq.common.protobuf.ScheduledEvent[]{
                scheduled.toBuilder().clearId().build(),
                scheduled.toBuilder().clearTriggerAt().build(),
                scheduled.toBuilder().clearEvent().build(),
                scheduled.toBuilder().setTriggerAt(Timestamp.newBuilder().setNanos(-1)).build(),
                scheduled.toBuilder().setTriggerAt(Timestamp.newBuilder().setNanos(1_000_000_000)).build()}) {
            assertThrows(UnresolvableProtobufException.class, () -> ScheduledEvent.fromProto(invalid,
                    StateMachinePersistenceTest::eventFromAny));
        }
    }

    private static TestEvent eventFromAny(Any any) throws UnresolvableProtobufException {
        return new TestEvent(unpack(any, StringValue.class).getValue());
    }

    private static Effect effectFromAny(Any any) throws UnresolvableProtobufException {
        return switch (ProtobufUtils.getProtoType(any)) {
            case "common.SendMessage" -> Effect.SendMessage.fromProto(unpack(any, bisq.common.protobuf.SendMessage.class),
                    payload -> new TestMessage(unpack(payload, StringValue.class).getValue()),
                    payload -> StringLongPair.fromProto(unpack(payload, bisq.common.protobuf.StringLongPair.class)));
            case "common.ScheduledEvent" -> ScheduledEvent.fromProto(unpack(any, bisq.common.protobuf.ScheduledEvent.class),
                    StateMachinePersistenceTest::eventFromAny);
            case "common.CancelScheduledEvent" -> CancelScheduledEvent.fromProto(unpack(any, bisq.common.protobuf.CancelScheduledEvent.class));
            case "google.protobuf.StringValue" -> new CustomEffect(unpack(any, StringValue.class).getValue());
            default -> throw new UnresolvableProtobufMessageException(any);
        };
    }

    private static <P extends Message> P unpack(Any any, Class<P> type) throws UnresolvableProtobufMessageException {
        try {
            return any.unpack(type);
        } catch (InvalidProtocolBufferException exception) {
            throw new UnresolvableProtobufMessageException(any, exception);
        }
    }

    private record TestMessage(String value) implements NetworkProto {
        @Override
        public StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(value);
        }

        @Override
        public void verify() {
        }
    }

    private record CustomEffect(String value) implements Effect {
        @Override
        public StringValue.Builder getBuilder(boolean serializeForHash) {
            return StringValue.newBuilder().setValue(value);
        }
    }
}
