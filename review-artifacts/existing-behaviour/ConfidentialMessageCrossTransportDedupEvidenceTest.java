/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at
 * your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU Affero
 * General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.network.p2p.services.confidential;

import bisq.network.NetworkExecutors;
import bisq.network.p2p.node.NodesById;
import bisq.network.p2p.services.peer_group.keep_alive.Ping;
import bisq.security.ConfidentialData;
import bisq.security.HybridEncryption;
import bisq.security.keys.KeyBundleService;
import bisq.security.keys.KeyGeneration;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Characterizes duplicate suppression across the separate confidential
 * message services used by different transports in unchanged Bisq 2.1.11.
 */
class ConfidentialMessageCrossTransportDedupEvidenceTest {
    @Test
    void sameEncryptedPayloadIsDeliveredOncePerConfidentialMessageService() throws Exception {
        NetworkExecutors.initialize(8);

        String receiverKeyId = "receiver-key-id";
        KeyPair receiverKeyPair = KeyGeneration.generateDefaultEcKeyPair();
        KeyPair senderKeyPair = KeyGeneration.generateDefaultEcKeyPair();

        ConfidentialMessageService firstService = createService(receiverKeyId, receiverKeyPair);
        ConfidentialMessageService secondService = createService(receiverKeyId, receiverKeyPair);

        Ping payload = new Ping(42);
        ConfidentialData confidentialData = HybridEncryption.encryptAndSign(
                payload.serialize(), receiverKeyPair.getPublic(), senderKeyPair);
        ConfidentialMessage confidentialMessage = new ConfidentialMessage(
                confidentialData, receiverKeyId);

        CountDownLatch bothCallbacksEntered = new CountDownLatch(2);
        CountDownLatch bothCallbacksCompleted = new CountDownLatch(2);
        AtomicInteger deliveryCount = new AtomicInteger();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        ConfidentialMessageService.Listener listener = envelopePayloadMessage -> {
            try {
                assertEquals(payload, envelopePayloadMessage);
                deliveryCount.incrementAndGet();
                bothCallbacksEntered.countDown();
                if (!bothCallbacksEntered.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("The two listener callbacks did not overlap");
                }
            } catch (Throwable throwable) {
                callbackFailure.compareAndSet(null, throwable);
            } finally {
                bothCallbacksCompleted.countDown();
            }
        };

        firstService.addListener(listener);
        secondService.addListener(listener);

        try {
            // Repeating the payload within one service is suppressed. Passing
            // the same encrypted payload to a second service is not.
            firstService.onMessage(confidentialMessage, null, null);
            firstService.onMessage(confidentialMessage, null, null);
            secondService.onMessage(confidentialMessage, null, null);

            assertTrue(bothCallbacksCompleted.await(10, TimeUnit.SECONDS));
            assertNull(callbackFailure.get());
            assertEquals(2, deliveryCount.get());
            assertEquals(1, firstService.getProcessedEnvelopePayloadMessages().size());
            assertEquals(1, secondService.getProcessedEnvelopePayloadMessages().size());
        } finally {
            firstService.shutdown();
            secondService.shutdown();
            NetworkExecutors.shutdown();
        }
    }

    private static ConfidentialMessageService createService(String receiverKeyId,
                                                             KeyPair receiverKeyPair) {
        NodesById nodesById = mock(NodesById.class);
        KeyBundleService keyBundleService = mock(KeyBundleService.class);
        when(keyBundleService.findKeyPair(receiverKeyId)).thenReturn(Optional.of(receiverKeyPair));
        return new ConfidentialMessageService(
                nodesById, keyBundleService, Optional.empty(), Optional.empty());
    }
}
