package dev.codex.lottery.service;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JournaledYamlStorageTest {

    @TempDir Path folder;

    private JournaledYamlStorage open(boolean failMirror) throws IOException {
        return new JournaledYamlStorage(folder, Map.of("data", folder.resolve("data.yml"),
            "transactions", folder.resolve("transactions.yml")), (name, config) -> {
                if (failMirror) {
                    throw new IllegalStateException("Simulated unavailable database");
                }
            }, Logger.getAnonymousLogger());
    }

    @Test
    void detachedSnapshotsAndFlushPreserveLatestStateAndDeletions() throws Exception {
        YamlConfiguration data = new YamlConfiguration();
        data.set("statistics.player.tickets", 1);
        data.set("pending.messages", List.of("first"));
        try (JournaledYamlStorage storage = open(false)) {
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            Map<String, Object> original = storage.capture().get("data");
            data.set("statistics.player.tickets", 2);
            data.set("pending.messages", null);
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            data.set("statistics.player.tickets", 999);
            storage.flush();
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(folder.resolve("data.yml").toFile());
            assertEquals(2, disk.getInt("statistics.player.tickets"));
            YamlConfiguration exported = new YamlConfiguration();
            exported.loadFromString(JournaledYamlStorage.serializeSnapshot(original));
            assertEquals(1, exported.getInt("statistics.player.tickets"));
            assertFalse(disk.contains("pending.messages"));
            assertEquals(0, Files.size(folder.resolve("persistence.wal")));
        }
        try (JournaledYamlStorage reloaded = open(false)) {
            assertEquals(2, reloaded.read("data").getInt("statistics.player.tickets"));
        }
    }

    @Test
    void recoversCommittedTicketsAndTransactionsWhenCheckpointFails() throws Exception {
        YamlConfiguration data = new YamlConfiguration();
        data.set("round.tickets.player", 3);
        YamlConfiguration transactions = new YamlConfiguration();
        transactions.set("entries.purchase.amount", -750.0D);
        JournaledYamlStorage storage = open(true);
        storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data),
            "transactions", JournaledYamlStorage.snapshot(transactions)));
        assertThrows(IllegalStateException.class, storage::close);
        assertTrue(Files.size(folder.resolve("persistence.wal")) > 0);
        Files.deleteIfExists(folder.resolve("data.yml"));
        Files.deleteIfExists(folder.resolve("transactions.yml"));
        try (JournaledYamlStorage recovered = open(false)) {
            assertEquals(3, recovered.read("data").getInt("round.tickets.player"));
            assertEquals(-750.0D, recovered.read("transactions").getDouble("entries.purchase.amount"));
        }
    }

    @Test
    void ignoresIncompleteFinalRecordButRejectsCompleteCorruption() throws Exception {
        try (JournaledYamlStorage storage = open(false)) {
            YamlConfiguration data = new YamlConfiguration();
            data.set("round.tickets.player", 1);
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
        }
        Files.write(folder.resolve("persistence.wal"), new byte[] {0, 0, 1}, StandardOpenOption.APPEND);
        try (JournaledYamlStorage recovered = open(false)) {
            assertEquals(1, recovered.read("data").getInt("round.tickets.player"));
        }
        Files.write(folder.resolve("persistence.wal"), new byte[] {0, 0, 0, 1, 0, 0, 0, 0, 42});
        assertThrows(IOException.class, () -> open(false));
    }

    @Test
    void aSinglePurchaseJournalsOnlyChangedValuesDespiteLargeHistory() throws Exception {
        YamlConfiguration data = new YamlConfiguration();
        for (int player = 0; player < 1000; player++) {
            data.set("statistics.player" + player + ".tickets-bought", 100);
            data.set("statistics.player" + player + ".money-spent", 25000);
        }
        try (JournaledYamlStorage storage = open(false)) {
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            storage.flush();
            long fullSize = Files.size(folder.resolve("data.yml"));
            data.set("statistics.player500.tickets-bought", 101);
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            long deltaSize = Files.size(folder.resolve("persistence.wal"));
            assertTrue(deltaSize > 0 && deltaSize < 512, "A one-field change must not journal the full history");
            assertTrue(fullSize > deltaSize * 20);
        }
    }

    @Test
    void changesCommittedDuringCheckpointAreNotLostByJournalCompaction() throws Exception {
        var writing = new java.util.concurrent.CountDownLatch(1);
        var resume = new java.util.concurrent.CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        try (JournaledYamlStorage storage = new JournaledYamlStorage(folder,
            Map.of("data", folder.resolve("data.yml")), (name, config) -> {
                if (first.getAndSet(false)) {
                    writing.countDown();
                    try {
                        if (!resume.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                            throw new IllegalStateException("Test did not release checkpoint");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }
            }, Logger.getAnonymousLogger())) {
            YamlConfiguration data = new YamlConfiguration();
            data.set("tickets", 1);
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            storage.executeAfterFlush(() -> { });
            assertTrue(writing.await(5, java.util.concurrent.TimeUnit.SECONDS));
            data.set("tickets", 2);
            storage.commit(Map.of("data", JournaledYamlStorage.snapshot(data)));
            resume.countDown();
            storage.flush();
            assertEquals(2, YamlConfiguration.loadConfiguration(folder.resolve("data.yml").toFile()).getInt("tickets"));
            assertEquals(0, Files.size(folder.resolve("persistence.wal")));
        } finally {
            resume.countDown();
        }
    }
}
