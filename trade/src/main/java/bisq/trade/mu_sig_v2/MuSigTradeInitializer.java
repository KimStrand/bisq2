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

import bisq.account.accounts.Account;
import bisq.account.accounts.AccountPayload;
import bisq.account.payment_method.PaymentMethod;
import bisq.account.payment_method.PaymentRail;
import bisq.common.processing.lane.InitializationRejectedException;
import bisq.common.processing.lane.InputPolicy;
import bisq.common.util.StringUtils;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.offer.mu_sig.MuSigOffer;
import bisq.offer.options.AccountOption;
import bisq.offer.options.OfferOptionUtil;
import bisq.trade.ServiceProvider;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.handler.maker.MuSigTakeOfferRequestValidator;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.user.profile.UserProfileService;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.Set;

import static bisq.offer.options.OfferOptionUtil.createSaltedAccountPayloadHash;
import static com.google.common.base.Preconditions.checkArgument;

/** Validates requests and prepares new trades on their lane worker. Publication, persistence and setup belong to the lane. */
@Slf4j
final class MuSigTradeInitializer {
    private final ServiceProvider serviceProvider;
    private final InputPolicy inputPolicy;
    private final MuSigTradeMessageSender messageSender;

    MuSigTradeInitializer(ServiceProvider serviceProvider,
                          InputPolicy inputPolicy,
                          MuSigTradeMessageSender messageSender) {
        this.serviceProvider = serviceProvider;
        this.inputPolicy = inputPolicy;
        this.messageSender = messageSender;
    }

    // The router checked the input policy when the input arrived. Both initializers check it again, because
    // a restriction or a ban can arrive while the input waits for a worker. A new trade must not start then.
    MuSigV2Trade<?> initializeForTaker(InitializeTrade command) {
        inputPolicy.check(command);
        MuSigOffer offer = command.getOffer();
        Identity identity = command.getTakerIdentity();
        AccountPayload<?> accountPayload = command.getAccountPayload();
        MuSigContract contract = new MuSigContract(command.getTakeOfferDate(),
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
    MuSigV2Trade<?> initializeForMaker(SetupTradeMessage_A message) {
        // Not a final rejection: the same request can start the trade when the restriction has ended.
        inputPolicy.check(message);
        checkArgument(message.getProtocolVersion().equals(MuSigProtocol.VERSION), "Wrong protocol version");
        UserProfileService profiles = serviceProvider.getUserService().getUserProfileService();
        // Both ignored-peer checks drop silently without blocking the trade ID for a later resend.
        if (profiles.isChatUserIgnored(message.getSender().getId())) {
            throw offerNotAvailable();
        }
        try {
            MuSigTakeOfferRequestValidator.validateIdentity(serviceProvider.getContractService(), message);
        } catch (RuntimeException exception) {
            // An unverified request must not prevent a valid request from initializing the claimed trade ID.
            throw reportRejection(message, exception);
        }
        try {
            MuSigTakeOfferRequestValidator.validateTakerProfileKnown(profiles, message);
            bisq.trade.mu_sig.MuSigTradeService v1TradeService = serviceProvider.getMuSigTradeService();
            MuSigTakeOfferRequestValidator.validateEconomics(
                    serviceProvider.getBondedRolesService().getMarketPriceService(),
                    serviceProvider.getSettingsService(),
                    v1TradeService.getMuSigTraderMediationService(),
                    v1TradeService.getMuSigTraderArbitrationService(),
                    message);
        } catch (RuntimeException exception) {
            throw new InitializationRejectedException(reportRejection(message, exception));
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
            throw new InitializationRejectedException(reportRejection(message, exception));
        }
        try {
            // The key identifies the maker; its addresses can have changed since it published the offer.
            Identity myIdentity = serviceProvider.getIdentityService()
                    .findAnyIdentityById(offer.getMakerNetworkId().getId())
                    .orElseThrow(MuSigTradeInitializer::offerNotAvailable);
            if (serviceProvider.getUserService().getUserIdentityService().findUserIdentity(myIdentity.getId()).isEmpty()) {
                throw offerNotAvailable();
            }
            MuSigContract contract = message.getContract();
            AccountPayload<?> accountPayload = findMakerAccountPayload(contract, offer);
            if (hasChatChannel(message.getTradeId())) {
                throw offerNotAvailable();
            }
            TradeRole myRole = offer.getDirection().isBuy() ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER;
            return MuSigV2Trade.create(contract, myIdentity, myRole, accountPayload);
        } catch (TradeProtocolException failure) {
            throw new InitializationRejectedException(reportRejection(message, failure));
        }
    }

    // A new trade creates its own chat channel. A channel which exists already for its trade ID belongs to something
    // else: on a node which is also a mediator or an arbitrator, a request can create a channel for any trade ID, and
    // a taker knows the ID of its trade before it sends the take offer request. The trade would then post its log
    // messages into that channel and build its mediation request from it.
    private boolean hasChatChannel(String tradeId) {
        return serviceProvider.getChatService()
                .getMuSigOpenTradeChannelService()
                .findChannelByTradeId(tradeId)
                .isPresent();
    }

    private AccountPayload<?> findMakerAccountPayload(MuSigContract contract, MuSigOffer offer) {
        PaymentMethod<? extends PaymentRail> paymentMethod =
                contract.getNonBtcSidePaymentMethodSpec().getPaymentMethod();
        Set<Account<? extends PaymentMethod<?>, ?>> accounts =
                serviceProvider.getAccountService().getAccounts(paymentMethod);
        return OfferOptionUtil.findAccountOptions(offer.getOfferOptions()).stream()
                .filter(option -> option.getPaymentMethod().equals(paymentMethod))
                .map(AccountOption::getSaltedAccountId)
                .flatMap(saltedId -> OfferOptionUtil.findAccountFromSaltedAccountId(accounts, saltedId, offer.getId()).stream())
                .findAny()
                .orElseThrow(MuSigTradeInitializer::offerNotAvailable)
                .getAccountPayload();
    }

    /** Reports the rejection to the taker and returns the failure, which the caller throws. */
    private TradeProtocolException reportRejection(SetupTradeMessage_A message, RuntimeException exception) {
        TradeProtocolException failure = takeOfferValidationFailure(message, exception);
        sendTakeOfferRejection(message, failure);
        return failure;
    }

    /** A rejected request has no initialized trade; reporting is best effort and preserves the admission failure. */
    private void sendTakeOfferRejection(SetupTradeMessage_A message, TradeProtocolException exception) {
        try {
            // The key identifies the receiving identity; its addresses can have changed since it published the offer.
            Optional<Identity> receivingIdentity = serviceProvider.getIdentityService()
                    .findAnyIdentityById(message.getReceiver().getId());
            if (receivingIdentity.isEmpty()) {
                log.warn("Cannot report a MuSig take offer rejection because the receiving identity is unavailable. tradeId={}",
                        StringUtils.sanitizeForLog(message.getTradeId()));
                return;
            }
            Identity identity = receivingIdentity.orElseThrow();
            String errorMessage = Optional.ofNullable(exception.getMessage())
                    .orElse(exception.getTradeProtocolFailure().name());
            // The sender is the network ID to which the taker addressed the request (rule 3 of
            // docs/specifications/user/identity-by-key.md).
            MuSigReportErrorMessage error = new MuSigReportErrorMessage(StringUtils.createUid(),
                    message.getTradeId(),
                    MuSigProtocol.VERSION,
                    message.getReceiver(),
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
