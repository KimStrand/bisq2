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
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static com.google.common.base.Preconditions.checkArgument;

@Slf4j
public class NetworkEnvelopeSocket implements Closeable {
    private static final int MAX_ALLOWED_SIZE = 2_250_000;
    private static final int MAX_RECURSION_LIMIT = 20;
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
     * Reads a size-delimited envelope with bounded message size and recursion.
     * Callers must close the connection after a read failure because the next frame boundary may be unknown.
     *
     * @return NetworkEnvelope or null if EOF
     * @throws IOException framing or transport errors
     * @throws IllegalArgumentException invalid size
     * @throws InvalidProtocolBufferException malformed protobuf payload
     */
    public bisq.network.protobuf.NetworkEnvelope receiveNextEnvelope() throws IOException {
        int firstByte = inputStream.read();
        if (firstByte == -1) {
            return null; // EOF
        }

        // Decode at most five bytes into a long so an oversized value cannot wrap into an allowed size.
        long size = firstByte & 0x7F;
        int nextByte = firstByte;
        for (int byteCount = 1; (nextByte & 0x80) != 0; byteCount++) {
            if (byteCount == 5) {
                throw new IOException("Malformed varint size prefix: exceeds 5 bytes");
            }
            nextByte = inputStream.read();
            if (nextByte == -1) {
                throw new EOFException("Truncated varint size prefix");
            }
            size |= (long) (nextByte & 0x7F) << (7 * byteCount);
        }

        checkArgument(size > 0, "Size of protobuf message must not be 0");
        checkArgument(size <= MAX_ALLOWED_SIZE, "Size of protobuf message exceeds our limit. size=" + size);

        // This prevents reading beyond the declared message boundary
        InputStream limitedStream = ByteStreams.limit(inputStream, size);

        CodedInputStream codedInput = CodedInputStream.newInstance(limitedStream);
        codedInput.setRecursionLimit(MAX_RECURSION_LIMIT);

        bisq.network.protobuf.NetworkEnvelope envelope = bisq.network.protobuf.NetworkEnvelope.parseFrom(codedInput);
        codedInput.checkLastTagWas(0);
        return envelope;
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }

    public boolean isClosed() {
        return socket.isClosed();
    }
}
