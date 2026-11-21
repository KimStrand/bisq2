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

import bisq.common.processing.lane.SnapshotListener;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.util.StringUtils;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Receives every published trade update, after each saved step and once per trade at startup, and forwards it to
 * the components that react to trade changes outside the protocol. The read model is updated first. A failing
 * reaction is logged and does not stop the others or the trade.
 */
@Slf4j
final class MuSigTradeUpdateListener implements SnapshotListener<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> {
    private final MuSigTradeReadModelStore readModelStore;
    private final MuSigTakenOfferRemoval takenOfferRemoval;
    private final MuSigPeerContactAddition peerContactAddition;

    MuSigTradeUpdateListener(MuSigTradeReadModelStore readModelStore,
                             MuSigTakenOfferRemoval takenOfferRemoval,
                             MuSigPeerContactAddition peerContactAddition) {
        this.readModelStore = readModelStore;
        this.takenOfferRemoval = takenOfferRemoval;
        this.peerContactAddition = peerContactAddition;
    }

    @Override
    public void onSnapshot(StateMachineSnapshot<MuSigV2Trade<?>, Flow> snapshot,
                        Optional<RuntimeException> persistenceFailure,
                        Optional<RuntimeException> setupFailure) {
        MuSigV2Trade<?> trade = snapshot.document();
        try {
            readModelStore.onSnapshot(snapshot, persistenceFailure, setupFailure);
        } catch (RuntimeException failure) {
            log.warn("Updating the read model failed for trade {}", StringUtils.sanitizeForLog(trade.getId()), failure);
        }
        try {
            takenOfferRemoval.onTradeUpdated(trade);
        } catch (RuntimeException failure) {
            log.warn("Removing the taken offer failed for trade {}", StringUtils.sanitizeForLog(trade.getId()), failure);
        }
        try {
            peerContactAddition.onTradeUpdated(trade);
        } catch (RuntimeException failure) {
            log.warn("Adding the peer to the contact list failed for trade {}. The trade is unaffected.",
                    StringUtils.sanitizeForLog(trade.getId()), failure);
        }
    }
}
