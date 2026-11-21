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

package bisq.support.dispute.mu_sig;

import bisq.contract.Role;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.identity.NetworkId;
import bisq.user.profile.UserProfile;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;

public final class MuSigDisputeContractIdentityChecks {
    private MuSigDisputeContractIdentityChecks() {
    }

    // A party is identified by its key, so these checks compare IDs. The contract holds the network IDs from when the
    // offer was published and taken; their addresses can have changed since then (identity-by-key.md, rule 1).
    // A trade has two different parties, so a request which names one profile as requester and peer does not match.
    public static boolean hasMatchingContractParties(MuSigContract contract,
                                                     UserProfile requester,
                                                     UserProfile peer) {
        String requesterId = requester.getId();
        String peerId = peer.getId();
        if (requesterId.equals(peerId)) {
            return false;
        }
        String makerId = contract.getMaker().getNetworkId().getId();
        String takerId = contract.getTaker().getNetworkId().getId();
        return requesterId.equals(makerId) && peerId.equals(takerId) ||
                requesterId.equals(takerId) && peerId.equals(makerId);
    }

    // The requester supplies the unsigned contract, so it can name the dispute agent's profile as a trader. Chat
    // messages of that profile would then pass as messages of a trader, although they claim to come from the agent.
    public static boolean isDisputeAgentAParty(UserProfile requester, UserProfile peer, NetworkId receiver) {
        String disputeAgentId = receiver.getId();
        return requester.getId().equals(disputeAgentId) || peer.getId().equals(disputeAgentId);
    }

    public static boolean hasMatchingContractDisputeAgent(Optional<UserProfile> disputeAgent,
                                                          NetworkId receiver) {
        return disputeAgent
                .map(agent -> agent.getId().equals(receiver.getId()))
                .orElse(false);
    }

    public static Role resolveSenderRole(MuSigContract contract, String senderUserProfileId) {
        checkArgument(isContractParty(contract, senderUserProfileId),
                "senderUserProfileId must be one of the contract parties");
        return senderUserProfileId.equals(contract.getOffer().getMakersUserProfileId()) ? Role.MAKER : Role.TAKER;
    }

    private static boolean isContractParty(MuSigContract contract, String senderUserProfileId) {
        return senderUserProfileId.equals(contract.getOffer().getMakersUserProfileId()) ||
                senderUserProfileId.equals(contract.getTaker().getNetworkId().getId());
    }
}
