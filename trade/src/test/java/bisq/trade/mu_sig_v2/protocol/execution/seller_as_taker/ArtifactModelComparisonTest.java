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

import bisq.account.accounts.AccountPayload;
import bisq.contract.ContractSignatureData;
import bisq.trade.mu_sig.messages.grpc.CloseTradeResponse;
import bisq.trade.mu_sig.messages.grpc.DepositPsbt;
import bisq.trade.mu_sig.messages.grpc.NonceSharesMessage;
import bisq.trade.mu_sig.messages.grpc.PartialSignaturesMessage;
import bisq.trade.mu_sig.messages.grpc.SwapTxSignatureResponse;
import bisq.trade.mu_sig.messages.network.mu_sig_data.NonceShares;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PartialSignatures;
import bisq.trade.mu_sig.messages.network.mu_sig_data.PubKeyShares;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Executable design comparison, not a replacement protocol. The records illustrate artifact
 * ownership and required input types; transitions below assume backend and peer validation
 * already succeeded. No network, Rust or persistence operations run here.
 */
class ArtifactModelComparisonTest {
    @TempDir
    Path directory;

    // Group values by the operation that creates them, independently of the stage names.
    record InitializationArtifacts(PubKeyShares myPubKeyShares,
                                   ContractSignatureData myContractSignature) {
        InitializationArtifacts {
            requireNonNull(myPubKeyShares);
            requireNonNull(myContractSignature);
        }
    }

    record SetupArtifacts(InitializationArtifacts initialization,
                          ContractSignatureData peerContractSignature,
                          PubKeyShares peerPubKeyShares,
                          NonceShares peerNonceShares,
                          NonceSharesMessage myNonceShares,
                          PartialSignaturesMessage myPartialSignatures) {
        SetupArtifacts {
            requireNonNull(initialization);
            requireNonNull(peerContractSignature);
            requireNonNull(peerPubKeyShares);
            requireNonNull(peerNonceShares);
            requireNonNull(myNonceShares);
            requireNonNull(myPartialSignatures);
        }
    }

    record DepositArtifacts(PartialSignatures peerRedactedPartialSignatures,
                            DepositPsbt myDepositPsbt,
                            DepositPsbt peerDepositPsbt) {
        DepositArtifacts {
            requireNonNull(peerRedactedPartialSignatures);
            requireNonNull(myDepositPsbt);
            requireNonNull(peerDepositPsbt);
        }
    }

    // This aggregate is available after account-payload verification, before confirmation.
    record PaymentPreparationArtifacts(SetupArtifacts setup,
                                       DepositArtifacts deposit,
                                       AccountPayload<?> peerAccountPayload) {
        PaymentPreparationArtifacts {
            requireNonNull(setup);
            requireNonNull(deposit);
            requireNonNull(peerAccountPayload);
        }
    }

    record PaymentArtifacts(PaymentPreparationArtifacts preparation,
                            PartialSignatures peerPartialSignatures) {
        PaymentArtifacts {
            requireNonNull(preparation);
            requireNonNull(peerPartialSignatures);
        }
    }

    record TradeInitialized(InitializationArtifacts artifacts) {
        TradeInitialized {
            requireNonNull(artifacts);
        }
    }

    record NonceSharesAndPartialSignaturesCreated(SetupArtifacts artifacts) {
        NonceSharesAndPartialSignaturesCreated {
            requireNonNull(artifacts);
        }
    }

    record DepositTxSignedAndPublished(SetupArtifacts setup, DepositArtifacts deposit) {
        DepositTxSignedAndPublished {
            requireNonNull(setup);
            requireNonNull(deposit);
        }
    }

    record AccountPayloadReceived(PaymentPreparationArtifacts artifacts) {
        AccountPayloadReceived {
            requireNonNull(artifacts);
        }
    }

    interface HasPeerRedactedPartialSignatures {
        PartialSignatures peerRedactedPartialSignatures();
    }

    record DepositTxConfirmed(PaymentPreparationArtifacts artifacts) implements HasPeerRedactedPartialSignatures {
        DepositTxConfirmed {
            requireNonNull(artifacts);
        }

