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

package bisq.network.p2p.node.netty.p2p.node;


import bisq.common.network.Address;
import bisq.network.p2p.node.handshake.HandshakeHandler;
import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.NettyInboundConnection;
import bisq.network.p2p.node.netty.p2p.conn.NettyOutboundConnection;
import bisq.network.p2p.node.netty.p2p.transport.clearnet.NettyTcpTransportService;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public final class NettyNode implements AutoCloseable {
    private final NettyTcpTransportService transport;
    private final int port;
    private final Map<Address, NettyInboundConnection> inboundConnectionsByAddress = new ConcurrentHashMap<>();
    private final Map<Address, NettyOutboundConnection> outboundConnectionsByAddress = new ConcurrentHashMap<>();

    public NettyNode(NettyTcpTransportService transport, int port) {
        this.transport = transport;
        this.port = port;
    }

    public void start() throws Exception {
        HandshakeHandler.Handler handler = new HandshakeHandler.Handler() {
          /*  @Override
            public void onHandshakeCompleted(Channel channel) {
                log.error("Server.onHandshakeCompleted {} {}", new Address("127.0.0.1", port), channel);
                inboundConnectionsByAddress.put(new Address("127.0.0.1", port), new NettyInboundConnection(channel));
            }*/


            @Override
            public void onHandshakeCompleted(ChannelHandlerContext context, HandshakeHandler.Result result) {

            }

            @Override
            public void onClosed(Channel channel) {

            }
        };
       // transport.startServer(port, new InboundHandshakeHandler(handler));
    }

    public CompletableFuture<Connection> connect(Address address, Duration timeout) {
        HandshakeHandler.Handler handler = new HandshakeHandler.Handler() {
         /*   @Override
            public void onHandshakeCompleted(Channel channel) {
                log.error("Client.onHandshakeCompleted {} {}", address, channel);
                outboundConnectionsByAddress.put(address, new NettyOutboundConnection(channel));
            }*/

            @Override
            public void onHandshakeCompleted(ChannelHandlerContext context, HandshakeHandler.Result result) {

            }

            @Override
            public void onClosed(Channel channel) {

            }
        };
       // return transport.connect(address, new OutboundHandshakeHandler(handler));
        return null;
    }

    @Override
    public void close() {
        transport.close();
    }
}
