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
import bisq.common.processing.ProtocolLogMessage;
import bisq.common.processing.SendMessage;
import bisq.common.processing.lane.EffectExecutor;
import bisq.common.statemachine.StateMachineSnapshot;
import bisq.common.util.StringUtils;
import bisq.network.identity.NetworkId;
import bisq.network.p2p.message.EnvelopePayloadMessage;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.ObserveDepositTxConfirmation;
import bisq.trade.mu_sig_v2.protocol.mediation.ActivateMediationChat;
import lombok.extern.slf4j.Slf4j;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Executes requested MuSig operations through the application's I/O adapters. */
@Slf4j
final class MuSigTradeEffectHandler implements EffectExecutor<StateMachineSnapshot<MuSigV2Trade<?>, Flow>> {
    private final MuSigTradeMessageSender messageSender;
    private final MuSigTradeChatService tradeChatService;
    private final MuSigDepositTxObserver depositTxObserver;
    private final Consumer<String> onDepositTxConfirmed;
    private final BiConsumer<String, Throwable> onDepositTxObservationFailed;

    MuSigTradeEffectHandler(MuSigTradeMessageSender messageSender,
                            MuSigTradeChatService tradeChatService,
                            MuSigDepositTxObserver depositTxObserver,
                            Consumer<String> onDepositTxConfirmed,
                            BiConsumer<String, Throwable> onDepositTxObservationFailed) {
        this.messageSender = messageSender;
        this.tradeChatService = tradeChatService;
        this.depositTxObserver = depositTxObserver;
        this.onDepositTxConfirmed = onDepositTxConfirmed;
        this.onDepositTxObservationFailed = onDepositTxObservationFailed;
    }

    @Override
    public void execute(StateMachineSnapshot<MuSigV2Trade<?>, Flow> snapshot, Effect effect) {
        MuSigV2Trade<?> trade = snapshot.document();
        String tradeId = trade.getId();
        if (effect instanceof ProtocolLogMessage entry) {
            tradeChatService.postLog(tradeId, entry);
        } else if (effect instanceof ActivateMediationChat) {
            tradeChatService.activateMediation(tradeId);
        } else if (effect instanceof ObserveDepositTxConfirmation) {
            if (DevMode.isDevMode()) {
                log.info("Skipping automatic deposit confirmation observation for trade {} in development mode",
                        StringUtils.sanitizeForLog(tradeId));
                return;
            }
            depositTxObserver.observe(tradeId,
                    () -> onDepositTxConfirmed.accept(tradeId),
                    failure -> onDepositTxObservationFailed.accept(tradeId, failure));
        } else if (effect instanceof SendMessage<?, ?> send) {
            messageSender.sendSavedMessage(tradeId,
                    EnvelopePayloadMessage.class.cast(send.message()),
                    trade.getMyIdentity(),
                    NetworkId.class.cast(send.receiver()));
        } else {
            throw new IllegalArgumentException("Unsupported MuSig effect: " + effect.getClass().getSimpleName());
        }
    }
}