        @Override
        public PartialSignatures peerRedactedPartialSignatures() {
            return artifacts.deposit().peerRedactedPartialSignatures();
        }
    }

    record PaymentInitiatedMessageReceived(PaymentArtifacts artifacts) {
        PaymentInitiatedMessageReceived {
            requireNonNull(artifacts);
        }
    }

    record SellerForceClosedTrade(PaymentArtifacts artifacts,
                                  SwapTxSignatureResponse mySwapTxSignatureResponse,
                                  CloseTradeResponse myCloseTradeResponse) {
        SellerForceClosedTrade {
            requireNonNull(artifacts);
            requireNonNull(mySwapTxSignatureResponse);
            requireNonNull(myCloseTradeResponse);
        }
    }

    // The same middle sequence with flat records. Its accessors implement the capability directly.
    record FlatDepositTxSignedAndPublished(ContractSignatureData myContractSignature,
                                           ContractSignatureData peerContractSignature,
                                           PartialSignatures peerRedactedPartialSignatures,
                                           DepositPsbt myDepositPsbt,
                                           DepositPsbt peerDepositPsbt) implements HasPeerRedactedPartialSignatures {
        FlatDepositTxSignedAndPublished {
            requireNonNull(myContractSignature);
            requireNonNull(peerContractSignature);
            requireNonNull(peerRedactedPartialSignatures);
            requireNonNull(myDepositPsbt);
            requireNonNull(peerDepositPsbt);
        }
    }

    record FlatAccountPayloadReceived(ContractSignatureData myContractSignature,
                                      ContractSignatureData peerContractSignature,
                                      PartialSignatures peerRedactedPartialSignatures,
                                      DepositPsbt myDepositPsbt,
                                      DepositPsbt peerDepositPsbt,
                                      AccountPayload<?> peerAccountPayload) implements HasPeerRedactedPartialSignatures {
        FlatAccountPayloadReceived {
            requireNonNull(myContractSignature);
            requireNonNull(peerContractSignature);
            requireNonNull(peerRedactedPartialSignatures);
            requireNonNull(myDepositPsbt);
            requireNonNull(peerDepositPsbt);
            requireNonNull(peerAccountPayload);
        }
    }

    record FlatDepositTxConfirmed(ContractSignatureData myContractSignature,
                                  ContractSignatureData peerContractSignature,
                                  PartialSignatures peerRedactedPartialSignatures,
                                  DepositPsbt myDepositPsbt,
                                  DepositPsbt peerDepositPsbt,
                                  AccountPayload<?> peerAccountPayload) implements HasPeerRedactedPartialSignatures {
        FlatDepositTxConfirmed {
            requireNonNull(myContractSignature);
            requireNonNull(peerContractSignature);
            requireNonNull(peerRedactedPartialSignatures);
            requireNonNull(myDepositPsbt);
            requireNonNull(peerDepositPsbt);
            requireNonNull(peerAccountPayload);
        }
    }

    // A candidate terminal payload, not a direct transition from deposit confirmation to closure.
    // Includes the account for the comparison's retention scenario; production currently drops it.
    record FlatClosureArtifacts(ContractSignatureData myContractSignature,
                                ContractSignatureData peerContractSignature,
                                DepositPsbt myDepositPsbt,
                                DepositPsbt peerDepositPsbt,
                                AccountPayload<?> peerAccountPayload,
                                SwapTxSignatureResponse mySwapTxSignatureResponse,
                                CloseTradeResponse myCloseTradeResponse) {
        FlatClosureArtifacts {
            requireNonNull(myContractSignature);
            requireNonNull(peerContractSignature);
            requireNonNull(myDepositPsbt);
            requireNonNull(peerDepositPsbt);
            requireNonNull(peerAccountPayload);
            requireNonNull(mySwapTxSignatureResponse);
            requireNonNull(myCloseTradeResponse);
        }
    }

    // Comparison: a separate optional bag has no relationship to the position's type.
    record EmptyDepositTxConfirmed() {}
    record OptionalArtifacts(Optional<ContractSignatureData> signature,
                             Optional<PartialSignatures> peerPartialSignatures) {}
    record SeparateSnapshot(EmptyDepositTxConfirmed position, OptionalArtifacts artifacts) {}

