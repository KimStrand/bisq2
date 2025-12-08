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

import bisq.common.network.PeerSocket;
import bisq.network.p2p.message.NetworkEnvelope;
import com.google.common.io.ByteStreams;
import com.google.protobuf.CodedInputStream;
import com.google.protobuf.InvalidProtocolBufferException;
import lombok.extern.slf4j.Slf4j;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class NetworkEnvelopeSocket implements Closeable {
    private final static int MAX_ALLOWED_SIZE = 2_250_000;
    private final static int MAX_RECURSION_LIMIT = 20;
    private final PeerSocket socket;
    private final InputStream inputStream;
    private final OutputStream outputStream;

    public NetworkEnvelopeSocket(PeerSocket socket) {
        this.socket = socket;
        this.inputStream = socket.getInputStream();
        this.outputStream = socket.getOutputStream();
    }

    public void send(NetworkEnvelope networkEnvelope) throws IOException {
        networkEnvelope.writeDelimitedTo(outputStream);
        outputStream.flush();
    }

    /**
     * Reads the next NetworkEnvelope from the stream with comprehensive protection:
     * - Validates varint size prefix (max 5 bytes)
     * - Enforces message size limit
     * - Limits stream to exact declared bytes
     * - Protects against malformed protobufs
     * - Prevents recursion attacks
     *
     * @return NetworkEnvelope or null if EOF
     * @throws IOException framing errors (malformed varint)
     * @throws IllegalArgumentException invalid size
     * @throws InvalidProtocolBufferException malformed protobuf payload
     */
    public bisq.network.protobuf.NetworkEnvelope receiveNextEnvelope() throws IOException {
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            return null; // EOF
        }

        // Read and validate the varint size prefix
        int size;
        try {
            // This will throw InvalidProtocolBufferException if varint > 5 bytes
            size = CodedInputStream.readRawVarint32(firstByte, inputStream);
        } catch (InvalidProtocolBufferException e) {
            throw new IOException("Malformed varint size prefix (possibly > 5 bytes): " + e.getMessage(), e);
        }

        checkArgument(size > 0, "Size of protobuf message must not be 0");
        checkArgument(size <= MAX_ALLOWED_SIZE, "Size of protobuf message exceeds our limit. size=" + size);

        // This prevents reading beyond the declared message boundary
        InputStream limitedStream = ByteStreams.limit(inputStream, size);

        try {
            CodedInputStream codedInput = CodedInputStream.newInstance(limitedStream);
            codedInput.setRecursionLimit(MAX_RECURSION_LIMIT);

            bisq.network.protobuf.NetworkEnvelope envelope = bisq.network.protobuf.NetworkEnvelope.parseFrom(codedInput);
            codedInput.checkLastTagWas(0);

            return envelope;

        } catch (InvalidProtocolBufferException e) {
            throw new InvalidProtocolBufferException(
                    "Failed to parse NetworkEnvelope: " + e.getMessage()
            );
        }
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }
}
