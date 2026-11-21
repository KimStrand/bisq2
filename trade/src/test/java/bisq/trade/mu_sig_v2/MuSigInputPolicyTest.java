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
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.trade.mu_sig_v2;

import bisq.bonded_roles.release.AppType;
import bisq.bonded_roles.security_manager.alert.AlertType;
import bisq.bonded_roles.security_manager.alert.AuthorizedAlertData;
import bisq.common.application.DevMode;
import bisq.common.observable.collection.ObservableSet;
import bisq.common.statemachine.Event;
import bisq.support.dispute.mu_sig.MuSigDisputeCasePaymentDetailsRequest;
import bisq.support.mediation.mu_sig.MuSigMediationClosedMessage;
import bisq.support.mediation.mu_sig.MuSigMediationOpenedMessage;
import bisq.trade.ServiceProvider;
import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
import bisq.trade.mu_sig.messages.network.MuSigMediationResultRejectionMessage;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_D;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigV2EventType;
import bisq.trade.mu_sig_v2.protocol.events.TradeArchivedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositConfirmationSkippedEvent;
import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.BuyersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationRequestedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByMyselfAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultByPeerAcceptedEvent;
import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultRejectedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.PaymentReceiptConfirmedEvent;
import bisq.trade.mu_sig_v2.protocol.events.seller.SellersCloseTradeTimeoutEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigInputPolicyTest {
    private final boolean devModeBefore = DevMode.isDevMode();
    private final ServiceProvider services = mock(ServiceProvider.class);
    private final MuSigInputPolicy policy = new MuSigInputPolicy(services, AppType.DESKTOP);

    @AfterEach
    void restoreDevMode() {
        DevMode.setDevMode(devModeBefore);
    }

    @Test
    void skippingTheDepositConfirmationIsRejectedOutsideDevelopmentMode() {
        DevMode.setDevMode(false);

        var failure = assertThrows(MuSigProtocolException.class,
                () -> policy.check(new DepositConfirmationSkippedEvent()));

        assertEquals(MuSigFailureCategory.INPUT_REJECTED, failure.getCategory());
        verifyNoInteractions(services);
    }

    @Test
    void aMediationRequestNeedsNeitherDevelopmentModeNorTradingPermission() {
        DevMode.setDevMode(false);

        assertDoesNotThrow(() -> policy.check(new MediationRequestedEvent()));
        verifyNoInteractions(services);
    }

    @Test
    void everyEventTypeHasADecisionWhetherATradeRestrictionPausesIt() {
        assertEquals(EnumSet.allOf(MuSigV2EventType.class),
                pausedByATradeRestriction().keySet().stream()
                        .map(MuSigV2EventType::fromEventClass)
                        .collect(Collectors.toCollection(() -> EnumSet.noneOf(MuSigV2EventType.class))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aTradeRestrictionPausesThePeerMessagesAndTheSavedReactionsToARestrictedInput(boolean haltTrading) {
        var restricted = policyWithAlerts(restriction(haltTrading));

        pausedByATradeRestriction().forEach((type, paused) ->
                assertEquals(paused, restricted.isPaused(mock(type)), type.getSimpleName()));
    }

    @Test
    void withoutATradeRestrictionNoInputIsPaused() {
        var unrestricted = policyWithAlerts();

        pausedByATradeRestriction().keySet().forEach(type ->
                assertFalse(unrestricted.isPaused(mock(type)), type.getSimpleName()));
    }

    // Every event type of MuSig V2, with the decision whether a trade restriction pauses it. A new event type needs
    // a row here.
    private static Map<Class<? extends Event>, Boolean> pausedByATradeRestriction() {
        Map<Class<? extends Event>, Boolean> paused = new LinkedHashMap<>();
        // Messages of the peer.
        List.of(SetupTradeMessage_A.class,
                SetupTradeMessage_B.class,
                SetupTradeMessage_C.class,
                SetupTradeMessage_D.class,
                SendAccountPayloadAndDepositTxMessage.class,
                SendAccountPayloadMessage.class,
                PaymentInitiatedMessage_E.class,
                PaymentReceivedMessage_F.class,
                CooperativeClosureMessage_G.class,
                MuSigCustomPayoutPsbtMessage.class,
                MuSigMediationResultRejectionMessage.class,
                MuSigReportErrorMessage.class).forEach(type -> paused.put(type, true));
        // Reactions to a restricted input, which the lane saves: the taker's first step, the close timers, and the
        // signing and the finalization of a mediated payout.
        List.of(MuSigTakeOfferEvent.class,
                SellersCloseTradeTimeoutEvent.class,
                BuyersCloseTradeTimeoutEvent.class,
                MediationResultByMyselfAcceptedEvent.class,
                MediationResultByPeerAcceptedEvent.class).forEach(type -> paused.put(type, true));
        // Commands of the user. The router refuses the restricted ones on arrival, and the lane keeps none.
        List.of(DepositConfirmationSkippedEvent.class,
                PaymentInitiatedEvent.class,
                PaymentReceiptConfirmedEvent.class,
                MediationResultAcceptedEvent.class,
                MediationRequestedEvent.class,
                MediationResultRejectedEvent.class,
                TradeArchivedEvent.class).forEach(type -> paused.put(type, false));
        // Available during a trade restriction: the deposit confirmation, the messages of the mediator and the
        // handling of failures.
        List.of(DepositTxConfirmedEvent.class,
                MuSigMediationOpenedMessage.class,
                MuSigMediationClosedMessage.class,
                MuSigDisputeCasePaymentDetailsRequest.class,
                MuSigProcessingFailedEvent.class,
                ExecutionFailedEvent.class).forEach(type -> paused.put(type, false));
        return paused;
    }

    private static MuSigInputPolicy policyWithAlerts(AuthorizedAlertData... alerts) {
        var services = mock(ServiceProvider.class, RETURNS_DEEP_STUBS);
        when(services.getBondedRolesService().getAlertService().getAuthorizedAlertDataSet())
                .thenReturn(new ObservableSet<>(List.of(alerts)));
        return new MuSigInputPolicy(services, AppType.DESKTOP);
    }

    private static AuthorizedAlertData restriction(boolean haltTrading) {
        var alert = mock(AuthorizedAlertData.class);
        when(alert.getAlertType()).thenReturn(AlertType.EMERGENCY);
        when(alert.getAppType()).thenReturn(AppType.DESKTOP);
        when(alert.isHaltTrading()).thenReturn(haltTrading);
        when(alert.isRequireVersionForTrading()).thenReturn(!haltTrading);
        when(alert.getMinVersion()).thenReturn(Optional.of("999.0.0"));
        return alert;
    }
}
