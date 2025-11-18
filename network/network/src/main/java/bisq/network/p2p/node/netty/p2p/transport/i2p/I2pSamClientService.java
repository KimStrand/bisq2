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
import bisq.network.p2p.node.netty.p2p.core.TcpRemote;
import bisq.network.p2p.node.netty.p2p.transport.TransportService;
import bisq.network.p2p.node.netty.p2p.transport.clearnet.ClearnetNettyService;

import java.time.Duration;
import java.util.concurrent.CompletableFuture; /** Client: dial SAM’s local TCP bridge to reach a remote I2P dest. */
public final class I2pSamClientService implements TransportService {
    private final ClearnetNettyService bridgeClient;

    public I2pSamClientService(ClearnetNettyService bridgeClient) {
        this.bridgeClient = bridgeClient;
    }

    @Override
    public void startServerOLD(int port, ConnectionHandler handler) throws Exception {

    }

    @Override
    public CompletableFuture<Connection> connect(String host, int port, ConnectionHandler handler) {
        return null;
    }
    @Override public CompletableFuture<Void> startServerOLD(ServerListener l) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Client-only"));
    }

    @Override public CompletableFuture<RawChannel> dial(RemoteAddress remote, Duration timeout) {
        // remote.hostOrDest() is the I2P dest; your SAM bridge exposes it as local TCP (e.g., 127.0.0.1:PORT)
        // Resolve the bridge port for that dest (depends on your SAM setup)
        RemoteAddress bridged = new TcpRemote("127.0.0.1", /*bridgePortFor(remote)*/ 12345);
        return bridgeClient.dial(bridged, timeout);
    }

    @Override public void close() { bridgeClient.close(); }
}
