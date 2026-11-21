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

import bisq.common.util.StringUtils;
import bisq.network.p2p.services.data.BroadcastResult;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.settings.SettingsService;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Removes the maker's offer once the offer was taken, if the user chose to close offers when taken. The offer counts
 * as taken when the maker has received the peer's deposit transaction; that data stays in the trade in every later
 * state, including failure. The rule only looks at the current trade and offer, so it also catches up after a
 * restart. Removal is best effort: a failure is logged and never affects the trade.
 */
@Slf4j
final class MuSigTakenOfferRemoval {
    private final MuSigOfferService offerService;
    private final SettingsService settingsService;
    // Avoids repeated removal requests while an asynchronous removal is still running.
    private final Set<String> requestedOfferIds = ConcurrentHashMap.newKeySet();

    MuSigTakenOfferRemoval(MuSigOfferService offerService, SettingsService settingsService) {
        this.offerService = offerService;
        this.settingsService = settingsService;
    }

    void onTradeUpdated(MuSigV2Trade<?> trade) {
        if (!trade.getMyRole().isMaker() ||
                trade.getExecution().data().getPeerDepositTx().isEmpty() ||
                // Read only when needed, so the setting is current and only makers with a received deposit read it.
                !settingsService.getCloseMyOfferWhenTaken().get()) {
            return;
        }
        String offerId = trade.getContract().getOffer().getId();
        offerService.findOffer(offerId)
                .filter(offer -> requestedOfferIds.add(offerId))
                .ifPresent(offer -> removeOffer(offer)
                        .whenComplete((result, failure) -> {
                            if (failure == null) {
                                log.info("Offer with ID {} removed", StringUtils.sanitizeForLog(offerId));
                            } else {
                                requestedOfferIds.remove(offerId);
                                log.warn("Removing taken offer {} failed. The trade is unaffected.",
                                        StringUtils.sanitizeForLog(offerId), failure);
                            }
                        }));
    }

    // The offer service can fail before it returns its future. Such a failure ends the request like a failed future,
    // so that a later update tries again.
    private CompletableFuture<BroadcastResult> removeOffer(MuSigOffer offer) {
        try {
            return offerService.removeOffer(offer);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }
}
