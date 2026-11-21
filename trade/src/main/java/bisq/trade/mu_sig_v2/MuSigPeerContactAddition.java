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

import bisq.settings.SettingsService;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.user.UserService;
import bisq.user.contact_list.ContactReason;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds the trade peer to the contact list when a new trade has completed its first step, if the settings ask for it.
 * A trade whose first step fails adds no contact, as for the maker in V1. Best effort: the caller logs a failure,
 * and the trade is unaffected.
 */
final class MuSigPeerContactAddition {
    private final SettingsService settingsService;
    private final UserService userService;
    // The trades which this run has seen before their first step. A restored trade which is past its first step is
    // never in this set, so a restart does not add its contact again.
    private final Set<String> tradesBeforeFirstStep = ConcurrentHashMap.newKeySet();

    MuSigPeerContactAddition(SettingsService settingsService, UserService userService) {
        this.settingsService = settingsService;
        this.userService = userService;
    }

    void onTradeUpdated(MuSigV2Trade<?> trade) {
        MuSigTradeExecutionState executionState = trade.getExecution().state();
        if (executionState instanceof MuSigTradeExecutionState.Init) {
            tradesBeforeFirstStep.add(trade.getId());
            return;
        }
        if (!tradesBeforeFirstStep.remove(trade.getId()) || executionState.isFailed()) {
            return;
        }
        if (!settingsService.getDoAutoAddToContactList()) {
            return;
        }
        UserProfileService userProfileService = userService.getUserProfileService();
        Optional<UserProfile> peersProfile =
                userProfileService.findUserProfile(trade.getPeerParty().getNetworkId().getId());
        Optional<UserProfile> myProfile = userProfileService.findUserProfile(trade.getMyIdentity().getId());
        if (peersProfile.isPresent() && myProfile.isPresent()) {
            userService.getContactListService().addContactListEntry(peersProfile.get(),
                    myProfile.get(),
                    ContactReason.MUSIG_TRADE);
        }
    }
}
