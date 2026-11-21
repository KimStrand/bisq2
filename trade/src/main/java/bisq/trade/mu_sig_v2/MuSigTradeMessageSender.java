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
import bisq.identity.Identity;
import bisq.network.NetworkService;
import bisq.network.SendMessageResult;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.user.banned.BannedUserService;
import bisq.user.banned.UserProfileBannedException;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.CompletableFuture;

/** Hands messages to the network without waiting for delivery. The lane owns saved-message retries. */
@Slf4j
final class MuSigTradeMessageSender {
    private final NetworkService networkService;
    private final BannedUserService bannedUserService;

    MuSigTradeMessageSender(NetworkService networkService,
                            BannedUserService bannedUserService) {
        this.networkService = networkService;
        this.bannedUserService = bannedUserService;
    }

    void sendSavedMessage(String tradeId,
                          EnvelopePayloadMessage message,
                          Identity sendingIdentity,
                          NetworkId receiver) {
        // Do not catch an immediate exception: the lane keeps this exact message pending.
        // Observe the returned future without waiting. A ban uses V1's failed-future path below.
        sendWithTraderChecks(message, sendingIdentity, receiver)
                .whenComplete((result, failure) -> {
                    String sanitizedTradeId = StringUtils.sanitizeForLog(tradeId);
                    if (failure != null) {
                        log.warn("Delivery of {} failed for trade {}",
                                message.getClass().getSimpleName(),
                                sanitizedTradeId,
                                failure);
                    } else {
                        SendMessageResult.findAnyErrorMsg(result).ifPresent(error ->
                                log.warn("Delivery of {} reported an error for trade {}: {}",
                                        message.getClass().getSimpleName(),
                                        sanitizedTradeId,
                                        StringUtils.sanitizeForLog(error)));
                    }
                });
    }

    /** Direct network handoff without trader ban checks; the caller handles send failures. */
    CompletableFuture<SendMessageResult> sendDirect(EnvelopePayloadMessage message,
                                                    Identity sendingIdentity,
                                                    NetworkId receiver) {
        return networkService.confidentialSend(message, receiver, sendingIdentity.getNetworkIdWithKeyPair());
    }

    private CompletableFuture<SendMessageResult> sendWithTraderChecks(EnvelopePayloadMessage message,
                                                                      Identity sendingIdentity,
                                                                      NetworkId receiver) {
        if (message instanceof MuSigTradeMessage tradeMessage) {
            // Match V1's TradeMessageSender: a ban returns a failed future, without calling the network.
            if (bannedUserService.isUserProfileBanned(tradeMessage.getSender())) {
                return CompletableFuture.failedFuture(new UserProfileBannedException(tradeMessage.getSender().getId()));
            }
            if (bannedUserService.isUserProfileBanned(receiver)) {
                return CompletableFuture.failedFuture(new UserProfileBannedException(receiver.getId()));
            }
        }
        return sendDirect(message, sendingIdentity, receiver);
    }
}
