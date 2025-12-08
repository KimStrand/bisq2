package bisq.network.p2p.node.envelope;

import bisq.common.network.DefaultPeerSocket;
import bisq.common.network.PeerSocket;
import com.google.protobuf.InvalidProtocolBufferException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
        // six continuation bytes -> varint > 5 bytes -> should trigger InvalidProtocolBufferException wrapped in IOException
        byte[] malformedVarint = new byte[] {
                (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80, (byte) 0x80
        };

        try (Socket client = startServerThatWritesAndCloses(malformedVarint)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                IOException ex = assertThrows(IOException.class, socket::receiveNextEnvelope);
                assertTrue(ex.getMessage().contains("Malformed varint size prefix"),
                        "exception message should indicate malformed varint");
                assertInstanceOf(InvalidProtocolBufferException.class, ex.getCause(), "cause should be InvalidProtocolBufferException");
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
    void oversizedLength_then_validMessage_streamRecovers() throws Exception {
        byte[] payload = new byte[]{0x08, 0x01, 0x12, 0x02, 0x68, 0x69};
        byte[] validMessage = concat(varint32(payload.length), payload);
        // first write an oversized length prefix, then a valid small message
        byte[] serverData = concat(varint32(10_000_000), validMessage);

        try (Socket client = startServerThatWritesAndCloses(serverData)) {
            PeerSocket peerSocket = new DefaultPeerSocket(client);
            try (NetworkEnvelopeSocket socket = new NetworkEnvelopeSocket(peerSocket)) {
                // first call should fail due to size > MAX_ALLOWED_SIZE
                assertThrows(IllegalArgumentException.class, socket::receiveNextEnvelope,
                        "First envelope with oversized length should cause an IllegalArgumentException");

                // second call should successfully read the following valid message
                var proto = socket.receiveNextEnvelope();
                assertNotNull(proto,
                        "After the oversized length prefix is consumed, the next valid envelope should be read successfully");
                assertArrayEquals(payload, proto.toByteArray(), "payload bytes should match the sent bytes");

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