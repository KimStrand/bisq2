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

package bisq.network.p2p.node.netty.p2p.transport.i2p;


import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.ConnectionHandler;
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import bisq.network.p2p.node.netty.p2p.core.RemoteAddress;
import bisq.network.p2p.node.netty.p2p.transport.TransportService;
import bisq.network.p2p.node.netty.p2p.transport.clearnet.ClearnetNettyService;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/** Server: SAM forwards I2P dest:port -> local TCP; we listen locally via Netty. */
public final class I2pSamServerService implements TransportService {
    private final ClearnetNettyService localServer;
    private final SamForwarder forwarder;  // your SAM wrapper

    public interface SamForwarder extends AutoCloseable {
        CompletableFuture<Void> startForward(String i2pDest, int i2pPort, InetSocketAddress localTarget);
        @Override void close();
    }

    public I2pSamServerService(InetSocketAddress bindLocal, SamForwarder fwd, String i2pDest, int i2pPort) {
        this.localServer = new ClearnetNettyService(bindLocal);
        this.forwarder = fwd;
        this.i2pDest = i2pDest; this.i2pPort = i2pPort;
    }

    private final String i2pDest; private final int i2pPort;

    @Override
    public void startServerOLD(int port, ConnectionHandler handler) throws Exception {

    }

    @Override
    public CompletableFuture<Connection> connect(String host, int port, ConnectionHandler handler) {
        return null;
    }
    @Override
    public CompletableFuture<Void> startServerOLD(ServerListener listener) {
        return localServer.startServerOLD(listener)
                .thenCompose(v -> forwarder.startForward(i2pDest, i2pPort, bindLocal()));
    }

    private InetSocketAddress bindLocal() {
        // Return the actual bound address from localServer (left as exercise; mirror Tor server approach)
        return (InetSocketAddress) new InetSocketAddress("127.0.0.1", ((InetSocketAddress) new InetSocketAddress(0)).getPort());
    }

    @Override public CompletableFuture<RawChannel> dial(RemoteAddress r, Duration t) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Server-side only; use I2pSamClientService to dial"));
    }

    @Override public void close() {
        try { forwarder.close(); } finally { localServer.close(); }
    }
}

