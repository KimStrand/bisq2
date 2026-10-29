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

package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;

import bisq.common.processing.Effect;
import bisq.contract.ContractSignatureData;
import bisq.trade.exceptions.TradeProtocolFailure;
import bisq.trade.mu_sig.messages.network.MuSigReportErrorMessage;
import bisq.trade.mu_sig.messages.network.SetupTradeMessage_B;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol.Flow;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.events.ExecutionFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.MuSigProcessingFailedEvent;
import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.FailedAtPeer;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Set;

import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertOperationFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertProtocolFailure;
import static bisq.trade.mu_sig_v2.MuSigFailureAssertions.assertRejected;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MuSigFailureHandlingTest extends SellerAsTakerFixture {
    @Test
    void aWrongSetupContractHashStillFailsAndACorrectedMessageCannotReviveExecution() {
        protocol.handle(new MuSigTakeOfferEvent());
        var signature = contractSignatureData();
        byte[] wrongHash = signature.getContractHash().clone();
        wrongHash[0] ^= 1;
        var invalid = new SetupTradeMessage_B(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                muSigContract,
                new ContractSignatureData(wrongHash, signature.getSignature(), signature.getPublicKey()),
                pubKeyShares(),
                nonceShares());

        var failure = assertProtocolFailure(() -> protocol.handle(invalid));

        assertTrue(failure.getMessage().contains("Peer's contractHash"));
        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertEquals(new MuSigTradeState.Failed(), protocol.getTrade().getState());
        var failed = protocol.getTrade();
        assertTrue(failed.getExecution().data().getPeerContractSignature().isEmpty());
        assertEquals(MuSigReportErrorMessage.class, sentMessageTypes().getLast());
        protocol.handle(setupMessage());
        assertSame(failed, protocol.getTrade());
        verify(blockingStub(), never()).getPartialSignatures(any());
    }

    @Test
    void onlyAnAuthenticatedPeerCanReportFailureAndTheReportIsNotEchoed() {
        var before = protocol.getTrade();
        var invalid = List.of(
                new MuSigReportErrorMessage(uid(),
                        "another-trade",
                        "1.0.0",
                        peerNetworkId(),
                        myNetworkId(),
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.OFFER_NOT_AVAILABLE),
                new MuSigReportErrorMessage(uid(),
                        TRADE_ID,
                        "1.0.0",
                        myNetworkId(),
                        myNetworkId(),
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.OFFER_NOT_AVAILABLE),
                new MuSigReportErrorMessage(uid(),
                        TRADE_ID,
                        "1.0.0",
                        peerNetworkId(),
                        peerNetworkId(),
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.OFFER_NOT_AVAILABLE),
                new MuSigReportErrorMessage(uid(),
                        TRADE_ID,
                        "2.0.0",
                        peerNetworkId(),
                        myNetworkId(),
                        "Peer failure",
                        "Peer stack trace",
                        TradeProtocolFailure.OFFER_NOT_AVAILABLE));
        for (var message : invalid) {
            assertRejected(() -> protocol.handle(message));
            assertSame(before, protocol.getTrade());
        }
        assertRejected(() -> protocol.handle(new MuSigProcessingFailedEvent(
                TradeProtocolFailure.UNKNOWN,
                "Injected failure",
                "Injected stack trace")));
        assertRejected(() -> protocol.handle(new ExecutionFailedEvent()));
        assertSame(before, protocol.getTrade());
        assertTrue(effects.isEmpty());

        var report = report();
        protocol.handle(report);

        assertInstanceOf(FailedAtPeer.class, protocol.getExecutionState());
        assertEquals(new MuSigTradeState.Failed(), protocol.getTrade().getState());
        assertTrue(protocol.getProcessedEventTypes().getOrDefault(Flow.TRADE,
                Set.of()).contains(ExecutionFailedEvent.class));
        var data = protocol.getTrade().getExecution().data();
        var peerFailure = data.getPeerFailure().orElseThrow();
        assertEquals(report.getTradeProtocolFailure(), peerFailure.tradeProtocolFailure());
        assertEquals(report.getErrorMessage(), peerFailure.errorMessage());
        assertEquals(report.getStackTrace(), peerFailure.errorStackTrace());
        assertTrue(data.getMyFailure().isEmpty());
        assertTrue(sentMessageTypes().isEmpty());
        var failed = protocol.getTrade();
        protocol.handle(report());
        protocol.resume();
        assertSame(failed, protocol.getTrade());
        verifyNoInteractions(blockingStub());
    }

    @Test
    void aLatePeerErrorCannotReplaceSuccessfulClosure() {
        runSetup(protocol::handle);
        runSettlement(protocol::handle);
        var closed = protocol.getTrade();
        effects.clear();

        protocol.handle(report());

        assertSame(closed, protocol.getTrade());
        assertTrue(effects.isEmpty());
    }

    @Test
    void anOperationFailureKeepsFullLocalDetailsAndUsesTheExistingWireLimits() {
        String message = "Backend failure ".repeat(100);
        var cause = new IllegalStateException(message);
        when(blockingStub().initTrade(any())).thenThrow(cause);

        assertSame(cause, assertOperationFailure(() -> protocol.handle(new MuSigTakeOfferEvent())).getCause());

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        var data = protocol.getTrade().getExecution().data();
        assertEquals(message, data.getMyFailure().orElseThrow().errorMessage());
        var report = effects.stream().filter(Effect.SendMessage.class::isInstance)
                .map(effect -> ((Effect.SendMessage<?, ?>) effect).message())
                .map(MuSigReportErrorMessage.class::cast).findFirst().orElseThrow();
        report.verify();
        assertTrue(report.getErrorMessage().length() <= MuSigReportErrorMessage.MAX_LENGTH_ERROR_MESSAGE);
        assertTrue(report.getStackTrace().length() <= MuSigReportErrorMessage.MAX_LENGTH_STACKTRACE);
        assertEquals(TradeProtocolFailure.UNKNOWN, report.getTradeProtocolFailure());
        assertTrue(data.getMyContractSignature().isEmpty());
        assertRejected(() -> protocol.handle(new MuSigTakeOfferEvent()));
        verify(blockingStub()).initTrade(any());
    }

    @Test
    void checkedContractSigningFailureIsAnOperationFailure() throws Exception {
        var cause = new GeneralSecurityException("Signing failed");
        when(services.getContractService().signContract(any(), any())).thenThrow(cause);

        assertSame(cause, assertOperationFailure(() -> protocol.handle(new MuSigTakeOfferEvent())).getCause());

        assertInstanceOf(Failed.class, protocol.getExecutionState());
        assertEquals("Signing failed",
                protocol.getTrade().getExecution().data().getMyFailure().orElseThrow().errorMessage());
        assertTrue(protocol.getTrade().getExecution().data().getMyPubKeyShares().isEmpty());
        assertEquals(List.of(MuSigReportErrorMessage.class), sentMessageTypes());
        verify(blockingStub(), never()).getNonceShares(any());
    }

    private MuSigReportErrorMessage report() {
        return new MuSigReportErrorMessage(uid(),
                TRADE_ID,
                "1.0.0",
                peerNetworkId(),
                myNetworkId(),
                "Offer unavailable",
                "Peer stack trace",
                TradeProtocolFailure.OFFER_NOT_AVAILABLE);
    }
}
