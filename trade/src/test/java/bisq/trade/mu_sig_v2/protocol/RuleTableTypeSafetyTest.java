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

package bisq.trade.mu_sig_v2.protocol;

import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionProtocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuleTableTypeSafetyTest {
    @TempDir
    Path directory;

    @Test
    void matchingSourceEventAndTargetCompile() throws Exception {
        assertTrue(compiles("Init", "MuSigTakeOfferEvent", "TradeInitialized"));
    }

    @Test
    void aHandlerCannotReadADocumentFromTheWrongStage() throws Exception {
        assertFalse(compiles("DepositTxConfirmed", "MuSigTakeOfferEvent", "TradeInitialized"));
    }

    @Test
    void mediationAcceptanceRequiresItsDeclaredSourceEventAndTarget() throws Exception {
        assertTrue(decisionCompiles("Init", "MediationResultAcceptedEvent", "Accepted"));
        assertFalse(decisionCompiles("Accepted", "MediationResultAcceptedEvent", "Accepted"));
        assertFalse(decisionCompiles("Init", "MuSigCustomPayoutPsbtMessage", "Accepted"));
        assertFalse(decisionCompiles("Init", "MediationResultAcceptedEvent", "Rejected"));
    }

    @Test
    void customPayoutFinalizationRequiresItsDeclaredSourceEventAndTarget() throws Exception {
        String events = "bisq.trade.mu_sig_v2.protocol.events.mediation.";
        String finalizationHandler = "bisq.trade.mu_sig_v2.protocol.execution.CustomPayoutFinalizationHandler";
        assertTrue(compiles("SellerAsTakerState",
                "CustomPayoutSigned",
                events + "MediationResultByPeerAcceptedEvent",
                finalizationHandler,
                "CustomPayoutClosedTrade",
                "MuSigV2TradeExecutionData"));
        assertFalse(compiles("SellerAsTakerState",
                "DepositTxConfirmed",
                events + "MediationResultByPeerAcceptedEvent",
                finalizationHandler,
                "CustomPayoutClosedTrade",
                "MuSigV2TradeExecutionData"));
        assertFalse(compiles("SellerAsTakerState",
                "CustomPayoutSigned",
                events + "MediationResultByMyselfAcceptedEvent",
                finalizationHandler,
                "CustomPayoutClosedTrade",
                "MuSigV2TradeExecutionData"));
        assertFalse(compiles("SellerAsTakerState",
                "CustomPayoutSigned",
                events + "MediationResultByPeerAcceptedEvent",
                finalizationHandler,
                "CustomPayoutSigned",
                "MuSigV2TradeExecutionData"));
    }

    @Test
    void aHandlerCannotReceiveTheWrongEvent() throws Exception {
        assertFalse(compiles("Init", "DepositTxConfirmedEvent", "TradeInitialized"));
    }

    @Test
    void aHandlersDocumentMustHaveTheDeclaredTargetStage() throws Exception {
        assertFalse(compiles("Init", "MuSigTakeOfferEvent", "DepositTxConfirmed"));
    }

    @Test
    void anExecutionHandlerCannotReceiveTheMediationDocument() throws Exception {
        assertFalse(compiles("Init",
                "MuSigTakeOfferEvent",
                "TradeInitialized",
                "bisq.trade.mu_sig_v2.protocol.mediation.MuSigV2MediationData"));
    }

    @Test
    void theSharedTakeOfferHandlerCompilesForBothRoles() throws Exception {
        for (String role : List.of("BuyerAsTakerState", "SellerAsTakerState")) {
            assertTrue(compiles(role,
                    "Init",
                    "MuSigTakeOfferEvent",
                    "bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler",
                    "TradeInitialized",
                    "MuSigV2TradeExecutionData"));
        }
    }

    @Test
    void buyerOnlyStatesCannotBeUsedInSellerRules() throws Exception {
        for (String role : List.of("BuyerAsMakerState", "BuyerAsTakerState", "SellerAsMakerState", "SellerAsTakerState")) {
            boolean buyer = role.startsWith("Buyer");
            assertEquals(buyer, compiles(role,
                    "DepositTxConfirmed",
                    "PaymentInitiatedEvent",
                    "bisq.trade.mu_sig_v2.protocol.execution.buyer.PaymentInitiatedEventHandler",
                    "PaymentInitiated",
                    "MuSigV2TradeExecutionData"));
            assertEquals(buyer, compiles(role,
                    "PaymentInitiated",
                    "PaymentReceivedMessage_F",
                    "bisq.trade.mu_sig_v2.protocol.execution.buyer.PaymentReceivedMessage_F_Handler",
                    "TradeClosed",
                    "MuSigV2TradeExecutionData"));
        }
    }

    @Test
    void sellerOnlyStatesCannotBeUsedInBuyerRules() throws Exception {
        for (String role : List.of("BuyerAsMakerState", "BuyerAsTakerState", "SellerAsMakerState", "SellerAsTakerState")) {
            boolean seller = role.startsWith("Seller");
            assertEquals(seller, compiles(role,
                    "DepositTxConfirmed",
                    "PaymentInitiatedMessage_E",
                    "bisq.trade.mu_sig_v2.protocol.execution.seller.PaymentInitiatedMessage_E_Handler",
                    "PaymentInitiatedMessageReceived",
                    "MuSigV2TradeExecutionData"));
            assertEquals(seller, compiles(role,
                    "PaymentReceiptConfirmed",
                    "CooperativeClosureMessage_G",
                    "bisq.trade.mu_sig_v2.protocol.execution.seller.CooperativeClosureMessage_G_Handler",
                    "TradeClosed",
                    "MuSigV2TradeExecutionData"));
        }
    }

    @Test
    void makerAndTakerSetupStatesCannotBeMixed() throws Exception {
        for (String role : List.of("BuyerAsMakerState", "BuyerAsTakerState", "SellerAsMakerState", "SellerAsTakerState")) {
            boolean maker = role.endsWith("MakerState");
            assertEquals(maker, compiles(role,
                    "Init",
                    "SetupTradeMessage_A",
                    "bisq.trade.mu_sig_v2.protocol.execution.maker.SetupTradeMessage_A_Handler",
                    "TradeInitializedAndNonceSharesCreated",
                    "MuSigV2TradeExecutionData"));
            assertEquals(maker, compiles(role,
                    "TradeInitializedAndNonceSharesCreated",
                    "SetupTradeMessage_C",
                    role.startsWith("Seller") ? "bisq.trade.mu_sig_v2.protocol.execution.seller_as_maker.SetupTradeMessage_C_Handler"
                            : "bisq.trade.mu_sig_v2.protocol.execution.buyer_as_maker.SetupTradeMessage_C_Handler",
                    "PartialSignaturesCreatedAndDepositTxSigned",
                    "MuSigV2TradeExecutionData"));
            assertEquals(!maker, compiles(role,
                    "Init",
                    "MuSigTakeOfferEvent",
                    "bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler",
                    "TradeInitialized",
                    "MuSigV2TradeExecutionData"));
        }
    }

    @Test
    void flowRegistrationPreservesTheExecutionRole() throws Exception {
        assertTrue(registrationCompiles("SellerAsTakerState", "SellerAsTakerProtocol", "withExecution"));
        assertTrue(registrationCompiles("BuyerAsTakerState", "BuyerAsTakerProtocol", "withExecution"));
        assertFalse(registrationCompiles("SellerAsTakerState", "BuyerAsTakerProtocol", "withExecution"));
        assertFalse(registrationCompiles("BuyerAsTakerState", "SellerAsTakerProtocol", "withExecution"));
    }

    @Test
    void flowRegistrationCannotReplaceAnUnrelatedModel() throws Exception {
        assertFalse(registrationCompiles("SellerAsTakerState", "SellerAsTakerProtocol", "withMediation"));
        assertFalse(registrationCompiles("SellerAsTakerState", "MuSigMediationProtocol", "withExecution"));
    }

    @Test
    void aHandlerCanRaiseADifferentEventWithinItsFamily() throws Exception {
        assertTrue(eventFamilyCompiles("Started", "FollowUp"));
    }

    @Test
    void admissionCannotAccessServicesOrSiblingModelUpdates() throws Exception {
        assertTrue(contextCompiles("MuSigProtocolContext", "Object", "context.serviceProvider()"));
        assertFalse(contextCompiles("MuSigReadOnlyContext", "Object", "context.serviceProvider()"));
        assertFalse(contextCompiles("MuSigReadOnlyContext", "Object", "context.execution().data().toBuilder()"));
        assertFalse(contextCompiles("MuSigReadOnlyContext",
                "Object",
                "context.mediation().orElseThrow().data().withFailure(null)"));
    }

    @Test
    void flowViewsKeepTheirStateAndDataTypes() throws Exception {
        assertTrue(contextCompiles("MuSigReadOnlyContext",
                "MuSigTradeExecutionState",
                "context.execution().state()"));
        assertTrue(contextCompiles("MuSigReadOnlyContext",
                "MuSigMediationState",
                "context.mediation().orElseThrow().state()"));
        assertTrue(contextCompiles("MuSigReadOnlyContext",
                "java.util.Optional<String>",
                "context.execution().data().myCustomPayoutTxId()"));
        assertTrue(contextCompiles("MuSigReadOnlyContext",
                "MuSigMediationReadOnlyData",
                "context.mediation().orElseThrow().data()"));
        assertFalse(contextCompiles("MuSigReadOnlyContext",
                "MuSigExecutionReadOnlyData",
                "context.mediation().orElseThrow().data()"));
    }

    @Test
    void executionTypesCannotUseMediationStates() throws Exception {
        for (Class<?> type : List.of(MuSigV2Trade.class, MuSigExecutionProtocol.class, MuSigTradeProcessor.class)) {
            assertTrue(contextCompiles("MuSigReadOnlyContext",
                    type.getCanonicalName() + "<MuSigTradeExecutionState>",
                    "null"));
            assertFalse(contextCompiles("MuSigReadOnlyContext",
                    type.getCanonicalName() + "<MuSigMediationState>",
                    "null"));
        }
    }

    @Test
    void aHandlerCannotRaiseAnEventOutsideItsFamily() throws Exception {
        assertFalse(eventFamilyCompiles("Started", "Unrelated"));
    }

    @Test
    void aHandlerInputMustBelongToItsEventFamily() throws Exception {
        assertFalse(eventFamilyCompiles("Unrelated", "FollowUp"));
    }

    private boolean eventFamilyCompiles(String inputType, String raisedType) throws Exception {
        Path source = directory.resolve("EventFamilyProbe.java");
        Files.writeString(source, """
                import bisq.common.statemachine.FlowSnapshot;
                import bisq.common.statemachine.RuleTable;
                import bisq.common.statemachine.Transition;
                import bisq.common.statemachine.TransitionHandler;
                import java.util.List;

                class EventFamilyProbe {
                    interface Event {}
                    record Started() implements Event {}
                    record FollowUp() implements Event {}
                    record Unrelated() {}
                    record Init() {}
                    record Ready() {}

                    static final class Handler extends TransitionHandler<Init, %s, Ready, Integer, Object, Event> {
                        Handler(Object context, FlowSnapshot<Init, Integer> flow) {
                            super(context, flow);
                        }

                        @Override
                        protected Transition<Ready, Integer, Event> process(%s event) {
                            return new Transition<>(flow.to(new Ready(), data + 1),
                                    List.of(), List.of(), List.of(new %s()));
                        }
                    }

                    static final RuleTable<Object, Integer, Event, Object> RULES =
                            RuleTable.<Object, Integer, Event, Object>builder()
                                    .rule(Init.class, %s.class, Handler::new, Ready.class)
                                    .build();
                }
                """.formatted(inputType, inputType, raisedType, inputType));
        return compile(source);
    }

    private boolean registrationCompiles(String stateFamily, String protocol, String writer) throws Exception {
        Path source = directory.resolve("FlowRegistrationProbe.java");
        Files.writeString(source, """
                package bisq.trade.mu_sig_v2.protocol;

                import bisq.common.statemachine.Event;
                import bisq.common.statemachine.FlowRegistration;
                import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
                import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
                import bisq.trade.mu_sig_v2.protocol.execution.buyer_as_taker.BuyerAsTakerProtocol;
                import bisq.trade.mu_sig_v2.protocol.execution.seller_as_taker.SellerAsTakerProtocol;
                import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationProtocol;
                import java.util.Optional;
                import java.util.function.Function;

                class FlowRegistrationProbe {
                    FlowRegistration<MuSigV2Trade<%s>, Event, MuSigProtocol.Flow> define(
                            MuSigProtocolContext context,
                            Function<MuSigV2Trade<%s>, MuSigReadOnlyContext> admission) {
                        return new %s().bind(MuSigProtocol.Flow.EXECUTION, Optional.empty(),
                                MuSigV2Trade::getExecution, MuSigV2Trade::%s, admission, (snapshot, event) -> context);
                    }
                }
                """.formatted(stateFamily, stateFamily, protocol, writer));
        return compile(source);
    }

    private boolean compiles(String sourceStage, String event, String targetStage) throws Exception {
        return compiles(sourceStage, event, targetStage, "MuSigV2TradeExecutionData");
    }

    private boolean compiles(String sourceStage, String event, String targetStage, String dataType) throws Exception {
        return compiles("SellerAsTakerState",
                sourceStage,
                event,
                "bisq.trade.mu_sig_v2.protocol.execution.taker.MuSigTakeOfferEventHandler",
                targetStage,
                dataType);
    }

    private boolean compiles(String stateFamily,
                             String sourceStage,
                             String event,
                             String handler,
                             String targetStage,
                             String dataType) throws Exception {
        Path source = directory.resolve("RuleTableProbe.java");
        Files.writeString(source, """
                import bisq.common.statemachine.RuleTable;
                import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
                import bisq.trade.mu_sig_v2.protocol.events.buyer.PaymentInitiatedEvent;
                import bisq.trade.mu_sig_v2.protocol.events.taker.MuSigTakeOfferEvent;
                import bisq.trade.mu_sig.messages.network.CooperativeClosureMessage_G;
                import bisq.trade.mu_sig.messages.network.PaymentInitiatedMessage_E;
                import bisq.trade.mu_sig.messages.network.PaymentReceivedMessage_F;
                import bisq.trade.mu_sig.messages.network.SetupTradeMessage_A;
                import bisq.trade.mu_sig.messages.network.SetupTradeMessage_C;
                import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsMakerState;
                import bisq.trade.mu_sig_v2.protocol.execution.BuyerAsTakerState;
                import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
                import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.*;
                import bisq.common.statemachine.Event;
                import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecutionData;
                import bisq.trade.mu_sig_v2.protocol.execution.SellerAsMakerState;
                import bisq.trade.mu_sig_v2.protocol.execution.SellerAsTakerState;
                class RuleTableProbe {
                    static final RuleTable<%s, %s, Event,
                            MuSigProtocolContext> RULES =
                        RuleTable.<%s, %s, Event, MuSigProtocolContext>builder()
                            .rule(%s.class, %s.class, %s::new, %s.class)
                            .build();
                }
                """.formatted(stateFamily, dataType, stateFamily, dataType, sourceStage, event, handler, targetStage));
        return compile(source);
    }

    private boolean decisionCompiles(String sourceState, String event, String targetState) throws Exception {
        Path source = directory.resolve("DecisionRuleProbe.java");
        Files.writeString(source, """
                package bisq.trade.mu_sig_v2.protocol.mediation;

                import bisq.common.statemachine.RuleTable;
                import bisq.trade.mu_sig_v2.protocol.events.mediation.MediationResultAcceptedEvent;
                import bisq.trade.mu_sig.messages.network.MuSigCustomPayoutPsbtMessage;
                import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
                import bisq.common.statemachine.Event;

                class DecisionRuleProbe {
                    void define() {
                        RuleTable.<MuSigMediationDecisionState, MuSigV2MediationDecisionData,
                                        Event, MuSigProtocolContext>builder()
                            .rule(MuSigMediationDecisionState.%s.class, %s.class,
                                MediationResultAcceptedEventHandler::new, MuSigMediationDecisionState.%s.class)
                            .build();
                    }
                }
                """.formatted(sourceState, event, targetState));
        return compile(source);
    }

    private boolean contextCompiles(String contextType, String returnType, String expression) throws Exception {
        Path source = directory.resolve("ContextProbe.java");
        Files.writeString(source, """
                import bisq.trade.mu_sig_v2.protocol.execution.MuSigExecutionReadOnlyData;
                import bisq.trade.mu_sig_v2.protocol.MuSigProtocolContext;
                import bisq.trade.mu_sig_v2.protocol.MuSigReadOnlyContext;
                import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
                import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationReadOnlyData;
                import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;

                class ContextProbe {
                    %s read(%s context) {
                        return %s;
                    }
                }
                """.formatted(returnType, contextType, expression));
        return compile(source);
    }

    private boolean compile(Path source) throws Exception {
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
}
