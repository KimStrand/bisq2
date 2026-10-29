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

import bisq.account.accounts.AccountPayload;
import bisq.common.processing.InitializationRejectedException;
import bisq.common.util.StringUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.handler.maker.MuSigTakeOfferRequestValidator;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import lombok.extern.slf4j.Slf4j;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static com.google.common.base.Preconditions.checkArgument;

/** Admits new trades on their lane worker. Publication, persistence and setup belong to the lane. */
@Slf4j
final class MuSigTradeAdmission {
    private final ServiceProvider serviceProvider;
    private final MuSigSubmissionPolicy submissionPolicy;
    private final MuSigTradeEffectHandler effectHandler;

    MuSigTradeAdmission(ServiceProvider serviceProvider,
                        MuSigSubmissionPolicy submissionPolicy,
                        MuSigTradeEffectHandler effectHandler) {
        this.serviceProvider = serviceProvider;
        this.submissionPolicy = submissionPolicy;
        this.effectHandler = effectHandler;
    }

    MuSigV2Trade<?> admitTaker(InitializeTrade command) {
        submissionPolicy.check(command);
        var offer = command.getOffer();
        var identity = command.getTakerIdentity();
        var accountPayload = command.getAccountPayload();
        var contract = new MuSigContract(command.getTakeOfferDate(),
                offer,
                identity.getNetworkId(),
                command.getBaseAmount().getValue(),
                command.getQuoteAmount().getValue(),
                command.getPaymentMethodSpec(),
                createSaltedAccountPayloadHash(accountPayload, offer.getId()),
                command.getMediator(),
                command.getArbitrator(),
                offer.getPriceSpec(),
                command.getMarketPrice());
        TradeRole myRole = offer.getTakersDirection().isBuy()
                ? TradeRole.BUYER_AS_TAKER : TradeRole.SELLER_AS_TAKER;
        return MuSigV2Trade.create(contract, identity, myRole, accountPayload);
    }

    /** Runs only for an uninitialized lane; duplicate requests go through normal protocol admission. */
    MuSigV2Trade<?> admitMaker(SetupTradeMessage_A message) {
        checkArgument(message.getProtocolVersion().equals(MuSigProtocol.VERSION), "Wrong protocol version");
        var profiles = serviceProvider.getUserService().getUserProfileService();
        // Both ignored-peer checks drop silently without blocking the trade ID for a later resend.
        if (profiles.isChatUserIgnored(message.getSender().getId())) {
            throw offerNotAvailable();
        }
        try {
            MuSigTakeOfferRequestValidator.validateIdentity(serviceProvider.getContractService(), message);
        } catch (RuntimeException exception) {
            // An unverified request must not prevent a valid request from initializing the claimed trade ID.
            var failure = takeOfferValidationFailure(message, exception);
            effectHandler.sendTakeOfferRejection(message, failure);
            throw failure;
        }
        try {
            MuSigTakeOfferRequestValidator.validateTakerProfileKnown(profiles, message);
            var legacyService = serviceProvider.getMuSigTradeService();
            MuSigTakeOfferRequestValidator.validateEconomics(
                    serviceProvider.getBondedRolesService().getMarketPriceService(),
                    serviceProvider.getSettingsService(),
                    legacyService.getMuSigTraderMediationService(),
                    legacyService.getMuSigTraderArbitrationService(),
                    message);
        } catch (RuntimeException exception) {
            var failure = takeOfferValidationFailure(message, exception);
            effectHandler.sendTakeOfferRejection(message, failure);
            throw new InitializationRejectedException(failure);
        }
        if (profiles.isChatUserIgnored(message.getSender().getId())) {
            throw offerNotAvailable();
        }
        MuSigOffer offer;
        try {
            offer = MuSigTakeOfferRequestValidator.validateOffer(
                    serviceProvider.getOfferService().getMuSigOfferService().getMyMuSigOffersService(),
                    message);
        } catch (RuntimeException exception) {
            var failure = takeOfferValidationFailure(message, exception);
            effectHandler.sendTakeOfferRejection(message, failure);
            throw new InitializationRejectedException(failure);
        }
        try {
            var myIdentity = serviceProvider.getIdentityService()
                    .findAnyIdentityByNetworkId(offer.getMakerNetworkId())
                    .orElseThrow(MuSigTradeAdmission::offerNotAvailable);
            if (serviceProvider.getUserService().getUserIdentityService().findUserIdentity(myIdentity.getId()).isEmpty()) {
                throw offerNotAvailable();
            }
            var contract = message.getContract();
            var accountPayload = findMakerAccountPayload(contract, offer);
            TradeRole myRole = offer.getDirection().isBuy() ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER;
            return MuSigV2Trade.create(contract, myIdentity, myRole, accountPayload);
        } catch (TradeProtocolException failure) {
            effectHandler.sendTakeOfferRejection(message, failure);
            throw new InitializationRejectedException(failure);
        }
    }

    private AccountPayload<?> findMakerAccountPayload(MuSigContract contract, MuSigOffer offer) {
        var paymentMethod = contract.getNonBtcSidePaymentMethodSpec().getPaymentMethod();
        var accounts = serviceProvider.getAccountService().getAccounts(paymentMethod);
        return OfferOptionUtil.findAccountOptions(offer.getOfferOptions()).stream()
                .filter(option -> option.getPaymentMethod().equals(paymentMethod))
                .map(AccountOption::getSaltedAccountId)
                .flatMap(saltedId -> OfferOptionUtil.findAccountFromSaltedAccountId(accounts, saltedId, offer.getId()).stream())
                .findAny()
                .orElseThrow(MuSigTradeAdmission::offerNotAvailable)
                .getAccountPayload();
    }

    private static TradeProtocolException takeOfferValidationFailure(SetupTradeMessage_A message,
                                                                     RuntimeException exception) {
        if (exception instanceof TradeProtocolException rejection) {
            return rejection;
        }
        log.warn("Unexpected failure while validating a MuSig take offer request. tradeId={}",
                StringUtils.sanitizeForLog(message.getTradeId()),
                exception);
        return new TradeProtocolException(
                "The maker has rejected the take offer request because the offer is not available anymore.",
                TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                exception);
    }

    private static TradeProtocolException offerNotAvailable() {
        return new TradeProtocolException("The maker can no longer take this offer.",
                TradeProtocolFailure.OFFER_NOT_AVAILABLE);
    }
}
