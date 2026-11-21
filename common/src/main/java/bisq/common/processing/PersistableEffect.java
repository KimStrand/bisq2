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

import bisq.common.proto.PersistableProto;
import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import com.google.protobuf.Any;

import static com.google.common.base.Preconditions.checkArgument;

/** Effect retained in the checkpoint. Persistence does not determine its execution failure policy. */
public non-sealed interface PersistableEffect extends Effect, PersistableProto {
    /**
     * What the lane does if executing this effect fails. Saved work is a barrier unless the effect declares
     * otherwise.
     */
    default EffectFailureRule failureRule() {
        return EffectFailureRule.BARRIER;
    }

    default bisq.common.protobuf.Effect toEffectProto(boolean serializeForHash) {
        return bisq.common.protobuf.Effect.newBuilder()
                .setPayload(Any.pack(toProto(serializeForHash)))
                .build();
    }

    static PersistableEffect fromProto(bisq.common.protobuf.Effect proto,
                                       ProtoResolver<? extends PersistableEffect> resolver) throws UnresolvableProtobufException {
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
}
