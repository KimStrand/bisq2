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

package bisq.trade.mu_sig_v2;

import bisq.common.processing.Checkpoint;
import bisq.common.processing.Command;
import bisq.common.processing.Effect;
import bisq.common.processing.PendingWork;
import bisq.common.proto.PersistableProto;
import bisq.common.proto.ProtobufUtils;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Delivery;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.ScheduledEvent;
import bisq.common.statemachine.StateMachineResolver;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.MuSigV2EventType;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.google.common.base.Preconditions.checkArgument;

/** One owner's document, per-flow processing history and unfinished work, saved together. */
public record MuSigV2TradeRecord(MuSigV2Trade<?> trade,
                                 Map<Flow, Set<Class<?>>> processedEventTypes,
                                 List<Delivery<Flow, Event>> pendingInputs,
                                 List<ScheduledEvent<Event>> scheduledEvents,
                                 List<Effect> pendingEffects) implements PersistableProto {
    public MuSigV2TradeRecord {
        var history = new EnumMap<Flow, Set<Class<?>>>(Flow.class);
        for (var flow : Flow.values()) {
            if (flow == Flow.TRADE || flow == Flow.EXECUTION || trade.getMediation().isPresent()) {
                history.put(flow, Set.copyOf(processedEventTypes.getOrDefault(flow, Set.of())));
            }
        }
        checkArgument(history.keySet().containsAll(processedEventTypes.keySet()),
                "Processing history contains a flow that is absent from the trade");
        processedEventTypes = Map.copyOf(history);
        pendingInputs = List.copyOf(pendingInputs);
        scheduledEvents = List.copyOf(scheduledEvents);
        pendingEffects = List.copyOf(pendingEffects);
        checkArgument(scheduledEvents.stream().map(ScheduledEvent::id).distinct().count() == scheduledEvents.size(),
                "Duplicate scheduled event id");
    }

    public static MuSigV2TradeRecord initial(MuSigV2Trade<?> trade) {
        return new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(), List.of());
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeRecord.Builder getBuilder(boolean serializeForHash) {
        return bisq.trade.protobuf.MuSigV2TradeRecord.newBuilder()
                .setTrade(trade.toProto(serializeForHash))
                .putAllProcessedEventTypes(processedEventTypes.entrySet().stream()
                        .collect(Collectors.toMap(entry -> entry.getKey().name(),
                                entry -> processedEventTypesToProto(entry.getValue()))))
                .addAllPendingInputs(pendingInputs.stream()
                        .map(input -> input.toProto(serializeForHash, Flow::name)).toList())
                .addAllScheduledEvents(scheduledEvents.stream().map(event -> event.toProto(serializeForHash)).toList())
                .addAllPendingEffects(pendingEffects.stream().map(effect -> effect.toEffectProto(serializeForHash)).toList());
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeRecord toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static MuSigV2TradeRecord fromProto(bisq.trade.protobuf.MuSigV2TradeRecord proto) throws UnresolvableProtobufException {
        try {
            checkArgument(proto.hasTrade(), "Missing persisted trade");
            var record = new MuSigV2TradeRecord(MuSigV2Trade.fromProto(proto.getTrade()),
                    ProtobufUtils.fromProtoEntryStream(proto.getProcessedEventTypesMap(),
                                    MuSigV2TradeRecord::processedEventTypesFromProto)
                            .collect(Collectors.toMap(entry -> Flow.valueOf(entry.getKey()), Map.Entry::getValue)),
                    ProtobufUtils.fromProtoStream(proto.getPendingInputsList(), input -> Delivery.fromProto(input,
                                    Flow::valueOf,
                                    StateMachineResolver::eventFromAny))
                            .toList(),
                    ProtobufUtils.fromProtoStream(proto.getScheduledEventsList(), event -> ScheduledEvent.fromProto(event, StateMachineResolver::eventFromAny)).toList(),
                    ProtobufUtils.fromProtoStream(proto.getPendingEffectsList(), effect -> Effect.fromProto(effect, StateMachineResolver::effectFromAny)).toList());
            checkArgument(proto.getProcessedEventTypesCount() == record.processedEventTypes().size(),
                    "Missing persisted processing history: a trade document alone cannot be resumed");
            return record;
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    /** Restores lane data without creating a processor or starting any work. */
    public Checkpoint<StateMachineSnapshot<MuSigV2Trade<?>, Flow>, Event, Delivery<Flow, Event>> toCheckpoint() {
        return new Checkpoint<>(new StateMachineSnapshot<>(trade, processedEventTypes),
                pendingInputs.stream()
                        .map(input -> new PendingWork<>(input, input.event() instanceof Command))
                        .toList(),
                pendingEffects,
                List.copyOf(scheduledEvents));
    }

    /** Uses the existing persistence format for the lane's snapshot and unfinished work. */
    public static <T extends MuSigV2Trade<?>> MuSigV2TradeRecord fromCheckpoint(
            Checkpoint<StateMachineSnapshot<T, Flow>, Event, Delivery<Flow, Event>> checkpoint) {
        for (var work : checkpoint.pendingWork()) {
            // MuSig persists the event type, from which its command classification is restored.
            checkArgument(work.command() == (work.value().event() instanceof Command),
                    "Pending input command classification does not match event type: %s",
                    work.value().event().getClass().getSimpleName());
        }
        var snapshot = checkpoint.snapshot();
        return new MuSigV2TradeRecord(snapshot.document(),
                snapshot.processedEventTypes(),
                checkpoint.pendingWork().stream().map(PendingWork::value).toList(),
                checkpoint.scheduledInputs().stream()
                        .map(input -> new ScheduledEvent<>(input.id(), input.triggerAt(), input.input()))
                        .toList(),
                checkpoint.pendingEffects());
    }


    private static bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes processedEventTypesToProto(
            Set<Class<?>> eventClasses) {
        return bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes.newBuilder()
                .addAllTypes(eventClasses.stream()
                        .map(MuSigV2EventType::fromEventClass)
                        .map(MuSigV2EventType::toProtoEnum)
                        .sorted(Comparator.comparingInt(bisq.trade.protobuf.MuSigV2EventType::getNumber))
                        .toList())
                .build();
    }

    private static Set<Class<?>> processedEventTypesFromProto(
            bisq.trade.protobuf.MuSigV2TradeRecord.ProcessedEventTypes proto) throws UnresolvableProtobufException {
        try {
            return ProtobufUtils.fromProtoStream(proto.getTypesList(), MuSigV2EventType::fromProto)
                    .map(MuSigV2EventType::getEventClass)
                    .collect(Collectors.toUnmodifiableSet());
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
