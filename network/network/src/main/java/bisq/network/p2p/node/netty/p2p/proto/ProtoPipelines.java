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

package bisq.network.p2p.node.netty.p2p.proto;

import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.protobuf.*;

public final class ProtoPipelines {
    private ProtoPipelines() {}

    /** Inbound: frame -> protobuf envelope */
    public static void addInbound(ChannelPipeline p, com.google.protobuf.MessageLite defaultInstance) {
        p.addLast("frameDecoder", new ProtobufVarint32FrameDecoder());
        p.addLast("protoDecoder", new ProtobufDecoder(defaultInstance));
    }

    /** Outbound: protobuf envelope -> frame */
    public static void addOutbound(ChannelPipeline p) {
        p.addLast("frameEncoder", new ProtobufVarint32LengthFieldPrepender());
        p.addLast("protoEncoder", new ProtobufEncoder());
    }
}

