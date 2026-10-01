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

package bisq.network.p2p.node.envelope;

import bisq.common.network.DefaultPeerSocket;
import bisq.common.network.PeerSocket;
import com.google.protobuf.CodedOutputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkEnvelopeSocketTest {

    @Test
    void receiveNextEnvelope_returnsNull_onEof() throws Exception {
        byte[] toWrite = new byte[0]; // server will close immediately -> EOF at client
        try (Socket client = startServerThatWritesAndCloses(toWrite)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                assertNull(socket.receiveNextEnvelope());
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onVarintTooLong() throws Exception {
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x02, 0x68, 0x69};
        byte[] malformedVarint = new byte[]{
                (byte) 0x86, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x00
        };

        try (Socket client = startServerThatWritesAndCloses(concat(malformedVarint, payload))) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                IOException ex = assertThrows(IOException.class, socket::receiveNextEnvelope);
                assertTrue(ex.getMessage().contains("Malformed varint size prefix"),
                        "exception message should indicate malformed varint");
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onTruncatedVarint() throws Exception {
        try (Socket client = startServerThatWritesAndCloses(new byte[]{(byte) 0x80})) {
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(new DefaultPeerSocket(client))) {
                assertThrows(EOFException.class, socket::receiveNextEnvelope);
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onOverflowingVarint() throws Exception {
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x02, 0x68, 0x69};
        // Encodes 2^32 + 6, which must not wrap around to an allowed size of 6.
        byte[] length = new byte[]{(byte) 0x86, (byte) 0x80, (byte) 0x80, (byte) 0x80, 0x10};
        try (Socket client = startServerThatWritesAndCloses(concat(length, payload))) {
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(new DefaultPeerSocket(client))) {
                assertThrows(IllegalArgumentException.class, socket::receiveNextEnvelope);
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onZeroSize() throws Exception {
        byte[] data = varint32(0); // length prefix 0
        try (Socket client = startServerThatWritesAndCloses(data)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                assertThrows(IllegalArgumentException.class, socket::receiveNextEnvelope);
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onSizeExceedsLimit() throws Exception {
        byte[] data = varint32(10_000_000); // large length only
        try (Socket client = startServerThatWritesAndCloses(data)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                assertThrows(IllegalArgumentException.class, socket::receiveNextEnvelope);
            }
        }
    }

    @Test
    void receiveNextEnvelope_throws_onTruncatedPayload() throws Exception {
        byte[] partialPayload = new byte[]{0x08, 0x01, 0x12};
        byte[] data = concat(varint32(6), partialPayload);

        try (Socket client = startServerThatWritesAndCloses(data)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                assertThrows(InvalidProtocolBufferException.class,
                        socket::receiveNextEnvelope,
                        "Truncated payload should cause parsing failure");
            }
        }
    }

    @Test
    void receiveNextEnvelope_returnsNonNull_onValidSmallMessage() throws Exception {
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x02, 0x68, 0x69};
        byte[] data = concat(varint32(payload.length), payload);
        try (Socket client = startServerThatWritesAndCloses(data)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                var proto = socket.receiveNextEnvelope();
                assertNotNull(proto);
                assertArrayEquals(payload, proto.toByteArray(), "payload bytes should match the sent bytes");
            }
        }
    }

    @Test
    void receiveNextEnvelope_acceptsMaximumSize() throws Exception {
        int size = 2_250_000;
        ByteArrayOutputStream payloadOutput = new ByteArrayOutputStream();
        CodedOutputStream codedOutput = CodedOutputStream.newInstance(payloadOutput);
        // An unknown bytes field uses one byte for its tag and four for its length.
        codedOutput.writeByteArray(4, new byte[size - 5]);
        codedOutput.flush();
        byte[] payload = payloadOutput.toByteArray();
        assertEquals(size, payload.length);

        try (Socket client = startServerThatWritesAndCloses(concat(varint32(size), payload))) {
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(new DefaultPeerSocket(client))) {
                assertArrayEquals(payload, socket.receiveNextEnvelope().toByteArray());
            }
        }
    }

    @Test
    void receiveNextEnvelope_preservesConsecutiveMessageBoundaries() throws Exception {
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x02, 0x68, 0x69};
        byte[] validMessage = concat(varint32(payload.length), payload);
        byte[] serverData = concat(validMessage, validMessage);

        try (Socket client = startServerThatWritesAndCloses(serverData)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                assertArrayEquals(payload, socket.receiveNextEnvelope().toByteArray());
                assertArrayEquals(payload, socket.receiveNextEnvelope().toByteArray());
                assertNull(socket.receiveNextEnvelope());
            }
        }
    }

    private static Socket startServerThatWritesAndCloses(byte[] data) throws IOException {
        ServerSocket server = new ServerSocket(0);
        Thread serverThread = new Thread(() -> {
            try (Socket s = server.accept()) {
                if (data.length > 0) {
                    s.getOutputStream().write(data);
                    s.getOutputStream().flush();
                }
                // close socket to simulate EOF/finished write
            } catch (IOException ignored) {
            } finally {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
        }, "test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        // connect client socket to the server and return it (client reads what server wrote)
        return new Socket("127.0.0.1", server.getLocalPort());
    }

    // Helper: encode an int as protobuf varint32
    private static byte[] varint32(int value) {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            int v = value;
            while ((v & ~0x7F) != 0) {
                baos.write((v & 0x7F) | 0x80);
                v >>>= 7;
            }
            baos.write(v);
            return baos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Helper: concat two byte arrays
    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }
}