    @Test
    void setupReadsItsRequiredSignatureWithoutAnOptionalLookup() {
        InitializationArtifacts initialization = initialization();
        TradeInitialized state = new TradeInitialized(initialization);
        ContractSignatureData signatureForVerification = state.artifacts().myContractSignature();
        assertSame(initialization.myContractSignature(), signatureForVerification);

        SetupArtifacts setup = setup(state.artifacts());
        NonceSharesAndPartialSignaturesCreated next = new NonceSharesAndPartialSignaturesCreated(setup);
        assertSame(initialization, next.artifacts().initialization());
    }

    @Test
    void paymentAddsCompleteSignaturesAndRetainsExistingArtifacts() {
        PaymentPreparationArtifacts preparation = preparation();
        DepositTxConfirmed state = new DepositTxConfirmed(preparation);
        byte[] receivedSignature = new byte[]{7};

        // The real handler first calls Rust to validate this signature, as it does today.
        PartialSignatures complete = PartialSignatures.toUnRedacted(
                state.artifacts().deposit().peerRedactedPartialSignatures(),
                receivedSignature);
        PaymentInitiatedMessageReceived next = new PaymentInitiatedMessageReceived(
                new PaymentArtifacts(state.artifacts(), complete));

        assertSame(preparation, next.artifacts().preparation());
        assertArrayEquals(receivedSignature,
                next.artifacts().peerPartialSignatures().getSwapTxInputPartialSignature().orElseThrow());
        assertTrue(preparation.deposit().peerRedactedPartialSignatures().getSwapTxInputPartialSignature().isEmpty());
    }

    @Test
    void closureRetainsTheAccountAndDepositArtifactsWithoutIndividualForwarding() {
        PaymentPreparationArtifacts preparation = preparation();
        PaymentArtifacts payment = new PaymentArtifacts(preparation, mock(PartialSignatures.class));
        SellerForceClosedTrade closed = new SellerForceClosedTrade(payment,
                mock(SwapTxSignatureResponse.class),
                mock(CloseTradeResponse.class));

        assertSame(preparation.peerAccountPayload(), closed.artifacts().preparation().peerAccountPayload());
        assertSame(preparation.deposit(), closed.artifacts().preparation().deposit());
        assertSame(preparation.setup().initialization().myContractSignature(),
                closed.artifacts().preparation().setup().initialization().myContractSignature());
    }

    @Test
    void groupedStagesComposeSetupAndDepositBeforeAddingTheAccount() {
        PaymentPreparationArtifacts fixture = preparation();
        NonceSharesAndPartialSignaturesCreated setup = new NonceSharesAndPartialSignaturesCreated(fixture.setup());

        // SetupTradeMessage_D supplies deposit artifacts after backend work succeeds.
        DepositTxSignedAndPublished published = new DepositTxSignedAndPublished(setup.artifacts(), fixture.deposit());
        AccountPayloadReceived accountReceived = receiveAccount(published, fixture.peerAccountPayload());
        DepositTxConfirmed confirmed = confirmDeposit(accountReceived);

        assertSame(setup.artifacts(), published.setup());
        assertSame(published.setup(), confirmed.artifacts().setup());
        assertSame(published.deposit(), confirmed.artifacts().deposit());
        assertSame(fixture.peerAccountPayload(), confirmed.artifacts().peerAccountPayload());
        assertSame(accountReceived.artifacts(), confirmed.artifacts());
    }

    @Test
    void flatStagesForwardTheSameRequiredDataThroughTheMiddleSequence() {
        PaymentPreparationArtifacts fixture = preparation();
        FlatDepositTxSignedAndPublished published = flatPublished(fixture);
        FlatAccountPayloadReceived accountReceived = receiveAccount(published, fixture.peerAccountPayload());
        FlatDepositTxConfirmed confirmed = confirmDeposit(accountReceived);

        assertSame(published.myContractSignature(), confirmed.myContractSignature());
        assertSame(published.peerContractSignature(), confirmed.peerContractSignature());
        assertSame(published.myDepositPsbt(), confirmed.myDepositPsbt());
        assertSame(published.peerDepositPsbt(), confirmed.peerDepositPsbt());
        assertSame(published.peerRedactedPartialSignatures(), confirmed.peerRedactedPartialSignatures());
        assertSame(fixture.peerAccountPayload(), confirmed.peerAccountPayload());
    }

