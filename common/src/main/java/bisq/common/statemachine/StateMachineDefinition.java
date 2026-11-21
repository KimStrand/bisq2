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

package bisq.common.statemachine;

import bisq.common.processing.Effect;
import bisq.common.processing.ProcessingOutput;
import com.google.common.annotations.VisibleForTesting;

import java.util.List;

/**
 * The definition of one flow: its rule table and the flow's own policies. A definition is stateless;
 * {@link FlowRegistration.Builder} binds it to the flow's part of the document.
 * The processor records successfully processed event types and discards subsequent inputs of those types per flow.
 * Every transition produces effects, logs, raised events and timer changes through {@link ProcessingOutput}.
 *
 * @param <S> state family of the flow
 * @param <D> flow data
 * @param <E> event family accepted and raised by the state machine
 * @param <A> check context, read-only
 * @param <C> handler context, prepared from the current snapshot
 */
public abstract class StateMachineDefinition<S, D, E, A, C> {
    /** The flow's rules. The method is public for tests; production code calls it only from this package. */
    @VisibleForTesting
    public abstract RuleTable<S, D, E, C> rules();


    /* --------------------------------------------------------------------- */
    // Flow policies
    /* --------------------------------------------------------------------- */

    public abstract boolean isFailed(S state);

    /**
     * Controls this flow's own inputs and recovery. By default, a failed flow stops processing. Disabling is final:
     * no state for which this is false may lead to a state for which it is true, because the lane drops later
     * messages for a stopped flow without saving them.
     */
    public boolean isProcessingEnabled(S state) {
        return !isFailed(state);
    }

    /**
     * Controls the flow's descendants independently of its own transitions. Disabled descendants discard messages
     * and reject commands; temporary waiting belongs in their readiness checks instead. By default, a failed or
     * disabled flow also disables its descendants. Disabling is final, as for {@link #isProcessingEnabled}.
     */
    public boolean isChildProcessingEnabled(S state) {
        return !isFailed(state) && isProcessingEnabled(state);
    }

    /**
     * The flow's own rule for ignoring an input in the current situation. It need not be final, so the lane never uses
     * it to drop a message before saving it. The processor checks its final discard rules before this method.
     */
    protected boolean shouldDiscard(A context, FlowSnapshot<? extends S, D> flow, E event) {
        return false;
    }

    /**
     * The flow's own readiness rule, in addition to the rule table. It is asked only if the table has a rule for the
     * flow's state and the input. It must not change a flow or perform work. While it returns false, the processor
     * rejects a command and defers a message or a raised event; the lane retains deferred work for later.
     * Payload verification stays in handlers.
     */
    protected boolean isReady(A context, FlowSnapshot<? extends S, D> flow, E event) {
        return true;
    }

    /**
     * Called by the owner for commands after checking readiness, before handler processing.
     * Implementations may reject by throwing, but must not perform work or change a flow's data.
     */
    public void admit(A context, FlowSnapshot<? extends S, D> flow, E event) {
    }

    /** Describes effects for the lane to re-establish work required by a restored flow without replaying its handlers. */
    protected List<Effect> recoveryEffects(FlowSnapshot<? extends S, D> flow) {
        return List.of();
    }
}
