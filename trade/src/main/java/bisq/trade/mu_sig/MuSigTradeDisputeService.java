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

package bisq.trade.mu_sig;

import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannel;
import bisq.chat.mu_sig.open_trades.MuSigOpenTradeChannelService;
import bisq.contract.mu_sig.MuSigContract;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.support.arbitration.mu_sig.MuSigArbitrationClosedMessage;
import bisq.support.arbitration.mu_sig.MuSigArbitrationOpenedMessage;
import bisq.support.arbitration.mu_sig.MuSigArbitrationResult;
import bisq.support.arbitration.mu_sig.MuSigArbitrationResultService;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationResult;
import bisq.support.mediation.mu_sig.MuSigMediationResultService;
import bisq.trade.MuSigDisputeState;
import bisq.trade.mu_sig.arbitration.MuSigTraderArbitrationService;
import bisq.trade.mu_sig.mediation.MuSigTraderMediationService;
import bisq.user.banned.BannedUserService;
import bisq.user.profile.UserProfile;
import lombok.extern.slf4j.Slf4j;

import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Function;

import static bisq.trade.MuSigDisputeState.isArbitrationState;
import static bisq.trade.MuSigDisputeState.isMediationState;
import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
final class MuSigTradeDisputeService {
    private final BannedUserService bannedUserService;
    private final MuSigOpenTradeChannelService muSigOpenTradeChannelService;
    private final MuSigTraderMediationService muSigTraderMediationService;
    private final MuSigTraderArbitrationService muSigTraderArbitrationService;
    private final Function<String, Optional<MuSigTrade>> findTrade;
    private final Runnable persist;

    private final Map<String, Set<EnvelopePayloadMessage>> pendingDisputeMessagesByTradeId = new ConcurrentHashMap<>();

    MuSigTradeDisputeService(BannedUserService bannedUserService,
                             MuSigOpenTradeChannelService muSigOpenTradeChannelService,
                             MuSigTraderMediationService muSigTraderMediationService,
                             MuSigTraderArbitrationService muSigTraderArbitrationService,
                             Function<String, Optional<MuSigTrade>> findTrade,
                             Runnable persist) {
        this.bannedUserService = bannedUserService;
        this.muSigOpenTradeChannelService = muSigOpenTradeChannelService;
        this.muSigTraderMediationService = muSigTraderMediationService;
        this.muSigTraderArbitrationService = muSigTraderArbitrationService;
        this.findTrade = findTrade;
        this.persist = persist;
    }

    public void requestMediation(MuSigTrade trade) {
        checkArgument(!bannedUserService.isUserProfileBanned(trade.getMyIdentity().getNetworkId()));
        MuSigContract contract = trade.getContract();
        Optional<UserProfile> mediator = contract.getMediator();
        if (mediator.isPresent()) {
            MuSigTradeDispute tradeDispute = trade.getTradeDispute();
            MuSigDisputeState current = tradeDispute.getDisputeState();
            if (current != MuSigDisputeState.NO_DISPUTE) {
                log.warn("Cannot request mediation for trade {} because not in the right state.",
                        trade.getId());
                return;
            }

            MuSigOpenTradeChannel channel = findChannelByTradeId(trade.getId()).orElseThrow();

            tradeDispute.setDisputeState(MuSigDisputeState.MEDIATION_REQUESTED);
            persist.run();
            muSigTraderMediationService.requestMediation(trade.getId(), trade.getMyIdentity(),
                    trade.getPeer(), mediator.get(), contract, channel);
        }
    }

    public void requestArbitration(MuSigTrade trade) {
        checkArgument(!bannedUserService.isUserProfileBanned(trade.getMyIdentity().getNetworkId()));
        MuSigContract contract = trade.getContract();
        Optional<UserProfile> arbitrator = contract.getArbitrator();
        if (arbitrator.isPresent()) {
            MuSigTradeDispute tradeDispute = trade.getTradeDispute();
            MuSigDisputeState current = tradeDispute.getDisputeState();
            if (current != MuSigDisputeState.MEDIATION_CLOSED) {
                log.warn("Cannot request arbitration for trade {} because not in the right state.",
                        trade.getId());
                return;
            }

            MuSigOpenTradeChannel channel = findChannelByTradeId(trade.getId()).orElseThrow();

            tradeDispute.setDisputeState(MuSigDisputeState.ARBITRATION_REQUESTED);
            persist.run();
            muSigTraderArbitrationService.requestArbitration(
                    trade.getId(), trade.getMyIdentity(), trade.getPeer(),
                    arbitrator.get(), contract,
                    tradeDispute.getMuSigMediationResult().orElseThrow(),
                    tradeDispute.getMediationResultSignature().orElseThrow(),
                    channel);
        }
    }

