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

package bisq.trade.protocol.v2.events;

import bisq.common.proto.PersistableProto;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.Event;
import bisq.trade.mu_sig_v2.protocol.events.MuSigTradeEvent;
import lombok.EqualsAndHashCode;

/** A local trade input; P2P inputs use the shared TradeMessage hierarchy. */
@EqualsAndHashCode
public abstract class TradeEvent implements Event, PersistableProto {
    @Override
    public abstract bisq.trade.protobuf.TradeEvent.Builder getBuilder(boolean serializeForHash);

    @Override
    public final bisq.trade.protobuf.TradeEvent toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    public static TradeEvent fromProto(bisq.trade.protobuf.TradeEvent proto) throws UnresolvableProtobufException {
        try {
            return switch (proto.getEventCase()) {
                case MUSIGTRADEEVENT -> MuSigTradeEvent.fromProto(proto.getMuSigTradeEvent());
                case EVENT_NOT_SET -> throw new IllegalArgumentException("Missing or unknown persisted trade event");
            };
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }
}
