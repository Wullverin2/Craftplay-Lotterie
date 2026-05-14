package dev.codex.lottery.service;

import dev.codex.lottery.LotteryPlugin;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class DatabaseStorageService {

    private final LotteryPlugin plugin;
    private Connection connection;
    private String status = "disabled";

    public DatabaseStorageService(LotteryPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean connect() {
        close();
        String type = plugin.getConfig().getString("storage.type", "yaml").toLowerCase();
        if (type.equals("yaml")) {
            status = "yaml";
            return false;
        }

        try {
            if (type.equals("sqlite")) {
                Class.forName("org.sqlite.JDBC");
                File databaseFile = new File(plugin.getDataFolder(), plugin.getConfig().getString("storage.sqlite.file", "storage.db"));
                connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.getAbsolutePath());
            } else if (type.equals("mysql")) {
                Class.forName("com.mysql.cj.jdbc.Driver");
                String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
                int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String database = plugin.getConfig().getString("storage.mysql.database", "craftplay_lottery");
                String user = plugin.getConfig().getString("storage.mysql.username", "root");
                String password = plugin.getConfig().getString("storage.mysql.password", "");
                String url = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC";
                connection = DriverManager.getConnection(url, user, password);
            } else {
                status = "unknown storage type: " + type;
                plugin.getLogger().warning("Unknown storage.type '" + type + "'. Falling back to YAML.");
                return false;
            }

            createSchema();
            status = type + " connected";
            return true;
        } catch (ClassNotFoundException | SQLException exception) {
            status = type + " failed: " + exception.getMessage();
            plugin.getLogger().warning("Could not connect lottery database storage: " + exception.getMessage());
            connection = null;
            return false;
        }
    }

    public boolean isConnected() {
        return connection != null;
    }

    public String getStatus() {
        return status;
    }

    public void restoreSnapshot(String name, File targetFile) {
        if (!isConnected()) {
            return;
        }

        String content = loadSnapshot(name);
        if (content == null || content.isBlank()) {
            return;
        }

        try {
            Files.writeString(targetFile.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not restore " + name + " from database: " + exception.getMessage());
        }
    }

    public void saveSnapshot(String name, File sourceFile) {
        if (!isConnected() || !sourceFile.exists()) {
            return;
        }

        try {
            saveSnapshot(name, Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8));
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not read " + sourceFile.getName() + " for database storage: " + exception.getMessage());
        }
    }

    public void saveStructuredData(YamlConfiguration dataConfig) {
        if (!isConnected()) {
            return;
        }

        boolean previousAutoCommit;
        try {
            previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            replaceRoundTickets(dataConfig);
            replaceStatistics(dataConfig);
            replaceHistory(dataConfig);
            replacePendingPayments(dataConfig);
            connection.commit();
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Best-effort rollback; the snapshot remains the authoritative fallback.
            }
            plugin.getLogger().warning("Could not save structured lottery database data: " + exception.getMessage());
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Ignore recovery failure.
            }
        }
    }

    public void close() {
        if (connection == null) {
            return;
        }

        try {
            connection.close();
        } catch (SQLException ignored) {
            // Closing during plugin shutdown should never block disable.
        } finally {
            connection = null;
        }
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS craftplay_lottery_snapshots (
                    name VARCHAR(64) PRIMARY KEY,
                    content LONGTEXT NOT NULL,
                    updated_at BIGINT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS craftplay_lottery_round_tickets (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    tickets INT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS craftplay_lottery_player_stats (
                    player_uuid VARCHAR(36) PRIMARY KEY,
                    player_name VARCHAR(64) NOT NULL,
                    tickets_bought INT NOT NULL,
                    money_spent DOUBLE NOT NULL,
                    wins INT NOT NULL,
                    highest_win DOUBLE NOT NULL,
                    total_won DOUBLE NOT NULL,
                    rounds_played INT NOT NULL,
                    last_purchase_at VARCHAR(32),
                    last_win_at VARCHAR(32)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS craftplay_lottery_winner_history (
                    position_index INT PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    player_name VARCHAR(64) NOT NULL,
                    amount DOUBLE NOT NULL,
                    won_at VARCHAR(32) NOT NULL,
                    tickets_bought INT NOT NULL
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS craftplay_lottery_pending_payments (
                    payment_id VARCHAR(64) PRIMARY KEY,
                    player_uuid VARCHAR(36) NOT NULL,
                    amount DOUBLE NOT NULL,
                    reason VARCHAR(32) NOT NULL,
                    attempts INT NOT NULL,
                    last_attempt VARCHAR(32)
                )
                """);
        }
    }

    private void replaceRoundTickets(YamlConfiguration dataConfig) throws SQLException {
        deleteAll("craftplay_lottery_round_tickets");
        ConfigurationSection ticketsSection = dataConfig.getConfigurationSection("round.tickets");
        if (ticketsSection == null) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement(
            "INSERT INTO craftplay_lottery_round_tickets (player_uuid, tickets) VALUES (?, ?)")) {
            for (String playerId : ticketsSection.getKeys(false)) {
                statement.setString(1, playerId);
                statement.setInt(2, ticketsSection.getInt(playerId, 0));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceStatistics(YamlConfiguration dataConfig) throws SQLException {
        deleteAll("craftplay_lottery_player_stats");
        ConfigurationSection statisticsSection = dataConfig.getConfigurationSection("statistics");
        if (statisticsSection == null) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO craftplay_lottery_player_stats
            (player_uuid, player_name, tickets_bought, money_spent, wins, highest_win, total_won, rounds_played, last_purchase_at, last_win_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """)) {
            for (String playerId : statisticsSection.getKeys(false)) {
                String path = playerId + ".";
                statement.setString(1, playerId);
                statement.setString(2, statisticsSection.getString(path + "name", "Unknown"));
                statement.setInt(3, statisticsSection.getInt(path + "tickets-bought", 0));
                statement.setDouble(4, statisticsSection.getDouble(path + "money-spent", 0.0D));
                statement.setInt(5, statisticsSection.getInt(path + "wins", 0));
                statement.setDouble(6, statisticsSection.getDouble(path + "highest-win", 0.0D));
                statement.setDouble(7, statisticsSection.getDouble(path + "total-won", 0.0D));
                statement.setInt(8, statisticsSection.getInt(path + "rounds-played", 0));
                statement.setString(9, statisticsSection.getString(path + "last-purchase-at"));
                statement.setString(10, statisticsSection.getString(path + "last-win-at"));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replaceHistory(YamlConfiguration dataConfig) throws SQLException {
        deleteAll("craftplay_lottery_winner_history");
        ConfigurationSection historySection = dataConfig.getConfigurationSection("history");
        if (historySection == null) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO craftplay_lottery_winner_history
            (position_index, player_uuid, player_name, amount, won_at, tickets_bought)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            for (String index : historySection.getKeys(false)) {
                String path = index + ".";
                statement.setInt(1, Integer.parseInt(index));
                statement.setString(2, historySection.getString(path + "uuid", ""));
                statement.setString(3, historySection.getString(path + "name", "Unknown"));
                statement.setDouble(4, historySection.getDouble(path + "amount", 0.0D));
                statement.setString(5, historySection.getString(path + "won-at", ""));
                statement.setInt(6, historySection.getInt(path + "tickets-bought", 0));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void replacePendingPayments(YamlConfiguration dataConfig) throws SQLException {
        deleteAll("craftplay_lottery_pending_payments");
        ConfigurationSection paymentsSection = dataConfig.getConfigurationSection("pending-payments");
        if (paymentsSection == null) {
            return;
        }

        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO craftplay_lottery_pending_payments
            (payment_id, player_uuid, amount, reason, attempts, last_attempt)
            VALUES (?, ?, ?, ?, ?, ?)
            """)) {
            for (String index : paymentsSection.getKeys(false)) {
                String path = index + ".";
                statement.setString(1, paymentsSection.getString(path + "id", index));
                statement.setString(2, paymentsSection.getString(path + "uuid", ""));
                statement.setDouble(3, paymentsSection.getDouble(path + "amount", 0.0D));
                statement.setString(4, paymentsSection.getString(path + "reason", "unknown"));
                statement.setInt(5, paymentsSection.getInt(path + "attempts", 0));
                statement.setString(6, paymentsSection.getString(path + "last-attempt", ""));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void deleteAll(String table) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM " + table);
        }
    }

    private String loadSnapshot(String name) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT content FROM craftplay_lottery_snapshots WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("content") : null;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not load " + name + " from database: " + exception.getMessage());
            return null;
        }
    }

    private void saveSnapshot(String name, String content) {
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE craftplay_lottery_snapshots SET content = ?, updated_at = ? WHERE name = ?")) {
            update.setString(1, content);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, name);
            if (update.executeUpdate() > 0) {
                return;
            }
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not update " + name + " in database: " + exception.getMessage());
            return;
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO craftplay_lottery_snapshots (name, content, updated_at) VALUES (?, ?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, content);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not insert " + name + " into database: " + exception.getMessage());
        }
    }
}