    // Must be called while holding the trade's protocol monitor.
    public void onDisputeMessage(EnvelopePayloadMessage envelopePayloadMessage) {
        if (envelopePayloadMessage instanceof MuSigMediationOpenedMessage message) {
            findTradeAndChannelOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(tradeAndChannel -> verifyMediatorMessage(message.getTradeId(),
                            message.getSenderNetworkId(), tradeAndChannel, bannedUserService))
                    .ifPresent(this::processMediationOpenedMessage);
        } else if (envelopePayloadMessage instanceof MuSigMediationClosedMessage message) {
            findTradeAndChannelOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(tradeAndChannel -> verifyMediatorMessage(message.getTradeId(),
                            message.getSenderNetworkId(), tradeAndChannel, bannedUserService))
                    .ifPresent(tradeAndChannel -> processMediationClosedMessage(message, tradeAndChannel));
        } else if (envelopePayloadMessage instanceof MuSigDisputeCasePaymentDetailsRequest message) {
            findTradeAndChannelOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(tradeAndChannel -> verifyDisputeCasePaymentDetailsRequest(message, tradeAndChannel, bannedUserService))
                    .ifPresent(tradeAndChannel -> processDisputeCasePaymentDetailsRequest(message, tradeAndChannel));
        } else if (envelopePayloadMessage instanceof MuSigArbitrationOpenedMessage message) {
            findTradeAndChannelOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(tradeAndChannel -> verifyArbitratorMessage(message.getTradeId(),
                            message.getSenderNetworkId(), tradeAndChannel, bannedUserService))
                    .ifPresent(tradeAndChannel -> processArbitrationOpenedMessage(message, tradeAndChannel));
        } else if (envelopePayloadMessage instanceof MuSigArbitrationClosedMessage message) {
            findTradeAndChannelOrQueue(message.getTradeId(), envelopePayloadMessage)
                    .flatMap(tradeAndChannel -> verifyArbitratorMessage(message.getTradeId(),
                            message.getSenderNetworkId(), tradeAndChannel, bannedUserService))
                    .ifPresent(tradeAndChannel -> processArbitrationClosedMessage(message, tradeAndChannel));
        }
    }

    // Must be called while holding the trade's protocol monitor.
    public void maybeProcessPendingDisputeMessages(String tradeId) {
        Set<EnvelopePayloadMessage> pendingMessages = pendingDisputeMessagesByTradeId.remove(tradeId);
        if (pendingMessages != null) {
            pendingMessages.forEach(this::onDisputeMessage);
        }
    }

    private static Optional<MuSigTradeAndChannel> verifyMediatorMessage(String tradeId,
                                                                        NetworkId senderNetworkId,
                                                                        MuSigTradeAndChannel tradeAndChannel,
                                                                        BannedUserService bannedUserService) {
        MuSigTrade trade = tradeAndChannel.trade();
        Optional<UserProfile> mediator = trade.getContract().getMediator();
        if (mediator.isEmpty()) {
            log.warn("Ignoring the mediator's message for trade {} because mediator is missing in contract.", tradeId);
            return Optional.empty();
        }
        if (!mediator.orElseThrow().getId().equals(senderNetworkId.getId())) {
            log.warn("Ignoring the mediator's message for trade {} with unexpected senderNetworkId {}.",
                    tradeId, senderNetworkId);
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(senderNetworkId)) {
            log.warn("Ignoring the mediator's message as sender is banned");
            return Optional.empty();
        }
        return Optional.of(tradeAndChannel);
    }

