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

package bisq.persistence;

import bisq.common.file.FileMutatorUtils;
import bisq.persistence.backup.MaxBackupSize;
import bisq.persistence.backup.RestoreService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class PersistableStoreReaderWriterTests {

    @Test
    void strictPersistenceReportsWriteFailure(@TempDir Path directory) throws Exception {
        var persistence = new Persistence<TimestampStore>(directory,
                "StrictStore",
                MaxBackupSize.ZERO,
                new RestoreService());
        // The writer must not report success when its temporary file cannot be opened.
        Files.createDirectory(directory.resolve("temp_strict_store.protobuf"));
        assertThrows(CompletionException.class, () -> persistence.persistAsync(new TimestampStore()).join());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCurrentFileIsRestoredOnlyByStrictRead(boolean strict, @TempDir Path directory) throws IOException {
        var original = new TimestampStore();
        original.getTimestampsByProfileId().put("saved", 42L);
        PersistableStoreResolver.addResolver(original);
        var manager = new PersistableStoreFileManager(directory.resolve("db/private/store.protobuf"),
                MaxBackupSize.TEN_MB);
        var writer = new PersistableStoreReaderWriter<TimestampStore>(manager, new RestoreService());
        writer.write(original);
        assertTrue(manager.maybeBackup());
        Files.delete(manager.getStoreFilePath());
        assertTrue(Files.notExists(manager.getStoreFilePath()));

        var restored = strict ? writer.readOrThrow() : writer.read();
        if (strict) {
            assertEquals(42L, restored.orElseThrow().getTimestampsByProfileId().get("saved"));
            assertTrue(Files.isRegularFile(manager.getStoreFilePath()));
        } else {
            assertTrue(restored.isEmpty());
            assertTrue(Files.notExists(manager.getStoreFilePath()));
            assertEquals(1, manager.getBackups().size());
        }
    }

    @Test
    void readNotExistingStore(@TempDir Path tempDirPath) {
        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());

        Optional<TimestampStore> optionalTimestampStore = persistableStoreReaderWriter.read();
        assertThat(optionalTimestampStore).isEmpty();
    }

    @Test
    void writeSurfacesFailureAndLeavesStoreUntouched(@TempDir Path tempDirPath) throws IOException {
        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());
        // A directory where the temp file goes makes the write fail before the store is touched;
        // the temp file step reports that as a failed serialization.
        Files.createDirectories(storeFileManager.getTempFilePath());

        assertThrows(CouldNotSerializePersistableStore.class, () -> persistableStoreReaderWriter.write(new TimestampStore()));
        assertThat(storageFilePath).doesNotExist();
    }

    @Test
    void writeSurfacesARefusedRenameOfTheTempFile(@TempDir Path tempDirPath) throws IOException {
        // The temp file is written in place, so the failure is confined to the final rename: the
        // step whose result used to be ignored, reporting a write that left no active store behind.
        PersistableStoreFileManagerTests.assumeCanRevokeDirectoryWrite();
        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());
        FileMutatorUtils.createFile(storeFileManager.getTempFilePath());

        Set<PosixFilePermission> original = Files.getPosixFilePermissions(tempDirPath);
        Files.setPosixFilePermissions(tempDirPath, PosixFilePermissions.fromString("r-x------"));
        try {
            assertThrows(IllegalStateException.class, () -> persistableStoreReaderWriter.write(new TimestampStore()));
        } finally {
            Files.setPosixFilePermissions(tempDirPath, original);
        }
        assertThat(storageFilePath).doesNotExist();
    }

    @Test
    void writeAndReadStore(@TempDir Path tempDirPath) {
        var timestampStore = new TimestampStore();
        Map<String, Long> timestampsByProfileId = timestampStore.getTimestampsByProfileId();
        timestampsByProfileId.put("A", 1L);
        timestampsByProfileId.put("B", 2L);
        timestampsByProfileId.put("C", 3L);

        PersistableStoreResolver.addResolver(timestampStore);

        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());
        persistableStoreReaderWriter.write(timestampStore);

        Optional<TimestampStore> readOptionalStore = persistableStoreReaderWriter.read();
        assertThat(readOptionalStore.isPresent()).isTrue();

        TimestampStore readStore = readOptionalStore.get();
        Map<String, Long> readTimestampsByProfileId = readStore.getTimestampsByProfileId();

        assertThat(readTimestampsByProfileId.get("A")).isEqualTo(1L);
        assertThat(readTimestampsByProfileId.get("B")).isEqualTo(2L);
        assertThat(readTimestampsByProfileId.get("C")).isEqualTo(3L);
    }

    @Test
    void readFromBackupAfterCorruption(@TempDir Path tempDirPath) throws Exception {
        // original store (will become the backup)
        var originalStore = new TimestampStore();
        Map<String, Long> originalMap = originalStore.getTimestampsByProfileId();
        originalMap.put("A", 1L);
        originalMap.put("B", 2L);
        originalMap.put("C", 3L);

        // modified store (will overwrite main file, backup keeps original)
        var modifiedStore = new TimestampStore();
        Map<String, Long> modifiedMap = modifiedStore.getTimestampsByProfileId();
        modifiedMap.put("A", 10L);
        modifiedMap.put("B", 20L);
        modifiedMap.put("C", 30L);

        // register resolver
        PersistableStoreResolver.addResolver(originalStore);

        Path storageFilePath = tempDirPath.resolve("alice").resolve("db").resolve("test-protofile-store.protobuf");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath, MaxBackupSize.TEN_MB);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());

        // write original
        persistableStoreReaderWriter.write(originalStore);

        // write modified (main now has modified values)
        persistableStoreReaderWriter.write(modifiedStore);

        // corrupt the main store file
        FileMutatorUtils.writeToPath("corrupted-data".getBytes(), storeFileManager.getStoreFilePath());

        // read should recover from the backup (original values)
        Optional<TimestampStore> readOptionalStore = persistableStoreReaderWriter.read();
        assertThat(readOptionalStore).isPresent();

        TimestampStore readStore = readOptionalStore.get();
        Map<String, Long> readTimestampsByProfileId = readStore.getTimestampsByProfileId();
        assertThat(readTimestampsByProfileId.get("A")).isEqualTo(1L);
        assertThat(readTimestampsByProfileId.get("B")).isEqualTo(2L);
        assertThat(readTimestampsByProfileId.get("C")).isEqualTo(3L);

        // one corrupted file should be stored
        Path corruptedFilesAtReadPath = storageFilePath.getParent().resolve("corruptedFilesAtRead");
        long count;
        try (var stream = Files.list(corruptedFilesAtReadPath)) {
            count = stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.startsWith(storageFilePath.getFileName().toString()))
                    .count();
        }
        assertEquals(1, count, "Expected exactly one file starting with " + storageFilePath.getFileName());
    }

    @ParameterizedTest
    @CsvSource({"true,false", "true,true", "false,true"})
    void strictReadRetainsUnreadableFilesAcrossRestarts(boolean hasCurrent,
                                                        boolean hasBackup,
                                                        @TempDir Path directory) throws Exception {
        var original = new TimestampStore();
        PersistableStoreResolver.addResolver(original);
        Path storageDirectory = directory.resolve("db/private");
        var persistence = new Persistence<TimestampStore>(storageDirectory,
                "StrictStore",
                MaxBackupSize.TEN_MB,
                new RestoreService());
        persistence.persistAsync(original).join();
        byte[] corrupted = new byte[]{1, 2, 3};
        if (hasBackup) {
            persistence.persistAsync(original).join();
            Files.write(persistence.getBackups().getFirst().getPath(), corrupted);
        }
        if (hasCurrent) {
            Files.write(persistence.getStorePath(), corrupted);
        } else {
            Files.delete(persistence.getStorePath());
        }

        for (int restart = 0; restart < 2; restart++) {
            var restarted = new Persistence<TimestampStore>(storageDirectory,
                    "StrictStore",
                    MaxBackupSize.TEN_MB,
                    new RestoreService());
            var failure = assertThrows(IllegalStateException.class, restarted::readOrThrow);
            assertTrue(failure.getMessage().contains(persistence.getStorePath().toString()));
            assertEquals((hasCurrent ? 1 : 0) + (hasBackup ? 1 : 0), failure.getSuppressed().length);
            if (hasCurrent) {
                assertArrayEquals(corrupted, Files.readAllBytes(persistence.getStorePath()));
            } else {
                assertTrue(Files.notExists(persistence.getStorePath()));
            }
            if (hasBackup) {
                assertEquals(1, restarted.getBackups().size());
                assertArrayEquals(corrupted, Files.readAllBytes(restarted.getBackups().getFirst().getPath()));
            }
        }
    }

    @Test
    void strictReadDoesNotTreatAnUnreadableBackupDirectoryAsNoBackups(@TempDir Path directory) throws Exception {
        Path backupDirectory = directory.resolve("backups/private/strict");
        Files.createDirectories(backupDirectory.getParent());
        // A file at the expected directory path makes listing fail deterministically on every platform.
        Files.writeString(backupDirectory, "not a directory");
        var persistence = new Persistence<TimestampStore>(directory.resolve("db/private"),
                "StrictStore",
                MaxBackupSize.TEN_MB,
                new RestoreService());

        var failure = assertThrows(UncheckedIOException.class, persistence::readOrThrow);
        assertTrue(failure.getMessage().contains(backupDirectory.toString()));
    }

    @Test
    void strictReadSkipsAnUnreadableBackupAndRestoresAnOlderOne(@TempDir Path directory) throws Exception {
        var original = new TimestampStore();
        original.getTimestampsByProfileId().put("saved", 42L);
        PersistableStoreResolver.addResolver(original);
        var restoreService = new RestoreService();
        var persistence = new Persistence<TimestampStore>(directory.resolve("db/private"),
                "StrictStore",
                MaxBackupSize.TEN_MB,
                restoreService);
        persistence.persistAsync(original).join();
        persistence.persistAsync(original).join();
        Path newestBackup = persistence.getBackups().getFirst().getPath();
        Path olderBackup = newestBackup.resolveSibling("strict_store.protobuf_2000-01-01_0000");
        Files.copy(newestBackup, olderBackup);
        byte[] corrupted = new byte[]{1, 2, 3};
        Files.write(newestBackup, corrupted);
        Files.delete(persistence.getStorePath());

        var restored = persistence.readOrThrow().orElseThrow();

        assertEquals(42L, restored.getTimestampsByProfileId().get("saved"));
        assertArrayEquals(corrupted, Files.readAllBytes(newestBackup));
        assertEquals(olderBackup, restoreService.getRestoredBackupFileInfos().getFirst().getPath());
        assertTrue(Files.isRegularFile(persistence.getStorePath()));
    }

    @Test
    void writeStoreTwice(@TempDir Path tempDirPath) {
        var timestampStore = new TimestampStore();
        Map<String, Long> timestampsByProfileId = timestampStore.getTimestampsByProfileId();
        timestampsByProfileId.put("A", 1L);
        timestampsByProfileId.put("B", 2L);
        timestampsByProfileId.put("C", 3L);

        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());
        persistableStoreReaderWriter.write(timestampStore);

        // Triggers rename
        persistableStoreReaderWriter.write(timestampStore);
    }

    @Test
    void strictReadNotExistingStore(@TempDir Path tempDirPath) {
        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());

        Optional<TimestampStore> optionalTimestampStore = persistableStoreReaderWriter.readOrThrow();
        assertThat(optionalTimestampStore).isEmpty();
    }

    @Test
    void strictWriteAndReadStore(@TempDir Path tempDirPath) {
        var timestampStore = new TimestampStore();
        Map<String, Long> timestampsByProfileId = timestampStore.getTimestampsByProfileId();
        timestampsByProfileId.put("A", 1L);
        timestampsByProfileId.put("B", 2L);
        timestampsByProfileId.put("C", 3L);

        PersistableStoreResolver.addResolver(timestampStore);

        Path storageFilePath = tempDirPath.resolve("protoFile");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());
        persistableStoreReaderWriter.write(timestampStore);

        Optional<TimestampStore> readOptionalStore = persistableStoreReaderWriter.readOrThrow();
        assertThat(readOptionalStore.isPresent()).isTrue();

        TimestampStore readStore = readOptionalStore.get();
        Map<String, Long> readTimestampsByProfileId = readStore.getTimestampsByProfileId();

        assertThat(readTimestampsByProfileId.get("A")).isEqualTo(1L);
        assertThat(readTimestampsByProfileId.get("B")).isEqualTo(2L);
        assertThat(readTimestampsByProfileId.get("C")).isEqualTo(3L);
    }

    @Test
    void strictReadFromBackupAfterCorruption(@TempDir Path tempDirPath) throws Exception {
        // original store (will become the backup)
        var originalStore = new TimestampStore();
        Map<String, Long> originalMap = originalStore.getTimestampsByProfileId();
        originalMap.put("A", 1L);
        originalMap.put("B", 2L);
        originalMap.put("C", 3L);

        // modified store (will overwrite main file, backup keeps original)
        var modifiedStore = new TimestampStore();
        Map<String, Long> modifiedMap = modifiedStore.getTimestampsByProfileId();
        modifiedMap.put("A", 10L);
        modifiedMap.put("B", 20L);
        modifiedMap.put("C", 30L);

        // register resolver
        PersistableStoreResolver.addResolver(originalStore);

        Path storageFilePath = tempDirPath.resolve("alice").resolve("db").resolve("test-protofile-store.protobuf");
        var storeFileManager = new PersistableStoreFileManager(storageFilePath, MaxBackupSize.TEN_MB);
        var persistableStoreReaderWriter = new PersistableStoreReaderWriter<TimestampStore>(storeFileManager, new RestoreService());

        // write original
        persistableStoreReaderWriter.write(originalStore);

        // write modified (main now has modified values)
        persistableStoreReaderWriter.write(modifiedStore);

        // corrupt the main store file
        FileMutatorUtils.writeToPath("corrupted-data".getBytes(), storeFileManager.getStoreFilePath());

        // read should recover from the backup (original values)
        Optional<TimestampStore> readOptionalStore = persistableStoreReaderWriter.readOrThrow();
        assertThat(readOptionalStore).isPresent();

        TimestampStore readStore = readOptionalStore.get();
        Map<String, Long> readTimestampsByProfileId = readStore.getTimestampsByProfileId();
        assertThat(readTimestampsByProfileId.get("A")).isEqualTo(1L);
        assertThat(readTimestampsByProfileId.get("B")).isEqualTo(2L);
        assertThat(readTimestampsByProfileId.get("C")).isEqualTo(3L);

        // one corrupted file should be stored
        Path corruptedFilesAtReadPath = storageFilePath.getParent().resolve("corruptedFilesAtRead");
        long count;
        try (var stream = Files.list(corruptedFilesAtReadPath)) {
            count = stream
                    .filter(Files::isRegularFile)
                    .map(Path::getFileName)
                    .map(Path::toString)
                    .filter(name -> name.startsWith(storageFilePath.getFileName().toString()))
                    .count();
        }
        assertEquals(1, count, "Expected exactly one file starting with " + storageFilePath.getFileName());
    }
}
