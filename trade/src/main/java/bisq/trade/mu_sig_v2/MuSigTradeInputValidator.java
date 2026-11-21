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

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.common.processing.lane.InputValidator;
import bisq.common.statemachine.Event;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.network.identity.NetworkId;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.events.InternalEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.user.banned.BannedUserService;
import bisq.user.profile.UserProfile;

import static com.google.common.base.Preconditions.checkArgument;

/**
 * Checks whether a submitted input may enter the trade's lane. A trade message must come from the peer for this
 * trade and protocol version, a mediator message must come from the assigned mediator, internal events never come
 * from outside, and a mediation request needs a local trader who is not banned and the trade chat. Whether a command
 * fits the trade's current state is decided while handling, by the flows' admission rules.
 */
final class MuSigTradeInputValidator
        implements InputValidator<StateMachineSnapshot<? extends MuSigV2Trade<?>, Flow>, Event> {
    private final BannedUserService bannedUserService;
    private final MuSigOpenTradeChannelService tradeChannelService;

    MuSigTradeInputValidator(BannedUserService bannedUserService,
                             MuSigOpenTradeChannelService tradeChannelService) {
        this.bannedUserService = bannedUserService;
        this.tradeChannelService = tradeChannelService;
    }

    /**
     * Throws a {@link MuSigProtocolException} with {@link MuSigFailureCategory#INPUT_REJECTED} if the input may not
     * enter the trade's lane.
     */
    @Override
    public void validate(StateMachineSnapshot<? extends MuSigV2Trade<?>, Flow> snapshot, Event event) {
        MuSigV2Trade<?> trade = snapshot.document();
        try {
            checkOrigin(trade, event);
            checkMediationRequest(trade, event);
        } catch (RuntimeException failure) {
            throw new MuSigProtocolException(MuSigFailureCategory.INPUT_REJECTED, failure.getMessage(), failure);
        }
    }

    private static void checkOrigin(MuSigV2Trade<?> trade, Event event) {
        checkArgument(!(event instanceof InternalEvent), "Internal facts must come from the protocol");
        if (event instanceof MuSigTradeMessage message) {
            message.verify();
            checkArgument(message.getTradeId().equals(trade.getId()), "Wrong trade");
            checkArgument(isSameParty(message.getSender(), trade.getPeerParty().getNetworkId()), "Wrong peer");
            checkArgument(isSameParty(message.getReceiver(), trade.getMyIdentity().getNetworkId()), "Wrong receiver");
            checkArgument(message.getProtocolVersion().equals(trade.getProtocolVersion()), "Wrong protocol version");
        } else if (event instanceof MuSigMediationOpenedMessage message) {
            message.verify();
            checkMediator(trade, message.getTradeId(), message.getSenderNetworkId());
        } else if (event instanceof MuSigMediationClosedMessage message) {
            message.verify();
            checkMediator(trade, message.getTradeId(), message.getSenderNetworkId());
        } else if (event instanceof MuSigDisputeCasePaymentDetailsRequest message) {
            message.verify();
            checkMediator(trade, message.getTradeId(), message.getSenderNetworkId());
        }
    }

    // Like V1, mediation remains available during a trading halt, but not to a banned local trader. Also like V1, a
    // request without the trade chat is refused before anything changes, so the trader can repeat it. The handler
    // builds the request from the chat; if it failed there, the mediation flow would fail for good.
    private void checkMediationRequest(MuSigV2Trade<?> trade, Event event) {
        if (event instanceof MediationRequestedEvent) {
            checkArgument(!bannedUserService.isUserProfileBanned(trade.getMyIdentity().getNetworkId()),
                    "Cannot request mediation because the local trader is banned");
            checkArgument(tradeChannelService.findChannelByTradeId(trade.getId()).isPresent(),
                    "Cannot request mediation because the trade chat is not available");
        }
    }

    private static void checkMediator(MuSigV2Trade<?> trade, String tradeId, NetworkId sender) {
        checkArgument(tradeId.equals(trade.getId()), "Wrong mediation trade");
        UserProfile mediator = trade.getContract().getMediator()
                .orElseThrow(() -> new IllegalArgumentException("No mediator is assigned to this trade"));
        checkArgument(isSameParty(sender, mediator.getNetworkId()), "Wrong mediator");
    }

    // A party is identified by its key. The confidential message layer has verified that the sender signed the
    // message with the key of its network ID and that the receiver's key decrypted it. The addresses of a network ID
    // are routing data: they change, for example, when a party adds a transport (trade-message-sender.md).
    private static boolean isSameParty(NetworkId first, NetworkId second) {
        return first.getId().equals(second.getId());
    }
}
