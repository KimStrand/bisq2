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

package bisq.network.p2p.node.netty.p2p.conn;

import com.google.protobuf.Message;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.net.SocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;

@ToString
@Slf4j
public abstract class AbstractConnection implements Connection {
    protected final Channel channel;
    protected final Executor writeSerializer; // single-thread or ordered executor
    protected final ConcurrentMap<String, CompletableFuture<Message>> inflight = new ConcurrentHashMap<>();

    protected AbstractConnection(Channel channel) {
        this.channel = Objects.requireNonNull(channel);
        this.writeSerializer = r -> channel.eventLoop().execute(r); // serialize on channel’s event loop
       // installInboundDispatch();
    }

    private void installInboundDispatch() {
        channel.pipeline().addLast("installInboundDispatch", new SimpleChannelInboundHandler<Message>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Message msg) {
                log.error("channelRead0 {}", msg);
                // If isReply -> complete inflight; else route to handlers and maybe reply
                // (You’ll unwrap your Envelope here)
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                log.error("exceptionCaught", cause);
                inflight.values().forEach(f -> f.completeExceptionally(cause));
                inflight.clear();
                ctx.close();
            }
        });
    }

    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
    }

    @Override
    public boolean isOpen() {
        return channel.isActive();
    }

    @Override
    public CompletableFuture<Void> send(Message msg) {
        CompletableFuture<Void> cf = new CompletableFuture<>();
        writeSerializer.execute(() ->
                channel.writeAndFlush(msg).addListener(f -> {
                    if (f.isSuccess()) cf.complete(null);
                    else cf.completeExceptionally(f.cause());
                })
        );
        return cf;
    }

    @Override
    public <T extends Message> CompletableFuture<T> request(Message req, Class<T> replyType, Duration timeout) {
        String correlationId = /* create */ java.util.UUID.randomUUID().toString();
        // Wrap req in Envelope with correlationId
        CompletableFuture<T> cf = new CompletableFuture<>();
        inflight.put(correlationId, (CompletableFuture<Message>) (CompletableFuture<?>) cf);

        writeSerializer.execute(() ->
                channel.writeAndFlush(/* envelope */ req).addListener(f -> {
                    if (!f.isSuccess()) {
                        CompletableFuture<Message> removed = inflight.remove(correlationId);
                        if (removed != null) removed.completeExceptionally(f.cause());
                    }
                })
        );

        channel.eventLoop().schedule(() -> {
            CompletableFuture<Message> removed = inflight.remove(correlationId);
            if (removed != null) removed.completeExceptionally(new TimeoutException("request timeout"));
        }, timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

        return cf;
    }

    @Override
    public void close() {
        channel.close();
    }
}

