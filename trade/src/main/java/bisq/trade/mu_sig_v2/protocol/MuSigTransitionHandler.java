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

package bisq.trade.mu_sig_v2.protocol;

import bisq.common.statemachine.Event;
import bisq.common.statemachine.FlowSnapshot;
import bisq.common.statemachine.Transition;
import bisq.common.statemachine.TransitionHandler;
import bisq.contract.mu_sig.MuSigContract;
import bisq.trade.ServiceProvider;
import bisq.trade.protobuf.MusigGrpc;

import java.util.function.Supplier;

/**
 * One transition: it validates the input, performs the synchronous MuSig and signing work, and
 * returns its next flow document together with outgoing work and internal follow-up events.
 * <p>
 * A handler is created per transition by the rule table, so it may keep intermediate values in
 * fields while {@code process} runs. It must not send messages, persist or mutate anything the
 * driver owns; those belong to the effects it returns.
 * <p>
 * The MuSig calls are blocking, which is acceptable while the Rust side runs locally.
 */
public abstract class MuSigTransitionHandler<S extends MuSigState, E extends Event, T extends MuSigState, D>
        extends TransitionHandler<S, E, T, D, MuSigProtocolContext, Event> {
    protected final ServiceProvider serviceProvider;
    protected final MusigGrpc.MusigBlockingStub blockingStub;
    protected final String tradeId;
    protected final MuSigContract contract;

    protected MuSigTransitionHandler(MuSigProtocolContext context, FlowSnapshot<S, D> flow) {
        super(context, flow);
        serviceProvider = context.serviceProvider();
        tradeId = context.tradeId();
        contract = context.contract();
        blockingStub = context.musigGrpcClient().getBlockingStub();
    }

    @Override
    protected abstract Transition<T, D, Event> process(E event);

    /** Classifies failure at the backend operation boundary, without making a retryability claim. */
    protected final <R> R callBackend(String operation, Supplier<R> call) {
        try {
            return call.get();
        } catch (RuntimeException failure) {
            throw new MuSigProtocolException(MuSigFailureCategory.OPERATION_FAILED,
                    operation + " failed",
                    failure);
        }
    }
}
