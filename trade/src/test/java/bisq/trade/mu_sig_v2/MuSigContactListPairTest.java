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

import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.user.contact_list.ContactListService;
import bisq.user.contact_list.ContactReason;
import bisq.user.profile.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A new trade adds the peer to the contact list of the trader: once, only if the setting allows it, and without
 * an effect on the trade if it fails.
 */
class MuSigContactListPairTest extends MuSigTradeServicePairFixture {
    @Test
    void aNewTradeAddsThePeerToTheContactListOfTheTakerAndOfTheMaker() throws Exception {
        allowAddingContacts(buyer);
        allowAddingContacts(seller);

        startTrade();

        assertPeerAdded(taker(), maker());
        assertPeerAdded(maker(), taker());
    }

    @Test
    void noContactIsAddedIfTheSettingDoesNotAllowIt() throws Exception {
        startTrade();

        for (Peer peer : List.of(buyer, seller)) {
            verify(contactList(peer), never()).addContactListEntry(any(), any(), any());
        }
    }

    @Test
    void noContactIsAddedIfTheOwnProfileIsUnknown() throws Exception {
        allowAddingContacts(buyer);
        allowAddingContacts(seller);
        Peer taker = taker();
        when(taker.services.getUserService().getUserProfileService().findUserProfile(taker.profile.getId()))
                .thenReturn(Optional.empty());

        startTrade();

        verify(contactList(taker), never()).addContactListEntry(any(), any(), any());
        assertPeerAdded(maker(), taker);
    }

    @Test
    void aFailingContactListDoesNotStopTheTrade() throws Exception {
        for (Peer peer : List.of(buyer, seller)) {
            allowAddingContacts(peer);
            when(contactList(peer).addContactListEntry(any(), any(), any()))
                    .thenThrow(new IllegalStateException("The contact list is not available"));
        }

        startTrade();

        for (Peer peer : List.of(buyer, seller)) {
            verify(contactList(peer), times(1)).addContactListEntry(any(), any(), any());
            assertInstanceOf(AccountPayloadReceived.class, peer.saved().trade().getExecution().state());
        }
    }

    // V1 adds the contact after the handler of the take offer request has verified and processed it.
    @Test
    void theMakerAddsNoContactIfItsFirstStepFails() throws Exception {
        automaticDelivery = false;
        Peer maker = maker();
        allowAddingContacts(maker);
        when(maker.backend.initTrade(any())).thenThrow(new IllegalStateException("The backend is not available"));

        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, request()));

        assertTrue(maker.saved().trade().getExecution().state().isFailed());
        verify(contactList(maker), never()).addContactListEntry(any(), any(), any());
    }

    // V1 adds the contact of the taker when it creates the trade. V2 uses one rule for both roles.
    @Test
    void theTakerAddsNoContactIfItsFirstStepFails() throws Exception {
        automaticDelivery = false;
        Peer taker = taker();
        allowAddingContacts(taker);
        when(taker.backend.initTrade(any())).thenThrow(new IllegalStateException("The backend is not available"));

        assertFailure(MuSigProtocolException.class, taker.service.initializeTrade(
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));

        assertTrue(taker.saved().trade().getExecution().state().isFailed());
        verify(contactList(taker), never()).addContactListEntry(any(), any(), any());
    }

    @Test
    void aRestartDoesNotAddThePeerAgain() throws Exception {
        allowAddingContacts(buyer);
        allowAddingContacts(seller);
        startTrade();

        for (Peer peer : List.of(buyer, seller)) {
            await(peer.restoreTrade(peer.saved()));

            verify(contactList(peer), times(1)).addContactListEntry(any(), any(), any());
        }
    }

    private static void allowAddingContacts(Peer peer) {
        when(peer.services.getSettingsService().getDoAutoAddToContactList()).thenReturn(true);
    }

    private static ContactListService contactList(Peer peer) {
        return peer.services.getUserService().getContactListService();
    }

    private static void assertPeerAdded(Peer owner, Peer peer) {
        ArgumentCaptor<UserProfile> addedProfile = ArgumentCaptor.forClass(UserProfile.class);
        ArgumentCaptor<UserProfile> ownProfile = ArgumentCaptor.forClass(UserProfile.class);
        verify(contactList(owner), times(1))
                .addContactListEntry(addedProfile.capture(), ownProfile.capture(), eq(ContactReason.MUSIG_TRADE));
        assertEquals(peer.profile.getId(), addedProfile.getValue().getId());
        assertEquals(owner.profile.getId(), ownProfile.getValue().getId());
    }
}
