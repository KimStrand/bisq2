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
import io.grpc.stub.StreamObserver;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.google.common.base.Preconditions.checkState;

/** One cancellable backend subscription per trade. Callbacks must enqueue work on the trade owner. */
final class MuSigDepositTxObserver implements AutoCloseable {
    private final Object lock = new Object();
    private final Supplier<MusigGrpc.MusigStub> stubSupplier;
    private final Map<String, Observation> observationByTradeId = new HashMap<>();
    private boolean closed;

    MuSigDepositTxObserver(Supplier<MusigGrpc.MusigStub> stubSupplier) {
        this.stubSupplier = stubSupplier;
    }

    void observe(String tradeId, Runnable onConfirmed, Consumer<Throwable> onFailure) {
        Observation observation;
        synchronized (lock) {
            checkState(!closed, "Deposit transaction observer is closed");
            if (observationByTradeId.containsKey(tradeId)) {
                return;
            }
            observation = new Observation(tradeId, onConfirmed, onFailure);
            observationByTradeId.put(tradeId, observation);
        }
        try {
            var request = SubscribeTxConfirmationStatusRequest.newBuilder().setTradeId(tradeId).build();
            observation.context.run(() -> stubSupplier.get().subscribeTxConfirmationStatus(request, observation));
        } catch (RuntimeException exception) {
            // Remove first so cancellation callbacks cannot report this startup error as a stream failure.
            observation.finish();
            observation.context.cancel(exception);
            throw exception;
        }
    }

    @Override
    public void close() {
        List<Observation> observations;
        synchronized (lock) {
            closed = true;
            observations = List.copyOf(observationByTradeId.values());
            observationByTradeId.clear();
        }
        observations.forEach(observation -> observation.context.close());
    }

    private final class Observation implements StreamObserver<TxConfirmationStatus> {
        private final String tradeId;
        private final Runnable onConfirmed;
        private final Consumer<Throwable> onFailure;
        private final Context.CancellableContext context = Context.ROOT.withCancellation();

        private Observation(String tradeId, Runnable onConfirmed, Consumer<Throwable> onFailure) {
            this.tradeId = tradeId;
            this.onConfirmed = onConfirmed;
            this.onFailure = onFailure;
        }

        @Override
        public void onNext(TxConfirmationStatus status) {
            if (status.getNumConfirmations() > 0 && finish()) {
                context.close();
                onConfirmed.run();
            }
        }

        @Override
        public void onError(Throwable failure) {
            if (finish()) {
                context.cancel(failure);
                onFailure.accept(failure);
            }
        }

        @Override
        public void onCompleted() {
            onError(new IllegalStateException("Deposit observation completed before confirmation"));
        }

        private boolean finish() {
            synchronized (lock) {
                return observationByTradeId.remove(tradeId, this);
            }
        }
    }
}
