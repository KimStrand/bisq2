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
import lombok.ToString;

import java.time.Duration;

@ToString
public final class NettyOutboundConnection extends OutboundConnection {
    private final NettyInboundConnection asBase;

    public NettyOutboundConnection(Channel ch) {
        this.asBase = new NettyInboundConnection(ch);
    }

    @Override
    public java.net.SocketAddress localAddress() {
        return asBase.localAddress();
    }

    @Override
    public java.net.SocketAddress remoteAddress() {
        return asBase.remoteAddress();
    }

    @Override
    public boolean isOpen() {
        return asBase.isOpen();
    }

    @Override
    public java.util.concurrent.CompletableFuture<Void> send(Message msg) {
        return asBase.send(msg);
    }

    @Override
    public <T extends Message> java.util.concurrent.CompletableFuture<T> request(Message req,
                                                                                 Class<T> replyType,
                                                                                 Duration timeout) {
        return asBase.request(req, replyType, timeout);
    }

    @Override
    public void close() {
        asBase.close();
    }
}
