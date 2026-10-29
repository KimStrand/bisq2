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

import bisq.common.proto.ProtoResolver;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.proto.UnresolvableProtobufMessageException;
import bisq.persistence.PersistableStore;
import com.google.protobuf.InvalidProtocolBufferException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.base.Preconditions.checkArgument;

final class MuSigV2TradeStore implements PersistableStore<MuSigV2TradeStore> {
    private final Map<String, MuSigV2TradeRecord> tradeById = new HashMap<>();

    MuSigV2TradeStore() {}

    private MuSigV2TradeStore(Map<String, MuSigV2TradeRecord> trades) {
        tradeById.putAll(trades);
    }

    synchronized void put(MuSigV2TradeRecord record) {
        tradeById.put(record.trade().getId(), record);
    }

    synchronized List<MuSigV2TradeRecord> getTrades() {
        return List.copyOf(tradeById.values());
    }

    @Override
    public synchronized MuSigV2TradeStore getClone() {
        return new MuSigV2TradeStore(Map.copyOf(tradeById));
    }

    @Override
    public synchronized void applyPersisted(MuSigV2TradeStore persisted) {
        tradeById.clear();
        persisted.getTrades().forEach(this::put);
    }

    @Override
    public synchronized bisq.trade.protobuf.MuSigV2TradeStore.Builder getBuilder(boolean serializeForHash) {
        var builder = bisq.trade.protobuf.MuSigV2TradeStore.newBuilder();
        tradeById.forEach((id, record) -> builder.putTradeById(id, record.toProto(serializeForHash)));
        return builder;
    }

    @Override
    public bisq.trade.protobuf.MuSigV2TradeStore toProto(boolean serializeForHash) {
        return unsafeToProto(serializeForHash);
    }

    private static MuSigV2TradeStore fromProto(bisq.trade.protobuf.MuSigV2TradeStore proto) throws UnresolvableProtobufException {
        try {
            var store = new MuSigV2TradeStore();
            for (var entry : proto.getTradeByIdMap().entrySet()) {
                var record = MuSigV2TradeRecord.fromProto(entry.getValue());
                checkArgument(entry.getKey().equals(record.trade().getId()), "Persisted trade id does not match its store key");
                store.put(record);
            }
            return store;
        } catch (RuntimeException exception) {
            throw new UnresolvableProtobufException(exception);
        }
    }

    @Override
    public ProtoResolver<PersistableStore<?>> getResolver() {
        return any -> {
            try {
                return fromProto(any.unpack(bisq.trade.protobuf.MuSigV2TradeStore.class));
            } catch (InvalidProtocolBufferException exception) {
                throw new UnresolvableProtobufMessageException(exception);
            }
        };
    }
}
