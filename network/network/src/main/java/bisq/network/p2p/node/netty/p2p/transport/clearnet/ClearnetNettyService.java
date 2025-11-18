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

package bisq.network.p2p.node.netty.p2p.transport.clearnet;

import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.ConnectionHandler;
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import bisq.network.p2p.node.netty.p2p.core.RemoteAddress;
import bisq.network.p2p.node.netty.p2p.transport.TransportService;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.logging.LoggingHandler;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public final class ClearnetNettyService implements TransportService {
    private final NioEventLoopGroup boss = new NioEventLoopGroup(1);
    private final NioEventLoopGroup workers = new NioEventLoopGroup();
    private final InetSocketAddress bind;
    private volatile Channel serverCh;

    public ClearnetNettyService(InetSocketAddress bind) {
        this.bind = bind;
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
        CompletableFuture<Void> started = new CompletableFuture<>();
        ServerBootstrap sb = new ServerBootstrap();
        sb.group(boss, workers)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler()) // acceptor logging
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) {
                        listener.onAccepted(() -> ch); // give RawChannel (pre-handshake)
                    }
                });
        sb.bind(bind).addListener(f -> {
            if (f.isSuccess()) {
                serverCh = ((ChannelFuture) f).channel();
                started.complete(null);
            } else started.completeExceptionally(f.cause());
        });
        return started;
    }

    @Override
    public CompletableFuture<RawChannel> dial(RemoteAddress remote, Duration timeout) {
        CompletableFuture<RawChannel> cf = new CompletableFuture<>();
        Bootstrap b = new Bootstrap()
                .group(workers)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) Math.min(timeout.toMillis(), Integer.MAX_VALUE))
                .handler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel ch) { /* add later by handshake */ }
                });
        b.connect(new InetSocketAddress(remote.hostOrDest(), remote.port()))
                .addListener((ChannelFuture f) -> {
                    if (f.isSuccess()) cf.complete(() -> f.channel());
                    else cf.completeExceptionally(f.cause());
                });
        return cf;
    }

    @Override
    public void close() {
        if (serverCh != null) serverCh.close();
        boss.shutdownGracefully();
        workers.shutdownGracefully();
    }
}

