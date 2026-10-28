package dev.codex.lottery.service;

import dev.codex.lottery.LotteryPlugin;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

public final class DatabaseStorageService {

    private final LotteryPlugin plugin;
    private final Logger logger;
    private Connection connection;
    private volatile String status = "disabled";
    private final Map<String, Map<String, List<Object>>> storedRows = new HashMap<>();
    private final Map<String, Map<String, List<Object>>> pendingRows = new HashMap<>();

    public DatabaseStorageService(LotteryPlugin plugin) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
    }

    DatabaseStorageService(Connection connection, Logger logger) throws SQLException {
        this.plugin = null;
        this.connection = connection;
        this.logger = logger;
        createSchema();
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
                    + "?useSSL=false&characterEncoding=utf8&serverTimezone=UTC&connectTimeout=5000&socketTimeout=10000";
                connection = DriverManager.getConnection(url, user, password);
            } else {
                status = "unknown storage type: " + type;
                logger.warning("Unknown storage.type '" + type + "'. Falling back to YAML.");
                return false;
            }

            createSchema();
            status = type + " connected";
            return true;
        } catch (ClassNotFoundException | SQLException exception) {
            status = type + " failed: " + exception.getMessage();
            logger.warning("Could not connect lottery database storage: " + exception.getMessage());
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
        if (!isConnected() || (targetFile.exists() && targetFile.length() > 0)) {
            return;
        }

        String content = loadSnapshot(name);
        if (content == null || content.isBlank()) {
            return;
        }

        try {
            JournaledYamlStorage.writeAtomic(targetFile.toPath(), content);
        } catch (IOException exception) {
            logger.warning("Could not restore " + name + " from database: " + exception.getMessage());
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
            storedRows.putAll(pendingRows);
            pendingRows.clear();
            connection.setAutoCommit(previousAutoCommit);
        } catch (SQLException exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
                // Best-effort rollback; the snapshot remains the authoritative fallback.
            }
            logger.warning("Could not save structured lottery database data: " + exception.getMessage());
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
                // Ignore recovery failure.
            }
            pendingRows.clear();
            throw new IllegalStateException("Could not save structured lottery database data", exception);
        }
    }

    public void close() {
        storedRows.clear();
        pendingRows.clear();
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
        Map<String, List<Object>> rows = new LinkedHashMap<>();
        ConfigurationSection ticketsSection = dataConfig.getConfigurationSection("round.tickets");
        if (ticketsSection != null) {
            for (String playerId : ticketsSection.getKeys(false)) {
                rows.put(playerId, List.of(playerId, ticketsSection.getInt(playerId, 0)));
            }
        }
        synchronizeRows("craftplay_lottery_round_tickets", List.of("player_uuid", "tickets"), rows);
    }

    private void replaceStatistics(YamlConfiguration dataConfig) throws SQLException {
        Map<String, List<Object>> rows = new LinkedHashMap<>();
        ConfigurationSection statisticsSection = dataConfig.getConfigurationSection("statistics");
        if (statisticsSection != null) {
            for (String playerId : statisticsSection.getKeys(false)) {
                String path = playerId + ".";
                rows.put(playerId, Arrays.asList(playerId, statisticsSection.getString(path + "name", "Unknown"),
                    statisticsSection.getInt(path + "tickets-bought", 0), statisticsSection.getDouble(path + "money-spent", 0),
                    statisticsSection.getInt(path + "wins", 0), statisticsSection.getDouble(path + "highest-win", 0),
                    statisticsSection.getDouble(path + "total-won", 0), statisticsSection.getInt(path + "rounds-played", 0),
                    statisticsSection.getString(path + "last-purchase-at"), statisticsSection.getString(path + "last-win-at")));
            }
        }
        synchronizeRows("craftplay_lottery_player_stats", List.of("player_uuid", "player_name", "tickets_bought",
            "money_spent", "wins", "highest_win", "total_won", "rounds_played", "last_purchase_at", "last_win_at"), rows);
    }

    private void replaceHistory(YamlConfiguration dataConfig) throws SQLException {
        Map<String, List<Object>> rows = new LinkedHashMap<>();
        ConfigurationSection historySection = dataConfig.getConfigurationSection("history");
        if (historySection != null) {
            for (String index : historySection.getKeys(false)) {
                String path = index + ".";
                rows.put(index, List.of(Integer.parseInt(index), historySection.getString(path + "uuid", ""),
                    historySection.getString(path + "name", "Unknown"), historySection.getDouble(path + "amount", 0),
                    historySection.getString(path + "won-at", ""), historySection.getInt(path + "tickets-bought", 0)));
            }
        }
        synchronizeRows("craftplay_lottery_winner_history", List.of("position_index", "player_uuid", "player_name",
            "amount", "won_at", "tickets_bought"), rows);
    }

    private void replacePendingPayments(YamlConfiguration dataConfig) throws SQLException {
        Map<String, List<Object>> rows = new LinkedHashMap<>();
        ConfigurationSection paymentsSection = dataConfig.getConfigurationSection("pending-payments");
        if (paymentsSection != null) {
            for (String index : paymentsSection.getKeys(false)) {
                String path = index + ".";
                String id = paymentsSection.getString(path + "id", index);
                rows.put(id, List.of(id, paymentsSection.getString(path + "uuid", ""),
                    paymentsSection.getDouble(path + "amount", 0), paymentsSection.getString(path + "reason", "unknown"),
                    paymentsSection.getInt(path + "attempts", 0), paymentsSection.getString(path + "last-attempt", "")));
            }
        }
        synchronizeRows("craftplay_lottery_pending_payments", List.of("payment_id", "player_uuid", "amount", "reason",
            "attempts", "last_attempt"), rows);
    }

    private void synchronizeRows(String table, List<String> columns, Map<String, List<Object>> rows) throws SQLException {
        Map<String, List<Object>> previous = storedRows.get(table);
        if (previous == null) {
            previous = new HashMap<>();
            try (Statement query = connection.createStatement();
                 ResultSet result = query.executeQuery("SELECT " + String.join(",", columns) + " FROM " + table)) {
                while (result.next()) {
                    List<Object> values = new ArrayList<>();
                    for (int index = 1; index <= columns.size(); index++) {
                        values.add(result.getObject(index));
                    }
                    previous.put(String.valueOf(values.get(0)), values);
                }
            }
            storedRows.put(table, previous);
        }
        Set<String> changed = new HashSet<>();
        for (Map.Entry<String, List<Object>> row : rows.entrySet()) {
            if (!sameRow(previous.get(row.getKey()), row.getValue())) {
                changed.add(row.getKey());
            }
        }
        try (PreparedStatement delete = connection.prepareStatement("DELETE FROM " + table + " WHERE " + columns.get(0) + " = ?")) {
            for (String key : previous.keySet()) {
                if (!rows.containsKey(key) || changed.contains(key)) {
                    delete.setObject(1, previous.get(key).get(0));
                    delete.addBatch();
                }
            }
            delete.executeBatch();
        }
        if (!changed.isEmpty()) {
            String parameters = String.join(",", java.util.Collections.nCopies(columns.size(), "?"));
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO " + table + " ("
                + String.join(",", columns) + ") VALUES (" + parameters + ")")) {
                for (String key : changed) {
                    List<Object> row = rows.get(key);
                    for (int index = 0; index < row.size(); index++) {
                        insert.setObject(index + 1, row.get(index));
                    }
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
        pendingRows.put(table, rows);
    }

    private boolean sameRow(List<Object> left, List<Object> right) {
        if (left == null || left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            Object a = left.get(index);
            Object b = right.get(index);
            if (a instanceof Number x && b instanceof Number y) {
                if (Double.compare(x.doubleValue(), y.doubleValue()) != 0) {
                    return false;
                }
            } else if (!Objects.equals(a, b)) {
                return false;
            }
        }
        return true;
    }

    private String loadSnapshot(String name) {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT content FROM craftplay_lottery_snapshots WHERE name = ?")) {
            statement.setString(1, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("content") : null;
            }
        } catch (SQLException exception) {
            logger.warning("Could not load " + name + " from database: " + exception.getMessage());
            return null;
        }
    }

    public void saveSnapshot(String name, String content) {
        if (!isConnected()) {
            return;
        }
        try (PreparedStatement update = connection.prepareStatement(
            "UPDATE craftplay_lottery_snapshots SET content = ?, updated_at = ? WHERE name = ?")) {
            update.setString(1, content);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, name);
            if (update.executeUpdate() > 0) {
                return;
            }
        } catch (SQLException exception) {
            logger.warning("Could not update " + name + " in database: " + exception.getMessage());
            throw new IllegalStateException("Could not update lottery database snapshot", exception);
        }

        try (PreparedStatement insert = connection.prepareStatement(
            "INSERT INTO craftplay_lottery_snapshots (name, content, updated_at) VALUES (?, ?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, content);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException exception) {
            logger.warning("Could not insert " + name + " into database: " + exception.getMessage());
            throw new IllegalStateException("Could not insert lottery database snapshot", exception);
        }
    }
}
