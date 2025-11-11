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
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import com.google.protobuf.Message;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Example skeleton handler that sends Hello, awaits HelloAck, then completes.
 */
public final class HandshakeClient implements Handshake {
    private final Duration timeout;
    private final Message hello;                   // your Hello protobuf
    private final Class<? extends Message> helloAckType;

    public HandshakeClient(Duration timeout, Message hello, Class<? extends Message> helloAckType) {
        this.timeout = timeout;
        this.hello = hello;
        this.helloAckType = helloAckType;
    }

    @Override
    public CompletableFuture<Established> runClient(RawChannel raw) {
        CompletableFuture<Established> done = new CompletableFuture<>();
        Channel ch = raw.netty();

        ch.pipeline().addLast("handshakeClient", new SimpleChannelInboundHandler<Message>() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) {
                ctx.writeAndFlush(hello);
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                if (helloAckType.isInstance(msg)) {
                    // Upgrade pipeline: remove handshake, add your RR manager & router
                    ctx.pipeline().remove(this);
                    Connection conn = null; // todo  /* build OutboundConnection impl wrapping ch */;
                    done.complete(new Established(conn));
                } else {
                    // buffer or ignore until ack arrives
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                done.completeExceptionally(cause);
                ctx.close();
            }
        });
        // add timeout schedule…
        return done;
    }

    @Override
    public CompletableFuture<Established> runServer(RawChannel raw) {
        CompletableFuture<Established> done = new CompletableFuture<>();
        Channel channel = raw.netty();
        channel.pipeline().addLast("handshakeServer", new SimpleChannelInboundHandler<Message>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                // Expect Hello; validate; write HelloAck
                // Then upgrade to InboundConnection and complete
                Connection conn = null; // todo /* build InboundConnection impl wrapping ch */;
                ctx.pipeline().remove(this);
                done.complete(new Established(conn));
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                done.completeExceptionally(cause);
                ctx.close();
            }
        });
        return done;
    }
}
