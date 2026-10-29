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

import bisq.common.application.DevMode;
import bisq.common.processing.Effect;
import bisq.common.util.StringUtils;
import bisq.identity.IdentityService;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.offer.mu_sig.MuSigOfferService;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.execution.RemoveTakenOffer;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Executes requested MuSig operations through the application's I/O adapters. */
@Slf4j
final class MuSigTradeEffectHandler {
    private final IdentityService identityService;
    private final MuSigOfferService offerService;
    private final MuSigTradeMessageSender messageSender;
    private final MuSigTradeChatService tradeChatService;
    private final MuSigDepositTxObserver depositTxObserver;
    private final Consumer<String> onDepositTxConfirmed;
    private final BiConsumer<String, Throwable> onDepositTxObservationFailed;

    MuSigTradeEffectHandler(IdentityService identityService,
                            MuSigOfferService offerService,
                            MuSigTradeMessageSender messageSender,
                            MuSigTradeChatService tradeChatService,
                            MuSigDepositTxObserver depositTxObserver,
                            Consumer<String> onDepositTxConfirmed,
                            BiConsumer<String, Throwable> onDepositTxObservationFailed) {
        this.identityService = identityService;
        this.offerService = offerService;
        this.messageSender = messageSender;
        this.tradeChatService = tradeChatService;
        this.depositTxObserver = depositTxObserver;
        this.onDepositTxConfirmed = onDepositTxConfirmed;
        this.onDepositTxObservationFailed = onDepositTxObservationFailed;
    }

    // The lane supplies its saved current snapshot. Effects retain their original retry payloads.
    void handle(MuSigV2Trade<?> trade, Effect effect) {
        String tradeId = trade.getId();
        if (effect instanceof ActivateMediationChat) {
            tradeChatService.activateMediation(tradeId);
        } else if (effect instanceof RemoveTakenOffer) {
            removeTakenOffer(trade);
        } else if (effect instanceof ObserveDepositTxConfirmation) {
            if (DevMode.isDevMode()) {
                log.info("Skipping automatic deposit confirmation observation for trade {} in development mode",
                        StringUtils.sanitizeForLog(tradeId));
                return;
            }
            depositTxObserver.observe(tradeId,
                    () -> onDepositTxConfirmed.accept(tradeId),
                    failure -> onDepositTxObservationFailed.accept(tradeId, failure));
        } else if (effect instanceof Effect.SendMessage<?, ?> send) {
            messageSender.sendSavedMessage(tradeId,
                    EnvelopePayloadMessage.class.cast(send.message()),
                    trade.getMyIdentity(),
                    NetworkId.class.cast(send.receiver()));
        } else {
            throw new IllegalArgumentException("Unsupported MuSig effect: " + effect.getClass().getSimpleName());
        }
    }

    /** A rejected request has no initialized trade; reporting is best effort and preserves the admission failure. */
    void sendTakeOfferRejection(SetupTradeMessage_A message,
                               TradeProtocolException exception) {
        try {
            var receivingIdentity = identityService.findAnyIdentityByNetworkId(message.getReceiver());
            if (receivingIdentity.isEmpty()) {
                log.warn("Cannot report a MuSig take offer rejection because the receiving identity is unavailable. tradeId={}",
                        StringUtils.sanitizeForLog(message.getTradeId()));
                return;
            }
            var identity = receivingIdentity.orElseThrow();
            String errorMessage = Optional.ofNullable(exception.getMessage())
                    .orElse(exception.getTradeProtocolFailure().name());
            var error = new MuSigReportErrorMessage(StringUtils.createUid(),
                    message.getTradeId(),
                    MuSigProtocol.VERSION,
                    identity.getNetworkId(),
                    message.getSender(),
                    StringUtils.truncate(errorMessage, MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE),
                    "",
                    exception.getTradeProtocolFailure());
            // Match V1's direct rejection reply, without the saved-message trader ban checks.
            messageSender.sendDirect(error, identity, message.getSender())
                    .exceptionally(failure -> {
                        log.warn("Sending a MuSig take offer rejection failed. tradeId={}",
                                StringUtils.sanitizeForLog(message.getTradeId()),
                                failure);
                        return null;
                    });
        } catch (RuntimeException failure) {
            log.warn("Creating or sending a MuSig take offer rejection failed. tradeId={}",
                    StringUtils.sanitizeForLog(message.getTradeId()),
                    failure);
        }
    }

    private void removeTakenOffer(MuSigV2Trade<?> trade) {
        var offer = trade.getContract().getOffer();
        try {
            offerService.removeOffer(offer)
                    .whenComplete((result, failure) -> {
                        if (failure == null) {
                            log.info("Offer with ID {} removed", StringUtils.sanitizeForLog(offer.getId()));
                        } else {
                            log.warn("Removing taken offer {} failed. The trade is unaffected.",
                                    StringUtils.sanitizeForLog(offer.getId()), failure);
                        }
                    });
        } catch (RuntimeException exception) {
            log.warn("Removing taken offer {} failed. The trade is unaffected.",
                    StringUtils.sanitizeForLog(offer.getId()), exception);
        }
    }
}
