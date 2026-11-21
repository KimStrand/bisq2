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

import bisq.bonded_roles.release.AppType;
import bisq.persistence.PersistenceService;
import bisq.trade.mu_sig_v2.protocol.MuSigTradeState;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.DepositTxConfirmed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Failed;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.Init;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.SellerForceClosedTrade;
import bisq.trade.mu_sig_v2.protocol.execution.MuSigTradeExecutionState.TradeClosed;
import com.google.protobuf.Any;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static bisq.trade.mu_sig_v2.MuSigPersistenceTestSupport.initialRecord;
import static bisq.trade.mu_sig_v2.MuSigTradeServiceTestSupport.savedRecord;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * How the trade service reads its store file: a trade which cannot be read, a wrong key, a file which cannot be
 * read, and the backup.
 */
class MuSigTradeStoreFileTest extends MuSigTradePersistenceFixture {
    @Test
    void aSavedTradeWhichCannotBeReadIsSkippedAndTheOthersAreRestored() throws Exception {
        var record = initialRecord(tradeAt(new Init())).toProto(false);
        // A saved trade without its processing history cannot be resumed.
        var proto = bisq.trade.protobuf.MuSigV2TradeStore.newBuilder()
                .putTradeById(tradeId, record)
                .putTradeById("unreadable-trade", record.toBuilder().clearProcessedEventTypes().build()).build();
        var output = new ByteArrayOutputStream();
        Any.pack(proto).writeDelimitedTo(output);
        byte[] stored = output.toByteArray();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            Path storePath = service.getPersistence().getStorePath();
            Files.createDirectories(storePath.getParent());
            Files.write(storePath, stored);

            assertTrue(await(persistenceService.readAllPersisted()));

            var trades = service.getPersistableStore().getTrades();
            assertEquals(1, trades.size());
            assertEquals(tradeId, trades.getFirst().trade().getId());
            // The next write removes the skipped entry, so the persistence layer keeps a copy of the file.
            try (var copies = Files.list(directory.resolve("db").resolve("unreadable"))) {
                var copy = copies.toList();
                assertEquals(1, copy.size());
                assertArrayEquals(stored, Files.readAllBytes(copy.getFirst()));
            }
            assertTrue(await(service.initialize()));
        } finally {
            service.shutdown().join();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void aSavedTradeUnderAnotherKeyDoesNotReplaceTheTradeWithThatId(boolean otherKeyFirst) throws Exception {
        var closed = initialRecord(tradeAt(new TradeClosed())).toProto(false);
        var sameTradeAtItsStart = initialRecord(tradeAt(new Init())).toProto(false);
        // The store saves a trade under its trade ID. Only a damaged or changed file holds it under another key too.
        var builder = bisq.trade.protobuf.MuSigV2TradeStore.newBuilder();
        if (otherKeyFirst) {
            builder.putTradeById("another-key", sameTradeAtItsStart).putTradeById(tradeId, closed);
        } else {
            builder.putTradeById(tradeId, closed).putTradeById("another-key", sameTradeAtItsStart);
        }
        var output = new ByteArrayOutputStream();
        Any.pack(builder.build()).writeDelimitedTo(output);
        byte[] stored = output.toByteArray();
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            Path storePath = service.getPersistence().getStorePath();
            Files.createDirectories(storePath.getParent());
            Files.write(storePath, stored);

            assertTrue(await(persistenceService.readAllPersisted()));

            var trades = service.getPersistableStore().getTrades();
            assertEquals(1, trades.size());
            assertEquals(closed, trades.getFirst().toProto(false));
            // The next write removes the entry under the other key, so the persistence layer keeps a copy of the file.
            try (var copies = Files.list(directory.resolve("db").resolve("unreadable"))) {
                var copy = copies.toList();
                assertEquals(1, copy.size());
                assertArrayEquals(stored, Files.readAllBytes(copy.getFirst()));
            }
            assertTrue(await(service.initialize()));
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void aStoreFileWhichCannotBeReadIsKeptAsideAndTheServiceStartsWithoutTrades() throws Exception {
        byte[] unreadable = new byte[]{1, 2, 3};
        var persistenceService = new PersistenceService(directory);
        when(services.getPersistenceService()).thenReturn(persistenceService);
        var service = new MuSigTradeService(services, grpcClient, AppType.DESKTOP);
        try {
            Path storePath = service.getPersistence().getStorePath();
            Files.createDirectories(storePath.getParent());
            Files.write(storePath, unreadable);

            assertTrue(await(persistenceService.readAllPersisted()));

            assertTrue(service.getPersistableStore().getTrades().isEmpty());
            // As for every store of the user's own data, the node moves the file into the folder of unreadable files.
            assertFalse(Files.exists(storePath));
            try (var kept = Files.list(directory.resolve("db").resolve("unreadable"))) {
                var files = kept.toList();
                assertEquals(1, files.size());
                assertArrayEquals(unreadable, Files.readAllBytes(files.getFirst()));
            }
            assertTrue(await(service.initialize()));
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            service.shutdown().join();
        }
    }

    @Test
    void aReadableBackupAllowsInitializationAfterStoreCorruption() throws Exception {
        var original = tradeAt(new DepositTxConfirmed());
        Path storePath;
        seed(initialRecord(original));
        var service = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {

            assertTrue(await(service.persist()));
            assertEquals(1, service.getPersistence().getBackups().size());
            storePath = service.getPersistence().getStorePath();
        } finally {
            service.shutdown().join();
        }
        Files.write(storePath, new byte[]{1, 2, 3});

        var restoredService = startAndAwaitRecovery(effect -> fail("No peer send is expected"));
        try {
            assertEquals(original.toProto(false), savedRecord(restoredService, tradeId).trade().toProto(false));
            assertEquals(original.toProto(false), saved(restoredService).trade().toProto(false));
            assertEquals(1, services.getPersistenceService().getRestoreService().getRestoredBackupFileInfos().size());
            verifyNoInteractions(backend, asyncBackend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void bothTakerRolesRestoreFromTheSameStore() throws Exception {
        var seller = tradeAt(new DepositTxConfirmed());
        var buyer = buyerTradeAt(new DepositTxConfirmed());
        seed(initialRecord(seller),
                initialRecord(buyer));
        var service = startAndAwaitRecovery(effect -> fail("No send is expected"));
        try {


        } finally {
            service.shutdown().join();
        }
        var restoredService = startAndAwaitRecovery(effect -> fail("No send is expected"));
        try {
            assertEquals(seller.toProto(false), savedRecord(restoredService, seller.getId()).trade().toProto(false));
            assertEquals(buyer.toProto(false), savedRecord(restoredService, buyer.getId()).trade().toProto(false));
            verifyNoInteractions(backend);
        } finally {
            restoredService.shutdown().join();
        }
    }

    @Test
    void tradeStateSurvivesFilePersistenceAndServiceRestart() throws Exception {
        for (var tradeState : List.<MuSigTradeState>of(new MuSigTradeState.Active(),
                new MuSigTradeState.Failed(),
                new MuSigTradeState.Historical())) {
            var trade = withTradeState(tradeAt(new SellerForceClosedTrade()), tradeState);
            seed(new MuSigV2TradeRecord(trade, Map.of(), List.of(), List.of(), List.of()));

            var service = startAndAwaitRecovery(effect -> {});
            try {
                var restored = savedRecord(service, tradeId).trade();
                assertEquals(tradeState, restored.getState());
                assertEquals(trade.toProto(false), restored.toProto(false));
                assertEquals(tradeState, saved(service).trade().getState());
            } finally {
                service.shutdown().join();
            }
        }
    }
}
