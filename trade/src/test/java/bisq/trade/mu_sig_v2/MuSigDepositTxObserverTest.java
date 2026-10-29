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

package bisq.trade.mu_sig_v2;

import bisq.trade.protobuf.MusigGrpc;
import bisq.trade.protobuf.SubscribeTxConfirmationStatusRequest;
import bisq.trade.protobuf.TxConfirmationStatus;
import io.grpc.Context;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class MuSigDepositTxObserverTest {
    private final MusigGrpc.MusigStub stub = mock(MusigGrpc.MusigStub.class);
    private final MuSigDepositTxObserver observer = new MuSigDepositTxObserver(() -> stub);
    private final Map<String, List<Subscription>> subscriptions = new HashMap<>();
    private final AtomicInteger confirmations = new AtomicInteger();
    private final List<Throwable> failures = new ArrayList<>();

    @BeforeEach
    void captureSubscriptions() {
        doAnswer(invocation -> {
            SubscribeTxConfirmationStatusRequest request = invocation.getArgument(0);
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            Context context = Context.current();
            subscriptions.computeIfAbsent(request.getTradeId(), ignored -> new ArrayList<>())
                    .add(new Subscription(context, responses));
            context.addListener(ignored -> responses.onError(Status.CANCELLED.asRuntimeException()), Runnable::run);
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());
    }

    @AfterEach
    void close() {
        observer.close();
    }

    @Test
    void onlyTheFirstConfirmationNotifiesAndCancelsTheSubscription() {
        observe("trade");
        Subscription subscription = subscription("trade");
        subscription.responses().onNext(status(0));
        assertEquals(0, confirmations.get());
        assertFalse(subscription.context().isCancelled());

        subscription.responses().onNext(status(1));
        subscription.responses().onNext(status(2));
        subscription.responses().onCompleted();

        assertEquals(1, confirmations.get());
        assertTrue(subscription.context().isCancelled());
        assertTrue(failures.isEmpty());
    }

    @Test
    void repeatedRequestsShareOneActiveSubscriptionAndTradesAreIndependent() {
        observe("first");
        observe("first");
        observe("second");
        verify(stub, times(2)).subscribeTxConfirmationStatus(any(), any());

        subscription("first").responses().onNext(status(1));
        assertFalse(subscription("second").context().isCancelled());
        subscription("second").responses().onNext(status(1));
        assertEquals(2, confirmations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void backendFailureReleasesTheSubscriptionAndOldCallbacksCannotFinishItsReplacement() {
        observe("trade");
        Subscription previous = subscription("trade");
        var failure = Status.UNAVAILABLE.asRuntimeException();
        previous.responses().onError(failure);
        assertSame(failure, failures.getFirst());
        assertTrue(previous.context().isCancelled());

        observe("trade");
        Subscription replacement = subscription("trade");
        previous.responses().onNext(status(1));
        previous.responses().onError(failure);
        previous.responses().onCompleted();
        assertEquals(0, confirmations.get());
        assertEquals(1, failures.size());
        assertFalse(replacement.context().isCancelled());

        replacement.responses().onNext(status(1));
        assertEquals(1, confirmations.get());
        assertTrue(replacement.context().isCancelled());
    }

    @Test
    void synchronousRegistrationFailureIsThrownAndReleasesTheSubscriptionForRetry() {
        var failure = new IllegalStateException("backend unavailable");
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            Context context = Context.current();
            subscriptions.computeIfAbsent("trade", ignored -> new ArrayList<>())
                    .add(new Subscription(context, responses));
            context.addListener(ignored -> responses.onError(Status.CANCELLED.asRuntimeException()), Runnable::run);
            throw failure;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        assertSame(failure, assertThrows(IllegalStateException.class, () -> observe("trade")));
        Subscription previous = subscription("trade");
        assertTrue(previous.context().isCancelled());
        assertSame(failure, previous.context().cancellationCause());
        assertTrue(failures.isEmpty());
        assertEquals(0, confirmations.get());

        captureSubscriptions();
        observe("trade");
        previous.responses().onNext(status(1));
        previous.responses().onError(failure);
        assertEquals(0, confirmations.get());
        assertFalse(subscription("trade").context().isCancelled());
        subscription("trade").responses().onNext(status(1));
        assertEquals(1, confirmations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void anImmediateStreamErrorStillUsesTheFailureCallback() {
        var failure = Status.UNAVAILABLE.asRuntimeException();
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            responses.onError(failure);
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        observe("trade");

        assertEquals(List.of(failure), failures);
        assertEquals(0, confirmations.get());
    }

    @Test
    void endingTheStreamWithoutConfirmationReportsFailure() {
        observe("trade");
        Subscription subscription = subscription("trade");
        subscription.responses().onNext(status(0));
        subscription.responses().onCompleted();

        assertEquals(1, failures.size());
        assertInstanceOf(IllegalStateException.class, failures.getFirst());
        assertEquals(0, confirmations.get());
        assertTrue(subscription.context().isCancelled());
    }

    @Test
    void immediateBackendConfirmationIsSupported() {
        doAnswer(invocation -> {
            StreamObserver<TxConfirmationStatus> responses = invocation.getArgument(1);
            responses.onNext(status(1));
            assertTrue(Context.current().isCancelled());
            return null;
        }).when(stub).subscribeTxConfirmationStatus(any(), any());

        observe("trade");
        assertEquals(1, confirmations.get());
        assertTrue(failures.isEmpty());
    }

    @Test
    void shutdownCancelsAllSubscriptionsAndIgnoresLateCallbacks() {
        observe("first");
        observe("second");
        observer.close();
        observer.close();

        for (String tradeId : List.of("first", "second")) {
            Subscription subscription = subscription(tradeId);
            assertTrue(subscription.context().isCancelled());
            subscription.responses().onNext(status(1));
            subscription.responses().onError(Status.UNAVAILABLE.asRuntimeException());
            subscription.responses().onCompleted();
        }
        assertEquals(0, confirmations.get());
        assertTrue(failures.isEmpty());
        assertThrows(IllegalStateException.class, () -> observe("third"));
    }

    private void observe(String tradeId) {
        observer.observe(tradeId, confirmations::incrementAndGet, failures::add);
    }

    private Subscription subscription(String tradeId) {
        return subscriptions.get(tradeId).getLast();
    }

    private static TxConfirmationStatus status(int confirmations) {
        return TxConfirmationStatus.newBuilder().setNumConfirmations(confirmations).build();
    }

    private record Subscription(Context context, StreamObserver<TxConfirmationStatus> responses) {}
}
