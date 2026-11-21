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

package bisq.support.arbitration.mu_sig;

import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.network.identity.NetworkId;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.support.arbitration.ArbitrationPayoutDistributionType;
import bisq.support.arbitration.ArbitrationResultReason;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MuSigArbitrationClosedMessageTest {
    private static final byte[] SIGNATURE = new byte[70];

    @Test
    void messageKeepsItsValuesThroughProtobuf() throws Exception {
        MuSigArbitrationClosedMessage message = createMessage(SIGNATURE);

        MuSigArbitrationClosedMessage restored = MuSigArbitrationClosedMessage.fromProto(message.getValueBuilder(false).build());

        assertEquals(message, restored);
        assertEquals(message.getMuSigArbitrationResult(), restored.getMuSigArbitrationResult());
        assertArrayEquals(SIGNATURE, restored.getArbitrationResultSignature());
    }

    @Test
    void messageWithoutResultIsRefused() {
        bisq.support.protobuf.MuSigArbitrationClosedMessage proto = createMessage(SIGNATURE).getValueBuilder(false)
                .clearMuSigArbitrationResult()
                .build();

        assertThrows(UnresolvableProtobufException.class, () -> MuSigArbitrationClosedMessage.fromProto(proto));
    }

    @Test
    void messageWithoutSignatureIsRefused() {
        bisq.support.protobuf.MuSigArbitrationClosedMessage proto = createMessage(SIGNATURE).getValueBuilder(false)
                .clearArbitrationResultSignature()
                .build();

        assertThrows(UnresolvableProtobufException.class, () -> MuSigArbitrationClosedMessage.fromProto(proto));
    }

    @Test
    void signatureCannotBeChangedFromOutside() {
        byte[] signature = SIGNATURE.clone();
        MuSigArbitrationClosedMessage message = createMessage(signature);

        signature[0] = 1;
        message.getArbitrationResultSignature()[1] = 1;

        assertArrayEquals(SIGNATURE, message.getArbitrationResultSignature());
    }

    private static MuSigArbitrationClosedMessage createMessage(byte[] signature) {
        Address address = Address.from("127.0.0.1", 1000);
        NetworkId arbitrator = new NetworkId(new AddressByTransportTypeMap(Map.of(address.getTransportType(), address)),
                new PubKey(KeyGeneration.generateDefaultEcKeyPair().getPublic(), "arbitrator"));
        MuSigArbitrationResult result = new MuSigArbitrationResult(new byte[20],
                ArbitrationResultReason.BUG,
                ArbitrationPayoutDistributionType.CUSTOM_PAYOUT,
                10L,
                20L,
                Optional.of("summary"));
        return new MuSigArbitrationClosedMessage("message-id", "trade-id", arbitrator, result, signature);
    }
}