    @Test
    void theSameCapabilityCanReadGroupedAndFlatRecords() {
        PaymentPreparationArtifacts fixture = preparation();
        DepositTxConfirmed grouped = new DepositTxConfirmed(fixture);
        FlatDepositTxConfirmed flat = confirmDeposit(receiveAccount(flatPublished(fixture), fixture.peerAccountPayload()));
        byte[] signature = new byte[]{7};

        assertEquals(completePeerSignatures(grouped, signature), completePeerSignatures(flat, signature));
    }

    @Test
    void bothTerminalShapesRetainTheChosenEvidenceButOnlyTheNestedShapeRetainsSetup() {
        PaymentPreparationArtifacts fixture = preparation();
        SwapTxSignatureResponse swap = mock(SwapTxSignatureResponse.class);
        CloseTradeResponse close = mock(CloseTradeResponse.class);
        SellerForceClosedTrade grouped = new SellerForceClosedTrade(
                new PaymentArtifacts(fixture, mock(PartialSignatures.class)),
                swap,
                close);
        FlatClosureArtifacts flat = new FlatClosureArtifacts(
                fixture.setup().initialization().myContractSignature(),
                fixture.setup().peerContractSignature(),
                fixture.deposit().myDepositPsbt(),
                fixture.deposit().peerDepositPsbt(),
                fixture.peerAccountPayload(),
                swap,
                close);

        assertSame(grouped.artifacts().preparation().peerAccountPayload(), flat.peerAccountPayload());
        assertSame(grouped.artifacts().preparation().deposit().myDepositPsbt(), flat.myDepositPsbt());
        assertSame(grouped.artifacts().preparation().deposit().peerDepositPsbt(), flat.peerDepositPsbt());
        assertSame(fixture.setup(), grouped.artifacts().preparation().setup());
        // The flat terminal record's declared fields contain no setup, key-share or nonce bundle.
        assertEquals(List.of(ContractSignatureData.class,
                ContractSignatureData.class,
                DepositPsbt.class,
                DepositPsbt.class,
                AccountPayload.class,
                SwapTxSignatureResponse.class,
                CloseTradeResponse.class),
                Arrays.stream(FlatClosureArtifacts.class.getRecordComponents())
                        .map(RecordComponent::getType).toList());
    }

