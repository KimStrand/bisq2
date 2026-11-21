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

import bisq.common.observable.map.ObservableHashMap;
import bisq.common.observable.map.ReadOnlyObservableMap;
import bisq.common.processing.lane.SnapshotListener;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.util.ExceptionUtil;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Keeps one observable read-model instance per trade in memory, rebuilt from saved trades at startup.
 * The router populates the store through lane publication before recovery; later updates run on the trade's lane.
 * Different trade IDs may be updated concurrently. Callers observe the collection and each model read-only.
 */
@Slf4j
final class MuSigTradeReadModelStore implements SnapshotListener<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> {
    private final ObservableHashMap<String, MuSigTradeReadModel> readModelByTradeId = new ObservableHashMap<>();

    /** Updates an existing model or publishes a fully populated new model, notifying the map only on insertion. */
    @Override
    public void onSnapshot(StateMachineSnapshot<MuSigV2Trade<?>, Flow> snapshot,
                        Optional<RuntimeException> persistenceFailure,
                        Optional<RuntimeException> setupFailure) {
        MuSigV2Trade<?> trade = snapshot.document();
        MuSigTradeReadModel readModel = readModelByTradeId.get(trade.getId());
        boolean isNew = readModel == null;
        if (isNew) {
            readModel = new MuSigTradeReadModel(trade.getId(),
                    trade.getMyRole(),
                    trade.getState(),
                    trade.getExecution().state());
        }

        // Make failure details available before notifying state observers.
        readModel.setPersistenceError(persistenceFailure.map(ExceptionUtil::getRootCauseMessage));
        readModel.setPreparationError(setupFailure.map(ExceptionUtil::getRootCauseMessage));
        readModel.setMyFailure(trade.getExecution().data().getMyFailure());
        readModel.setPeerFailure(trade.getExecution().data().getPeerFailure());
        readModel.setState(trade.getState());
        readModel.setExecutionState(trade.getExecution().state());
        readModel.setMediationState(trade.getMediation().map(flow -> flow.state()));

        if (isNew) {
            try {
                readModelByTradeId.put(trade.getId(), readModel);
            } catch (Throwable failure) {
                // The map already contains the model; observers must not fail initialization or recovery.
                log.warn("Read model observer failed for trade {}", StringUtils.sanitizeForLog(trade.getId()), failure);
            }
        }
    }

    MuSigTradeReadModel get(String tradeId) {
        return Optional.ofNullable(readModelByTradeId.get(tradeId))
                .orElseThrow(() -> new IllegalArgumentException("Unknown trade: " + tradeId));
    }

    ReadOnlyObservableMap<String, MuSigTradeReadModel> getAll() {
        return readModelByTradeId;
    }
}
