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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaFileObject;
import javax.tools.ToolProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MuSigSerializationContractTest {
    @TempDir
    Path directory;

    @Test
    void existingEventsAndNetworkMessagesSatisfyTheContract() throws Exception {
        assertTrue(compiles("""
                Event event = new DepositTxConfirmedEvent();
                Proto serialized = event;
                TradeEvent local = new DepositTxConfirmedEvent();
                PersistableProto persisted = local;
                Event networkInput(MuSigTradeMessage message) {
                    return message;
                }
                Effect outgoing(MuSigTradeMessage message, NetworkId receiver) {
                    return new Effect.SendMessage<>(message, receiver);
                }
                """));
    }

    @Test
    void anEventMustProvideProtobufMethods() throws Exception {
        assertFalse(compiles("record MissingSerialization() implements Event {}"));
    }

    @Test
    void anEffectMustProvideProtobufMethods() throws Exception {
        assertFalse(compiles("record MissingSerialization() implements Effect {}"));
    }

    @Test
    void aScheduledInputMustBeASerializableEvent() throws Exception {
        assertFalse(compiles("""
                Object scheduled = new bisq.common.statemachine.ScheduledEvent<>(
                    "timeout", java.time.Instant.EPOCH, new Object());
                """));
    }

    @Test
    void aSentMessageMustProvideNetworkProtobufMethods() throws Exception {
        assertFalse(compiles("""
                Effect outgoing(Object message, NetworkId receiver) {
                    return new Effect.SendMessage<>(message, receiver);
                }
                """));
    }

    @Test
    void aRecipientMustProvideProtobufMethods() throws Exception {
        assertFalse(compiles("""
                Effect outgoing(MuSigTradeMessage message, Object receiver) {
                    return new Effect.SendMessage<>(message, receiver);
                }
                """));
    }

    private boolean compiles(String members) throws Exception {
        Path source = directory.resolve("SerializationProbe.java");
        Files.writeString(source, """
                import bisq.common.proto.PersistableProto;
                import bisq.common.proto.Proto;
                import bisq.common.processing.Effect;
                import bisq.common.statemachine.Event;
                import bisq.network.identity.NetworkId;
                import bisq.trade.mu_sig_v2.protocol.events.blockchain.DepositTxConfirmedEvent;
                import bisq.trade.mu_sig.messages.network.MuSigTradeMessage;
                import bisq.trade.protocol.v2.events.TradeEvent;
                class SerializationProbe {
                    %s
                }
                """.formatted(members));
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
