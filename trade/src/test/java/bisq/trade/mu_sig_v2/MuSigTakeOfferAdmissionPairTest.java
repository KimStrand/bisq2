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
import bisq.contract.mu_sig.MuSigContract;
import bisq.offer.Direction;
import bisq.security.keys.KeyGeneration;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigV2TradeTestFactory.initializeTrade;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * How the maker admits or rejects a take offer request, and what it reports to the taker.
 */
class MuSigTakeOfferAdmissionPairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @ValueSource(strings = {"offer-withdrawn", "identity-retired", "peer-ignored"})
    void makerAvailabilityIsCheckedAfterValidation(String change) throws Exception {
        automaticDelivery = false;
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        pauseNextRequestValidation(validating, release);
        var pending = submitMessage(buyer.service, request());
        try {
            assertTrue(validating.await(15, TimeUnit.SECONDS));
            switch (change) {
                case "offer-withdrawn" -> when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                        .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());
                case "identity-retired" -> when(buyer.services.getUserService().getUserIdentityService()
                        .findUserIdentity(buyer.identity.getId())).thenReturn(Optional.empty());
                case "peer-ignored" -> when(buyer.services.getUserService().getUserProfileService()
                        .isChatUserIgnored(seller.profile.getId())).thenReturn(true);
                default -> throw new AssertionError(change);
            }
        } finally {
            release.countDown();
        }
        assertFailure(TradeProtocolException.class, pending);
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        if (change.equals("peer-ignored")) {
            assertTrue(messages.isEmpty());
        } else {
            assertEquals(1, messages.size());
            assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                    sent(MuSigReportErrorMessage.class).getTradeProtocolFailure());
        }
    }

    @Test
    void unsupportedRequestVersionsDoNotClaimTheOfferOrCreateATrade() throws Exception {
        automaticDelivery = false;
        var valid = request();
        var invalid = new SetupTradeMessage_A(StringUtils.createUid(),
                tradeId,
                "2.0.0",
                valid.getSender(),
                valid.getReceiver(),
                contract,
                valid.getContractSignatureData(),
                valid.getPubKeyShares());

        assertFailure(IllegalArgumentException.class, submitMessage(buyer.service, invalid));

        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verify(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), never())
                .claimActivatedOffer(offer.getId());
        verifyNoInteractions(buyer.backend);
        assertTrue(messages.isEmpty());

        await(submitMessage(buyer.service, valid));
        var trade = savedRecord(buyer.service, valid.getTradeId()).trade();
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void ignoredPeersCanResendTheSameRequestAfterBeingUnignored(boolean ignoredDuringValidation) throws Exception {
        automaticDelivery = false;
        var profiles = buyer.services.getUserService().getUserProfileService();
        var message = request();
        CompletableFuture<Void> pending;
        if (ignoredDuringValidation) {
            var validating = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            pauseNextRequestValidation(validating, release);
            pending = submitMessage(buyer.service, message);
            try {
                assertTrue(validating.await(15, TimeUnit.SECONDS));
                when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(true);
            } finally {
                release.countDown();
            }
        } else {
            when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(true);
            pending = submitMessage(buyer.service, message);
        }

        assertFailure(TradeProtocolException.class, pending);
        assertTrue(messages.isEmpty());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        verifyNoInteractions(buyer.backend);
        verify(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService(), never())
                .claimActivatedOffer(offer.getId());

        when(profiles.isChatUserIgnored(seller.profile.getId())).thenReturn(false);
        await(submitMessage(buyer.service, message));
        var trade = savedRecord(buyer.service, message.getTradeId()).trade();

        assertEquals(tradeId, trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
        assertEquals(0, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-signature", "wrong-sender", "wrong-receiver", "wrong-trade-id"})
    void unverifiedRequestsDoNotBlockAValidRequestForTheSameTradeId(String problem) throws Exception {
        automaticDelivery = false;
        var valid = request();
        var invalid = switch (problem) {
            case "foreign-signature" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    valid.getSender(),
                    valid.getReceiver(),
                    contract,
                    contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                    valid.getPubKeyShares());
            case "wrong-sender" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    buyer.identity.getNetworkId(),
                    valid.getReceiver(),
                    contract,
                    valid.getContractSignatureData(),
                    valid.getPubKeyShares());
            case "wrong-receiver" -> new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    valid.getProtocolVersion(),
                    valid.getSender(),
                    valid.getSender(),
                    contract,
                    valid.getContractSignatureData(),
                    valid.getPubKeyShares());
            case "wrong-trade-id" -> {
                var differentAttempt = request(contractWithTakeOfferDate(contract.getTakeOfferDate() + 1));
                yield new SetupTradeMessage_A(StringUtils.createUid(),
                        tradeId,
                        valid.getProtocolVersion(),
                        valid.getSender(),
                        valid.getReceiver(),
                        differentAttempt.getContract(),
                        differentAttempt.getContractSignatureData(),
                        valid.getPubKeyShares());
            }
            default -> throw new AssertionError(problem);
        };

        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, invalid));
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        verifyNoInteractions(buyer.backend);

        await(submitMessage(buyer.service, valid));
        var trade = savedRecord(buyer.service, valid.getTradeId()).trade();
        assertEquals(tradeId, trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @Test
    void rejectedAttemptStaysBlockedWhenMarketPriceReturnsButAFreshAttemptCanStart() throws Exception {
        automaticDelivery = false;
        var prices = buyer.services.getBondedRolesService().getMarketPriceService();
        var freshPrice = prices.findMarketPrice(offer.getMarket());
        when(prices.findMarketPrice(any())).thenReturn(Optional.empty());
        var rejectedRequest = request();

        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, rejectedRequest));
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());

        when(prices.findMarketPrice(any())).thenReturn(freshPrice);
        clearInvocations(prices);
        for (var replay : List.of(rejectedRequest, request())) {
            var failure = assertFailure(IllegalStateException.class, submitMessage(buyer.service, replay));
            assertEquals("Initialization was already rejected for this entity ID", failure.getMessage());
        }
        verifyNoInteractions(prices, buyer.backend);
        assertEquals(1, messages.size());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());

        var freshRequest = request(contractWithTakeOfferDate(contract.getTakeOfferDate() + 1));
        await(submitMessage(buyer.service, freshRequest));
        var trade = savedRecord(buyer.service, freshRequest.getTradeId()).trade();
        assertNotEquals(tradeId, trade.getId());
        assertEquals(freshRequest.getTradeId(), trade.getId());
        assertInstanceOf(TradeInitializedAndNonceSharesCreated.class, trade.getExecution().state());
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
        verify(buyer.backend, times(1)).initTrade(any());
    }

    @ParameterizedTest
    @ValueSource(strings = {"foreign-signature", "wrong-trade-id", "unavailable-offer", "ignored-peer", "missing-identity",
            "retired-identity", "missing-account", "unknown-peer", "invalid-price", "wrong-mediator", "wrong-arbitrator"})
    void rejectedRequestsCreateNoTradeOrBackendWork(String problem) throws Exception {
        automaticDelivery = false;
        var request = request();
        switch (problem) {
            case "foreign-signature" -> request = new SetupTradeMessage_A(StringUtils.createUid(),
                    tradeId,
                    "1.0.0",
                    seller.identity.getNetworkId(),
                    buyer.identity.getNetworkId(),
                    contract,
                    contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                    request.getPubKeyShares());
            case "wrong-trade-id" -> request = new SetupTradeMessage_A(StringUtils.createUid(),
                    "wrong-trade",
                    "1.0.0",
                    seller.identity.getNetworkId(),
                    buyer.identity.getNetworkId(),
                    contract,
                    request.getContractSignatureData(),
                    request.getPubKeyShares());
            case "unavailable-offer" -> when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                    .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());
            case "ignored-peer" -> when(buyer.services.getUserService().getUserProfileService().isChatUserIgnored(seller.profile.getId()))
                    // The rejected request stays silent even if the peer is subsequently unignored.
                    .thenReturn(true, false);
            case "missing-identity" -> when(buyer.services.getIdentityService().findAnyIdentityById(buyer.identity.getId()))
                    .thenReturn(Optional.empty());
            case "retired-identity" -> when(buyer.services.getUserService().getUserIdentityService().findUserIdentity(buyer.identity.getId()))
                    .thenReturn(Optional.empty());
            case "missing-account" -> when(buyer.services.getAccountService().getAccounts(paymentMethod)).thenReturn(Set.of());
            case "unknown-peer" -> when(buyer.services.getUserService().getUserProfileService().findUserProfile(seller.profile.getId()))
                    .thenReturn(Optional.empty());
            case "invalid-price" -> {
                var invalid = new MuSigContract(contract.getTakeOfferDate(),
                        offer,
                        seller.identity.getNetworkId(),
                        2_000_000,
                        20_000_000,
                        contract.getNonBtcSidePaymentMethodSpec(),
                        contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                        contract.getMediator(),
                        contract.getArbitrator(),
                        offer.getPriceSpec(),
                        0);
                request = new SetupTradeMessage_A(StringUtils.createUid(),
                        tradeId,
                        "1.0.0",
                        seller.identity.getNetworkId(),
                        buyer.identity.getNetworkId(),
                        invalid,
                        contractService.signContract(invalid, seller.identity.getKeyBundle().getKeyPair()),
                        request.getPubKeyShares());
            }
            case "wrong-mediator" -> when(buyer.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenReturn(contract.getArbitrator());
            case "wrong-arbitrator" -> when(buyer.services.getMuSigTradeService().getMuSigTraderArbitrationService()
                    .selectArbitrator(anyString(), anyString(), anyString(), any())).thenReturn(contract.getMediator());
            default -> throw new AssertionError(problem);
        }
        var failure = assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request));
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        verifyNoInteractions(buyer.services.getChatService().getMuSigOpenTradeChannelService());
        if (problem.equals("ignored-peer") || problem.equals("missing-identity")) {
            assertTrue(messages.isEmpty());
        } else {
            assertEquals(1, messages.size());
            var report = sent(MuSigReportErrorMessage.class);
            assertEquals(request.getTradeId(), report.getTradeId());
            assertEquals(buyer.identity.getNetworkId(), report.getSender());
            assertEquals(seller.identity.getNetworkId(), report.getReceiver());
            assertEquals("1.0.0", report.getProtocolVersion());
            assertEquals(failure.getTradeProtocolFailure(), report.getTradeProtocolFailure());
            assertEquals(failure.getMessage(), report.getErrorMessage());
            assertTrue(report.getStackTrace().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerRejectionNotifiesTheTakerWithoutCreatingAMakerTrade(boolean buyerAsMaker) throws Exception {
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        var maker = maker();
        var taker = taker();
        when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenReturn(Optional.empty());

        var initial = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));
        var failed = await(delivery(taker, MuSigReportErrorMessage.class));

        assertFailure(TradeProtocolException.class, delivery(maker, SetupTradeMessage_A.class));
        assertInstanceOf(FailedAtPeer.class, failed.getExecution().state());
        assertInstanceOf(MuSigTradeState.Failed.class, failed.getState());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getPeerFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(failed.toProto(false), taker.saved().trade().toProto(false));
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(maker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(maker.backend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());
        assertEquals(2, messages.size());
        assertInstanceOf(SetupTradeMessage_A.class, messages.element());
        assertEquals(1, messages.stream().filter(MuSigReportErrorMessage.class::isInstance).count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void rejectionSendFailurePreservesTheAdmissionFailure(boolean immediate) throws Exception {
        automaticDelivery = false;
        var rejection = new TradeProtocolException("Offer unavailable", TradeProtocolFailure.OFFER_NOT_AVAILABLE);
        when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenThrow(rejection);
        var networkService = buyer.services.getNetworkService();
        var sendFailure = new IllegalStateException("Could not send rejection");
        if (immediate) {
            doThrow(sendFailure).when(networkService).confidentialSend(any(), any(), any());
        } else {
            doReturn(CompletableFuture.failedFuture(sendFailure))
                    .when(networkService).confidentialSend(any(), any(), any());
        }

        assertSame(rejection, assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request())));
        var replayFailure = assertFailure(IllegalStateException.class, submitMessage(buyer.service, request()));
        assertEquals("Initialization was already rejected for this entity ID", replayFailure.getMessage());

        verify(networkService).confidentialSend(any(), any(), any());
        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        assertTrue(messages.isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"identity", "profile", "economics", "offer"})
    void unexpectedMakerValidationFailuresNotifyTheTakerWithoutCreatingATrade(String validation) throws Exception {
        var maker = maker();
        var taker = taker();
        var unexpected = new IllegalStateException("Internal validation detail");
        switch (validation) {
            case "identity" -> when(maker.services.getContractService()).thenThrow(unexpected);
            case "profile" -> when(maker.services.getUserService().getUserProfileService()
                    .findUserProfile(taker.profile.getId())).thenThrow(unexpected);
            case "economics" -> when(maker.services.getMuSigTradeService().getMuSigTraderMediationService()
                    .selectMediator(anyString(), anyString(), anyString())).thenThrow(unexpected);
            case "offer" -> when(maker.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                    .claimActivatedOffer(offer.getId())).thenThrow(unexpected);
            default -> throw new AssertionError(validation);
        }

        var initial = await(initializeTrade(taker.service,
                initializeTrade(contract, taker.identity, taker.account.getAccountPayload())));

        var rejection = assertFailure(TradeProtocolException.class, delivery(maker, SetupTradeMessage_A.class));
        assertSame(unexpected, rejection.getCause());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE, rejection.getTradeProtocolFailure());
        assertEquals("The maker has rejected the take offer request because the offer is not available anymore.",
                rejection.getMessage());
        var failed = await(delivery(taker, MuSigReportErrorMessage.class));
        assertInstanceOf(FailedAtPeer.class, failed.getExecution().state());
        assertInstanceOf(MuSigTradeState.Failed.class, failed.getState());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getPeerFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(failed.toProto(false), taker.saved().trade().toProto(false));
        assertTrue(maker.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(maker.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> maker.service.getTradeReadModel(tradeId));
        verifyNoInteractions(maker.backend);
        verifyNoInteractions(maker.services.getChatService().getMuSigOpenTradeChannelService());
        assertEquals(2, messages.size());
        var report = sent(MuSigReportErrorMessage.class);
        assertEquals(rejection.getMessage(), report.getErrorMessage());
        assertTrue(report.getStackTrace().isEmpty());
    }

    @Test
    void makerSetupFailuresAreNotReportedAsValidationRejections() throws Exception {
        automaticDelivery = false;
        var failure = new IllegalStateException("Identity service unavailable");
        when(buyer.services.getUserService().getUserIdentityService().findUserIdentity(buyer.identity.getId()))
                .thenThrow(failure);

        assertSame(failure, assertFailure(IllegalStateException.class, submitMessage(buyer.service, request())));

        assertTrue(buyer.service.getPersistableStore().getTrades().isEmpty());
        assertFalse(Files.exists(buyer.service.getPersistence().getStorePath()));
        assertThrows(IllegalArgumentException.class, () -> buyer.service.getTradeReadModel(tradeId));
        verifyNoInteractions(buyer.backend);
        verifyNoInteractions(buyer.services.getChatService().getMuSigOpenTradeChannelService());
        var network = buyer.services.getNetworkService();
        verify(network).subscribeToConfidentialMessages(buyer.service);
        verifyNoMoreInteractions(network);
        assertTrue(messages.isEmpty());
    }

    @Test
    void rejectionReportLimitsItsTextAndPreservesItsReason() throws Exception {
        automaticDelivery = false;
        String errorMessage = "x".repeat(MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE + 100);
        var rejection = new TradeProtocolException(errorMessage, TradeProtocolFailure.PRICE_DEVIATION);
        when(buyer.services.getOfferService().getMuSigOfferService().getMyMuSigOffersService()
                .claimActivatedOffer(offer.getId())).thenThrow(rejection);

        assertSame(rejection, assertFailure(TradeProtocolException.class, submitMessage(buyer.service, request())));

        assertEquals(1, messages.size());
        var report = sent(MuSigReportErrorMessage.class);
        assertEquals(MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE, report.getErrorMessage().length());
        assertEquals(TradeProtocolFailure.PRICE_DEVIATION, report.getTradeProtocolFailure());
        assertTrue(report.getStackTrace().isEmpty());
    }

    private MuSigContract contractWithTakeOfferDate(long takeOfferDate) {
        return new MuSigContract(takeOfferDate,
                offer,
                taker().identity.getNetworkId(),
                contract.getBaseSideAmount(),
                contract.getQuoteSideAmount(),
                contract.getNonBtcSidePaymentMethodSpec(),
                contract.getTaker().getSaltedAccountPayloadHash().orElseThrow(),
                contract.getMediator(),
                contract.getArbitrator(),
                contract.getPriceSpec(),
                contract.getMarketPrice());
    }
}
