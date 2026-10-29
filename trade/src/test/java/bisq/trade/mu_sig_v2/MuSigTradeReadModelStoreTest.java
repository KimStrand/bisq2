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

import bisq.account.accounts.AccountPayload;
import bisq.contract.mu_sig.MuSigContract;
import bisq.identity.Identity;
import bisq.trade.TradeRole;
import bisq.trade.mu_sig_v2.protocol.MuSigProtocol;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.MuSigV2Trade;
import bisq.trade.mu_sig_v2.protocol.MuSigV2TradeData;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigV2TradeExecution;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class MuSigTradeReadModelStoreTest {
    @Test
    void exposesSaveFailureWithLocalStateAndClearsItWithoutADomainTransition() {
        var trade = new MuSigV2Trade<MuSigTradeExecutionState>("trade",
                new MuSigTradeState.Active(),
                new MuSigV2TradeData(mock(MuSigContract.class),
                        mock(Identity.class),
                        TradeRole.SELLER_AS_TAKER,
                        MuSigProtocol.VERSION,
                        mock(AccountPayload.class)),
                MuSigV2TradeExecution.initial(),
                Optional.empty());
        var store = new MuSigTradeReadModelStore();
        store.upsert(trade, Optional.empty(), Optional.empty());
        var readModel = store.get(trade.getId());
        var failed = trade.withExecution(new MuSigV2TradeExecution<>(new MuSigTradeExecutionState.Failed(),
                trade.getExecution().data()));
        var seenOnStateChange = new AtomicReference<Optional<String>>();
        var errors = new ArrayList<Optional<String>>();
        var errorPin = readModel.persistenceErrorObservable().addObserver(errors::add);
        var statePin = readModel.executionStateObservable().addObserver(state -> {
            if (state instanceof MuSigTradeExecutionState.Failed) {
                seenOnStateChange.set(readModel.getPersistenceError());
            }
        });
        try {
            store.upsert(failed,
                    Optional.of(new IllegalStateException("Save failed", new IllegalStateException("Disk full"))),
                    Optional.empty());

            assertEquals(Optional.of("Disk full"), seenOnStateChange.get());
            assertInstanceOf(MuSigTradeExecutionState.Failed.class, readModel.getExecutionState());

            store.upsert(failed, Optional.empty(), Optional.empty());

            assertInstanceOf(MuSigTradeExecutionState.Failed.class, readModel.getExecutionState());
            assertEquals(List.of(Optional.empty(), Optional.of("Disk full"), Optional.empty()), errors);
            assertSame(readModel, store.get(trade.getId()));
            var restored = new MuSigTradeReadModelStore();
            restored.upsert(failed, Optional.empty(), Optional.empty());
            assertTrue(restored.get(trade.getId()).getPersistenceError().isEmpty());
        } finally {
            statePin.unbind();
            errorPin.unbind();
        }
    }
}
