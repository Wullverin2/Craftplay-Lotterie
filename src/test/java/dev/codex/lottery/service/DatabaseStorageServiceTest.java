package dev.codex.lottery.service;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.logging.Logger;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

class DatabaseStorageServiceTest {

    @org.junit.jupiter.api.io.TempDir java.nio.file.Path folder;

    @Test
    void databaseFallbackDoesNotOverwriteNewerLocalCheckpoint() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseStorageService storage = new DatabaseStorageService(connection, Logger.getAnonymousLogger());
            storage.saveSnapshot("data", "tickets: 1\n");
            java.nio.file.Path local = folder.resolve("data.yml");
            JournaledYamlStorage.writeAtomic(local, "tickets: 2\n");
            storage.restoreSnapshot("data", local.toFile());
            assertEquals(2, YamlConfiguration.loadConfiguration(local.toFile()).getInt("tickets"));
            java.nio.file.Files.delete(local);
            storage.restoreSnapshot("data", local.toFile());
            assertEquals(1, YamlConfiguration.loadConfiguration(local.toFile()).getInt("tickets"));
        }
    }

    @Test
    void updatesOnlyChangedRowsAndRemovesDeletedTickets() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseStorageService storage = new DatabaseStorageService(connection, Logger.getAnonymousLogger());
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE writes (operation TEXT)");
                statement.execute("CREATE TRIGGER ticket_insert AFTER INSERT ON craftplay_lottery_round_tickets BEGIN INSERT INTO writes VALUES ('insert'); END");
                statement.execute("CREATE TRIGGER ticket_delete AFTER DELETE ON craftplay_lottery_round_tickets BEGIN INSERT INTO writes VALUES ('delete'); END");
            }
            YamlConfiguration config = new YamlConfiguration();
            config.set("round.tickets.alex", 1);
            config.set("round.tickets.steve", 1);
            storage.saveStructuredData(config);
            assertEquals(2, count(connection, "writes"));
            storage.saveStructuredData(config);
            assertEquals(2, count(connection, "writes"));
            config.set("round.tickets.alex", 2);
            storage.saveStructuredData(config);
            assertEquals(4, count(connection, "writes"));
            config.set("round.tickets.steve", null);
            storage.saveStructuredData(config);
            assertEquals(5, count(connection, "writes"));
            assertEquals(1, count(connection, "craftplay_lottery_round_tickets"));
        }
    }

    @Test
    void failedBatchRollsBackAndCanBeRetried() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            DatabaseStorageService storage = new DatabaseStorageService(connection, Logger.getAnonymousLogger());
            YamlConfiguration config = new YamlConfiguration();
            config.set("round.tickets.alex", 1);
            storage.saveStructuredData(config);
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TRIGGER reject_ticket BEFORE INSERT ON craftplay_lottery_round_tickets BEGIN SELECT RAISE(ABORT, 'test failure'); END");
            }
            config.set("round.tickets.alex", 2);
            assertThrows(IllegalStateException.class, () -> storage.saveStructuredData(config));
            assertEquals(1, count(connection, "craftplay_lottery_round_tickets"));
            try (var statement = connection.createStatement()) {
                statement.execute("DROP TRIGGER reject_ticket");
            }
            storage.saveStructuredData(config);
            try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT tickets FROM craftplay_lottery_round_tickets WHERE player_uuid = 'alex'")) {
                assertTrue(result.next());
                assertEquals(2, result.getInt(1));
            }
        }
    }

    private int count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }
}
