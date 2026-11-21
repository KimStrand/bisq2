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

import bisq.common.data.ByteArray;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.util.StringUtils;
import bisq.offer.Direction;
import bisq.security.keys.KeyGeneration;
import bisq.trade.TradeRole;
import bisq.trade.exceptions.TradeProtocolException;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.SendAccountPayloadAndDepositTxMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig_v2.protocol.MuSigFailureCategory;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocolException;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.AccountPayloadReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.CustomPayoutSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PartialSignaturesCreatedAndDepositTxSigned;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentInitiatedMessageReceived;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.PaymentReceiptConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitialized;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeInitializedAndNonceSharesCreated;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.submitMessage;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.whenExecutionState;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * A maker trade after a restart, and the checks of the peer's data which the maker repeats then.
 */
class MuSigMakerRestorePairTest extends MuSigTradeServicePairFixture {
    @ParameterizedTest
    @EnumSource(TradeRole.class)
    void aStoredTradeWhoseExecutionStateBelongsToAnotherRoleCannotBeRead(TradeRole role) throws Exception {
        boolean buyerAsMaker = role == TradeRole.BUYER_AS_MAKER || role == TradeRole.SELLER_AS_TAKER;
        configureOffer(buyerAsMaker ? Direction.BUY : Direction.SELL);
        var peer = role.isBuyer() ? buyer : seller;
        // The same side of the offer in the other position, whose state family contains the wrong state.
        var otherRole = switch (role) {
            case BUYER_AS_MAKER -> TradeRole.BUYER_AS_TAKER;
            case BUYER_AS_TAKER -> TradeRole.BUYER_AS_MAKER;
            case SELLER_AS_MAKER -> TradeRole.SELLER_AS_TAKER;
            case SELLER_AS_TAKER -> TradeRole.SELLER_AS_MAKER;
        };
        MuSigTradeExecutionState wrongState = role.isMaker()
                ? new TradeInitialized() : new TradeInitializedAndNonceSharesCreated();
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                peer.identity,
                role,
                "1.0.0",
                peer.account.getAccountPayload(),
                new Init());
        var otherTrade = MuSigV2TradeTestFactory.tradeAt(contract,
                peer.identity,
                otherRole,
                "1.0.0",
                peer.account.getAccountPayload(),
                wrongState);
        var stored = trade.toProto(false).toBuilder()
                .setExecution(otherTrade.toProto(false).getExecution())
                .build();

        var failure = assertThrows(UnresolvableProtobufException.class, () -> MuSigV2Trade.fromProto(stored));

