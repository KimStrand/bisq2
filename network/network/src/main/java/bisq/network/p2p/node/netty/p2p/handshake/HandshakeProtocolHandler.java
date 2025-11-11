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

package bisq.network.p2p.node.netty.p2p.handshake;

import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.NettyInboundConnection;
import com.google.protobuf.Message;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.extern.slf4j.Slf4j;


@Slf4j
public class HandshakeProtocolHandler extends SimpleChannelInboundHandler<Message> {
    public interface Handler {
        void onNewConnection(Connection connection);

        void onClosed(Connection connection);
    }

    private final Handler handler;
    private final boolean isInbound;

    public HandshakeProtocolHandler(Handler handler, boolean isInbound) {
        this.handler = handler;
        this.isInbound = isInbound;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Send handshake request
        if (!isInbound) {
            /* bisq.network.protobuf.Handshake.Request request = bisq.network.protobuf.Handshake.Request.newBuilder().setVersion(1).build();
            Envelope env = Envelope.newBuilder()
                    .setHandshakeRequest(request)
                    .build();
            ctx.writeAndFlush(env);*/
        }
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
        /*if (msg instanceof bisq.network.protobuf.Envelope envelope) {
            envelope.getHandshakeRequest();
            if (envelope.hasHandshakeRequest()) {
                Handshake.Request request = envelope.getHandshakeRequest();

                bisq.network.protobuf.Handshake.Response response = bisq.network.protobuf.Handshake.Response.newBuilder().setAccepted(true).build();
                Envelope env = Envelope.newBuilder()
                        .setHandshakeResponse(response)
                        .build();
                ctx.writeAndFlush(env);
                handler.onNewConnection(new NettyInboundConnection(ctx.channel()));
            } else if (envelope.hasHandshakeResponse()) {
                bisq.network.protobuf.Handshake.Response response = envelope.getHandshakeResponse();
                handler.onNewConnection(new NettyOutboundConnection(ctx.channel()));
            }
        }*/
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        handler.onClosed(new NettyInboundConnection(ctx.channel()));
    }
}