    private void processMediationOpenedMessage(MuSigTradeAndChannel tradeAndChannel) {
        MuSigTrade trade = tradeAndChannel.trade();
        MuSigDisputeState current = trade.getTradeDispute().getDisputeState();
        // In every other state the message changes nothing. A closed case stays closed.
        if (current != MuSigDisputeState.NO_DISPUTE && current != MuSigDisputeState.MEDIATION_REQUESTED) {
            return;
        }

        applyMediationState(tradeAndChannel, current, MuSigDisputeState.MEDIATION_OPEN);
        if (current == MuSigDisputeState.NO_DISPUTE) {
            MuSigContract contract = trade.getContract();
            muSigTraderMediationService.sendDisputeCaseDataMessage(
                    trade.getId(), trade.getMyIdentity(), contract.getMediator().orElseThrow(), contract);
        }
        maybeProcessPendingDisputeMessages(trade.getId());
    }

    private void processMediationClosedMessage(MuSigMediationClosedMessage message,
                                               MuSigTradeAndChannel tradeAndChannel) {
        MuSigTrade trade = tradeAndChannel.trade();
        MuSigTradeDispute tradeDispute = trade.getTradeDispute();
        MuSigDisputeState current = tradeDispute.getDisputeState();
        if (isArbitrationState(current) || current == MuSigDisputeState.MEDIATION_CLOSED) {
            return;
        }
        if (current != MuSigDisputeState.MEDIATION_OPEN) {
            addPendingDisputeMessage(trade.getId(), message);
            return;
        }
        if (!isMediationResultMessageValid(trade.getContract(), message)) {
            return;
        }

        MuSigMediationResult incomingResult = message.getMuSigMediationResult();
        byte[] incomingResultSignature = message.getMediationResultSignature();
        Optional<MuSigMediationResult> currentResult = tradeDispute.getMuSigMediationResult();
        Optional<byte[]> currentResultSignature = tradeDispute.getMediationResultSignature();
        if (currentResult.isEmpty() && currentResultSignature.isEmpty()) {
            tradeDispute.setMuSigMediationResult(incomingResult);
            tradeDispute.setMediationResultSignature(incomingResultSignature);
        } else if (!currentResult.orElseThrow().equals(incomingResult) ||
                !Arrays.equals(currentResultSignature.orElseThrow(), incomingResultSignature)) {
            log.warn("Ignoring changed MuSigMediationResult/Signature for trade {} because result/signature cannot be changed once set.",
                    message.getTradeId());
        }

        applyMediationState(tradeAndChannel, current, MuSigDisputeState.MEDIATION_CLOSED);
        maybeProcessPendingDisputeMessages(trade.getId());
    }

    private void applyMediationState(MuSigTradeAndChannel tradeAndChannel,
                                     MuSigDisputeState current,
                                     MuSigDisputeState next) {
        MuSigTrade trade = tradeAndChannel.trade();
        trade.getTradeDispute().setDisputeState(next);
        persist.run();
        muSigTraderMediationService.applyMediationStateToChannel(trade.getId(), next, current, tradeAndChannel.channel());
    }

