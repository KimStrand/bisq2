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

import bisq.common.data.ByteArray;
import bisq.common.observable.Observable;
import bisq.network.p2p.services.data.BroadcastResult;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.settings.SettingsService;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MuSigTakenOfferRemovalTest {
    private static final String OFFER_ID = "offer";

    private final MuSigOfferService offerService = mock(MuSigOfferService.class);
    private final MuSigOffer offer = mock(MuSigOffer.class);
    private final SettingsService settingsService = mock(SettingsService.class);
    private final Observable<Boolean> closeMyOfferWhenTaken = new Observable<>(true);
    private final MuSigTakenOfferRemoval removal = new MuSigTakenOfferRemoval(offerService, settingsService);

    {
        when(settingsService.getCloseMyOfferWhenTaken()).thenReturn(closeMyOfferWhenTaken);
    }

    @Test
    void aTakenOfferIsRemovedOnceWhileItsRemovalIsRunning() {
        var pending = new CompletableFuture<BroadcastResult>();
        when(offerService.findOffer(OFFER_ID)).thenReturn(Optional.of(offer));
        when(offerService.removeOffer(offer)).thenReturn(pending);
        var trade = trade(TradeRole.BUYER_AS_MAKER, true);

        removal.onTradeUpdated(trade);
        removal.onTradeUpdated(trade);

        verify(offerService, times(1)).removeOffer(offer);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aFailedRemovalIsRetriedAtTheNextUpdate(boolean failsAtOnce) {
        when(offerService.findOffer(OFFER_ID)).thenReturn(Optional.of(offer));
        var failure = new IllegalStateException("offline");
        if (failsAtOnce) {
            // The offer service fails before it returns its future.
            when(offerService.removeOffer(offer)).thenThrow(failure);
        } else {
            when(offerService.removeOffer(offer)).thenReturn(CompletableFuture.failedFuture(failure));
        }
        var trade = trade(TradeRole.SELLER_AS_MAKER, true);

        removal.onTradeUpdated(trade);
        removal.onTradeUpdated(trade);

        verify(offerService, times(2)).removeOffer(offer);
    }

    @Test
    void nothingIsRemovedBeforeTheDepositArrivesForATakerWhenTheSettingIsOffOrWhenTheOfferIsGone() {
        when(offerService.findOffer(OFFER_ID)).thenReturn(Optional.of(offer));

        removal.onTradeUpdated(trade(TradeRole.BUYER_AS_MAKER, false));
        removal.onTradeUpdated(trade(TradeRole.BUYER_AS_TAKER, true));
        closeMyOfferWhenTaken.set(false);
        removal.onTradeUpdated(trade(TradeRole.SELLER_AS_MAKER, true));
        closeMyOfferWhenTaken.set(true);
        when(offerService.findOffer(OFFER_ID)).thenReturn(Optional.empty());
        removal.onTradeUpdated(trade(TradeRole.SELLER_AS_MAKER, true));

        verify(offerService, never()).removeOffer(offer);
    }

    private static MuSigV2Trade<?> trade(TradeRole role, boolean depositReceived) {
        MuSigV2Trade<?> trade = mock(MuSigV2Trade.class, RETURNS_DEEP_STUBS);
        when(trade.getMyRole()).thenReturn(role);
        when(trade.getContract().getOffer().getId()).thenReturn(OFFER_ID);
        when(trade.getExecution().data().getPeerDepositTx())
                .thenReturn(depositReceived ? Optional.of(new ByteArray(new byte[]{1})) : Optional.empty());
        return trade;
    }
}
