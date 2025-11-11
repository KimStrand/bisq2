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
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class TorNettyClientService implements TransportService {
    private final NioEventLoopGroup group = new NioEventLoopGroup();
    private final InetSocketAddress socks5; // e.g., 127.0.0.1:9050

    public TorNettyClientService(InetSocketAddress socks5) {
        this.socks5 = socks5;
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
        // For Tor inbound, run a separate ClearnetNettyService bound to localPort,
        // and ensure your Tor layer publishes onion:remotePort -> 127.0.0.1:localPort.
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Use a paired clearnet server and Tor publisher"));
    }

    @Override
    public CompletableFuture<RawChannel> dial(RemoteAddress remote, Duration timeout) {
        CompletableFuture<RawChannel> cf = new CompletableFuture<>();
        Bootstrap b = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE))
                .handler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        ch.pipeline().addFirst("socks5", new Socks5ProxyHandler(socks5));
                    }
                });
        b.connect(remote.hostOrDest(), remote.port()).addListener((ChannelFuture f) -> {
            if (f.isSuccess()) cf.complete(() -> f.channel());
            else cf.completeExceptionally(f.cause());
        });
        return cf;
    }

    @Override
    public void close() {
        group.shutdownGracefully();
    }
}

