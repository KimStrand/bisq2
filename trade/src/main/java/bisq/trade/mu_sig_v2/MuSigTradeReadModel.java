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

import bisq.common.observable.Observable;
import bisq.common.observable.ReadOnlyObservable;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2FailureDetails;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.mediation.MuSigMediationState;
import lombok.Getter;

import java.util.Optional;

/**
 * Observable local trade state maintained by {@link MuSigTradeReadModelStore} and exposed read-only through the service.
 * State updates do not acknowledge a disk write. Persistence and preparation errors are transient;
 * neither changes the trade's domain state. Preparation errors describe required setup failures and
 * are cleared when that setup succeeds. Both errors are updated through the lane's snapshot publication.
 */
public final class MuSigTradeReadModel {
    @Getter
    private final String id;
    @Getter
    private final TradeRole myRole;
    private final Observable<MuSigTradeState> state;
    private final Observable<MuSigTradeExecutionState> executionState;
    private final Observable<Optional<MuSigMediationState>> mediationState = new Observable<>(Optional.empty());
    private final Observable<Optional<MuSigV2FailureDetails>> myFailure = new Observable<>(Optional.empty());
    private final Observable<Optional<MuSigV2FailureDetails>> peerFailure = new Observable<>(Optional.empty());
    private final Observable<Optional<String>> persistenceError = new Observable<>(Optional.empty());
    private final Observable<Optional<String>> preparationError = new Observable<>(Optional.empty());

    MuSigTradeReadModel(String id,
                        TradeRole myRole,
                        MuSigTradeState state,
                        MuSigTradeExecutionState executionState) {
        this.id = id;
        this.myRole = myRole;
        this.state = new Observable<>(state);
        this.executionState = new Observable<>(executionState);
    }

    public MuSigTradeState getState() {
        return state.get();
    }

    void setState(MuSigTradeState state) {
        this.state.set(state);
    }

    public ReadOnlyObservable<MuSigTradeState> stateObservable() {
        return state;
    }

    public MuSigTradeExecutionState getExecutionState() {
        return executionState.get();
    }

    void setExecutionState(MuSigTradeExecutionState executionState) {
        this.executionState.set(executionState);
    }

    public ReadOnlyObservable<MuSigTradeExecutionState> executionStateObservable() {
        return executionState;
    }

    public Optional<MuSigMediationState> getMediationState() {
        return mediationState.get();
    }

    void setMediationState(Optional<MuSigMediationState> mediationState) {
        this.mediationState.set(mediationState);
    }

    public ReadOnlyObservable<Optional<MuSigMediationState>> mediationStateObservable() {
        return mediationState;
    }

    public Optional<MuSigV2FailureDetails> getMyFailure() {
        return myFailure.get();
    }

    void setMyFailure(Optional<MuSigV2FailureDetails> myFailure) {
        this.myFailure.set(myFailure);
    }

    public ReadOnlyObservable<Optional<MuSigV2FailureDetails>> myFailureObservable() {
        return myFailure;
    }

    public Optional<MuSigV2FailureDetails> getPeerFailure() {
        return peerFailure.get();
    }

    void setPeerFailure(Optional<MuSigV2FailureDetails> peerFailure) {
        this.peerFailure.set(peerFailure);
    }

    public ReadOnlyObservable<Optional<MuSigV2FailureDetails>> peerFailureObservable() {
        return peerFailure;
    }

    public Optional<String> getPreparationError() {
        return preparationError.get();
    }

    void setPreparationError(Optional<String> preparationError) {
        this.preparationError.set(preparationError);
    }

    public ReadOnlyObservable<Optional<String>> preparationErrorObservable() {
        return preparationError;
    }

    public Optional<String> getPersistenceError() {
        return persistenceError.get();
    }

    void setPersistenceError(Optional<String> persistenceError) {
        this.persistenceError.set(persistenceError);
    }

    public ReadOnlyObservable<Optional<String>> persistenceErrorObservable() {
        return persistenceError;
    }
}
