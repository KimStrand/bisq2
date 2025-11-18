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

package bisq.network.p2p.node.netty.p2p.transport;

import bisq.network.p2p.node.netty.p2p.conn.Connection;
import bisq.network.p2p.node.netty.p2p.conn.ConnectionHandler;
import bisq.network.p2p.node.netty.p2p.core.RawChannel;
import bisq.network.p2p.node.netty.p2p.core.RemoteAddress;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public interface TransportService extends AutoCloseable {
    void startServerOLD(int port, ConnectionHandler handler) throws Exception;

   default CompletableFuture<Connection> connect(String host, int port, ConnectionHandler handler){
       return null;
   }

    /**
     * Start listening and notify node when a raw inbound channel is created (before handshake).
     */
    CompletableFuture<Void> startServerOLD(ServerListener listener);

    /**
     * Dial an outbound raw channel to remote (before handshake).
     */
    CompletableFuture<RawChannel> dial(RemoteAddress remote, Duration timeout);

    @Override
    void close();

    interface ServerListener {
        void onAccepted(RawChannel raw);            // node will run server-side handshake

        void onServerError(Throwable t);
    }
}
