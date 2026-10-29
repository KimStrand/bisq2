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

import bisq.chat.mu_sig.open_trades.MuSigDisputeAgentType;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.processing.LogEntry;
import bisq.i18n.Res;
import bisq.user.identity.UserIdentityService;
import bisq.user.profile.UserProfile;
import bisq.user.profile.UserProfileService;

import java.util.Optional;

/** Translates trade chat operations and log entries into calls to the existing channel service. */
final class MuSigTradeChatService {
    private final MuSigOpenTradeChannelService channelService;
    private final UserIdentityService userIdentityService;
    private final UserProfileService userProfileService;

    MuSigTradeChatService(MuSigOpenTradeChannelService channelService,
                          UserIdentityService userIdentityService,
                          UserProfileService userProfileService) {
        this.channelService = channelService;
        this.userIdentityService = userIdentityService;
        this.userProfileService = userProfileService;
    }

    /** Returns only after the channel is saved; called by the trade's serial worker. */
    void ensureChannel(String tradeId,
                       String myUserIdentityId,
                       String peerUserProfileId,
                       Optional<UserProfile> mediator,
                       Optional<UserProfile> arbitrator) {
        if (channelService.findChannelByTradeId(tradeId).isEmpty()) {
            var myUserIdentity = userIdentityService.findUserIdentity(myUserIdentityId)
                    .orElseThrow(() -> new IllegalStateException("Local user identity is not available"));
            var peer = userProfileService.findUserProfile(peerUserProfileId)
                    .orElseThrow(() -> new IllegalStateException("Peer's user profile is not available"));
            channelService.traderFindOrCreatesChannel(tradeId,
                    myUserIdentity,
                    peer,
                    mediator,
                    arbitrator);
        }
        // A failed write must be retried even when the channel already exists in memory.
        channelService.getPersistence()
                .persistAsync(channelService.getPersistableStore().getClone())
                .join();
    }

    void activateMediation(String tradeId) {
        var channel = channelService.findChannelByTradeId(tradeId)
                .orElseThrow(() -> new IllegalStateException("Trade chat is not available"));
        channelService.setDisputeAgentType(channel, MuSigDisputeAgentType.MEDIATOR);
    }

    void postLog(String tradeId, LogEntry entry) {
        channelService.findChannelByTradeId(tradeId).ifPresent(channel -> {
            String text = switch (entry.content()) {
                case LogEntry.Text plain -> plain.text();
                case LogEntry.Localized localized -> Res.encode(localized.key(), localized.args().toArray());
            };
            switch (entry) {
                case LogEntry.Local local -> channelService.addLocalTradeLogMessage(text, local.authorId(), channel);
                case LogEntry.Shared shared -> channelService.sendTradeLogMessage(text, channel);
            }
        });
    }
}
