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

package bisq.support.mediation.mu_sig;

import bisq.common.network.Address;
import bisq.common.network.AddressByTransportTypeMap;
import bisq.common.proto.UnresolvableProtobufException;
import bisq.network.identity.NetworkId;
import bisq.security.keys.KeyGeneration;
import bisq.security.keys.PubKey;
import bisq.support.mediation.MediationPayoutDistributionType;
import bisq.support.mediation.MediationResultReason;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MuSigMediationClosedMessageTest {
    private static final byte[] SIGNATURE = new byte[70];

    @Test
    void messageKeepsItsValuesThroughProtobuf() throws Exception {
        MuSigMediationClosedMessage message = createMessage(SIGNATURE);

        MuSigMediationClosedMessage restored = MuSigMediationClosedMessage.fromProto(message.getValueBuilder(false).build());

        assertEquals(message, restored);
        assertEquals(message.getMuSigMediationResult(), restored.getMuSigMediationResult());
        assertArrayEquals(SIGNATURE, restored.getMediationResultSignature());
    }

    @Test
    void messageWithoutResultIsRefused() {
        bisq.support.protobuf.MuSigMediationClosedMessage proto = createMessage(SIGNATURE).getValueBuilder(false)
                .clearMuSigMediationResult()
                .build();

        assertThrows(UnresolvableProtobufException.class, () -> MuSigMediationClosedMessage.fromProto(proto));
    }

    @Test
    void messageWithoutSignatureIsRefused() {
        bisq.support.protobuf.MuSigMediationClosedMessage proto = createMessage(SIGNATURE).getValueBuilder(false)
                .clearMediationResultSignature()
                .build();

        assertThrows(UnresolvableProtobufException.class, () -> MuSigMediationClosedMessage.fromProto(proto));
    }

    @Test
    void signatureCannotBeChangedFromOutside() {
        byte[] signature = SIGNATURE.clone();
        MuSigMediationClosedMessage message = createMessage(signature);

        signature[0] = 1;
        message.getMediationResultSignature()[1] = 1;

        assertArrayEquals(SIGNATURE, message.getMediationResultSignature());
    }

    private static MuSigMediationClosedMessage createMessage(byte[] signature) {
        Address address = Address.from("127.0.0.1", 1000);
        NetworkId mediator = new NetworkId(new AddressByTransportTypeMap(Map.of(address.getTransportType(), address)),
                new PubKey(KeyGeneration.generateDefaultEcKeyPair().getPublic(), "mediator"));
        MuSigMediationResult result = new MuSigMediationResult(new byte[20],
                MediationResultReason.BUG,
                MediationPayoutDistributionType.CUSTOM_PAYOUT,
                Optional.of(10L),
                Optional.of(20L),
                Optional.empty(),
                Optional.of("summary"));
        return new MuSigMediationClosedMessage("message-id", "trade-id", mediator, result, signature);
    }
}
