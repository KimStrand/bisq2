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

package bisq.network.p2p.node.netty.p2p;


import bisq.common.network.ClearnetAddress;
import bisq.network.p2p.node.netty.p2p.node.NettyNode;
import bisq.network.p2p.node.netty.p2p.transport.clearnet.NettyTcpTransportService;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

@Slf4j
public class App {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: java -jar app.jar <myPort <peersPort>");
            System.exit(2);
        }

        int myPort = Integer.parseInt(args[0]);
        int peersPort = Integer.parseInt(args[1]);

        // 1. Create transport service (clearnet for now)
        NettyTcpTransportService transportService = new NettyTcpTransportService();

        // 2. Create the node
        NettyNode node = new NettyNode(transportService, myPort);

        // 3. Start node server
        node.start(); // blocking
        log.error("Node started on localhost: {}", myPort);

        node.connect(new ClearnetAddress("127.0.0.1", peersPort), Duration.ofSeconds(100))
                .whenComplete((connection, t) -> {
                    log.error("Connected to {}, connection={}", peersPort, connection);
                });


        // Keep running
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                node.close();
                System.out.println("Node stopped.");
            } catch (Exception e) {
                e.printStackTrace();
            }
        }));

        // Block main thread
        Thread.currentThread().join();
    }
}