    @Test
    void missingRequiredValuesAreRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new InitializationArtifacts(mock(PubKeyShares.class), null));
        assertThrows(NullPointerException.class, () -> new DepositTxConfirmed(null));
    }

    @Test
    void aPositionWithMissingOptionalArtifactsStillCompilesAndConstructs() throws Exception {
        assertTrue(compiles("""
                static Object example() {
                    return new SeparateSnapshot(new EmptyDepositTxConfirmed(),
                            new OptionalArtifacts(java.util.Optional.empty(), java.util.Optional.empty()));
                }
                """));
        SeparateSnapshot snapshot = new SeparateSnapshot(new EmptyDepositTxConfirmed(),
                new OptionalArtifacts(Optional.empty(), Optional.empty()));
        assertTrue(snapshot.artifacts().peerPartialSignatures().isEmpty());
    }

    @Test
    void theRequiredArtifactGroupCompiles() throws Exception {
        assertTrue(compiles("""
                static Object example(PaymentPreparationArtifacts artifacts) {
                    return new DepositTxConfirmed(artifacts);
                }
                """));
    }

    @Test
    void anEarlierArtifactGroupCannotConstructTheLaterStage() throws Exception {
        assertFalse(compiles("""
                static Object example(InitializationArtifacts artifacts) {
                    return new DepositTxConfirmed(artifacts);
                }
                """));
    }

    @Test
    void theLaterStageCannotBeConstructedWithoutItsRequiredGroup() throws Exception {
        assertFalse(compiles("""
                static Object example() {
                    return new DepositTxConfirmed();
                }
                """));
    }

    @Test
    void aCapabilityCanBeAvailableBeforeThePaymentTransitionIsAllowed() throws Exception {
        assertTrue(compiles("""
                static HasPeerRedactedPartialSignatures example(FlatAccountPayloadReceived state) {
                    return state;
                }
                """));
        assertTrue(compiles("""
                static Object payment(FlatDepositTxConfirmed state) { return state; }
                static Object example(FlatDepositTxConfirmed state) { return payment(state); }
                """));
        assertFalse(compiles("""
                static Object payment(FlatDepositTxConfirmed state) { return state; }
                static Object example(FlatAccountPayloadReceived state) { return payment(state); }
                """));
    }

    private static AccountPayloadReceived receiveAccount(DepositTxSignedAndPublished state, AccountPayload<?> payload) {
        return new AccountPayloadReceived(new PaymentPreparationArtifacts(state.setup(), state.deposit(), payload));
    }

    private static DepositTxConfirmed confirmDeposit(AccountPayloadReceived state) {
        return new DepositTxConfirmed(state.artifacts());
    }

    private static FlatAccountPayloadReceived receiveAccount(FlatDepositTxSignedAndPublished state,
                                                             AccountPayload<?> payload) {
        return new FlatAccountPayloadReceived(state.myContractSignature(),
                state.peerContractSignature(),
                state.peerRedactedPartialSignatures(),
                state.myDepositPsbt(),
                state.peerDepositPsbt(),
                payload);
    }

    private static FlatDepositTxConfirmed confirmDeposit(FlatAccountPayloadReceived state) {
        return new FlatDepositTxConfirmed(state.myContractSignature(),
                state.peerContractSignature(),
                state.peerRedactedPartialSignatures(),
                state.myDepositPsbt(),
                state.peerDepositPsbt(),
                state.peerAccountPayload());
    }

    private static PartialSignatures completePeerSignatures(HasPeerRedactedPartialSignatures source, byte[] signature) {
        return PartialSignatures.toUnRedacted(source.peerRedactedPartialSignatures(), signature);
    }

    private static FlatDepositTxSignedAndPublished flatPublished(PaymentPreparationArtifacts fixture) {
        return new FlatDepositTxSignedAndPublished(fixture.setup().initialization().myContractSignature(),
                fixture.setup().peerContractSignature(),
                fixture.deposit().peerRedactedPartialSignatures(),
                fixture.deposit().myDepositPsbt(),
                fixture.deposit().peerDepositPsbt());
    }

    private boolean compiles(String method) throws Exception {
        Path source = directory.resolve("ArtifactShapeProbe.java");
        Files.writeString(source, """
                package bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker;
                import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.ArtifactModelComparisonTest.*;
                class ArtifactShapeProbe {
                %s
                }
                """.formatted(method));
        var compiler = ToolProvider.getSystemJavaCompiler();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        try (var files = compiler.getStandardFileManager(diagnostics, null, null)) {
            boolean success = compiler.getTask(null,
                    files,
                    diagnostics,
                    List.of("--release",
                            "21",
                            "-proc:none",
                            "-classpath",
                            System.getProperty("bisq.test.compile.classpath", System.getProperty("java.class.path")),
                            "-d",
                            directory.toString()),
                    null,
                    files.getJavaFileObjects(source)).call();
            if (!success) {
                diagnostics.getDiagnostics().forEach(System.out::println);
            }
            return success;
        }
    }

    private static InitializationArtifacts initialization() {
        return new InitializationArtifacts(mock(PubKeyShares.class), mock(ContractSignatureData.class));
    }

    private static SetupArtifacts setup(InitializationArtifacts initialization) {
        return new SetupArtifacts(initialization,
                mock(ContractSignatureData.class),
                mock(PubKeyShares.class),
                mock(NonceShares.class),
                mock(NonceSharesMessage.class),
                mock(PartialSignaturesMessage.class));
    }

    private static PaymentPreparationArtifacts preparation() {
        return new PaymentPreparationArtifacts(setup(initialization()),
                new DepositArtifacts(assertDoesNotThrow(() -> PartialSignatures.fromProto(bisq.trade.protobuf.PartialSignatures.getDefaultInstance())),
                        new DepositPsbt(new byte[]{1}),
                        new DepositPsbt(new byte[]{2})),
                mock(AccountPayload.class));
    }
}
