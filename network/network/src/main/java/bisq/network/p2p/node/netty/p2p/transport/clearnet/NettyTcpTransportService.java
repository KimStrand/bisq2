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

import bisq.common.network.Address;
import bisq.network.p2p.node.handshake.InboundHandshakeHandler;
import bisq.network.p2p.node.handshake.OutboundHandshakeHandler;
import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.ConnectionHandler;
import bisq.network.p2p.node.netty.p2p.conn.NettyOutboundConnection;
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import bisq.network.p2p.node.netty.p2p.core.RemoteAddress;
import bisq.network.p2p.node.netty.p2p.transport.TransportService;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.protobuf.ProtobufEncoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32FrameDecoder;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.logging.LoggingHandler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class NettyTcpTransportService implements TransportService {
    private final EventLoopGroup bossGroup = new NioEventLoopGroup();
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    public NettyTcpTransportService() {
    }

    public CompletableFuture<Channel> startServer(int port, InboundHandshakeHandler handshakeHandler) {
        ServerBootstrap bootstrap = new ServerBootstrap();
        bootstrap.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .handler(new LoggingHandler())
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    @Override
                    protected void initChannel(NioSocketChannel channel) {
                        channel.pipeline()
                                .addLast(new ProtobufVarint32FrameDecoder())
                                //.addLast(new ProtobufDecoder(Envelope.getDefaultInstance()))
                                .addLast(new ProtobufVarint32LengthFieldPrepender())
                                .addLast(new ProtobufEncoder())
                                .addLast(handshakeHandler);
                    }
                });
        CompletableFuture<Channel> serverStarted = new CompletableFuture<>();
        bootstrap.bind(port).addListener(future -> {
            if (future instanceof ChannelFuture channelFuture && future.isSuccess()) {
                Channel channel = channelFuture.channel();
                serverStarted.complete(channel);
            } else {
                serverStarted.completeExceptionally(future.cause());
            }
        });
        return serverStarted;
    }


    public CompletableFuture<Connection> connect(Address address, OutboundHandshakeHandler handshakeHandler) {
        CompletableFuture<Connection> future = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap();
        bootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast(new ProtobufVarint32FrameDecoder())
                              //  .addLast(new ProtobufDecoder(Envelope.getDefaultInstance()))
                                .addLast(new ProtobufVarint32LengthFieldPrepender())
                                .addLast(new ProtobufEncoder())
                                .addLast(handshakeHandler);
                    }
                });
        bootstrap.connect(address.getHost(), address.getPort()).addListener((ChannelFutureListener) channelFuture -> {
            if (channelFuture.isSuccess()) {
                future.complete(new NettyOutboundConnection(channelFuture.channel()));
            } else {
                future.completeExceptionally(channelFuture.cause());
            }
        });
        return future;
    }

    @Override
    public CompletableFuture<Void> startServerOLD(ServerListener listener) {
        return null;
    }

    @Override
    public CompletableFuture<RawChannel> dial(RemoteAddress remote, Duration timeout) {
        return null;
    }

    @Override
    public void close() {
        bossGroup.close();
        workerGroup.close();
    }

    @Override
    public void startServerOLD(int port, ConnectionHandler handler) throws Exception {

    }
}