        assertEquals("Execution state does not match the trade role", failure.getCause().getMessage());
        assertThrows(IllegalArgumentException.class, () -> MuSigV2TradeTestFactory.tradeAt(contract,
                peer.identity,
                role,
                "1.0.0",
                peer.account.getAccountPayload(),
                wrongState));
    }

    @Test
    void restoredInitialTradeStillRejectsAContractSignedByAForeignKey() throws Exception {
        automaticDelivery = false;
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                buyer.identity,
                TradeRole.BUYER_AS_MAKER,
                "1.0.0",
                buyer.account.getAccountPayload(),
                new Init());
        await(buyer.restoreTrade(initialRecord(trade)));
        var channels = buyer.services.getChatService().getMuSigOpenTradeChannelService();
        // Recovery already ensured the chat; now inspect only handling of the invalid message.
        clearInvocations(channels);
        var invalid = new SetupTradeMessage_A(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkId(),
                contract,
                contractService.signContract(contract, KeyGeneration.generateDefaultEcKeyPair()),
                request().getPubKeyShares());
        assertFailure(TradeProtocolException.class, submitMessage(buyer.service, invalid));
        assertInstanceOf(Failed.class,
                await(whenExecutionState(buyer.service, tradeId, Failed.class)));
        verifyNoInteractions(buyer.backend);
        verify(channels).findChannelByTradeId(tradeId);
        verifyNoMoreInteractions(channels);
        assertInstanceOf(MuSigReportErrorMessage.class, messages.element());
        assertEquals(1, messages.size());
        var network = buyer.services.getNetworkService();
        verify(network).subscribeToConfidentialMessages(buyer.service);
        verify(network).confidentialSend(messages.element(),
                seller.identity.getNetworkId(),
                buyer.identity.getNetworkIdWithKeyPair());
        verifyNoMoreInteractions(network);
    }

    @Test
    void aRestoredMakerKeepsTheExistingFailureReasonWhenItsHandlerRejectsThePeer() throws Exception {
        automaticDelivery = false;
        var initial = MuSigV2TradeTestFactory.tradeAt(contract,
                buyer.identity,
                TradeRole.BUYER_AS_MAKER,
                "1.0.0",
                buyer.account.getAccountPayload(),
                new Init());
        await(buyer.restoreTrade(initialRecord(initial)));
        when(buyer.services.getUserService().getUserProfileService().isChatUserIgnored(seller.profile.getId()))
                .thenReturn(true);

        var completion = assertThrows(ExecutionException.class, () -> await(submitMessage(buyer.service, request())));

        var failure = assertInstanceOf(MuSigProtocolException.class, completion.getCause());
        assertEquals(MuSigFailureCategory.PROTOCOL_FAILED, failure.getCategory());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE, failure.getTradeProtocolFailure());
        var failed = savedRecord(buyer.service, tradeId).trade();
        assertInstanceOf(Failed.class, failed.getExecution().state());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                failed.getExecution().data().getMyFailure().orElseThrow().tradeProtocolFailure());
        assertEquals(TradeProtocolFailure.OFFER_NOT_AVAILABLE,
                sent(MuSigReportErrorMessage.class).getTradeProtocolFailure());
        verifyNoInteractions(buyer.backend);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void wrongSignatureRedactionIsRejectedBeforeMakerSignsTheDeposit(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        automaticDelivery = false;
        var maker = maker();
        var taker = taker();
        await(submitMessage(maker.service, request()));
        var messageB = sent(SetupTradeMessage_B.class);
        clearInvocations(maker.backend);
        var signatures = bisq.trade.protobuf.PartialSignatures.newBuilder();
        if (!buyerAsMaker) {
            signatures.setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5}));
        }
        var invalid = new SetupTradeMessage_C(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                messageB.getNonceShares(),
                PartialSignatures.fromProto(signatures.build()));
        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, invalid));
        assertInstanceOf(Failed.class,
                await(whenExecutionState(maker.service, tradeId, Failed.class)));
        verifyNoInteractions(maker.backend);
        assertEquals(2, messages.size());

        if (buyerAsMaker) {
            signatures.setSwapTxInputPartialSignature(ByteString.copyFrom(new byte[]{5}));
        } else {
            signatures.clearSwapTxInputPartialSignature();
        }
        var valid = new SetupTradeMessage_C(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                messageB.getNonceShares(),
                PartialSignatures.fromProto(signatures.build()));
        await(submitMessage(maker.service, valid));
        assertInstanceOf(Failed.class, savedRecord(maker.service, valid.getTradeId()).trade().getExecution().state());
        verify(maker.backend, never()).signDepositTx(any());
        assertEquals(MuSigReportErrorMessage.class,
                messages.stream().reduce((first, last) -> last).orElseThrow().getClass());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void accountMismatchDoesNotRecordTheDepositOrStartObservation(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        automaticDelivery = false;
        var maker = maker();
        var taker = taker();
        var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                maker.identity,
                buyerAsMaker ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER,
                "1.0.0",
                maker.account.getAccountPayload(),
                new PartialSignaturesCreatedAndDepositTxSigned());
        await(maker.restoreTrade(initialRecord(trade)));
        var invalid = new SendAccountPayloadAndDepositTxMessage(StringUtils.createUid(),
                tradeId,
                "1.0.0",
                taker.identity.getNetworkId(),
                maker.identity.getNetworkId(),
                new ByteArray(new byte[]{4}),
                maker.account.getAccountPayload());
        assertFailure(MuSigProtocolException.class, submitMessage(maker.service, invalid));
        var unchanged = savedRecord(maker.service, tradeId).trade();
        assertInstanceOf(Failed.class, unchanged.getExecution().state());
        assertTrue(unchanged.getExecution().data().getPeerAccountPayload().isEmpty());
        assertTrue(unchanged.getExecution().data().getPeerDepositTx().isEmpty());
        assertFalse(maker.confirmation.isDone());
        assertInstanceOf(MuSigReportErrorMessage.class, messages.element());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void makerRestartRestoresDepositObservationAndDoesNotRepeatSetup(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        startTrade();
        var maker = maker();
        var before = savedRecord(maker.service, tradeId).trade().toProto(false);
        maker.service.shutdown().join();
        clearInvocations(maker.backend, maker.asyncBackend);
        maker.confirmation = new CompletableFuture<>();
        maker.start(taker());
        assertEquals(before, savedRecord(maker.service, tradeId).trade().toProto(false));
        verify(maker.asyncBackend).subscribeTxConfirmationStatus(any(), any());
        verifyNoInteractions(maker.backend);
        maker.confirmDeposit();
        assertInstanceOf(DepositTxConfirmed.class,
                await(whenExecutionState(maker.service, tradeId, DepositTxConfirmed.class)));
        await(submitMessage(maker.service, sent(SetupTradeMessage_A.class)));
        verifyNoInteractions(maker.backend);
        assertEquals(6, messages.size());
        assertArrayEquals(new byte[]{(byte) (buyerAsMaker ? 2 : 1)},
                maker.saved().trade().getExecution().data().getPeerDepositTx().orElseThrow().getBytes());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void everyMakerStateAndReceivedDepositSurviveSerialization(boolean buyerAsMaker) throws Exception {
        if (!buyerAsMaker) {
            configureOffer(Direction.SELL);
        }
        List<? extends MuSigTradeExecutionState> states = buyerAsMaker
                ? List.of(new Init(),
                        new TradeInitializedAndNonceSharesCreated(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived(),
                        new DepositTxConfirmed(),
                        new PaymentInitiated(),
                        new CustomPayoutSigned(),
                        new CustomPayoutClosedTrade(),
                        new TradeClosed(),
                        new Failed(),
                        new FailedAtPeer())
                : List.of(new Init(),
                        new TradeInitializedAndNonceSharesCreated(),
                        new PartialSignaturesCreatedAndDepositTxSigned(),
                        new AccountPayloadReceived(),
                        new DepositTxConfirmed(),
                        new PaymentInitiatedMessageReceived(),
                        new PaymentReceiptConfirmed(),
                        new CustomPayoutSigned(),
                        new CustomPayoutClosedTrade(),
                        new SellerForceClosedTrade(),
                        new TradeClosed(),
                        new Failed(),
                        new FailedAtPeer());
        var maker = maker();
        var role = buyerAsMaker ? TradeRole.BUYER_AS_MAKER : TradeRole.SELLER_AS_MAKER;
        for (var state : states) {
            var trade = MuSigV2TradeTestFactory.tradeAt(contract,
                    maker.identity,
                    role,
                    "1.0.0",
                    maker.account.getAccountPayload(),
                    state);
            trade = trade.withExecution(FlowSnapshot.of(state, trade.getExecution().data().toBuilder()
                    .peerDepositTx(Optional.of(new ByteArray(new byte[]{3, 4}))).build()));
            var proto = bisq.trade.protobuf.MuSigV2Trade.parseFrom(trade.serialize());
            var restored = MuSigV2Trade.fromProto(proto);
            assertEquals(role, restored.getMyRole());
            assertEquals(state, restored.getExecution().state());
            assertArrayEquals(new byte[]{3, 4},
                    restored.getExecution().data().getPeerDepositTx().orElseThrow().getBytes());
            assertEquals(proto, restored.toProto(false));
        }
    }
}
