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

package bisq.network.p2p.node.netty.p2p.transport.tor;

import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.ConnectionHandler;
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import bisq.network.p2p.node.netty.p2p.core.RemoteAddress;
import bisq.network.p2p.node.netty.p2p.transport.TransportService;
import bisq.network.p2p.node.netty.p2p.transport.clearnet.ClearnetNettyService;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Inbound Tor server pairing (local TCP + publisher)
 */
public final class TorNettyServerService implements TransportService {
    private final ClearnetNettyService localServer;
    private final TorPublisher publisher; // your existing tor lib
    private final int onionPort;

    public interface TorPublisher extends AutoCloseable {
        CompletableFuture<Void> publish(int onionPort, InetSocketAddress target);

        @Override
        void close();
    }

    public TorNettyServerService(InetSocketAddress bindLocal, TorPublisher publisher, int onionPort) {
        this.localServer = new ClearnetNettyService(bindLocal);
        this.publisher = publisher;
        this.onionPort = onionPort;
    }

    @Override
    public void startServerOLD(int port, ConnectionHandler handler) throws Exception {

    }

    @Override
    public CompletableFuture<Connection> connect(String host, int port, ConnectionHandler handler) {
        return null;
    }

    @Override
    public CompletableFuture<Void> startServerOLD(ServerListener listener) {
        return localServer.startServerOLD(listener).thenCompose(v ->
                publisher.publish(onionPort, (InetSocketAddress) bindLocal())
        );
    }

    private java.net.SocketAddress bindLocal() {
        return ((InetSocketAddress) ((InetSocketAddress) ((InetSocketAddress) null)));
    }

    @Override
    public CompletableFuture<RawChannel> dial(RemoteAddress r, Duration t) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("This is server-side. Use TorNettyClientService for dial."));
    }

    @Override
    public void close() {
        try {
            publisher.close();
        } finally {
            localServer.close();
        }
    }
}
