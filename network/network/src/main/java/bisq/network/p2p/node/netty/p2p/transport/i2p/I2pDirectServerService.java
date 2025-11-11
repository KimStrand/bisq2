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
import io.netty.channel.ChannelFuture;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;

import java.net.Socket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Accept path: your I2P lib gives you a blocking accept() -> Socket. Bridge each to Netty.
 */
public final class I2pDirectServerService implements TransportService {
    private final NioEventLoopGroup workers = new NioEventLoopGroup();
    private final I2pServer server = null; // TODO your router’s streaming server API
    private volatile boolean running;

    public interface I2pServer extends AutoCloseable {
        Socket accept() throws java.io.IOException;

        @Override
        void close();
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
        running = true;
        CompletableFuture<Void> started = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            started.complete(null);
            while (running) {
                try {
                    Socket s = server.accept();
                    workers.execute(() -> {
                        //  NioSocketChannel ch = new NioSocketChannel(s);
                        NioSocketChannel ch = new NioSocketChannel();
                        workers.register(ch).addListener((ChannelFuture f) -> {
                            if (f.isSuccess()) listener.onAccepted(() -> ch);
                            else ch.close();
                        });
                    });
                } catch (Exception e) {
                    if (running) listener.onServerError(e);
                }
            }
        }, "i2p-accept-loop");
        t.setDaemon(true);
        t.start();
        return started;
    }

    @Override
    public CompletableFuture<RawChannel> dial(RemoteAddress r, Duration t) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Server-only; implement a similar Socket-to-Netty dialer for client"));
    }

    @Override
    public void close() {
        running = false;
        try {
            server.close();
        } finally {
            workers.shutdownGracefully();
        }
    }
}