    private boolean isMediationResultMessageValid(MuSigContract contract,
                                                  MuSigMediationClosedMessage message) {
        try {
            if (!MuSigMediationResultService.verifyMediationResult(message.getMuSigMediationResult(),
                    message.getMediationResultSignature(),
                    contract,
                    contract.getMediator().orElseThrow().getPublicKey())) {
                log.warn("Ignoring MuSigMediationResult for trade {} because mediator signature verification failed.",
                        message.getTradeId());
                return false;
            }
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Ignoring MuSigMediationResult for trade {} because mediator signature verification failed.",
                    message.getTradeId(), e);
            return false;
        }
    }

    private static Optional<MuSigTradeAndChannel> verifyDisputeCasePaymentDetailsRequest(
            MuSigDisputeCasePaymentDetailsRequest message,
            MuSigTradeAndChannel tradeAndChannel,
            BannedUserService bannedUserService) {
        MuSigTrade trade = tradeAndChannel.trade();
        NetworkId sender = message.getSenderNetworkId();
        if (!isSenderMediator(sender, trade) && !isSenderArbitrator(sender, trade)) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} with unexpected senderNetworkId {}.",
                    message.getTradeId(), message.getSenderNetworkId());
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(message.getSenderNetworkId())) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest as sender is banned");
            return Optional.empty();
        }
        return Optional.of(tradeAndChannel);
    }

    private void processDisputeCasePaymentDetailsRequest(MuSigDisputeCasePaymentDetailsRequest message,
                                                         MuSigTradeAndChannel tradeAndChannel) {
        MuSigTrade trade = tradeAndChannel.trade();
        MuSigTradeDispute tradeDispute = trade.getTradeDispute();
        NetworkId sender = message.getSenderNetworkId();

        if (isSenderMediator(sender, trade)) {
            if (isArbitrationState(tradeDispute.getDisputeState())) {
                log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} from mediator {} because trade is already in arbitration state {}.",
                        message.getTradeId(), sender, tradeDispute.getDisputeState());
                return;
            }
            if (!isMediationState(tradeDispute.getDisputeState())) {
                addPendingDisputeMessage(trade.getId(), message);
                return;
            }
        } else if (isSenderArbitrator(sender, trade)) {
            if (!isArbitrationState(tradeDispute.getDisputeState())) {
                addPendingDisputeMessage(trade.getId(), message);
                return;
            }
        } else {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} with unexpected senderNetworkId {}.",
                    message.getTradeId(), sender);
            return;
        }

        if (trade.getMyself().getAccountPayload().isEmpty() || trade.getPeer().getAccountPayload().isEmpty()) {
            log.warn("Ignoring MuSigDisputeCasePaymentDetailsRequest for trade {} because account payloads are incomplete.",
                    message.getTradeId());
            return;
        }
        muSigTraderMediationService.sendDisputeCasePaymentDetailsResponse(
                trade.getId(), trade.getMyIdentity(), message.getSenderNetworkId(),
                trade.getTaker().getAccountPayload().orElseThrow(),
                trade.getMaker().getAccountPayload().orElseThrow());
    }

    private static Optional<MuSigTradeAndChannel> verifyArbitratorMessage(String tradeId,
                                                                          NetworkId senderNetworkId,
                                                                          MuSigTradeAndChannel tradeAndChannel,
                                                                          BannedUserService bannedUserService) {
        MuSigTrade trade = tradeAndChannel.trade();
        Optional<UserProfile> arbitrator = trade.getContract().getArbitrator();
        if (arbitrator.isEmpty()) {
            log.warn("Ignoring the arbitrator's message for trade {} because arbitrator is missing in contract.", tradeId);
            return Optional.empty();
        }
        if (!arbitrator.orElseThrow().getId().equals(senderNetworkId.getId())) {
            log.warn("Ignoring the arbitrator's message for trade {} with unexpected senderNetworkId {}.",
                    tradeId, senderNetworkId);
            return Optional.empty();
        }

        if (bannedUserService.isUserProfileBanned(senderNetworkId)) {
            log.warn("Ignoring the arbitrator's message as sender is banned");
            return Optional.empty();
        }
        return Optional.of(tradeAndChannel);
    }

    private void processArbitrationOpenedMessage(MuSigArbitrationOpenedMessage message,
                                                 MuSigTradeAndChannel tradeAndChannel) {
        MuSigTrade trade = tradeAndChannel.trade();
        MuSigDisputeState current = trade.getTradeDispute().getDisputeState();
        if (current == MuSigDisputeState.ARBITRATION_OPEN) {
            return;
        }
        if (current != MuSigDisputeState.ARBITRATION_REQUESTED && current != MuSigDisputeState.MEDIATION_CLOSED) {
            addPendingDisputeMessage(trade.getId(), message);
            return;
        }

        applyArbitrationState(tradeAndChannel, current, MuSigDisputeState.ARBITRATION_OPEN);
        maybeProcessPendingDisputeMessages(trade.getId());
    }

    private void processArbitrationClosedMessage(MuSigArbitrationClosedMessage message,
                                                 MuSigTradeAndChannel tradeAndChannel) {
        MuSigTrade trade = tradeAndChannel.trade();
        MuSigTradeDispute tradeDispute = trade.getTradeDispute();
        MuSigDisputeState current = tradeDispute.getDisputeState();
        if (current == MuSigDisputeState.ARBITRATION_CLOSED) {
            return;
        }
        if (current != MuSigDisputeState.ARBITRATION_OPEN) {
            addPendingDisputeMessage(trade.getId(), message);
            return;
        }
        if (!isArbitrationResultMessageValid(trade.getContract(), message)) {
            return;
        }

        MuSigArbitrationResult incomingResult = message.getMuSigArbitrationResult();
        byte[] incomingResultSignature = message.getArbitrationResultSignature();
        Optional<MuSigArbitrationResult> currentResult = tradeDispute.getMuSigArbitrationResult();
        Optional<byte[]> currentResultSignature = tradeDispute.getArbitrationResultSignature();
        if (currentResult.isEmpty() && currentResultSignature.isEmpty()) {
            tradeDispute.setMuSigArbitrationResult(incomingResult);
            tradeDispute.setArbitrationResultSignature(incomingResultSignature);
        } else if (!currentResult.orElseThrow().equals(incomingResult) ||
                !Arrays.equals(currentResultSignature.orElseThrow(), incomingResultSignature)) {
            log.warn("Ignoring changed MuSigArbitrationResult/Signature for trade {} because result/signature cannot be changed once set.",
                    message.getTradeId());
        }

        applyArbitrationState(tradeAndChannel, current, MuSigDisputeState.ARBITRATION_CLOSED);
        maybeProcessPendingDisputeMessages(trade.getId());
    }

    private void applyArbitrationState(MuSigTradeAndChannel tradeAndChannel,
                                       MuSigDisputeState current,
                                       MuSigDisputeState next) {
        MuSigTrade trade = tradeAndChannel.trade();
        trade.getTradeDispute().setDisputeState(next);
        persist.run();
        muSigTraderArbitrationService.applyArbitrationStateToChannel(trade.getId(), next, current, tradeAndChannel.channel());
    }

    private boolean isArbitrationResultMessageValid(MuSigContract contract,
                                                    MuSigArbitrationClosedMessage message) {
        try {
            if (!MuSigArbitrationResultService.verifyArbitrationResult(message.getMuSigArbitrationResult(),
                    message.getArbitrationResultSignature(),
                    contract,
                    contract.getArbitrator().orElseThrow().getPublicKey())) {
                log.warn("Ignoring MuSigArbitrationResult for trade {} because arbitrator signature verification failed.",
                        message.getTradeId());
                return false;
            }
            return true;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            log.warn("Ignoring MuSigArbitrationResult for trade {} because arbitrator signature verification failed.",
                    message.getTradeId(), e);
            return false;
        }
    }

    private static boolean isSenderMediator(NetworkId sender, MuSigTrade trade) {
        return trade.getContract().getMediator()
                .map(profile -> profile.getId().equals(sender.getId()))
                .orElse(false);
    }

    private static boolean isSenderArbitrator(NetworkId sender, MuSigTrade trade) {
        return trade.getContract().getArbitrator()
                .map(profile -> profile.getId().equals(sender.getId()))
                .orElse(false);
    }

    private Optional<MuSigOpenTradeChannel> findChannelByTradeId(String tradeId) {
        return muSigOpenTradeChannelService.findChannelByTradeId(tradeId);
    }

    private Optional<MuSigTradeAndChannel> findTradeAndChannelOrQueue(String tradeId,
                                                                      EnvelopePayloadMessage message) {
        Optional<MuSigTrade> trade = findTrade.apply(tradeId);
        Optional<MuSigOpenTradeChannel> channel = findChannelByTradeId(tradeId);
        if (trade.isPresent() && channel.isPresent()) {
            removePendingDisputeMessage(tradeId, message);
            return Optional.of(new MuSigTradeAndChannel(trade.get(), channel.get()));
        }
        addPendingDisputeMessage(tradeId, message);
        return Optional.empty();
    }

    // Before protocol registration, the caller holds pendingMessagesLock; afterwards it holds
    // the trade's protocol monitor. Registration coordinates the handoff between those locks.
    void addPendingDisputeMessage(String tradeId, EnvelopePayloadMessage message) {
        pendingDisputeMessagesByTradeId
                .computeIfAbsent(tradeId, key -> new CopyOnWriteArraySet<>())
                .add(message);
    }

    private void removePendingDisputeMessage(String tradeId, EnvelopePayloadMessage message) {
        pendingDisputeMessagesByTradeId.computeIfPresent(tradeId, (key, pendingMessages) -> {
            pendingMessages.remove(message);
            return pendingMessages.isEmpty() ? null : pendingMessages;
        });
    }

    private record MuSigTradeAndChannel(MuSigTrade trade, MuSigOpenTradeChannel channel) {
    }
}
