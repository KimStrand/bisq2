/*
 * This file is part of Bisq.
 *
 * Bisq is free software: you can redistribute it and/or modify it
 * under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Bisq is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Bisq. If not, see <http://www.gnu.org/licenses/>.
 */

package bisq.network.p2p.services.confidential;

import bisq.network.p2p.message.EnvelopePayloadMessage;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ConfidentialMessageListenerRegistryTest {
    private final EnvelopePayloadMessage message = mock(EnvelopePayloadMessage.class);
    private final ConfidentialMessageService.Listener listener = mock(ConfidentialMessageService.Listener.class);

    @Test
    void replaysMessageProcessedBeforeListenerRegistration() {
        ConfidentialMessageListenerRegistry registry = new ConfidentialMessageListenerRegistry();

        Optional<Set<ConfidentialMessageService.Listener>> listenersToNotify = registry.addMessage(message);
        Set<EnvelopePayloadMessage> replayMessages = registry.addListenerAndGetProcessedMessages(listener);

        assertTrue(listenersToNotify.isPresent());
        assertTrue(listenersToNotify.orElseThrow().isEmpty());
        assertEquals(Set.of(message), replayMessages);
    }

    @Test
    void deliversMessageProcessedAfterListenerRegistration() {
        ConfidentialMessageListenerRegistry registry = new ConfidentialMessageListenerRegistry();

        Set<EnvelopePayloadMessage> replayMessages = registry.addListenerAndGetProcessedMessages(listener);
        Optional<Set<ConfidentialMessageService.Listener>> listenersToNotify = registry.addMessage(message);

        assertTrue(replayMessages.isEmpty());
        assertEquals(Set.of(listener), listenersToNotify.orElseThrow());
    }

    @Test
    void ignoresDuplicateProcessedMessage() {
        ConfidentialMessageListenerRegistry registry = new ConfidentialMessageListenerRegistry();

        assertTrue(registry.addMessage(message).isPresent());
        assertTrue(registry.addMessage(message).isEmpty());
    }

    @Test
    void concurrentRegistrationAndMessageProcessingUsesExactlyOneDeliveryPath() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 100; iteration++) {
                ConfidentialMessageListenerRegistry registry = new ConfidentialMessageListenerRegistry();
                CountDownLatch start = new CountDownLatch(1);

                Future<Set<EnvelopePayloadMessage>> replayMessagesFuture = executor.submit(() -> {
                    start.await();
                    return registry.addListenerAndGetProcessedMessages(listener);
                });
                Future<Optional<Set<ConfidentialMessageService.Listener>>> listenersToNotifyFuture =
                        executor.submit(() -> {
                            start.await();
                            return registry.addMessage(message);
                        });

                start.countDown();
                boolean replayed = replayMessagesFuture.get(5, TimeUnit.SECONDS).contains(message);
                boolean deliveredLive = listenersToNotifyFuture.get(5, TimeUnit.SECONDS)
                        .orElseThrow()
                        .contains(listener);

                assertEquals(1, (replayed ? 1 : 0) + (deliveredLive ? 1 : 0));
            }
        } finally {
            executor.shutdownNow();
        }
    }
}
