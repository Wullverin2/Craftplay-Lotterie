package dev.codex.lottery.service;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.model.LotteryRound;
import dev.codex.lottery.model.PlayerLotteryStats;
import dev.codex.lottery.model.WinnerEntry;
import dev.codex.lottery.util.MessageUtil;
import dev.codex.lottery.util.TimeUtil;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.WeekFields;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.function.DoubleFunction;
import java.util.function.LongFunction;
import java.util.function.ToDoubleFunction;
import java.util.function.ToLongFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.Statistic;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.scheduler.BukkitTask;

public final class LotteryManager {

    private static final int HOLOGRAMS_PER_PAGE = 5;
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();
    private static final DateTimeFormatter WINNER_DATE_FORMAT =
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    private final LotteryPlugin plugin;
    private final EconomyService economyService;
    private final Map<String, LotteryRound> lotteryRounds = new HashMap<>();
    private final Map<String, List<WinnerEntry>> winnerHistories = new HashMap<>();
    private final Map<UUID, PlayerLotteryStats> playerStats = new HashMap<>();
    private final Map<UUID, PlayerLotteryStats> seasonStats = new HashMap<>();
    private final Map<UUID, List<PendingNotification>> pendingNotifications = new HashMap<>();
    private final Map<UUID, PendingPurchase> pendingPurchases = new HashMap<>();
    private final Map<UUID, PendingAdminAction> pendingAdminActions = new HashMap<>();
    private final Map<UUID, DailyUsage> dailyUsage = new HashMap<>();
    private final Map<UUID, Map<String, Long>> freeTicketClaims = new HashMap<>();
    private final Map<UUID, Integer> seasonPoints = new HashMap<>();
    private final Map<UUID, String> playerLotterySelections = new HashMap<>();
    private final Map<UUID, Map<String, Boolean>> notificationPreferences = new HashMap<>();
    private final Map<String, Boolean> potReminderSent = new HashMap<>();
    private final Map<String, Boolean> ticketHolderReminderSent = new HashMap<>();
    private final Map<UUID, PurchaseWindow> purchaseWindows = new HashMap<>();
    private final Map<UUID, Long> purchaseCooldowns = new HashMap<>();
    private final List<PendingPayment> pendingPayments = new ArrayList<>();
    private final Random random = new SecureRandom();
    private final File dataFile;
    private final File logFile;
    private final File transactionFile;
    private final DatabaseStorageService databaseStorage;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private YamlConfiguration dataConfig;
    private YamlConfiguration logConfig;
    private YamlConfiguration transactionConfig;
    private BukkitTask schedulerTask;
    private BukkitTask hologramTask;
    private BukkitTask announcementTask;
    private BukkitTask paymentRetryTask;
    private final Map<String, TextDisplay> hologramEntities = new HashMap<>();
    private String lastDrawKey;
    private final Map<String, String> lastDrawKeys = new HashMap<>();
    private String operationLotteryId;
    private String seasonId;
    private double totalTaxCollected;

    public LotteryManager(LotteryPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.logFile = new File(plugin.getDataFolder(), "admin-log.yml");
        this.transactionFile = new File(plugin.getDataFolder(), "transactions.yml");
        this.databaseStorage = new DatabaseStorageService(plugin);
    }

    public void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("Could not create plugin data folder.");
        }

        if (!dataFile.exists()) {
            try {
                if (!dataFile.createNewFile()) {
                    plugin.getLogger().warning("Could not create data.yml.");
                }
            } catch (IOException exception) {
                plugin.getLogger().severe("Could not create data.yml: " + exception.getMessage());
            }
        }

        databaseStorage.connect();
        databaseStorage.restoreSnapshot("data", dataFile);
        databaseStorage.restoreSnapshot("admin-log", logFile);
        databaseStorage.restoreSnapshot("transactions", transactionFile);

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        logConfig = YamlConfiguration.loadConfiguration(logFile);
        transactionConfig = YamlConfiguration.loadConfiguration(transactionFile);
        loadRound();
        loadHistory();
        loadStatistics();
        loadSeasonStatistics();
        loadDailyUsage();
        loadFreeTicketClaims();
        loadSeasonPoints();
        loadPlayerLotterySelections();
        loadNotificationPreferences();
        loadPendingNotifications();
        loadPendingPayments();
        loadMetaStats();
        loadDrawKeys();
    }

    private void loadDrawKeys() {
        lastDrawKeys.clear();
        lastDrawKey = dataConfig.getString("last-draw-key");

        String storedDate = dataConfig.getString("last-draw-date");
        if ((lastDrawKey == null || lastDrawKey.isBlank()) && storedDate != null && !storedDate.isBlank()) {
            lastDrawKey = storedDate + " 00:00";
        }
        if (lastDrawKey != null && !lastDrawKey.isBlank()) {
            lastDrawKeys.put("default", lastDrawKey);
        }

        ConfigurationSection drawKeysSection = dataConfig.getConfigurationSection("last-draw-keys");
        if (drawKeysSection != null) {
            for (String lotteryId : drawKeysSection.getKeys(false)) {
                lastDrawKeys.put(normalizeLotteryId(lotteryId), drawKeysSection.getString(lotteryId, ""));
            }
        }
    }

    public void reload() {
        save();
        load();
    }

    public void shutdown() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        if (hologramTask != null) {
            hologramTask.cancel();
        }
        if (announcementTask != null) {
            announcementTask.cancel();
        }
        if (paymentRetryTask != null) {
            paymentRetryTask.cancel();
        }
        removeHologram();
        save();
        databaseStorage.close();
    }

    public void startScheduler() {
        if (schedulerTask != null) {
            schedulerTask.cancel();
        }
        schedulerTask = Bukkit.getScheduler().runTaskTimer(plugin, this::checkDraw, 20L, 20L * 30L);
        startHologramTask();
        startAnnouncementTask();
        startPaymentRetryTask();
    }

    private LotteryRound currentRound() {
        return lotteryRounds.computeIfAbsent(getActiveLotteryId(), ignored -> new LotteryRound());
    }

    private LotteryRound roundFor(String lotteryId) {
        return lotteryRounds.computeIfAbsent(normalizeLotteryId(lotteryId), ignored -> new LotteryRound());
    }

    private List<WinnerEntry> winnerHistory() {
        return winnerHistories.computeIfAbsent(getActiveLotteryId(), ignored -> new ArrayList<>());
    }

    private List<WinnerEntry> winnerHistoryFor(String lotteryId) {
        return winnerHistories.computeIfAbsent(normalizeLotteryId(lotteryId), ignored -> new ArrayList<>());
    }

    private String normalizeLotteryId(String lotteryId) {
        if (lotteryId == null || lotteryId.isBlank()) {
            return "default";
        }
        String normalized = lotteryId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return normalized.isBlank() ? "default" : normalized;
    }

    private <T> T withLotteryContext(String lotteryId, java.util.function.Supplier<T> supplier) {
        String previousLotteryId = operationLotteryId;
        operationLotteryId = normalizeLotteryId(lotteryId);
        try {
            return supplier.get();
        } finally {
            operationLotteryId = previousLotteryId;
        }
    }

    private <T> T withPlayerLotteryContext(Player player, java.util.function.Supplier<T> supplier) {
        if (player == null || operationLotteryId != null) {
            return supplier.get();
        }
        return withLotteryContext(getSelectedLotteryId(player), supplier);
    }

    public void showStatus(CommandSender sender) {
        if (sender instanceof Player player && operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                showStatus(sender);
                return null;
            });
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.status", createCommonPlaceholders(sender instanceof Player player ? player : null));
        if (sender instanceof Player player) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.your-tickets", createCommonPlaceholders(player));
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.your-chance", createCommonPlaceholders(player));
        }
    }

    public void showWinners(CommandSender sender) {
        if (sender instanceof Player player && operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                showWinners(sender);
                return null;
            });
            return;
        }
        if (winnerHistory().isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-winners");
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.winners-header");
        int limit = Math.min(winnerHistory().size(), plugin.getConfig().getInt("settings.history-size", 10));
        for (int index = 0; index < limit; index++) {
            WinnerEntry entry = winnerHistory().get(index);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.winner-entry", Map.of(
                "player", entry.playerName(),
                "amount", economyService.format(entry.amount()),
                "date", entry.wonAt().format(WINNER_DATE_FORMAT),
                "tickets", String.valueOf(entry.ticketsBought())
            ));
        }
    }

    public void showStats(CommandSender sender) {
        if (sender instanceof Player player && operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                showStats(sender);
                return null;
            });
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.stats", createCommonPlaceholders(sender instanceof Player player ? player : null));
    }

    public void showNextDraw(CommandSender sender) {
        if (sender instanceof Player player && operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                showNextDraw(sender);
                return null;
            });
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.next-draw", createCommonPlaceholders(sender instanceof Player player ? player : null));
    }

    public void showPlayerInfo(CommandSender sender, OfflinePlayer target) {
        Map<String, String> placeholders = createCommonPlaceholders(null);
        placeholders.put("player", target.getName() != null ? target.getName() : target.getUniqueId().toString());
        placeholders.put("player_tickets", String.valueOf(getTicketsFor(target.getUniqueId())));
        placeholders.put("player_chance", formatChance(getWinChance(target.getUniqueId())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-info", placeholders);
    }

    public void sendPendingNotifications(Player player) {
        retryPendingPayments(player.getUniqueId());
        List<PendingNotification> notifications = pendingNotifications.remove(player.getUniqueId());
        if (notifications == null || notifications.isEmpty()) {
            return;
        }

        for (PendingNotification notification : notifications) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), notification.messagePath(), notification.placeholders());
        }
        save();
    }

    public void listPendingNotifications(CommandSender sender, OfflinePlayer target) {
        if (target != null) {
            List<PendingNotification> notifications = pendingNotifications.getOrDefault(target.getUniqueId(), List.of());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-list-player", Map.of(
                "player", target.getName() != null ? target.getName() : target.getUniqueId().toString(),
                "amount", String.valueOf(notifications.size())
            ));
            return;
        }

        if (pendingNotifications.isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-list-empty");
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-list-header", Map.of(
            "amount", String.valueOf(getPendingNotificationCount())
        ));
        pendingNotifications.entrySet().stream()
            .sorted(Comparator.comparing(entry -> getCachedPlayerName(entry.getKey()), String.CASE_INSENSITIVE_ORDER))
            .limit(10)
            .forEach(entry -> MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-list-entry", Map.of(
                "player", getCachedPlayerName(entry.getKey()),
                "uuid", entry.getKey().toString(),
                "amount", String.valueOf(entry.getValue().size())
            )));
    }

    public void clearPendingNotifications(CommandSender sender, OfflinePlayer target, boolean all) {
        int removed;
        if (all) {
            removed = getPendingNotificationCount();
            pendingNotifications.clear();
        } else {
            List<PendingNotification> notifications = pendingNotifications.remove(target.getUniqueId());
            removed = notifications == null ? 0 : notifications.size();
        }
        save();
        appendLog("clear_pending_notifications", Map.of("amount", String.valueOf(removed)));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-cleared", Map.of(
            "amount", String.valueOf(removed)
        ));
    }

    public void retryPayments(CommandSender sender) {
        int before = pendingPayments.size();
        retryAllPendingPayments();
        int retried = Math.max(0, before - pendingPayments.size());
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.payments-retry", Map.of(
            "retried", String.valueOf(retried),
            "remaining", String.valueOf(pendingPayments.size())
        ));
    }

    public void createBackup(CommandSender sender) {
        save();
        File backupFolder = new File(plugin.getDataFolder(), "backups");
        if (!backupFolder.exists() && !backupFolder.mkdirs()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.backup-failed");
            return;
        }

        String fileName = "lottery-backup-" + LocalDateTime.now(getZoneId()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".zip";
        File backupFile = new File(backupFolder, fileName);
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(backupFile.toPath()))) {
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "config.yml"), "config.yml");
            addFileToZip(zipOutputStream, dataFile, "data.yml");
            addFileToZip(zipOutputStream, logFile, "admin-log.yml");
            addFileToZip(zipOutputStream, transactionFile, "transactions.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "holograms.yml"), "holograms.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "lotteries.yml"), "lotteries.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "gui/gui.yml"), "gui/gui.yml");
            File languageFolder = new File(plugin.getDataFolder(), "lang");
            File[] languageFiles = languageFolder.listFiles((directory, name) -> name.endsWith(".yml"));
            if (languageFiles != null) {
                for (File languageFile : languageFiles) {
                    addFileToZip(zipOutputStream, languageFile, "lang/" + languageFile.getName());
                }
            }
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create lottery backup: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.backup-failed");
            return;
        }

        appendLog("backup", Map.of("file", backupFile.getName()));
        cleanupOldBackups(backupFolder);
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.backup-created", Map.of(
            "file", backupFile.getAbsolutePath()
        ));
    }

    private void cleanupOldBackups(File backupFolder) {
        int maxFiles = plugin.getConfig().getInt("backups.retention.max-files", 30);
        if (maxFiles <= 0) {
            return;
        }

        File[] backups = backupFolder.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".zip"));
        if (backups == null || backups.length <= maxFiles) {
            return;
        }

        List<File> sortedBackups = new ArrayList<>(List.of(backups));
        sortedBackups.sort(Comparator.comparingLong(File::lastModified).reversed());
        for (int index = maxFiles; index < sortedBackups.size(); index++) {
            try {
                Files.deleteIfExists(sortedBackups.get(index).toPath());
            } catch (IOException exception) {
                plugin.getLogger().warning("Could not delete old backup " + sortedBackups.get(index).getName()
                    + ": " + exception.getMessage());
            }
        }
    }

    public void exportData(CommandSender sender) {
        save();
        File exportFolder = new File(plugin.getDataFolder(), "exports");
        if (!exportFolder.exists() && !exportFolder.mkdirs()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-failed");
            return;
        }

        File exportFile = new File(exportFolder, "lottery-export-" + LocalDateTime.now(getZoneId()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".yml");
        try {
            Files.copy(dataFile.toPath(), exportFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not export lottery data: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-failed");
            return;
        }

        appendLog("export", Map.of("file", exportFile.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-created", Map.of(
            "file", exportFile.getAbsolutePath()
        ));
    }

    public void exportCsv(CommandSender sender) {
        save();
        File exportFolder = new File(plugin.getDataFolder(), "exports");
        if (!exportFolder.exists() && !exportFolder.mkdirs()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-failed");
            return;
        }

        String stamp = LocalDateTime.now(getZoneId()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File statsFile = new File(exportFolder, "lottery-stats-" + stamp + ".csv");
        File transactionsFile = new File(exportFolder, "lottery-transactions-" + stamp + ".csv");
        try {
            Files.writeString(statsFile.toPath(), buildStatsCsv(), StandardCharsets.UTF_8);
            Files.writeString(transactionsFile.toPath(), buildTransactionsCsv(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not export lottery CSV data: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-failed");
            return;
        }

        appendLog("export_csv", Map.of("stats", statsFile.getName(), "transactions", transactionsFile.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.export-csv-created", Map.of(
            "stats", statsFile.getAbsolutePath(),
            "transactions", transactionsFile.getAbsolutePath()
        ));
    }

    public void exportWebOverview(CommandSender sender) {
        save();
        File webFolder = new File(plugin.getDataFolder(), "web");
        if (!webFolder.exists() && !webFolder.mkdirs()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.web-export-failed");
            return;
        }

        File webFile = new File(webFolder, "index.html");
        try {
            Files.writeString(webFile.toPath(), buildWebOverviewHtml(), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not export web overview: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.web-export-failed");
            return;
        }

        appendLog("web_export", Map.of("file", webFile.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.web-export-created", Map.of(
            "file", webFile.getAbsolutePath()
        ));
    }

    public void createAuditExport(CommandSender sender) {
        save();
        File auditFolder = new File(plugin.getDataFolder(), "audit");
        if (!auditFolder.exists() && !auditFolder.mkdirs()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.audit-export-failed");
            return;
        }

        String stamp = LocalDateTime.now(getZoneId()).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        File auditFile = new File(auditFolder, "lottery-audit-" + stamp + ".zip");
        try (ZipOutputStream zipOutputStream = new ZipOutputStream(Files.newOutputStream(auditFile.toPath()))) {
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "config.yml"), "config.yml");
            addFileToZip(zipOutputStream, dataFile, "data.yml");
            addFileToZip(zipOutputStream, logFile, "admin-log.yml");
            addFileToZip(zipOutputStream, transactionFile, "transactions.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "holograms.yml"), "holograms.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "lotteries.yml"), "lotteries.yml");
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "gui/gui.yml"), "gui/gui.yml");
            File languageFolder = new File(plugin.getDataFolder(), "lang");
            File[] languageFiles = languageFolder.listFiles((directory, name) -> name.endsWith(".yml"));
            if (languageFiles != null) {
                for (File languageFile : languageFiles) {
                    addFileToZip(zipOutputStream, languageFile, "lang/" + languageFile.getName());
                }
            }
            addStringToZip(zipOutputStream, "audit-report.txt", buildAuditReport());
            addStringToZip(zipOutputStream, "web/index.html", buildWebOverviewHtml());
            addStringToZip(zipOutputStream, "stats.csv", buildStatsCsv());
            addStringToZip(zipOutputStream, "transactions.csv", buildTransactionsCsv());
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not create audit export: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.audit-export-failed");
            return;
        }

        appendLog("audit_export", Map.of("file", auditFile.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.audit-export-created", Map.of(
            "file", auditFile.getAbsolutePath()
        ));
    }

    private String buildWebOverviewHtml() {
        Map<String, String> placeholders = createCommonPlaceholders(null);
        StringBuilder winners = new StringBuilder();
        for (WinnerEntry winner : winnerHistory().stream().limit(10).toList()) {
            winners.append("<li><strong>").append(escapeHtml(winner.playerName())).append("</strong> - ")
                .append(escapeHtml(economyService.format(winner.amount()))).append(" (")
                .append(escapeHtml(winner.wonAt().format(WINNER_DATE_FORMAT))).append(")</li>");
        }
        if (winners.isEmpty()) {
            winners.append("<li>Noch keine Gewinner.</li>");
        }

        StringBuilder topTickets = new StringBuilder();
        for (TopEntry entry : getTopEntryList("tickets_bought")) {
            topTickets.append("<li><strong>").append(escapeHtml(entry.name())).append("</strong> - ")
                .append(escapeHtml(entry.value())).append("</li>");
        }
        if (topTickets.isEmpty()) {
            topTickets.append("<li>Noch keine Daten.</li>");
        }

        return """
            <!doctype html>
            <html lang="de">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1">
              <title>Craftplay Lotterie</title>
              <style>
                body{font-family:Arial,sans-serif;background:#101820;color:#f7f1e3;margin:0;padding:32px}
                .grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:16px}
                .card{background:#1d2b36;border:1px solid #315064;border-radius:16px;padding:18px}
                h1,h2{color:#ffd166} strong{color:#8bd3dd}
              </style>
            </head>
            <body>
              <h1>Craftplay Lotterie</h1>
              <div class="grid">
                <div class="card"><h2>Topf</h2><p>%pot%</p></div>
                <div class="card"><h2>Tickets</h2><p>%tickets_total%</p></div>
                <div class="card"><h2>Spieler</h2><p>%players%/%min_players%</p></div>
                <div class="card"><h2>Nächste Ziehung</h2><p>%next_draw%</p></div>
              </div>
              <div class="grid">
                <div class="card"><h2>Letzte Gewinner</h2><ol>%winners%</ol></div>
                <div class="card"><h2>Top Tickets</h2><ol>%top_tickets%</ol></div>
              </div>
            </body>
            </html>
            """
            .replace("%pot%", escapeHtml(placeholders.getOrDefault("pot", "")))
            .replace("%tickets_total%", escapeHtml(placeholders.getOrDefault("tickets_total", "0")))
            .replace("%players%", escapeHtml(placeholders.getOrDefault("players", "0")))
            .replace("%min_players%", escapeHtml(placeholders.getOrDefault("min_players", "0")))
            .replace("%next_draw%", escapeHtml(placeholders.getOrDefault("next_draw", "")))
            .replace("%winners%", winners.toString())
            .replace("%top_tickets%", topTickets.toString());
    }

    private String buildAuditReport() {
        AdminStatsSnapshot stats = collectAdminStats();
        return "Craftplay Lotterie Audit\n"
            + "Generated: " + LocalDateTime.now(getZoneId()).format(WINNER_DATE_FORMAT) + "\n"
            + "Lottery: " + getActiveLotteryDisplayName() + " (" + getActiveLotteryId() + ")\n"
            + "Pot: " + economyService.format(getTotalPot()) + "\n"
            + "Tickets current round: " + currentRound().getTotalTickets() + "\n"
            + "Players current round: " + currentRound().getUniquePlayers() + "\n"
            + "Pending notifications: " + getPendingNotificationCount() + "\n"
            + "Pending payments: " + pendingPayments.size() + "\n"
            + "Transactions today: " + stats.purchasesToday() + "\n"
            + "Revenue today: " + economyService.format(stats.ticketRevenueToday()) + "\n"
            + "Revenue week: " + economyService.format(stats.ticketRevenueWeek()) + "\n"
            + "Revenue month: " + economyService.format(stats.ticketRevenueMonth()) + "\n"
            + "Payouts month: " + economyService.format(stats.payoutsMonth()) + "\n"
            + "Taxes all time: " + economyService.format(stats.taxesAllTime()) + "\n";
    }

    private String escapeHtml(String input) {
        return input == null ? "" : input
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;");
    }

    private String buildStatsCsv() {
        StringBuilder builder = new StringBuilder("uuid,name,tickets_bought,money_spent,wins,highest_win,total_won,rounds_played,last_purchase_at,last_win_at,season_points\n");
        for (PlayerLotteryStats stats : playerStats.values().stream()
            .sorted(Comparator.comparing(stats -> stats.getPlayerName() == null ? "" : stats.getPlayerName(), String.CASE_INSENSITIVE_ORDER))
            .toList()) {
            builder.append(csv(stats.getPlayerId().toString())).append(',')
                .append(csv(stats.getPlayerName())).append(',')
                .append(stats.getTicketsBought()).append(',')
                .append(stats.getMoneySpent()).append(',')
                .append(stats.getWins()).append(',')
                .append(stats.getHighestWin()).append(',')
                .append(stats.getTotalWon()).append(',')
                .append(stats.getRoundsPlayed()).append(',')
                .append(csv(stats.getLastPurchaseAt() == null ? "" : stats.getLastPurchaseAt().toString())).append(',')
                .append(csv(stats.getLastWinAt() == null ? "" : stats.getLastWinAt().toString())).append(',')
                .append(getSeasonPoints(stats.getPlayerId())).append('\n');
        }
        return builder.toString();
    }

    private String buildTransactionsCsv() {
        StringBuilder builder = new StringBuilder("time,type,player,amount,details\n");
        ConfigurationSection entriesSection = transactionConfig.getConfigurationSection("entries");
        if (entriesSection == null) {
            return builder.toString();
        }
        for (String key : entriesSection.getKeys(false).stream().sorted().toList()) {
            String path = "entries." + key;
            builder.append(csv(transactionConfig.getString(path + ".time", ""))).append(',')
                .append(csv(transactionConfig.getString(path + ".type", ""))).append(',')
                .append(csv(transactionConfig.getString(path + ".player", ""))).append(',')
                .append(csv(transactionConfig.getString(path + ".amount", ""))).append(',')
                .append(csv(transactionConfig.getString(path + ".details", ""))).append('\n');
        }
        return builder.toString();
    }

    private String csv(String value) {
        String sanitized = value == null ? "" : value;
        return "\"" + sanitized.replace("\"", "\"\"") + "\"";
    }

    public void importData(CommandSender sender, String fileName) {
        File exportFolder = new File(plugin.getDataFolder(), "exports");
        File importFile = new File(exportFolder, fileName);
        Path exportPath = exportFolder.toPath().toAbsolutePath().normalize();
        Path importPath = importFile.toPath().toAbsolutePath().normalize();
        if (!importPath.startsWith(exportPath) || !importFile.exists() || !importFile.isFile()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.import-failed");
            return;
        }

        try {
            Files.copy(importFile.toPath(), dataFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            dataConfig = YamlConfiguration.loadConfiguration(dataFile);
            loadRound();
            loadHistory();
            loadStatistics();
            loadSeasonStatistics();
            loadDailyUsage();
            loadFreeTicketClaims();
            loadSeasonPoints();
            loadPlayerLotterySelections();
            loadNotificationPreferences();
            loadPendingNotifications();
            loadPendingPayments();
            loadMetaStats();
            loadDrawKeys();
            save();
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not import lottery data: " + exception.getMessage());
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.import-failed");
            return;
        }

        appendLog("import", Map.of("file", importFile.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.import-success", Map.of(
            "file", importFile.getName()
        ));
    }

    public void showDebug(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-header");
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Storage", "value", databaseStorage.getStatus()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Vault", "value", Bukkit.getPluginManager().isPluginEnabled("Vault") ? "enabled" : "missing"));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "PlaceholderAPI", "value", Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI") ? "enabled" : "missing"));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "HeadDatabase", "value", Bukkit.getPluginManager().isPluginEnabled("HeadDatabase") ? "enabled" : "missing"));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Next draw", "value", formatNextDraw()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Tickets", "value", String.valueOf(currentRound().getTotalTickets())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Winner shares", "value", getPrizeShares().toString()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Season", "value", getSeasonId()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Lottery profile", "value", getActiveLotteryId()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Lottery type", "value", getLotteryType()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Tax collected", "value", economyService.format(totalTaxCollected)));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending notifications", "value", String.valueOf(getPendingNotificationCount())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending payments", "value", String.valueOf(pendingPayments.size())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending confirmations", "value", String.valueOf(pendingPurchases.size())));
    }

    public void showAdminStats(CommandSender sender) {
        AdminStatsSnapshot stats = collectAdminStats();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-header");
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Heute Umsatz", "value", economyService.format(stats.ticketRevenueToday())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Woche Umsatz", "value", economyService.format(stats.ticketRevenueWeek())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Monat Umsatz", "value", economyService.format(stats.ticketRevenueMonth())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Monat Auszahlungen", "value", economyService.format(stats.payoutsMonth())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Monat Rückerstattung", "value", economyService.format(stats.refundsMonth())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Auszahlungsquote Monat", "value", formatDecimal(stats.payoutRatioMonth() * 100.0D) + "%"));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Käufe heute", "value", String.valueOf(stats.purchasesToday())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Steuern gesamt", "value", economyService.format(stats.taxesAllTime())));
    }

    public void showTaxReport(CommandSender sender) {
        AdminStatsSnapshot stats = collectAdminStats();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.tax-report-header");
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Ticket-Steuer", "value", economyService.format(stats.ticketTaxesAllTime())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Gewinn-Steuer", "value", economyService.format(stats.payoutTaxesAllTime())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Steuern gesamt", "value", economyService.format(stats.taxesAllTime())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Konfig Ticket-Steuer", "value", formatDecimal(getTaxRate() * 100.0D) + "%"));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.admin-stats-line", Map.of("key", "Konfig Gewinn-Steuer", "value", formatDecimal(plugin.getConfig().getDouble("payout-tax.rate", 0.0D) * 100.0D) + "%"));
    }

    private AdminStatsSnapshot collectAdminStats() {
        LocalDate today = LocalDate.now(getZoneId());
        LocalDate weekStart = today.minusDays(today.getDayOfWeek().getValue() - 1L);
        LocalDate monthStart = today.withDayOfMonth(1);
        double revenueToday = 0.0D;
        double revenueWeek = 0.0D;
        double revenueMonth = 0.0D;
        double payoutsMonth = 0.0D;
        double refundsMonth = 0.0D;
        double ticketTaxes = 0.0D;
        double payoutTaxes = 0.0D;
        int purchasesToday = 0;

        ConfigurationSection entriesSection = transactionConfig.getConfigurationSection("entries");
        if (entriesSection != null) {
            for (String key : entriesSection.getKeys(false)) {
                String path = "entries." + key;
                String type = transactionConfig.getString(path + ".type", "");
                double amount = parseTransactionAmount(path);
                LocalDate date = parseTransactionDate(transactionConfig.getString(path + ".time", "")).toLocalDate();

                if ("ticket_purchase".equals(type)) {
                    double revenue = Math.abs(amount);
                    if (!date.isBefore(today)) {
                        revenueToday += revenue;
                        purchasesToday++;
                    }
                    if (!date.isBefore(weekStart)) {
                        revenueWeek += revenue;
                    }
                    if (!date.isBefore(monthStart)) {
                        revenueMonth += revenue;
                    }
                } else if ("winner_payout".equals(type) && !date.isBefore(monthStart)) {
                    payoutsMonth += amount;
                } else if ("refund".equals(type) && !date.isBefore(monthStart)) {
                    refundsMonth += amount;
                } else if ("ticket_tax".equals(type)) {
                    ticketTaxes += amount;
                } else if ("payout_tax".equals(type)) {
                    payoutTaxes += amount;
                }
            }
        }

        double payoutRatio = revenueMonth <= 0.0D ? 0.0D : payoutsMonth / revenueMonth;
        return new AdminStatsSnapshot(revenueToday, revenueWeek, revenueMonth, payoutsMonth, refundsMonth,
            ticketTaxes, payoutTaxes, purchasesToday, payoutRatio);
    }

    private double parseTransactionAmount(String path) {
        if (transactionConfig.isSet(path + ".raw-amount")) {
            return transactionConfig.getDouble(path + ".raw-amount", 0.0D);
        }

        String amount = transactionConfig.getString(path + ".amount", "0");
        String normalized = amount.replace(',', '.').replaceAll("[^0-9.\\-]", "");
        if (normalized.isBlank() || normalized.equals("-")) {
            return 0.0D;
        }
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException exception) {
            return 0.0D;
        }
    }

    private LocalDateTime parseTransactionDate(String value) {
        try {
            return LocalDateTime.parse(value);
        } catch (RuntimeException exception) {
            return LocalDateTime.now(getZoneId());
        }
    }

    public void previewHolograms(CommandSender sender) {
        ConfigurationSection hologramsSection = plugin.getHologramsConfig().getConfigurationSection("holograms");
        if (hologramsSection == null || hologramsSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-list-empty");
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.preview-holograms-header");
        for (String id : hologramsSection.getKeys(false).stream().sorted().limit(5).toList()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.preview-hologram-entry", Map.of(
                "id", id,
                "lines", buildHologramText("holograms." + id).replace("\n", " &8/ &f")
            ));
        }
    }

    public void listLotteries(CommandSender sender) {
        ConfigurationSection profilesSection = plugin.getLotteriesConfig().getConfigurationSection("profiles");
        if (profilesSection == null || profilesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-empty");
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-header");
        for (String id : profilesSection.getKeys(false).stream().sorted().toList()) {
            String path = "profiles." + id;
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-entry", Map.of(
                "id", id,
                "name", plugin.getLotteriesConfig().getString(path + ".display-name", id),
                "enabled", String.valueOf(plugin.getLotteriesConfig().getBoolean(path + ".enabled", true)),
                "active", id.equalsIgnoreCase(getActiveLotteryId()) ? "yes" : "no"
            ));
        }
    }

    public List<String> getLotteryProfileIds() {
        ConfigurationSection profilesSection = plugin.getLotteriesConfig().getConfigurationSection("profiles");
        if (profilesSection == null) {
            return List.of();
        }
        return profilesSection.getKeys(false).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
    }

    public List<String> getSelectableLotteryProfileIds(Player player) {
        return getLotteryProfileIds().stream()
            .filter(id -> plugin.getLotteriesConfig().getBoolean("profiles." + id + ".enabled", true))
            .filter(id -> canUseLotteryProfile(player, id))
            .toList();
    }

    private boolean canUseLotteryProfile(Player player, String id) {
        String permission = plugin.getLotteriesConfig().getString("profiles." + id + ".permission", "");
        return permission == null || permission.isBlank() || player.hasPermission(permission);
    }

    public void setActiveLottery(CommandSender sender, String requestedId) {
        String normalizedId = requestedId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        String path = "profiles." + normalizedId;
        if (normalizedId.isBlank() || !plugin.getLotteriesConfig().isConfigurationSection(path)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-not-found", Map.of("id", requestedId));
            return;
        }
        if (!plugin.getLotteriesConfig().getBoolean(path + ".enabled", true)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-disabled", Map.of("id", normalizedId));
            return;
        }

        plugin.getLotteriesConfig().set("settings.active-profile", normalizedId);
        plugin.saveLotteriesConfig();
        appendLog("lottery_profile_select", Map.of("profile", normalizedId, "sender", sender.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-selected", Map.of(
            "id", normalizedId,
            "name", getActiveLotteryDisplayName()
        ));
    }

    public void setPlayerLottery(Player player, String requestedId, boolean openMenuAfterSelect) {
        String normalizedId = normalizeLotteryId(requestedId);
        String path = "profiles." + normalizedId;
        if (!areLotteryProfilesEnabled() || !plugin.getLotteriesConfig().isConfigurationSection(path)) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.lotteries-not-found", Map.of("id", requestedId));
            return;
        }
        if (!plugin.getLotteriesConfig().getBoolean(path + ".enabled", true)) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.lotteries-disabled", Map.of("id", normalizedId));
            return;
        }
        if (!canUseLotteryProfile(player, normalizedId)) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.no-permission");
            return;
        }

        playerLotterySelections.put(player.getUniqueId(), normalizedId);
        save();
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.profile-selected", Map.of(
            "id", normalizedId,
            "name", getLotteryDisplayName(normalizedId)
        ));
        if (openMenuAfterSelect) {
            withPlayerLotteryContext(player, () -> {
                openMenu(player);
                return null;
            });
        }
    }

    public void listPlayerLotteries(Player player) {
        if (!areLotteryProfilesEnabled()) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.profiles-disabled");
            return;
        }
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.profile-list-header", Map.of(
            "selected", getSelectedLotteryId(player)
        ));
        for (String id : getSelectableLotteryProfileIds(player)) {
            Component entry = legacy(MessageUtil.raw(player, plugin.getMessagesConfig(player), "messages.profile-list-entry", Map.of(
                "id", id,
                "name", getLotteryDisplayName(id),
                "selected", id.equalsIgnoreCase(getSelectedLotteryId(player)) ? "*" : ""
            ))).clickEvent(ClickEvent.runCommand("/lottery profile " + id));
            player.sendMessage(entry);
        }
    }

    public void listAdminLog(CommandSender sender, int requestedPage) {
        ConfigurationSection entriesSection = logConfig.getConfigurationSection("entries");
        if (entriesSection == null || entriesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-empty");
            return;
        }

        List<String> keys = entriesSection.getKeys(false).stream().sorted(Comparator.reverseOrder()).toList();
        sendAdminLogEntries(sender, keys, requestedPage, "/lottery log");
    }

    public void listTransactions(CommandSender sender, int requestedPage) {
        ConfigurationSection entriesSection = transactionConfig.getConfigurationSection("entries");
        if (entriesSection == null || entriesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-empty");
            return;
        }

        List<String> keys = entriesSection.getKeys(false).stream().sorted(Comparator.reverseOrder()).toList();
        sendTransactionEntries(sender, keys, requestedPage, "/lottery transactions");
    }

    public void searchTransactions(CommandSender sender, String filterType, String value, int requestedPage) {
        ConfigurationSection entriesSection = transactionConfig.getConfigurationSection("entries");
        if (entriesSection == null || entriesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-empty");
            return;
        }

        String normalizedFilter = filterType.toLowerCase(Locale.ROOT);
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        List<String> keys = entriesSection.getKeys(false).stream()
            .filter(key -> matchesTransactionFilter(key, normalizedFilter, normalizedValue))
            .sorted(Comparator.reverseOrder())
            .toList();
        if (keys.isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-search-empty", Map.of(
                "filter", filterType,
                "value", value
            ));
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-search-header", Map.of(
            "filter", filterType,
            "value", value
        ));
        sendTransactionEntries(sender, keys, requestedPage, "/lottery transactions filter " + filterType + " " + value);
    }

    private boolean matchesTransactionFilter(String key, String filterType, String value) {
        String path = "entries." + key;
        return switch (filterType) {
            case "player", "spieler" -> transactionConfig.getString(path + ".player", "").toLowerCase(Locale.ROOT).contains(value);
            case "type", "typ" -> transactionConfig.getString(path + ".type", "").toLowerCase(Locale.ROOT).contains(value);
            case "date", "datum" -> transactionConfig.getString(path + ".time", "").toLowerCase(Locale.ROOT).startsWith(value);
            case "details", "detail" -> transactionConfig.getString(path + ".details", "").toLowerCase(Locale.ROOT).contains(value);
            default -> transactionConfig.getString(path + ".player", "").toLowerCase(Locale.ROOT).contains(value)
                || transactionConfig.getString(path + ".type", "").toLowerCase(Locale.ROOT).contains(value)
                || transactionConfig.getString(path + ".details", "").toLowerCase(Locale.ROOT).contains(value);
        };
    }

    private void sendTransactionEntries(CommandSender sender, List<String> keys, int requestedPage, String commandPrefix) {
        int pageSize = 5;
        int maxPage = Math.max(1, (int) Math.ceil(keys.size() / (double) pageSize));
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-header", Map.of(
            "page", String.valueOf(page),
            "pages", String.valueOf(maxPage)
        ));
        int start = (page - 1) * pageSize;
        int end = Math.min(keys.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            String path = "entries." + keys.get(index);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.transactions-entry", Map.of(
                "time", transactionConfig.getString(path + ".time", "?"),
                "type", transactionConfig.getString(path + ".type", "?"),
                "player", transactionConfig.getString(path + ".player", "?"),
                "amount", transactionConfig.getString(path + ".amount", "0"),
                "details", transactionConfig.getString(path + ".details", "")
            ));
        }
        sendTransactionNavigation(sender, page, maxPage, commandPrefix);
    }

    private void sendTransactionNavigation(CommandSender sender, int page, int maxPage, String commandPrefix) {
        if (maxPage <= 1) {
            return;
        }

        Component navigation = Component.empty();
        boolean hasButton = false;
        if (page > 1) {
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-previous", Map.of("page", String.valueOf(page - 1))))
                .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page - 1))));
            hasButton = true;
        }

        if (page < maxPage) {
            if (hasButton) {
                navigation = navigation.append(Component.space());
            }
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-next", Map.of("page", String.valueOf(page + 1))))
                .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page + 1))));
        }

        sender.sendMessage(navigation);
    }

    public void searchAdminLog(CommandSender sender, String filterType, String value, int requestedPage) {
        ConfigurationSection entriesSection = logConfig.getConfigurationSection("entries");
        if (entriesSection == null || entriesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-empty");
            return;
        }

        String normalizedFilter = filterType.toLowerCase(Locale.ROOT);
        String normalizedValue = value.toLowerCase(Locale.ROOT);
        List<String> keys = entriesSection.getKeys(false).stream()
            .filter(key -> matchesLogFilter(key, normalizedFilter, normalizedValue))
            .sorted(Comparator.reverseOrder())
            .toList();
        if (keys.isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-search-empty", Map.of(
                "filter", filterType,
                "value", value
            ));
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-search-header", Map.of(
            "filter", filterType,
            "value", value
        ));
        sendAdminLogEntries(sender, keys, requestedPage, "/lottery log " + filterType + " " + value);
    }

    private boolean matchesLogFilter(String key, String filterType, String value) {
        String path = "entries." + key;
        return switch (filterType) {
            case "player", "spieler" -> logConfig.getString(path + ".data.player", "").toLowerCase(Locale.ROOT).contains(value);
            case "action", "aktion" -> logConfig.getString(path + ".action", "").toLowerCase(Locale.ROOT).contains(value);
            case "date", "datum" -> logConfig.getString(path + ".time", "").toLowerCase(Locale.ROOT).startsWith(value);
            default -> logConfig.getString(path + ".action", "").toLowerCase(Locale.ROOT).contains(value)
                || logConfig.getString(path + ".data.player", "").toLowerCase(Locale.ROOT).contains(value);
        };
    }

    private void sendAdminLogEntries(CommandSender sender, List<String> keys, int requestedPage, String commandPrefix) {
        int pageSize = 5;
        int maxPage = Math.max(1, (int) Math.ceil(keys.size() / (double) pageSize));
        int page = Math.max(1, Math.min(requestedPage, maxPage));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-header", Map.of(
            "page", String.valueOf(page),
            "pages", String.valueOf(maxPage)
        ));
        int start = (page - 1) * pageSize;
        int end = Math.min(keys.size(), start + pageSize);
        for (int index = start; index < end; index++) {
            String path = "entries." + keys.get(index);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-entry", Map.of(
                "time", logConfig.getString(path + ".time", "?"),
                "action", logConfig.getString(path + ".action", "?")
            ));
        }
        sendAdminLogNavigation(sender, page, maxPage, commandPrefix);
    }

    private void sendAdminLogNavigation(CommandSender sender, int page, int maxPage, String commandPrefix) {
        if (maxPage <= 1) {
            return;
        }

        Component navigation = Component.empty();
        boolean hasButton = false;
        if (page > 1) {
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-previous", Map.of("page", String.valueOf(page - 1))))
                .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page - 1))));
            hasButton = true;
        }

        if (page < maxPage) {
            if (hasButton) {
                navigation = navigation.append(Component.space());
            }
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-next", Map.of("page", String.valueOf(page + 1))))
                .clickEvent(ClickEvent.runCommand(commandPrefix + " " + (page + 1))));
        }

        sender.sendMessage(navigation);
    }

    public void runDoctor(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.doctor-header");
        doctorLine(sender, "Vault", Bukkit.getPluginManager().isPluginEnabled("Vault"), "Vault Economy Provider");
        doctorLine(sender, "Ticketpreis", getTicketPrice() > 0.0D, economyService.format(getTicketPrice()));
        doctorLine(sender, "GUI Größe", plugin.getGuiConfig().getInt("gui.size", 27) % 9 == 0, String.valueOf(plugin.getGuiConfig().getInt("gui.size", 27)));
        int invalidMaterials = countInvalidGuiMaterials();
        doctorLine(sender, "GUI Materialien", invalidMaterials == 0, invalidMaterials + " ungültig");
        int duplicateSlots = countDuplicateGuiSlots();
        doctorLine(sender, "GUI Slots", duplicateSlots == 0, duplicateSlots + " doppelt");
        doctorLine(sender, "Mindestspieler", getMinimumPlayers() >= 0, String.valueOf(getMinimumPlayers()));
        doctorLine(sender, "Storage", !databaseStorage.getStatus().startsWith("unknown") && !databaseStorage.getStatus().contains("failed"), databaseStorage.getStatus());
        doctorLine(sender, "Gewinnanteile", getPrizeShares().stream().mapToDouble(Double::doubleValue).sum() > 0.0D, getPrizeShares().toString());
        doctorLine(sender, "HeadDatabase", !plugin.getGuiConfig().saveToString().contains("head-database-id") || Bukkit.getPluginManager().isPluginEnabled("HeadDatabase"), "optional");
        doctorLine(sender, "LuckPerms", !plugin.getConfig().getBoolean("eligibility.groups.enabled", false) || Bukkit.getPluginManager().isPluginEnabled("LuckPerms"), "optional für Gruppenchecks");
        doctorLine(sender, "Lotterie-Profile", !areLotteryProfilesEnabled() || !getLotteryProfileIds().isEmpty(), getLotteryProfileIds().size() + " Profil(e)");
        doctorLine(sender, "Sprachen", !plugin.getAvailableLanguages().isEmpty(), String.join(", ", plugin.getAvailableLanguages()));
        doctorLine(sender, "GUI-Sprachen", plugin.getGuiConfigs().size() >= plugin.getAvailableLanguages().size(),
            plugin.getGuiConfigs().size() + " GUI-Datei(en)");
        doctorLine(sender, "Fairness", plugin.getConfig().getBoolean("fairness.enabled", true), "Seed/Hash pruefbar");
        doctorLine(sender, "Rollback", plugin.getConfig().getBoolean("rollback.enabled", true), "letzte Ziehung");
        int invalidHolograms = countInvalidHologramLocations();
        doctorLine(sender, "Hologramme", invalidHolograms == 0, invalidHolograms + " ungültige Orte");
        appendLog("doctor", Map.of("sender", sender.getName()));
    }

    public void fixDoctorIssues(CommandSender sender) {
        int fixed = plugin.updateConfigDefaults();
        if (getTicketPrice() <= 0.0D) {
            plugin.getConfig().set("settings.ticket-price", 1.0D);
            fixed++;
        }
        if (getMinimumPlayers() < 1) {
            plugin.getConfig().set("settings.minimum-players-to-draw", 1);
            fixed++;
        }
        double taxRate = plugin.getConfig().getDouble("settings.tax-rate", 0.0D);
        if (taxRate < 0.0D || taxRate > 1.0D) {
            plugin.getConfig().set("settings.tax-rate", Math.max(0.0D, Math.min(1.0D, taxRate)));
            fixed++;
        }
        int hour = plugin.getConfig().getInt("settings.draw-time.hour", 20);
        int minute = plugin.getConfig().getInt("settings.draw-time.minute", 0);
        if (hour < 0 || hour > 23) {
            plugin.getConfig().set("settings.draw-time.hour", 20);
            fixed++;
        }
        if (minute < 0 || minute > 59) {
            plugin.getConfig().set("settings.draw-time.minute", 0);
            fixed++;
        }
        if (getPrizeShares().isEmpty() || getPrizeShares().stream().mapToDouble(Double::doubleValue).sum() <= 0.0D) {
            plugin.getConfig().set("winners.prize-shares", List.of(100.0D));
            fixed++;
        }

        int guiSize = plugin.getGuiConfig().getInt("gui.size", 36);
        if (guiSize < 9 || guiSize > 54 || guiSize % 9 != 0) {
            plugin.getGuiConfig().set("gui.size", Math.max(9, Math.min(54, ((guiSize + 8) / 9) * 9)));
            plugin.saveGuiConfig();
            fixed++;
        }

        plugin.saveConfig();
        plugin.reloadPlugin();
        appendLog("doctor_fix", Map.of("fixed", String.valueOf(fixed), "sender", sender.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.doctor-fix-complete", Map.of(
            "fixed", String.valueOf(fixed)
        ));
    }

    public void showSetupWizard(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setupwizard-header");
        sendWizardLine(sender, "messages.setupwizard-price", "/lottery setup price " + formatDecimal(getTicketPrice()));
        sendWizardLine(sender, "messages.setupwizard-minplayers", "/lottery setup minplayers " + getMinimumPlayers());
        sendWizardLine(sender, "messages.setupwizard-drawtime", "/lottery setup drawtime " + TimeUtil.formatLocalTime(getDrawTime()));
        sendWizardLine(sender, "messages.setupwizard-multipledraws", "/lottery setup multipledraws " + !plugin.getConfig().getBoolean("settings.draw-schedule.multiple-draws-enabled", false));
        sendWizardLine(sender, "messages.setupwizard-type", "/lottery setup type " + getLotteryType());
        sendWizardLine(sender, "messages.setupwizard-hologram", "/lottery hologram create lottery_countdown countdown");
        sendWizardLine(sender, "messages.setupwizard-doctor", "/lottery doctor fix");
    }

    private void sendWizardLine(CommandSender sender, String messagePath, String command) {
        if (sender instanceof Player player) {
            player.sendMessage(legacy(MessageUtil.raw(player, plugin.getMessagesConfig(player), messagePath, Map.of(
                "command", command
            ))).clickEvent(ClickEvent.suggestCommand(command)));
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), messagePath, Map.of("command", command));
    }

    private void doctorLine(CommandSender sender, String check, boolean ok, String detail) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), ok ? "messages.doctor-ok" : "messages.doctor-warn", Map.of(
            "check", check,
            "detail", detail
        ));
    }

    private int countInvalidGuiMaterials() {
        int invalid = 0;
        for (String root : getGuiRootsWithItems()) {
            ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection(root + ".items");
            if (itemsSection == null) {
                continue;
            }
            for (String key : itemsSection.getKeys(false)) {
                String material = plugin.getGuiConfig().getString(root + ".items." + key + ".material", "");
                if (material == null || material.isBlank() || isSpecialHeadMaterial(material)) {
                    continue;
                }
                if (Material.matchMaterial(material.toUpperCase(Locale.ROOT)) == null) {
                    invalid++;
                }
            }
        }
        return invalid;
    }

    private int countDuplicateGuiSlots() {
        int duplicates = 0;
        for (String root : getGuiRootsWithItems()) {
            ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection(root + ".items");
            if (itemsSection == null) {
                continue;
            }
            Map<Integer, String> slots = new HashMap<>();
            for (String key : itemsSection.getKeys(false)) {
                int slot = plugin.getGuiConfig().getInt(root + ".items." + key + ".slot", -1);
                if (slot < 0) {
                    continue;
                }
                if (slots.putIfAbsent(slot, key) != null) {
                    duplicates++;
                }
            }
        }
        return duplicates;
    }

    private List<String> getGuiRootsWithItems() {
        List<String> roots = new ArrayList<>();
        for (String key : plugin.getGuiConfig().getKeys(false)) {
            if (plugin.getGuiConfig().isConfigurationSection(key + ".items")) {
                roots.add(key);
            }
        }
        return roots;
    }

    private boolean isSpecialHeadMaterial(String material) {
        String normalized = material.toLowerCase(Locale.ROOT);
        return normalized.startsWith("hdb:")
            || normalized.startsWith("headdatabase:")
            || normalized.startsWith("playerhead:")
            || normalized.startsWith("player_head:");
    }

    private int countInvalidHologramLocations() {
        ConfigurationSection section = plugin.getHologramsConfig().getConfigurationSection("holograms");
        if (section == null) {
            return 0;
        }

        int invalid = 0;
        for (String id : section.getKeys(false)) {
            String world = section.getString(id + ".location.world", "");
            if (world == null || world.isBlank() || Bukkit.getWorld(world) == null) {
                invalid++;
            }
        }
        return invalid;
    }

    public void resetSeason(CommandSender sender, String requestedSeasonId) {
        seasonStats.clear();
        seasonPoints.clear();
        seasonId = requestedSeasonId == null || requestedSeasonId.isBlank() ? defaultSeasonId() : requestedSeasonId;
        plugin.getConfig().set("seasons.current-id", seasonId);
        plugin.saveConfig();
        save();
        appendLog("season_reset", Map.of("season", seasonId));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.season-reset", Map.of("season", seasonId));
    }

    private void addSeasonPoints(UUID playerId, String playerName, int points) {
        if (points <= 0 || !plugin.getConfig().getBoolean("season-shop.enabled", false)) {
            return;
        }

        seasonPoints.merge(playerId, points, Integer::sum);
        getOrCreateStats(playerId, playerName);
    }

    public PurchaseResult buyTickets(Player player, int amount) {
        if (operationLotteryId == null) {
            return withPlayerLotteryContext(player, () -> buyTickets(player, amount));
        }

        if (amount <= 0) {
            return new PurchaseResult(false, "messages.invalid-amount", Map.of());
        }

        String eligibilityMessage = validateEligibility(player);
        if (eligibilityMessage != null) {
            return new PurchaseResult(false, eligibilityMessage, Map.of());
        }

        int maxPerPurchase = plugin.getConfig().getInt("settings.tickets-per-purchase-max", 64);
        if (amount > maxPerPurchase) {
            return new PurchaseResult(false, "messages.amount-too-large", Map.of("max", String.valueOf(maxPerPurchase)));
        }

        int maxPerPlayer = plugin.getConfig().getInt("settings.tickets-per-player-max", 256);
        int currentTickets = getTicketsFor(player.getUniqueId());
        if (currentTickets + amount > maxPerPlayer) {
            return new PurchaseResult(false, "messages.player-limit-reached", Map.of(
                "max", String.valueOf(maxPerPlayer),
                "tickets", String.valueOf(currentTickets)
            ));
        }

        boolean itemLottery = isItemLottery();
        double fullCost = itemLottery ? 0.0D : calculateTicketCost(amount);
        PurchaseResult securityResult = validatePurchaseSecurity(player, amount, fullCost);
        if (securityResult != null) {
            return securityResult;
        }

        PurchaseResult confirmationResult = requirePurchaseConfirmation(player, amount, fullCost);
        if (confirmationResult != null) {
            return confirmationResult;
        }

        if (itemLottery) {
            if (!removeItemLotteryStake(player, amount)) {
                return new PurchaseResult(false, "messages.item-stake-missing", Map.of(
                    "amount", String.valueOf(getItemLotteryStakeAmount() * amount),
                    "item", getItemLotteryStakeMaterial().name()
                ));
            }
        } else if (!economyService.has(player, fullCost) || !economyService.withdraw(player, fullCost)) {
            return new PurchaseResult(false, "messages.not-enough-money", Map.of(
                "cost", economyService.format(fullCost)
            ));
        }

        double taxRate = itemLottery ? 0.0D : getTaxRate();
        double netTicketAmount = fullCost * (1.0D - taxRate);
        double jackpotIncrease = itemLottery
            ? plugin.getConfig().getDouble("item-lottery.money-pot-per-ticket", 0.0D) * amount
            : netTicketAmount * getJackpotBoostMultiplier();
        double taxAmount = fullCost - netTicketAmount;
        currentRound().addTickets(player.getUniqueId(), amount, jackpotIncrease, fullCost);
        totalTaxCollected += taxAmount;
        routeTicketTax(player, taxAmount);
        recordDailyUsage(player.getUniqueId(), amount, fullCost);
        purchaseCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        getOrCreateStats(player.getUniqueId(), player.getName())
            .recordPurchase(player.getName(), amount, fullCost, LocalDateTime.now(getZoneId()));
        getOrCreateSeasonStats(player.getUniqueId(), player.getName())
            .recordPurchase(player.getName(), amount, fullCost, LocalDateTime.now(getZoneId()));
        addSeasonPoints(player.getUniqueId(), player.getName(), amount * plugin.getConfig().getInt("season-shop.points.ticket-purchase", 0));
        appendLog("ticket_purchase", Map.of(
            "player", player.getName(),
            "amount", String.valueOf(amount),
            "cost", economyService.format(fullCost)
        ));
        appendTransaction("ticket_purchase", player.getName(), -fullCost, Map.of(
            "tickets", String.valueOf(amount),
            "lottery", getActiveLotteryId()
        ));

        Map<String, String> placeholders = createCommonPlaceholders(player);
        placeholders.put("player", player.getName());
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("cost", economyService.format(fullCost));
        placeholders.put("tax", economyService.format(taxAmount));
        placeholders.put("boost_multiplier", formatDecimal(getJackpotBoostMultiplier()));
        placeholders.put("stake_amount", String.valueOf(getItemLotteryStakeAmount() * amount));
        placeholders.put("stake_item", getItemLotteryStakeMaterial().name());
        notifyAdminsAboutLargePurchase(player, amount, fullCost, placeholders);
        monitorAntiAbuse(player, amount, fullCost, placeholders);
        if (plugin.getConfig().getBoolean("settings.broadcast-ticket-purchases", true)) {
            broadcastConfigured("messages.ticket-purchase-broadcast", placeholders, player);
        }
        sendWebhookEvent("ticket-purchase", placeholders);
        maybeSendPotReminder(placeholders);
        save();
        return new PurchaseResult(true, itemLottery ? "messages.buy-success-items" : "messages.buy-success", placeholders);
    }

    public PurchaseResult claimFreeTickets(Player player, String requestedReason) {
        if (operationLotteryId == null) {
            return withPlayerLotteryContext(player, () -> claimFreeTickets(player, requestedReason));
        }

        if (!plugin.getConfig().getBoolean("free-tickets.enabled", false)) {
            return new PurchaseResult(false, "messages.free-tickets-disabled", Map.of());
        }

        String eligibilityMessage = validateEligibility(player);
        if (eligibilityMessage != null) {
            return new PurchaseResult(false, eligibilityMessage, Map.of());
        }

        String reason = normalizeConfigKey(requestedReason == null || requestedReason.isBlank() ? "default" : requestedReason);
        String path = "free-tickets.reasons." + reason;
        if (!plugin.getConfig().isConfigurationSection(path)) {
            path = "free-tickets.reasons.default";
            reason = "default";
        }

        String permission = plugin.getConfig().getString(path + ".permission", "");
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            return new PurchaseResult(false, "messages.no-permission", Map.of());
        }

        int minPlaytimeMinutes = plugin.getConfig().getInt(path + ".min-playtime-minutes", 0);
        if (minPlaytimeMinutes > 0 && player.getStatistic(Statistic.PLAY_ONE_MINUTE) < minPlaytimeMinutes * 60 * 20) {
            return new PurchaseResult(false, "messages.free-tickets-playtime", Map.of(
                "minutes", String.valueOf(minPlaytimeMinutes)
            ));
        }

        long now = System.currentTimeMillis();
        long cooldownMillis = Math.max(0L, plugin.getConfig().getLong(path + ".cooldown-hours", 24L)) * 60L * 60L * 1000L;
        long lastClaim = freeTicketClaims.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(reason, 0L);
        long remainingMillis = lastClaim + cooldownMillis - now;
        if (remainingMillis > 0L) {
            long hours = Math.max(1L, (long) Math.ceil(remainingMillis / 3_600_000.0D));
            return new PurchaseResult(false, "messages.free-tickets-cooldown", Map.of(
                "hours", String.valueOf(hours)
            ));
        }

        int amount = Math.max(1, plugin.getConfig().getInt(path + ".amount", 1));
        int maxPerPlayer = plugin.getConfig().getInt("settings.tickets-per-player-max", 256);
        int currentTickets = getTicketsFor(player.getUniqueId());
        if (currentTickets + amount > maxPerPlayer) {
            return new PurchaseResult(false, "messages.player-limit-reached", Map.of(
                "max", String.valueOf(maxPerPlayer),
                "tickets", String.valueOf(currentTickets)
            ));
        }

        double potAdd = plugin.getConfig().getDouble(path + ".money-pot-per-ticket", 0.0D) * amount;
        currentRound().addTickets(player.getUniqueId(), amount, potAdd, 0.0D);
        freeTicketClaims.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(reason, now);
        getOrCreateStats(player.getUniqueId(), player.getName()).recordPurchase(player.getName(), amount, 0.0D, LocalDateTime.now(getZoneId()));
        getOrCreateSeasonStats(player.getUniqueId(), player.getName()).recordPurchase(player.getName(), amount, 0.0D, LocalDateTime.now(getZoneId()));
        addSeasonPoints(player.getUniqueId(), player.getName(), plugin.getConfig().getInt("season-shop.points.free-ticket-claim", 0));
        appendTransaction("free_ticket_claim", player.getName(), 0.0D, Map.of(
            "tickets", String.valueOf(amount),
            "reason", reason,
            "lottery", getActiveLotteryId()
        ));
        for (String command : plugin.getConfig().getStringList(path + ".commands-on-claim")) {
            String resolvedCommand = MessageUtil.format(player, command, createCommonPlaceholders(player));
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        save();

        Map<String, String> placeholders = createCommonPlaceholders(player);
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("reason", reason);
        return new PurchaseResult(true, "messages.free-tickets-success", placeholders);
    }

    public void grantFreeTickets(CommandSender sender, OfflinePlayer target, String requestedReason, int requestedAmount) {
        int amount = Math.max(1, requestedAmount);
        UUID playerId = target.getUniqueId();
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        String lotteryId = onlinePlayer != null
            ? getSelectedLotteryId(onlinePlayer)
            : playerLotterySelections.getOrDefault(playerId, getActiveLotteryId());

        withLotteryContext(lotteryId, () -> {
            int maxPerPlayer = plugin.getConfig().getInt("settings.tickets-per-player-max", 256);
            int currentTickets = getTicketsFor(playerId);
            if (maxPerPlayer > 0 && currentTickets + amount > maxPerPlayer) {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-limit-reached", Map.of(
                    "max", String.valueOf(maxPerPlayer),
                    "tickets", String.valueOf(currentTickets)
                ));
                return null;
            }

            String reason = normalizeConfigKey(requestedReason == null || requestedReason.isBlank() ? "admin" : requestedReason);
            String playerName = target.getName() != null ? target.getName() : playerId.toString();
            String reasonPath = "free-tickets.reasons." + reason + ".money-pot-per-ticket";
            double potAdd = plugin.getConfig().getDouble(reasonPath, 0.0D) * amount;
            currentRound().addTickets(playerId, amount, potAdd, 0.0D);
            getOrCreateStats(playerId, playerName).recordPurchase(playerName, amount, 0.0D, LocalDateTime.now(getZoneId()));
            getOrCreateSeasonStats(playerId, playerName).recordPurchase(playerName, amount, 0.0D, LocalDateTime.now(getZoneId()));
            addSeasonPoints(playerId, playerName, plugin.getConfig().getInt("season-shop.points.free-ticket-claim", 0));
            appendTransaction("free_ticket_grant", playerName, 0.0D, Map.of(
                "tickets", String.valueOf(amount),
                "reason", reason,
                "lottery", getActiveLotteryId(),
                "sender", sender.getName()
            ));
            appendLog("free_ticket_grant", Map.of(
                "player", playerName,
                "amount", String.valueOf(amount),
                "reason", reason,
                "sender", sender.getName()
            ));

            Map<String, String> placeholders = new HashMap<>(createCommonPlaceholders(onlinePlayer));
            placeholders.put("player", playerName);
            placeholders.put("amount", String.valueOf(amount));
            placeholders.put("reason", reason);
            placeholders.put("lottery", getActiveLotteryDisplayName());
            if (onlinePlayer != null) {
                MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.free-tickets-grant-received", placeholders);
            } else {
                queuePendingNotification(playerId, "messages.free-tickets-grant-received", placeholders);
            }
            save();
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.free-tickets-granted", placeholders);
            return null;
        });
    }

    public void showSeasonShop(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("season-shop.enabled", false)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.season-shop-disabled");
            return;
        }
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return;
        }

        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-header", Map.of(
            "points", String.valueOf(getSeasonPoints(player.getUniqueId()))
        ));

        ConfigurationSection rewardsSection = plugin.getConfig().getConfigurationSection("season-shop.rewards");
        if (rewardsSection == null || rewardsSection.getKeys(false).isEmpty()) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-empty");
            return;
        }
        for (String id : rewardsSection.getKeys(false).stream().sorted().toList()) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-entry", Map.of(
                "id", id,
                "name", rewardsSection.getString(id + ".name", id),
                "cost", String.valueOf(rewardsSection.getInt(id + ".cost", 0))
            ));
        }
    }

    public void buySeasonShopReward(Player player, String rewardId) {
        if (!plugin.getConfig().getBoolean("season-shop.enabled", false)) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-disabled");
            return;
        }

        String id = normalizeConfigKey(rewardId);
        String path = "season-shop.rewards." + id;
        if (!plugin.getConfig().isConfigurationSection(path)) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-not-found", Map.of("id", rewardId));
            return;
        }

        int cost = Math.max(0, plugin.getConfig().getInt(path + ".cost", 0));
        int points = getSeasonPoints(player.getUniqueId());
        if (points < cost) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-not-enough", Map.of(
                "points", String.valueOf(points),
                "cost", String.valueOf(cost)
            ));
            return;
        }

        seasonPoints.put(player.getUniqueId(), points - cost);
        Map<String, String> placeholders = createCommonPlaceholders(player);
        placeholders.put("reward", plugin.getConfig().getString(path + ".name", id));
        placeholders.put("reward_id", id);
        placeholders.put("cost", String.valueOf(cost));
        placeholders.put("points", String.valueOf(points - cost));
        for (String command : plugin.getConfig().getStringList(path + ".commands")) {
            String resolvedCommand = MessageUtil.format(player, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        appendTransaction("season_shop_buy", player.getName(), 0.0D, Map.of(
            "reward", id,
            "cost", String.valueOf(cost),
            "lottery", getActiveLotteryId()
        ));
        save();
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.season-shop-bought", placeholders);
    }

    private void notifyAdminsAboutLargePurchase(Player player, int amount, double fullCost, Map<String, String> placeholders) {
        int ticketThreshold = plugin.getConfig().getInt("security.admin-warning-ticket-threshold", 0);
        double spendThreshold = plugin.getConfig().getDouble("security.admin-warning-spend-threshold", 0.0D);
        boolean overTicketThreshold = ticketThreshold > 0 && amount >= ticketThreshold;
        boolean overSpendThreshold = spendThreshold > 0.0D && fullCost >= spendThreshold;
        if (!overTicketThreshold && !overSpendThreshold) {
            return;
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("lottery.admin")) {
                MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.admin-purchase-warning", placeholders);
            }
        }
        appendLog("admin_purchase_warning", Map.of(
            "player", player.getName(),
            "amount", String.valueOf(amount),
            "cost", economyService.format(fullCost)
        ));
    }

    private void monitorAntiAbuse(Player player, int amount, double fullCost, Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("security.anti-abuse.enabled", false)) {
            return;
        }

        long windowMillis = Math.max(10L, plugin.getConfig().getLong("security.anti-abuse.window-seconds", 60L)) * 1000L;
        long now = System.currentTimeMillis();
        PurchaseWindow window = purchaseWindows.computeIfAbsent(player.getUniqueId(), ignored -> new PurchaseWindow(now));
        if (now - window.startedAtMillis > windowMillis) {
            window.startedAtMillis = now;
            window.tickets = 0;
            window.spent = 0.0D;
            window.warned = false;
        }

        window.tickets += amount;
        window.spent += fullCost;
        int ticketThreshold = plugin.getConfig().getInt("security.anti-abuse.tickets-threshold", 100);
        double spendThreshold = plugin.getConfig().getDouble("security.anti-abuse.spend-threshold", 0.0D);
        boolean overTickets = ticketThreshold > 0 && window.tickets >= ticketThreshold;
        boolean overSpend = spendThreshold > 0.0D && window.spent >= spendThreshold;
        if ((!overTickets && !overSpend) || window.warned) {
            return;
        }

        window.warned = true;
        Map<String, String> warningPlaceholders = new HashMap<>(placeholders);
        warningPlaceholders.put("window_seconds", String.valueOf(windowMillis / 1000L));
        warningPlaceholders.put("window_tickets", String.valueOf(window.tickets));
        warningPlaceholders.put("window_spent", economyService.format(window.spent));
        warningPlaceholders.put("player_uuid", player.getUniqueId().toString());
        if (plugin.getConfig().getBoolean("security.anti-abuse.include-ip-in-log", false) && player.getAddress() != null) {
            warningPlaceholders.put("player_ip", player.getAddress().getAddress().getHostAddress());
        } else {
            warningPlaceholders.put("player_ip", "hidden");
        }

        appendLog("anti_abuse_purchase", warningPlaceholders);
        if (!plugin.getConfig().getBoolean("security.anti-abuse.notify-admins", true)) {
            return;
        }

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.hasPermission("lottery.admin")) {
                MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.anti-abuse-warning", warningPlaceholders);
            }
        }
    }

    private void maybeSendPotReminder(Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("notifications.pot-threshold.enabled", false)) {
            return;
        }

        double threshold = plugin.getConfig().getDouble("notifications.pot-threshold.amount", 0.0D);
        String lotteryId = getActiveLotteryId();
        if (threshold <= 0.0D || getTotalPot() < threshold || potReminderSent.getOrDefault(lotteryId, false)) {
            return;
        }

        potReminderSent.put(lotteryId, true);
        Map<String, String> reminderPlaceholders = new HashMap<>(placeholders);
        reminderPlaceholders.put("threshold", economyService.format(threshold));
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (isNotificationEnabled(onlinePlayer.getUniqueId(), "pot")) {
                MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.pot-reminder", reminderPlaceholders);
            }
        }
    }

    private boolean isNotificationEnabled(UUID playerId, String type) {
        boolean defaultEnabled = plugin.getConfig().getBoolean("notifications.player-preferences.default-enabled", true);
        return notificationPreferences.getOrDefault(playerId, Map.of())
            .getOrDefault(type.toLowerCase(Locale.ROOT), defaultEnabled);
    }

    public void setNotificationPreference(Player player, String type, boolean enabled) {
        String normalizedType = normalizeNotificationType(type);
        notificationPreferences.computeIfAbsent(player.getUniqueId(), ignored -> new HashMap<>()).put(normalizedType, enabled);
        save();
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.reminder-updated", Map.of(
            "type", normalizedType,
            "state", enabled ? "on" : "off"
        ));
    }

    public void showNotificationPreferences(Player player) {
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.reminder-header");
        for (String type : List.of("draw", "win", "refund", "pot")) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.reminder-entry", Map.of(
                "type", type,
                "state", isNotificationEnabled(player.getUniqueId(), type) ? "on" : "off"
            ));
        }
    }

    private String normalizeNotificationType(String type) {
        String normalized = type == null ? "" : type.toLowerCase(Locale.ROOT).replace("-", "_");
        return switch (normalized) {
            case "draw", "ziehung" -> "draw";
            case "win", "gewinn" -> "win";
            case "refund", "rueckerstattung", "rückerstattung" -> "refund";
            case "pot", "topf" -> "pot";
            default -> "draw";
        };
    }

    private void routeTicketTax(Player player, double taxAmount) {
        if (taxAmount <= 0.0D || !plugin.getConfig().getBoolean("tax.target.enabled", false)) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>(createCommonPlaceholders(player));
        placeholders.put("tax", economyService.format(taxAmount));
        placeholders.put("tax_raw", String.valueOf(taxAmount));

        String account = plugin.getConfig().getString("tax.target.account", "");
        if (account != null && !account.isBlank()) {
            economyService.deposit(Bukkit.getOfflinePlayer(account), taxAmount);
            appendTransaction("ticket_tax", account, taxAmount, Map.of(
                "source", player.getName(),
                "lottery", getActiveLotteryId()
            ));
        }

        for (String command : plugin.getConfig().getStringList("tax.target.commands")) {
            String resolvedCommand = MessageUtil.format(player, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
    }

    private boolean isItemLottery() {
        return getLotteryType().equalsIgnoreCase("item") || plugin.getConfig().getBoolean("item-lottery.enabled", false);
    }

    private Material getItemLotteryStakeMaterial() {
        return parseMaterial(plugin.getConfig().getString("item-lottery.stake.material", "DIAMOND"), Material.DIAMOND);
    }

    private int getItemLotteryStakeAmount() {
        return Math.max(1, plugin.getConfig().getInt("item-lottery.stake.amount-per-ticket", 1));
    }

    private boolean removeItemLotteryStake(Player player, int tickets) {
        int required = getItemLotteryStakeAmount() * tickets;
        Material material = getItemLotteryStakeMaterial();
        int available = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (stack != null && stack.getType() == material) {
                available += stack.getAmount();
            }
        }
        if (available < required) {
            return false;
        }

        int remaining = required;
        ItemStack[] contents = player.getInventory().getContents();
        for (int slot = 0; slot < contents.length && remaining > 0; slot++) {
            ItemStack stack = contents[slot];
            if (stack == null || stack.getType() != material) {
                continue;
            }
            int remove = Math.min(remaining, stack.getAmount());
            stack.setAmount(stack.getAmount() - remove);
            remaining -= remove;
            if (stack.getAmount() <= 0) {
                player.getInventory().setItem(slot, null);
            } else {
                player.getInventory().setItem(slot, stack);
            }
        }
        return true;
    }

    private PurchaseResult validatePurchaseSecurity(Player player, int amount, double fullCost) {
        long cooldownSeconds = plugin.getConfig().getLong("security.purchase-cooldown-seconds", 0L);
        if (cooldownSeconds > 0L) {
            long lastPurchase = purchaseCooldowns.getOrDefault(player.getUniqueId(), 0L);
            long remainingMillis = (lastPurchase + cooldownSeconds * 1000L) - System.currentTimeMillis();
            if (remainingMillis > 0L) {
                return new PurchaseResult(false, "messages.purchase-cooldown", Map.of(
                    "seconds", String.valueOf(Math.max(1L, (long) Math.ceil(remainingMillis / 1000.0D)))
                ));
            }
        }

        DailyUsage usage = getTodayUsage(player.getUniqueId());
        int dailyTicketLimit = plugin.getConfig().getInt("security.daily-ticket-limit", 0);
        if (dailyTicketLimit > 0 && usage.tickets() + amount > dailyTicketLimit) {
            return new PurchaseResult(false, "messages.daily-ticket-limit", Map.of(
                "limit", String.valueOf(dailyTicketLimit),
                "used", String.valueOf(usage.tickets())
            ));
        }

        double dailySpendLimit = plugin.getConfig().getDouble("security.daily-spend-limit", 0.0D);
        if (dailySpendLimit > 0.0D && usage.spent() + fullCost > dailySpendLimit) {
            return new PurchaseResult(false, "messages.daily-spend-limit", Map.of(
                "limit", economyService.format(dailySpendLimit),
                "used", economyService.format(usage.spent())
            ));
        }

        return null;
    }

    private DailyUsage getTodayUsage(UUID playerId) {
        String today = LocalDate.now(getZoneId()).toString();
        DailyUsage usage = dailyUsage.get(playerId);
        if (usage == null || !usage.date().equals(today)) {
            usage = new DailyUsage(today, 0, 0.0D);
            dailyUsage.put(playerId, usage);
        }
        return usage;
    }

    private void recordDailyUsage(UUID playerId, int tickets, double spent) {
        DailyUsage usage = getTodayUsage(playerId);
        dailyUsage.put(playerId, new DailyUsage(usage.date(), usage.tickets() + tickets, usage.spent() + spent));
    }

    private PurchaseResult requirePurchaseConfirmation(Player player, int amount, double fullCost) {
        if (!plugin.getConfig().getBoolean("purchase-confirmation.enabled", false)) {
            pendingPurchases.remove(player.getUniqueId());
            return null;
        }

        double minCost = plugin.getConfig().getDouble("purchase-confirmation.min-total-cost", 10000.0D);
        if (fullCost < minCost) {
            pendingPurchases.remove(player.getUniqueId());
            return null;
        }

        long now = System.currentTimeMillis();
        long timeoutMillis = Math.max(1L, plugin.getConfig().getLong("purchase-confirmation.timeout-seconds", 20L)) * 1000L;
        PendingPurchase pendingPurchase = pendingPurchases.get(player.getUniqueId());
        if (pendingPurchase != null && pendingPurchase.amount() == amount && now <= pendingPurchase.expiresAtMillis()) {
            pendingPurchases.remove(player.getUniqueId());
            return null;
        }

        pendingPurchases.put(player.getUniqueId(), new PendingPurchase(amount, fullCost, now + timeoutMillis));
        return new PurchaseResult(false, "messages.purchase-confirm-required", Map.of(
            "amount", String.valueOf(amount),
            "cost", economyService.format(fullCost),
            "seconds", String.valueOf(timeoutMillis / 1000L)
        ));
    }

    public DrawResult forceDraw(CommandSender sender) {
        return draw(sender, true, formatDrawKey(ZonedDateTime.now(getZoneId())));
    }

    public DrawResult simulateDraw(CommandSender sender) {
        Map<String, String> placeholders = createCommonPlaceholders(sender instanceof Player player ? player : null);
        if (currentRound().getTotalTickets() <= 0) {
            return new DrawResult(true, "messages.simulate-no-players", placeholders);
        }

        if (currentRound().getUniquePlayers() < getMinimumPlayers()) {
            placeholders.put("needed", String.valueOf(getMinimumPlayers() - currentRound().getUniquePlayers()));
            return new DrawResult(true, "messages.simulate-not-enough-players", placeholders);
        }

        List<WinnerPayout> payouts = createWinnerPayouts(getDrawPayoutAmount());
        WinnerPayout mainWinner = payouts.get(0);
        placeholders.put("player", mainWinner.playerName());
        placeholders.put("amount", economyService.format(mainWinner.amount()));
        placeholders.put("tickets", String.valueOf(mainWinner.tickets()));
        placeholders.put("chance", formatChance((double) mainWinner.tickets() / Math.max(1, currentRound().getTotalTickets())));
        placeholders.put("winner_count", String.valueOf(payouts.size()));
        placeholders.put("winners", formatWinnerPayouts(payouts));
        appendLog("simulate_draw", Map.of(
            "player", mainWinner.playerName(),
            "amount", economyService.format(getTotalPot()),
            "tickets", String.valueOf(mainWinner.tickets())
        ));
        return new DrawResult(true, "messages.simulate-success", placeholders);
    }

    public double getJackpot() {
        return getTotalPot();
    }

    public int getTicketsFor(UUID playerId) {
        return currentRound().getTicketsFor(playerId);
    }

    public double getWinChance(UUID playerId) {
        int totalTickets = currentRound().getTotalTickets();
        if (totalTickets <= 0) {
            return 0.0D;
        }
        return (double) currentRound().getTicketsFor(playerId) / totalTickets;
    }

    public Map<String, String> createCommonPlaceholders(Player player) {
        if (player != null && operationLotteryId == null) {
            return withPlayerLotteryContext(player, () -> createCommonPlaceholders(player));
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("jackpot", economyService.format(getTotalPot()));
        placeholders.put("pot", economyService.format(getTotalPot()));
        placeholders.put("payout_pot", economyService.format(getDrawPayoutAmount()));
        placeholders.put("ticket_pot", economyService.format(currentRound().getJackpot()));
        placeholders.put("base_pot", economyService.format(getConfiguredBasePot()));
        placeholders.put("ticket_price", economyService.format(getTicketPrice()));
        placeholders.put("lottery_id", getActiveLotteryId());
        placeholders.put("lottery_name", getActiveLotteryDisplayName());
        placeholders.put("lottery_type", getLotteryType());
        placeholders.put("draw_time", TimeUtil.formatLocalTime(getNextDrawAt().toLocalTime()));
        placeholders.put("draw_times", formatDrawTimes());
        placeholders.put("next_draw", formatNextDraw());
        placeholders.put("time_left", TimeUtil.formatDurationCompact(Duration.between(ZonedDateTime.now(getZoneId()), getNextDrawAt())));
        placeholders.put("tickets_total", String.valueOf(currentRound().getTotalTickets()));
        placeholders.put("players", String.valueOf(currentRound().getUniquePlayers()));
        placeholders.put("min_players", String.valueOf(getMinimumPlayers()));
        placeholders.put("pending_notifications", String.valueOf(getPendingNotificationCount()));
        placeholders.put("pending_payments", String.valueOf(pendingPayments.size()));
        placeholders.put("tax_collected_total", economyService.format(totalTaxCollected));
        placeholders.put("season_id", getSeasonId());
        placeholders.put("round_started", currentRound().getStartedAt().format(WINNER_DATE_FORMAT));
        placeholders.put("tax_percent", String.valueOf((int) Math.round(getTaxRate() * 100.0D)));
        placeholders.put("boost_multiplier", formatDecimal(getJackpotBoostMultiplier()));
        placeholders.put("ticket_price_1", economyService.format(calculateTicketCost(1)));
        placeholders.put("ticket_price_5", economyService.format(calculateTicketCost(5)));
        placeholders.put("ticket_price_10", economyService.format(calculateTicketCost(10)));
        placeholders.put("ticket_price_25", economyService.format(calculateTicketCost(25)));
        placeholders.put("winner_count", String.valueOf(getPrizeShares().size()));
        if (player != null) {
            placeholders.put("player", player.getName());
            placeholders.put("player_name", player.getName());
            placeholders.put("player_uuid", player.getUniqueId().toString());
            placeholders.put("selected_lottery_id", getSelectedLotteryId(player));
            placeholders.put("selected_lottery_name", getLotteryDisplayName(getSelectedLotteryId(player)));
            placeholders.put("tickets", String.valueOf(getTicketsFor(player.getUniqueId())));
            placeholders.put("player_tickets", String.valueOf(getTicketsFor(player.getUniqueId())));
            placeholders.put("chance", formatChance(getWinChance(player.getUniqueId())));
            placeholders.put("player_chance", formatChance(getWinChance(player.getUniqueId())));
            placeholders.put("season_points", String.valueOf(getSeasonPoints(player.getUniqueId())));
            applyPersonalStatsPlaceholders(placeholders, player.getUniqueId());
            applySeasonStatsPlaceholders(placeholders, player.getUniqueId());
        } else {
            placeholders.put("player", "");
            placeholders.put("player_name", "");
            placeholders.put("player_uuid", "");
            placeholders.put("selected_lottery_id", getActiveLotteryId());
            placeholders.put("selected_lottery_name", getActiveLotteryDisplayName());
            placeholders.put("tickets", "0");
            placeholders.put("player_tickets", "0");
            placeholders.put("chance", "0.00%");
            placeholders.put("player_chance", "0.00%");
            placeholders.put("season_points", "0");
            applyEmptyPersonalStatsPlaceholders(placeholders);
            applyEmptySeasonStatsPlaceholders(placeholders);
        }
        return placeholders;
    }

    private void applyPersonalStatsPlaceholders(Map<String, String> placeholders, UUID playerId) {
        PlayerLotteryStats stats = getStatsFor(playerId);
        placeholders.put("player_stats_tickets_bought", String.valueOf(stats.getTicketsBought()));
        placeholders.put("player_stats_money_spent", economyService.format(stats.getMoneySpent()));
        placeholders.put("player_stats_wins", String.valueOf(stats.getWins()));
        placeholders.put("player_stats_highest_win", economyService.format(stats.getHighestWin()));
        placeholders.put("player_stats_total_won", economyService.format(stats.getTotalWon()));
        placeholders.put("player_stats_rounds_played", String.valueOf(stats.getRoundsPlayed()));
        placeholders.put("player_stats_profit", economyService.format(stats.getTotalWon() - stats.getMoneySpent()));
        placeholders.put("player_stats_last_purchase", stats.getLastPurchaseAt() == null ? "-" : stats.getLastPurchaseAt().format(WINNER_DATE_FORMAT));
        placeholders.put("player_stats_last_win", stats.getLastWinAt() == null ? "-" : stats.getLastWinAt().format(WINNER_DATE_FORMAT));
        applyRankPlaceholders(placeholders, playerId);
    }

    private void applySeasonStatsPlaceholders(Map<String, String> placeholders, UUID playerId) {
        PlayerLotteryStats stats = getSeasonStatsFor(playerId);
        placeholders.put("season_tickets_bought", String.valueOf(stats.getTicketsBought()));
        placeholders.put("season_money_spent", economyService.format(stats.getMoneySpent()));
        placeholders.put("season_wins", String.valueOf(stats.getWins()));
        placeholders.put("season_highest_win", economyService.format(stats.getHighestWin()));
        placeholders.put("season_total_won", economyService.format(stats.getTotalWon()));
        placeholders.put("season_rounds_played", String.valueOf(stats.getRoundsPlayed()));
        placeholders.put("season_profit", economyService.format(stats.getTotalWon() - stats.getMoneySpent()));
    }

    private void applyEmptyPersonalStatsPlaceholders(Map<String, String> placeholders) {
        placeholders.put("player_stats_tickets_bought", "0");
        placeholders.put("player_stats_money_spent", economyService.format(0.0D));
        placeholders.put("player_stats_wins", "0");
        placeholders.put("player_stats_highest_win", economyService.format(0.0D));
        placeholders.put("player_stats_total_won", economyService.format(0.0D));
        placeholders.put("player_stats_rounds_played", "0");
        placeholders.put("player_stats_profit", economyService.format(0.0D));
        placeholders.put("player_stats_last_purchase", "-");
        placeholders.put("player_stats_last_win", "-");
        for (String statistic : List.of("rounds_played", "tickets_bought", "money_spent", "wins", "highest_win", "total_won", "current_tickets")) {
            placeholders.put("my_rank_" + statistic, "-");
        }
    }

    private void applyRankPlaceholders(Map<String, String> placeholders, UUID playerId) {
        for (String statistic : List.of("rounds_played", "tickets_bought", "money_spent", "wins", "highest_win", "total_won", "current_tickets")) {
            int rank = getPlayerRank(playerId, statistic);
            placeholders.put("my_rank_" + statistic, rank > 0 ? String.valueOf(rank) : "-");
        }
    }

    private void applyEmptySeasonStatsPlaceholders(Map<String, String> placeholders) {
        placeholders.put("season_tickets_bought", "0");
        placeholders.put("season_money_spent", economyService.format(0.0D));
        placeholders.put("season_wins", "0");
        placeholders.put("season_highest_win", economyService.format(0.0D));
        placeholders.put("season_total_won", economyService.format(0.0D));
        placeholders.put("season_rounds_played", "0");
        placeholders.put("season_profit", economyService.format(0.0D));
    }

    public void setJackpot(double amount) {
        currentRound().setJackpot(Math.max(0.0D, amount - getConfiguredBasePot()));
        appendLog("set_jackpot", Map.of("amount", economyService.format(amount)));
        save();
    }

    public void addJackpot(double amount) {
        currentRound().setJackpot(currentRound().getJackpot() + amount);
        appendLog("add_jackpot", Map.of("amount", economyService.format(amount), "jackpot", economyService.format(getTotalPot())));
        save();
    }

    public void resetRound() {
        currentRound().reset(LocalDateTime.now(getZoneId()), 0.0D);
        clearRoundReminderState();
        appendLog("reset_round", Map.of());
        save();
    }

    public void rollbackLastDraw(CommandSender sender) {
        if (!plugin.getConfig().getBoolean("rollback.enabled", true)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rollback-disabled");
            return;
        }

        ConfigurationSection rollbackSection = dataConfig.getConfigurationSection("rollback.last");
        if (rollbackSection == null) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rollback-unavailable");
            return;
        }

        String lotteryId = normalizeLotteryId(rollbackSection.getString("lottery", getActiveLotteryId()));
        withLotteryContext(lotteryId, () -> {
            if (currentRound().getTotalTickets() > 0 && !plugin.getConfig().getBoolean("rollback.allow-overwrite-active-round", false)) {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rollback-active-round");
                return false;
            }

            if (plugin.getConfig().getBoolean("rollback.withdraw-payouts", true)) {
                ConfigurationSection payouts = rollbackSection.getConfigurationSection("payouts");
                if (payouts != null) {
                    for (String key : payouts.getKeys(false)) {
                        String uuid = payouts.getString(key + ".uuid", "");
                        double amount = payouts.getDouble(key + ".amount", 0.0D);
                        if (!uuid.isBlank() && amount > 0.0D) {
                            economyService.withdraw(Bukkit.getOfflinePlayer(UUID.fromString(uuid)), amount);
                        }
                    }
                }
            }

            LotteryRound round = currentRound();
            round.reset(LocalDateTime.parse(rollbackSection.getString("round.started-at", LocalDateTime.now(getZoneId()).toString())),
                rollbackSection.getDouble("round.jackpot", 0.0D));
            ConfigurationSection tickets = rollbackSection.getConfigurationSection("round.tickets");
            if (tickets != null) {
                for (String key : tickets.getKeys(false)) {
                    round.addTickets(UUID.fromString(key), tickets.getInt(key), 0.0D,
                        rollbackSection.getDouble("round.spent." + key, 0.0D));
                }
            }

            int payoutCount = rollbackSection.getConfigurationSection("payouts") == null
                ? 0 : rollbackSection.getConfigurationSection("payouts").getKeys(false).size();
            List<WinnerEntry> history = winnerHistory();
            for (int index = 0; index < payoutCount && !history.isEmpty(); index++) {
                history.remove(0);
            }

            dataConfig.set("rollback.last", null);
            save();
            appendLog("rollback_draw", Map.of("lottery", lotteryId));
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rollback-success", Map.of(
                "lottery", lotteryId,
                "tickets", String.valueOf(round.getTotalTickets()),
                "pot", economyService.format(getTotalPot())
            ));
            return true;
        });
    }

    public boolean requireAdminConfirmation(CommandSender sender, String action) {
        if (!plugin.getConfig().getBoolean("admin-safety.confirm-dangerous-actions", true)) {
            return false;
        }
        if (!(sender instanceof Player player)) {
            return false;
        }

        long now = System.currentTimeMillis();
        long timeoutMillis = Math.max(5L, plugin.getConfig().getLong("admin-safety.confirm-timeout-seconds", 20L)) * 1000L;
        String normalizedAction = action.toLowerCase(Locale.ROOT);
        PendingAdminAction pendingAction = pendingAdminActions.get(player.getUniqueId());
        if (pendingAction != null && pendingAction.action().equals(normalizedAction) && now <= pendingAction.expiresAtMillis()) {
            pendingAdminActions.remove(player.getUniqueId());
            return false;
        }

        pendingAdminActions.put(player.getUniqueId(), new PendingAdminAction(normalizedAction, now + timeoutMillis));
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.admin-confirm-required", Map.of(
            "action", normalizedAction,
            "seconds", String.valueOf(timeoutMillis / 1000L)
        ));
        return true;
    }

    public void createHologram(CommandSender sender, String id, String type, String statistic) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return;
        }

        String normalizedId = normalizeHologramId(id);
        if (normalizedId.isBlank()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-invalid-id");
            return;
        }

        String normalizedType = type.toLowerCase(Locale.ROOT);
        if (!normalizedType.equals("countdown") && !normalizedType.equals("statistic")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-invalid-type");
            return;
        }

        String path = "holograms." + normalizedId;
        if (plugin.getHologramsConfig().isConfigurationSection(path)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-already-exists", Map.of("id", normalizedId));
            return;
        }

        plugin.getHologramsConfig().set(path + ".type", normalizedType);
        String normalizedStatistic = normalizeStatisticKey(statistic);
        plugin.getHologramsConfig().set(path + ".statistic", normalizedStatistic);
        plugin.getHologramsConfig().set(path + ".lottery", getActiveLotteryId());
        setHologramLocation(path + ".location", player.getLocation());
        plugin.saveHologramsConfig();
        updateHolograms();

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-created", Map.of(
            "id", normalizedId,
            "type", normalizedType,
            "statistic", normalizedType.equals("statistic") ? getStatisticDisplayName(normalizedStatistic) : "-"
        ));
    }

    public void moveHologram(CommandSender sender, String id) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return;
        }

        String normalizedId = normalizeHologramId(id);
        String path = "holograms." + normalizedId;
        if (!plugin.getHologramsConfig().isConfigurationSection(path)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-not-found", Map.of("id", normalizedId));
            return;
        }

        setHologramLocation(path + ".location", player.getLocation());
        plugin.saveHologramsConfig();
        updateHolograms();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-moved", Map.of("id", normalizedId));
    }

    public void deleteHologram(CommandSender sender, String id) {
        String normalizedId = normalizeHologramId(id);
        String path = "holograms." + normalizedId;
        if (!plugin.getHologramsConfig().isConfigurationSection(path)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-not-found", Map.of("id", normalizedId));
            return;
        }

        TextDisplay display = hologramEntities.remove(normalizedId);
        if (display != null && !display.isDead()) {
            display.remove();
        }
        plugin.getHologramsConfig().set(path, null);
        plugin.saveHologramsConfig();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-deleted", Map.of("id", normalizedId));
    }

    public void listHolograms(CommandSender sender, int requestedPage) {
        ConfigurationSection hologramsSection = plugin.getHologramsConfig().getConfigurationSection("holograms");
        if (hologramsSection == null || hologramsSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-list-empty");
            return;
        }

        List<String> ids = hologramsSection.getKeys(false).stream()
            .sorted(String.CASE_INSENSITIVE_ORDER)
            .toList();
        int maxPage = Math.max(1, (int) Math.ceil(ids.size() / (double) HOLOGRAMS_PER_PAGE));
        int page = Math.max(1, Math.min(requestedPage, maxPage));

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-list-header", Map.of(
            "page", String.valueOf(page),
            "pages", String.valueOf(maxPage),
            "total", String.valueOf(ids.size())
        ));

        int start = (page - 1) * HOLOGRAMS_PER_PAGE;
        int end = Math.min(ids.size(), start + HOLOGRAMS_PER_PAGE);
        for (int index = start; index < end; index++) {
            String id = ids.get(index);
            String path = "holograms." + id;
            String type = plugin.getHologramsConfig().getString(path + ".type", "countdown");
            String statistic = "statistic".equalsIgnoreCase(type)
                ? getStatisticDisplayName(plugin.getHologramsConfig().getString(path + ".statistic", "tickets_bought"))
                : "-";
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-list-entry", Map.of(
                "id", id,
                "type", type,
                "statistic", statistic,
                "world", plugin.getHologramsConfig().getString(path + ".location.world", "?"),
                "x", formatCoordinate(plugin.getHologramsConfig().getDouble(path + ".location.x", 0.0D)),
                "y", formatCoordinate(plugin.getHologramsConfig().getDouble(path + ".location.y", 0.0D)),
                "z", formatCoordinate(plugin.getHologramsConfig().getDouble(path + ".location.z", 0.0D))
            ));
        }

        sendHologramListNavigation(sender, page, maxPage);
    }

    private void sendHologramListNavigation(CommandSender sender, int page, int maxPage) {
        if (maxPage <= 1) {
            return;
        }

        Component navigation = Component.empty();
        boolean hasButton = false;
        if (page > 1) {
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.hologram-list-previous", Map.of("page", String.valueOf(page - 1))))
                .clickEvent(ClickEvent.runCommand("/lottery hologram list " + (page - 1))));
            hasButton = true;
        }

        if (page < maxPage) {
            if (hasButton) {
                navigation = navigation.append(Component.space());
            }
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.hologram-list-next", Map.of("page", String.valueOf(page + 1))))
                .clickEvent(ClickEvent.runCommand("/lottery hologram list " + (page + 1))));
        }

        sender.sendMessage(navigation);
    }

    private String formatCoordinate(double coordinate) {
        return String.format(Locale.US, "%.1f", coordinate);
    }

    private Component legacy(String message) {
        return LEGACY_SERIALIZER.deserialize(message);
    }

    private FileConfiguration gui(Player player) {
        return plugin.getGuiConfig(player);
    }

    public void openMenu(Player player) {
        if (operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                openMenu(player);
                return null;
            });
            return;
        }

        FileConfiguration gui = gui(player);
        if (!gui.getBoolean("gui.enabled", true)) {
            showStatus(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, gui.getInt("gui.size", 27),
            MessageUtil.color(getMainGuiTitle(player)));
        Material fillerMaterial = parseMaterial(gui.getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = gui.getConfigurationSection("gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public boolean isLotteryMenu(String title) {
        for (FileConfiguration gui : plugin.getGuiConfigs()) {
            if (isGuiTitle(gui, title)) {
                return true;
            }
        }
        return title.equals(MessageUtil.color(getMainGuiTitle()))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("stats-gui.title", "&6Lottery Statistik")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("personal-stats-gui.title", "&6Deine Statistik")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("winner-wall-gui.title", "&6Gewinnerwand")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("language-gui.title", "&6Sprache")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-gui.title", "&cLotterie Admin")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("profile-gui.title", "&6Lotterie auswählen")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("editor-gui.title", "&dGUI Editor")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-overview-gui.title", "&cAdmin Übersicht")));
    }

    private boolean isGuiTitle(FileConfiguration gui, String title) {
        return title.equals(MessageUtil.color(gui.getString("gui.title", "&6Lottery")))
            || title.equals(MessageUtil.color(gui.getString("stats-gui.title", "&6Lottery Statistik")))
            || title.equals(MessageUtil.color(gui.getString("personal-stats-gui.title", "&6Deine Statistik")))
            || title.equals(MessageUtil.color(gui.getString("winner-wall-gui.title", "&6Gewinnerwand")))
            || title.equals(MessageUtil.color(gui.getString("language-gui.title", "&6Sprache")))
            || title.equals(MessageUtil.color(gui.getString("admin-gui.title", "&cLotterie Admin")))
            || title.equals(MessageUtil.color(gui.getString("profile-gui.title", "&6Lotterie auswaehlen")))
            || title.equals(MessageUtil.color(gui.getString("editor-gui.title", "&dGUI Editor")))
            || title.equals(MessageUtil.color(gui.getString("admin-overview-gui.title", "&cAdmin Uebersicht")));
    }

    private String getMainGuiTitle() {
        String profileTitle = getActiveProfileString("gui-title", "");
        if (profileTitle != null && !profileTitle.isBlank()) {
            return profileTitle;
        }
        return plugin.getGuiConfig().getString("gui.title", "&6Lottery");
    }

    private String getMainGuiTitle(Player player) {
        String profileTitle = getActiveProfileString("gui-title", "");
        if (profileTitle != null && !profileTitle.isBlank()) {
            return profileTitle;
        }
        return gui(player).getString("gui.title", "&6Lottery");
    }

    public void handleMenuClick(Player player, String title, int slot) {
        if (operationLotteryId == null) {
            withPlayerLotteryContext(player, () -> {
                handleMenuClick(player, title, slot);
                return null;
            });
            return;
        }

        boolean statsPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("stats-gui.title", "&6Lottery Statistik")));
        boolean personalStatsPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("personal-stats-gui.title", "&6Deine Statistik")));
        boolean winnerWallPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("winner-wall-gui.title", "&6Gewinnerwand")));
        boolean languagePage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("language-gui.title", "&6Sprache")));
        boolean adminPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-gui.title", "&cLotterie Admin")));
        boolean profilePage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("profile-gui.title", "&6Lotterie auswählen")));
        boolean editorPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("editor-gui.title", "&dGUI Editor")));
        boolean adminOverviewPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-overview-gui.title", "&cAdmin Übersicht")));
        FileConfiguration localizedGui = gui(player);
        statsPage = statsPage || title.equals(MessageUtil.color(localizedGui.getString("stats-gui.title", "&6Lottery Statistik")));
        personalStatsPage = personalStatsPage || title.equals(MessageUtil.color(localizedGui.getString("personal-stats-gui.title", "&6Deine Statistik")));
        winnerWallPage = winnerWallPage || title.equals(MessageUtil.color(localizedGui.getString("winner-wall-gui.title", "&6Gewinnerwand")));
        languagePage = languagePage || title.equals(MessageUtil.color(localizedGui.getString("language-gui.title", "&6Sprache")));
        adminPage = adminPage || title.equals(MessageUtil.color(localizedGui.getString("admin-gui.title", "&cLotterie Admin")));
        profilePage = profilePage || title.equals(MessageUtil.color(localizedGui.getString("profile-gui.title", "&6Lotterie auswaehlen")));
        editorPage = editorPage || title.equals(MessageUtil.color(localizedGui.getString("editor-gui.title", "&dGUI Editor")));
        adminOverviewPage = adminOverviewPage || title.equals(MessageUtil.color(localizedGui.getString("admin-overview-gui.title", "&cAdmin Uebersicht")));

        if (profilePage) {
            handleProfileMenuClick(player, slot);
            return;
        }
        if (editorPage) {
            handleEditorMenuClick(player, slot);
            return;
        }
        if (adminOverviewPage) {
            handleAdminOverviewClick(player, slot);
            return;
        }
        if (winnerWallPage) {
            if (slot == plugin.getGuiConfig().getInt("winner-wall-gui.back-slot", 49)) {
                openStatsMenu(player);
            }
            return;
        }
        if (languagePage) {
            String detectedLanguage = getDetectedLanguageBySlot(player, slot);
            if (!detectedLanguage.isBlank()) {
                plugin.setPlayerLanguage(player.getUniqueId(), detectedLanguage);
                MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.language-changed");
                openMenu(player);
                return;
            }
        }
        String itemRoot = statsPage ? "stats-gui.items" : personalStatsPage ? "personal-stats-gui.items" : languagePage ? "language-gui.items"
            : adminPage ? "admin-gui.items" : "gui.items";
        String itemPath = findItemPathBySlot(player, itemRoot, slot);
        if (itemPath == null) {
            return;
        }

        boolean refresh = handleItemActions(player, itemPath);
        if (refresh && player.getOpenInventory() != null) {
            if (statsPage) {
                openStatsMenu(player);
            } else if (personalStatsPage) {
                openPersonalStatsMenu(player);
            } else if (languagePage) {
                openLanguageMenu(player);
            } else if (adminPage) {
                openAdminMenu(player);
            } else if (profilePage) {
                openProfileMenu(player);
            } else if (editorPage) {
                openGuiEditorMenu(player);
            } else if (adminOverviewPage) {
                openAdminOverviewMenu(player);
            } else {
                openMenu(player);
            }
        }
    }

    public void openStatsMenu(Player player) {
        FileConfiguration gui = gui(player);
        if (!gui.getBoolean("stats-gui.enabled", true)) {
            openMenu(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, gui.getInt("stats-gui.size", 54),
            MessageUtil.color(gui.getString("stats-gui.title", "&6Lottery Statistik")));
        Material fillerMaterial = parseMaterial(gui.getString("stats-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = gui.getConfigurationSection("stats-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "stats-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openWinnerWall(Player player) {
        FileConfiguration gui = gui(player);
        int size = gui.getInt("winner-wall-gui.size", 54);
        Inventory inventory = Bukkit.createInventory(null, size,
            MessageUtil.color(gui.getString("winner-wall-gui.title", "&6Gewinnerwand")));
        Material fillerMaterial = parseMaterial(gui.getString("winner-wall-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21};
        List<WinnerEntry> winners = winnerHistory().stream().limit(slots.length).toList();
        for (int index = 0; index < winners.size(); index++) {
            WinnerEntry winner = winners.get(index);
            inventory.setItem(slots[index], createItem(Material.PLAYER_HEAD,
                "&6#" + (index + 1) + " " + winner.playerName(),
                "&7Gewinn: &f" + economyService.format(winner.amount()),
                "&7Tickets: &f" + winner.ticketsBought(),
                "&7Datum: &f" + winner.wonAt().format(WINNER_DATE_FORMAT)));
        }
        if (winners.isEmpty()) {
            inventory.setItem(22, createItem(Material.BARRIER, "&cNoch keine Gewinner", "&7Nach der ersten Ziehung füllt sich diese Wand."));
        }

        int backSlot = gui.getInt("winner-wall-gui.back-slot", 49);
        if (backSlot >= 0 && backSlot < inventory.getSize()) {
            inventory.setItem(backSlot, createItem(Material.ARROW, "&aZurück", "&7Zurück zur Statistik."));
        }
        player.openInventory(inventory);
    }

    public void openPersonalStatsMenu(Player player) {
        FileConfiguration gui = gui(player);
        if (!gui.getBoolean("personal-stats-gui.enabled", true)) {
            openMenu(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, gui.getInt("personal-stats-gui.size", 27),
            MessageUtil.color(gui.getString("personal-stats-gui.title", "&6Deine Statistik")));
        Material fillerMaterial = parseMaterial(gui.getString("personal-stats-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = gui.getConfigurationSection("personal-stats-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "personal-stats-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openLanguageMenu(Player player) {
        FileConfiguration gui = gui(player);
        Inventory inventory = Bukkit.createInventory(null, gui.getInt("language-gui.size", 27),
            MessageUtil.color(gui.getString("language-gui.title", "&6Sprache")));
        Material fillerMaterial = parseMaterial(gui.getString("language-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = gui.getConfigurationSection("language-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "language-gui.items." + key);
            }
        }

        if (gui.getBoolean("language-gui.auto-detect.enabled", true)) {
            addDetectedLanguageItems(inventory, player, gui);
        }

        player.openInventory(inventory);
    }

    private void addDetectedLanguageItems(Inventory inventory, Player player, FileConfiguration gui) {
        List<Integer> slots = getDetectedLanguageSlots(gui);
        if (slots.isEmpty()) {
            return;
        }

        List<String> languages = plugin.getAvailableLanguages().stream().sorted().toList();
        for (int index = 0; index < languages.size() && index < slots.size(); index++) {
            int slot = slots.get(index);
            if (slot < 0 || slot >= inventory.getSize()) {
                continue;
            }
            String language = languages.get(index);
            Map<String, String> placeholders = createCommonPlaceholders(player);
            placeholders.put("language", language);
            placeholders.put("language_name", plugin.getLanguageDisplayName(language));
            placeholders.put("selected", language.equalsIgnoreCase(plugin.getPlayerLanguage(player.getUniqueId())) ? "ausgewaehlt" : "");

            Material material = parseMaterial(gui.getString("language-gui.auto-detect.material", "LIME_DYE"), Material.LIME_DYE);
            String name = MessageUtil.raw(player, gui, "language-gui.auto-detect.name", placeholders);
            List<String> lore = MessageUtil.rawList(player, gui, "language-gui.auto-detect.lore", placeholders);
            inventory.setItem(slot, createItem(material, name, lore.toArray(String[]::new)));
        }
    }

    private String getDetectedLanguageBySlot(Player player, int clickedSlot) {
        FileConfiguration gui = gui(player);
        if (!gui.getBoolean("language-gui.auto-detect.enabled", true)) {
            return "";
        }
        List<Integer> slots = getDetectedLanguageSlots(gui);
        List<String> languages = plugin.getAvailableLanguages().stream().sorted().toList();
        for (int index = 0; index < languages.size() && index < slots.size(); index++) {
            if (slots.get(index) == clickedSlot) {
                return languages.get(index);
            }
        }
        return "";
    }

    private List<Integer> getDetectedLanguageSlots(FileConfiguration gui) {
        List<Integer> slots = new ArrayList<>();
        List<?> configuredSlots = gui.getList("language-gui.auto-detect.slots", List.of(10, 11, 12, 13, 14, 15, 16));
        for (Object entry : configuredSlots) {
            if (entry instanceof Number number) {
                slots.add(number.intValue());
            } else {
                try {
                    slots.add(Integer.parseInt(String.valueOf(entry).trim()));
                } catch (NumberFormatException exception) {
                    plugin.getLogger().warning("Invalid language GUI auto slot: " + entry);
                }
            }
        }
        return slots;
    }

    public void openAdminMenu(Player player) {
        if (!player.hasPermission("lottery.admin")) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.no-permission");
            return;
        }
        FileConfiguration gui = gui(player);
        if (!gui.getBoolean("admin-gui.enabled", true)) {
            showStatus(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, gui.getInt("admin-gui.size", 27),
            MessageUtil.color(gui.getString("admin-gui.title", "&cLotterie Admin")));
        Material fillerMaterial = parseMaterial(gui.getString("admin-gui.filler-material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = gui.getConfigurationSection("admin-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "admin-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openProfileMenu(Player player) {
        if (!areLotteryProfilesEnabled()) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.profiles-disabled");
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("profile-gui.size", 54),
            MessageUtil.color(plugin.getGuiConfig().getString("profile-gui.title", "&6Lotterie auswählen")));
        ItemStack filler = createItem(parseMaterial(plugin.getGuiConfig().getString("profile-gui.filler-material", "BLACK_STAINED_GLASS_PANE"),
            Material.BLACK_STAINED_GLASS_PANE), "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        List<String> profiles = getSelectableLotteryProfileIds(player);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int index = 0; index < profiles.size() && index < slots.length; index++) {
            String id = profiles.get(index);
            boolean selected = id.equalsIgnoreCase(getSelectedLotteryId(player));
            ItemStack item = createItem(selected ? Material.EMERALD_BLOCK : Material.CHEST,
                (selected ? "&a" : "&e") + getLotteryDisplayName(id),
                "&7ID: &f" + id,
                "&7Topf: &f" + withLotteryContext(id, () -> economyService.format(getTotalPot())),
                "&7Tickets: &f" + withLotteryContext(id, () -> String.valueOf(currentRound().getTotalTickets())),
                selected ? "&aAktuell ausgewählt" : "&aKlicken zum Auswählen");
            inventory.setItem(slots[index], item);
        }

        inventory.setItem(49, createItem(Material.ARROW, "&aZurück", "&7Zurück zur Lotterie."));
        player.openInventory(inventory);
    }

    private void handleProfileMenuClick(Player player, int slot) {
        if (slot == 49) {
            openMenu(player);
            return;
        }

        List<String> profiles = getSelectableLotteryProfileIds(player);
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};
        for (int index = 0; index < profiles.size() && index < slots.length; index++) {
            if (slots[index] == slot) {
                setPlayerLottery(player, profiles.get(index), true);
                return;
            }
        }
    }

    public void openGuiEditorMenu(Player player) {
        if (!player.hasPermission("lottery.admin")) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.no-permission");
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("editor-gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("editor-gui.title", "&dGUI Editor")));
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(10, createItem(Material.CHEST, "&eHauptmenü", "&7Klick: Items auflisten", "&7Befehl: /lottery editor set gui <item> <feld> <wert>"));
        inventory.setItem(11, createItem(Material.BOOK, "&eStatistik-Menü", "&7Klick: Items auflisten", "&7Root: stats-gui"));
        inventory.setItem(12, createItem(Material.PLAYER_HEAD, "&ePersönliche Statistik", "&7Klick: Items auflisten", "&7Root: personal-stats-gui"));
        inventory.setItem(13, createItem(Material.WRITABLE_BOOK, "&eSprachmenü", "&7Klick: Items auflisten", "&7Root: language-gui"));
        inventory.setItem(14, createItem(Material.COMMAND_BLOCK, "&cAdmin-Menü", "&7Klick: Items auflisten", "&7Root: admin-gui"));
        inventory.setItem(16, createItem(Material.ANVIL, "&aConfig-Update", "&7Fügt fehlende Default-Keys ein.", "&aKlicken zum Ausführen"));
        inventory.setItem(22, createItem(Material.ARROW, "&aZurück", "&7Zurück zum Admin-Menü."));
        player.openInventory(inventory);
    }

    private void handleEditorMenuClick(Player player, int slot) {
        switch (slot) {
            case 10 -> listGuiEditorItems(player, "gui");
            case 11 -> listGuiEditorItems(player, "stats-gui");
            case 12 -> listGuiEditorItems(player, "personal-stats-gui");
            case 13 -> listGuiEditorItems(player, "language-gui");
            case 14 -> listGuiEditorItems(player, "admin-gui");
            case 16 -> updateConfigFiles(player);
            case 22 -> openAdminMenu(player);
            default -> {
            }
        }
    }

    public void openAdminOverviewMenu(Player player) {
        if (!player.hasPermission("lottery.admin")) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.no-permission");
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("admin-overview-gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("admin-overview-gui.title", "&cAdmin Übersicht")));
        ItemStack filler = createItem(Material.RED_STAINED_GLASS_PANE, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(10, createItem(Material.CHEST, "&eAktive Runden", "&7Zeigt Tickets und Topf je Profil."));
        inventory.setItem(12, createItem(Material.EMERALD, "&aTransaktionen", "&7Öffnet die Transaktionen im Chat."));
        inventory.setItem(14, createItem(Material.BOOK, "&eAdmin-Log", "&7Öffnet das Admin-Log im Chat."));
        inventory.setItem(16, createItem(Material.PAPER, "&bOffene Zahlungen", "&7Zeigt ausstehende Zahlungen."));
        inventory.setItem(22, createItem(Material.ARROW, "&aZurück", "&7Zurück zum Admin-Menü."));
        player.openInventory(inventory);
    }

    private void handleAdminOverviewClick(Player player, int slot) {
        switch (slot) {
            case 10 -> showActiveRounds(player);
            case 12 -> listTransactions(player, 1);
            case 14 -> listAdminLog(player, 1);
            case 16 -> listPendingPaymentOverview(player);
            case 22 -> openAdminMenu(player);
            default -> {
            }
        }
    }

    public void listGuiEditorItems(CommandSender sender, String menu) {
        String root = normalizeGuiRoot(menu);
        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection(root + ".items");
        if (itemsSection == null || itemsSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-empty", Map.of("menu", root));
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-header", Map.of("menu", root));
        for (String key : itemsSection.getKeys(false).stream().sorted().toList()) {
            String path = root + ".items." + key;
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-entry", Map.of(
                "item", key,
                "slot", String.valueOf(plugin.getGuiConfig().getInt(path + ".slot", -1)),
                "material", plugin.getGuiConfig().getString(path + ".material", "-"),
                "name", plugin.getGuiConfig().getString(path + ".name", "")
            ));
        }
    }

    public void editGuiItem(CommandSender sender, String menu, String item, String field, String value) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return;
        }

        String root = normalizeGuiRoot(menu);
        String itemKey = normalizeConfigKey(item);
        String path = root + ".items." + itemKey;
        if (!plugin.getGuiConfig().isConfigurationSection(path)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-item-missing", Map.of(
                "menu", root,
                "item", itemKey
            ));
            return;
        }

        String normalizedField = field.toLowerCase(Locale.ROOT).replace("-", "_");
        switch (normalizedField) {
            case "slot" -> plugin.getGuiConfig().set(path + ".slot", parsePositiveInt(value, 0));
            case "material" -> plugin.getGuiConfig().set(path + ".material", value.toUpperCase(Locale.ROOT));
            case "name" -> plugin.getGuiConfig().set(path + ".name", value);
            case "permission", "view_permission" -> plugin.getGuiConfig().set(path + ".permission", value);
            case "hide_without_permission" -> plugin.getGuiConfig().set(path + ".hide-without-permission", Boolean.parseBoolean(value));
            case "lore" -> plugin.getGuiConfig().set(path + ".lore", List.of(value.split("\\|", -1)));
            case "actions" -> plugin.getGuiConfig().set(path + ".actions", List.of(value.split("\\|", -1)));
            case "add_action" -> {
                List<String> actions = new ArrayList<>(plugin.getGuiConfig().getStringList(path + ".actions"));
                actions.add(value);
                plugin.getGuiConfig().set(path + ".actions", actions);
            }
            case "buy_amount" -> plugin.getGuiConfig().set(path + ".buy-amount", parsePositiveInt(value, 1));
            default -> {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-field-invalid");
                return;
            }
        }

        plugin.saveGuiConfig();
        appendLog("gui_editor_update", Map.of("menu", root, "item", itemKey, "field", normalizedField, "sender", sender.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.editor-updated", Map.of(
            "menu", root,
            "item", itemKey,
            "field", normalizedField
        ));
    }

    public void updateConfigFiles(CommandSender sender) {
        int updated = plugin.updateConfigDefaults();
        reload();
        appendLog("config_update", Map.of("updated", String.valueOf(updated), "sender", sender.getName()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.config-update-success", Map.of(
            "files", String.valueOf(updated),
            "report", plugin.getLastConfigUpdateReportFile() == null ? "-" : plugin.getLastConfigUpdateReportFile().getAbsolutePath()
        ));
    }

    private String normalizeGuiRoot(String menu) {
        String normalized = menu.toLowerCase(Locale.ROOT).replace("_", "-");
        return switch (normalized) {
            case "main", "gui" -> "gui";
            case "stats", "statistik" -> "stats-gui";
            case "personal", "personal-stats" -> "personal-stats-gui";
            case "language", "sprache" -> "language-gui";
            case "admin" -> "admin-gui";
            default -> normalized.endsWith("-gui") ? normalized : "gui";
        };
    }

    public void showActiveRounds(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rounds-header");
        for (String lotteryId : getScheduledLotteryIds()) {
            withLotteryContext(lotteryId, () -> {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.rounds-entry", Map.of(
                    "id", lotteryId,
                    "name", getActiveLotteryDisplayName(),
                    "tickets", String.valueOf(currentRound().getTotalTickets()),
                    "players", String.valueOf(currentRound().getUniquePlayers()),
                    "pot", economyService.format(getTotalPot()),
                    "next_draw", formatNextDraw()
                ));
                return null;
            });
        }
    }

    public void listPendingPaymentOverview(CommandSender sender) {
        if (pendingPayments.isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.payments-empty");
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.payments-header", Map.of(
            "amount", String.valueOf(pendingPayments.size())
        ));
        pendingPayments.stream().limit(10).forEach(payment -> MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.payments-entry", Map.of(
            "id", payment.id(),
            "player", getCachedPlayerName(payment.playerId()),
            "amount", economyService.format(payment.amount()),
            "reason", payment.reason(),
            "attempts", String.valueOf(payment.attempts())
        )));
    }

    public String resolvePlaceholder(OfflinePlayer player, String params) {
        String normalizedParams = params.toLowerCase(Locale.ROOT);
        String topValue = resolveTopPlaceholder(normalizedParams);
        if (topValue != null) {
            return topValue;
        }
        if (normalizedParams.startsWith("my_rank_") && player != null) {
            int rank = getPlayerRank(player.getUniqueId(), normalizeStatisticKey(normalizedParams.substring("my_rank_".length())));
            return rank > 0 ? String.valueOf(rank) : "-";
        }

        return switch (normalizedParams) {
            case "jackpot", "pot" -> economyService.format(getTotalPot());
            case "payout_pot" -> economyService.format(getDrawPayoutAmount());
            case "ticket_pot" -> economyService.format(currentRound().getJackpot());
            case "base_pot" -> economyService.format(getConfiguredBasePot());
            case "ticket_price" -> economyService.format(getTicketPrice());
            case "lottery_id" -> getActiveLotteryId();
            case "lottery_name" -> getActiveLotteryDisplayName();
            case "selected_lottery_id" -> player instanceof Player onlinePlayer ? getSelectedLotteryId(onlinePlayer) : getActiveLotteryId();
            case "selected_lottery_name" -> player instanceof Player onlinePlayer ? getLotteryDisplayName(getSelectedLotteryId(onlinePlayer)) : getActiveLotteryDisplayName();
            case "lottery_type" -> getLotteryType();
            case "draw_time" -> TimeUtil.formatLocalTime(getNextDrawAt().toLocalTime());
            case "draw_times" -> formatDrawTimes();
            case "next_draw" -> formatNextDraw();
            case "time_left" -> TimeUtil.formatDurationCompact(Duration.between(ZonedDateTime.now(getZoneId()), getNextDrawAt()));
            case "total_tickets" -> String.valueOf(currentRound().getTotalTickets());
            case "players" -> String.valueOf(currentRound().getUniquePlayers());
            case "min_players" -> String.valueOf(getMinimumPlayers());
            case "pending_notifications" -> String.valueOf(getPendingNotificationCount());
            case "pending_payments" -> String.valueOf(pendingPayments.size());
            case "tax_collected_total" -> economyService.format(totalTaxCollected);
            case "season_id" -> getSeasonId();
            case "season_points" -> player == null ? "0" : String.valueOf(getSeasonPoints(player.getUniqueId()));
            case "winner_count" -> String.valueOf(getPrizeShares().size());
            case "boost_multiplier" -> formatDecimal(getJackpotBoostMultiplier());
            case "player_tickets" -> player == null ? "0" : String.valueOf(getTicketsFor(player.getUniqueId()));
            case "player_chance" -> player == null ? "0.00%" : formatChance(getWinChance(player.getUniqueId()));
            case "player_tickets_bought" -> player == null ? "0" : String.valueOf(getStatsFor(player.getUniqueId()).getTicketsBought());
            case "player_money_spent" -> player == null ? economyService.format(0.0D) : economyService.format(getStatsFor(player.getUniqueId()).getMoneySpent());
            case "player_wins" -> player == null ? "0" : String.valueOf(getStatsFor(player.getUniqueId()).getWins());
            case "player_total_won" -> player == null ? economyService.format(0.0D) : economyService.format(getStatsFor(player.getUniqueId()).getTotalWon());
            case "player_stats_tickets_bought" -> player == null ? "0" : String.valueOf(getStatsFor(player.getUniqueId()).getTicketsBought());
            case "player_stats_money_spent" -> player == null ? economyService.format(0.0D) : economyService.format(getStatsFor(player.getUniqueId()).getMoneySpent());
            case "player_stats_wins" -> player == null ? "0" : String.valueOf(getStatsFor(player.getUniqueId()).getWins());
            case "player_stats_highest_win" -> player == null ? economyService.format(0.0D) : economyService.format(getStatsFor(player.getUniqueId()).getHighestWin());
            case "player_stats_total_won" -> player == null ? economyService.format(0.0D) : economyService.format(getStatsFor(player.getUniqueId()).getTotalWon());
            case "player_stats_rounds_played" -> player == null ? "0" : String.valueOf(getStatsFor(player.getUniqueId()).getRoundsPlayed());
            case "player_stats_profit" -> {
                if (player == null) {
                    yield economyService.format(0.0D);
                }
                PlayerLotteryStats stats = getStatsFor(player.getUniqueId());
                yield economyService.format(stats.getTotalWon() - stats.getMoneySpent());
            }
            case "season_tickets_bought" -> player == null ? "0" : String.valueOf(getSeasonStatsFor(player.getUniqueId()).getTicketsBought());
            case "season_money_spent" -> player == null ? economyService.format(0.0D) : economyService.format(getSeasonStatsFor(player.getUniqueId()).getMoneySpent());
            case "season_wins" -> player == null ? "0" : String.valueOf(getSeasonStatsFor(player.getUniqueId()).getWins());
            case "season_total_won" -> player == null ? economyService.format(0.0D) : economyService.format(getSeasonStatsFor(player.getUniqueId()).getTotalWon());
            case "season_profit" -> {
                if (player == null) {
                    yield economyService.format(0.0D);
                }
                PlayerLotteryStats stats = getSeasonStatsFor(player.getUniqueId());
                yield economyService.format(stats.getTotalWon() - stats.getMoneySpent());
            }
            default -> null;
        };
    }

    private String resolveTopPlaceholder(String params) {
        if (!params.startsWith("top_")) {
            return null;
        }

        String[] parts = params.split("_");
        if (parts.length < 3) {
            return null;
        }

        String selector = parts[parts.length - 1];
        String rankText = parts[parts.length - 2];
        String statistic = params.substring("top_".length(), params.length() - rankText.length() - selector.length() - 2);
        if (!selector.equals("name") && !selector.equals("value")) {
            selector = "entry";
            rankText = parts[parts.length - 1];
            statistic = params.substring("top_".length(), params.length() - rankText.length() - 1);
        }

        int rank = parsePositiveInt(rankText, 0);
        if (rank <= 0) {
            return null;
        }

        TopEntry entry = getTopEntry(normalizeStatisticKey(statistic), rank);
        if (entry == null) {
            return "";
        }

        return switch (selector) {
            case "name" -> entry.name();
            case "value" -> entry.value();
            default -> entry.name() + " - " + entry.value();
        };
    }

    private TopEntry getTopEntry(String statistic, int rank) {
        List<TopEntry> entries = getTopEntryList(statistic);
        return rank <= entries.size() ? entries.get(rank - 1) : null;
    }

    private List<TopEntry> getTopEntryList(String statistic) {
        return switch (statistic) {
            case "rounds_played" -> getTopLongEntries(PlayerLotteryStats::getRoundsPlayed, value -> value + " Runden");
            case "tickets_bought" -> getTopLongEntries(PlayerLotteryStats::getTicketsBought, value -> value + " Tickets");
            case "money_spent" -> getTopDoubleEntries(PlayerLotteryStats::getMoneySpent, economyService::format);
            case "wins" -> getTopLongEntries(PlayerLotteryStats::getWins, value -> value + " Gewinne");
            case "highest_win" -> getTopDoubleEntries(PlayerLotteryStats::getHighestWin, economyService::format);
            case "total_won" -> getTopDoubleEntries(PlayerLotteryStats::getTotalWon, economyService::format);
            case "current_tickets" -> getCurrentTicketTopEntries();
            case "last_winners" -> getLastWinnerEntries();
            default -> List.of();
        };
    }

    private int getPlayerRank(UUID playerId, String statistic) {
        List<UUID> rankedPlayers;
        if ("current_tickets".equals(statistic)) {
            rankedPlayers = currentRound().getTicketsByPlayer().entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
                .map(Map.Entry::getKey)
                .toList();
        } else {
            rankedPlayers = playerStats.entrySet().stream()
                .filter(entry -> getStatisticValue(entry.getValue(), statistic) > 0.0D)
                .sorted(Comparator.<Map.Entry<UUID, PlayerLotteryStats>>comparingDouble(entry -> getStatisticValue(entry.getValue(), statistic)).reversed())
                .map(Map.Entry::getKey)
                .toList();
        }

        for (int index = 0; index < rankedPlayers.size(); index++) {
            if (rankedPlayers.get(index).equals(playerId)) {
                return index + 1;
            }
        }
        return 0;
    }

    private double getStatisticValue(PlayerLotteryStats stats, String statistic) {
        return switch (statistic) {
            case "rounds_played" -> stats.getRoundsPlayed();
            case "tickets_bought" -> stats.getTicketsBought();
            case "money_spent" -> stats.getMoneySpent();
            case "wins" -> stats.getWins();
            case "highest_win" -> stats.getHighestWin();
            case "total_won" -> stats.getTotalWon();
            default -> 0.0D;
        };
    }

    private int getPendingNotificationCount() {
        return pendingNotifications.values().stream().mapToInt(List::size).sum();
    }

    private void checkDraw() {
        ensureSeasonIsCurrent();
        ZonedDateTime now = ZonedDateTime.now(getZoneId());
        for (String lotteryId : getScheduledLotteryIds()) {
            withLotteryContext(lotteryId, () -> {
                checkDrawForCurrentLottery(now);
                return null;
            });
        }
    }

    private void checkDrawForCurrentLottery(ZonedDateTime now) {
        if (!isDrawDayAllowed(now.toLocalDate())) {
            return;
        }

        ZonedDateTime dueDraw = getLatestDueDrawAt(now);
        if (dueDraw == null) {
            return;
        }

        String drawKey = formatDrawKey(dueDraw);
        if (drawKey.equals(getLastDrawKey())) {
            return;
        }
        draw(Bukkit.getConsoleSender(), false, drawKey);
    }

    private List<String> getScheduledLotteryIds() {
        if (!areLotteryProfilesEnabled()) {
            return List.of("default");
        }
        List<String> ids = getLotteryProfileIds().stream()
            .filter(id -> plugin.getLotteriesConfig().getBoolean("profiles." + id + ".enabled", true))
            .toList();
        return ids.isEmpty() ? List.of("default") : ids;
    }

    private void ensureSeasonIsCurrent() {
        if (!plugin.getConfig().getBoolean("seasons.auto.enabled", false)) {
            return;
        }

        String expectedSeasonId = getAutomaticSeasonId();
        String oldSeasonId = getSeasonId();
        if (expectedSeasonId.equalsIgnoreCase(oldSeasonId)) {
            return;
        }

        Map<String, String> placeholders = new HashMap<>(createCommonPlaceholders(null));
        placeholders.put("old_season", oldSeasonId);
        placeholders.put("new_season", expectedSeasonId);
        runSeasonCommands("seasons.auto.commands-before-reset", placeholders);
        runSeasonRewardCommands(oldSeasonId, expectedSeasonId);
        seasonStats.clear();
        seasonPoints.clear();
        seasonId = expectedSeasonId;
        plugin.getConfig().set("seasons.current-id", seasonId);
        plugin.saveConfig();
        save();
        appendLog("season_auto_reset", Map.of("old_season", oldSeasonId, "new_season", expectedSeasonId));
        runSeasonCommands("seasons.auto.commands-after-reset", placeholders);
    }

    private void runSeasonRewardCommands(String oldSeasonId, String newSeasonId) {
        if (!plugin.getConfig().getBoolean("seasons.rewards.enabled", false)) {
            return;
        }

        String statistic = normalizeStatisticKey(plugin.getConfig().getString("seasons.rewards.statistic", "total_won"));
        List<TopEntry> topEntries = getSeasonRewardTopEntries(statistic);
        int limit = Math.min(Math.max(1, plugin.getConfig().getInt("seasons.rewards.top-limit", 3)), topEntries.size());
        for (int index = 0; index < limit; index++) {
            TopEntry entry = topEntries.get(index);
            Map<String, String> placeholders = new HashMap<>(createCommonPlaceholders(null));
            placeholders.put("player", entry.name());
            placeholders.put("rank", String.valueOf(index + 1));
            placeholders.put("value", entry.value());
            placeholders.put("old_season", oldSeasonId);
            placeholders.put("new_season", newSeasonId);
            for (String command : plugin.getConfig().getStringList("seasons.rewards.commands")) {
                String resolvedCommand = MessageUtil.format(null, command, placeholders);
                if (!resolvedCommand.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
                }
            }
            appendLog("season_reward", Map.of(
                "player", entry.name(),
                "rank", String.valueOf(index + 1),
                "value", entry.value(),
                "season", oldSeasonId
            ));
        }
    }

    private List<TopEntry> getSeasonRewardTopEntries(String statistic) {
        return switch (normalizeStatisticKey(statistic)) {
            case "rounds_played" -> getTopLongEntries(seasonStats, PlayerLotteryStats::getRoundsPlayed, value -> value + " Runden");
            case "tickets_bought" -> getTopLongEntries(seasonStats, PlayerLotteryStats::getTicketsBought, value -> value + " Tickets");
            case "money_spent" -> getTopDoubleEntries(seasonStats, PlayerLotteryStats::getMoneySpent, economyService::format);
            case "wins" -> getTopLongEntries(seasonStats, PlayerLotteryStats::getWins, value -> value + " Gewinne");
            case "highest_win" -> getTopDoubleEntries(seasonStats, PlayerLotteryStats::getHighestWin, economyService::format);
            default -> getTopDoubleEntries(seasonStats, PlayerLotteryStats::getTotalWon, economyService::format);
        };
    }

    private String getAutomaticSeasonId() {
        LocalDate today = LocalDate.now(getZoneId());
        String period = plugin.getConfig().getString("seasons.auto.period", "monthly").toLowerCase(Locale.ROOT);
        return switch (period) {
            case "daily", "taeglich", "täglich" -> today.toString();
            case "weekly", "woechentlich", "wöchentlich" -> {
                WeekFields weekFields = WeekFields.ISO;
                int week = today.get(weekFields.weekOfWeekBasedYear());
                int year = today.get(weekFields.weekBasedYear());
                yield String.format(Locale.US, "%04d-W%02d", year, week);
            }
            case "yearly", "jaehrlich", "jährlich" -> String.valueOf(today.getYear());
            default -> today.format(DateTimeFormatter.ofPattern("yyyy-MM"));
        };
    }

    private void runSeasonCommands(String path, Map<String, String> placeholders) {
        for (String command : plugin.getConfig().getStringList(path)) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
    }

    private void startHologramTask() {
        if (hologramTask != null) {
            hologramTask.cancel();
        }
        removeMissingHolograms();

        if (!plugin.getHologramsConfig().getBoolean("settings.enabled", true)) {
            return;
        }

        long intervalTicks = Math.max(1L, plugin.getHologramsConfig().getLong("settings.update-interval-seconds", 30L)) * 20L;
        hologramTask = Bukkit.getScheduler().runTaskTimer(plugin, this::updateHolograms, 20L, intervalTicks);
    }

    private void startAnnouncementTask() {
        if (announcementTask != null) {
            announcementTask.cancel();
        }

        if (!plugin.getConfig().getBoolean("announcements.draw.enabled", false)) {
            return;
        }

        long intervalTicks = Math.max(1L, plugin.getConfig().getLong("announcements.draw.interval-minutes", 30L)) * 60L * 20L;
        long delayTicks = Math.max(1L, plugin.getConfig().getLong("announcements.draw.start-delay-seconds", 60L)) * 20L;
        announcementTask = Bukkit.getScheduler().runTaskTimer(plugin, this::broadcastDrawAnnouncement, delayTicks, intervalTicks);
    }

    private void startPaymentRetryTask() {
        if (paymentRetryTask != null) {
            paymentRetryTask.cancel();
        }

        if (!plugin.getConfig().getBoolean("payments.retry.enabled", true)) {
            return;
        }

        long intervalTicks = Math.max(1L, plugin.getConfig().getLong("payments.retry.interval-minutes", 10L)) * 60L * 20L;
        paymentRetryTask = Bukkit.getScheduler().runTaskTimer(plugin, this::retryAllPendingPayments, intervalTicks, intervalTicks);
    }

    private void broadcastDrawAnnouncement() {
        if (areLotteryProfilesEnabled() && plugin.getConfig().getBoolean("announcements.draw.per-profile-enabled", true)) {
            for (String lotteryId : getScheduledLotteryIds()) {
                withLotteryContext(lotteryId, () -> {
                    broadcastDrawAnnouncementForCurrentLottery();
                    return null;
                });
            }
            return;
        }
        broadcastDrawAnnouncementForCurrentLottery();
    }

    private void broadcastDrawAnnouncementForCurrentLottery() {
        Map<String, String> placeholders = createCommonPlaceholders(null);
        Bukkit.getConsoleSender().sendMessage(MessageUtil.prefixed(plugin.getMessagesConfig(), "messages.draw-announcement", placeholders));
        maybeSendTicketHolderReminder(placeholders);

        boolean buttonEnabled = plugin.getConfig().getBoolean("announcements.draw.buy-button.enabled", true);
        String command = plugin.getConfig().getString("announcements.draw.buy-button.command", "/lottery gui");
        if (command == null || command.isBlank()) {
            command = "/lottery gui";
        }
        if (!command.startsWith("/")) {
            command = "/" + command;
        }
        command = MessageUtil.format(null, command, placeholders);

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isNotificationEnabled(player.getUniqueId(), "draw")) {
                continue;
            }
            Component message = legacy(MessageUtil.prefixed(player, plugin.getMessagesConfig(player), "messages.draw-announcement", placeholders));
            if (buttonEnabled) {
                String buttonText = plugin.getConfig().getString("announcements.draw.buy-button.text");
                if (buttonText == null || buttonText.isBlank()) {
                    buttonText = MessageUtil.raw(player, plugin.getMessagesConfig(player), "messages.draw-announcement-button", placeholders);
                } else {
                    buttonText = MessageUtil.format(player, buttonText, placeholders);
                }
                message = message.append(Component.space())
                    .append(legacy(buttonText).clickEvent(ClickEvent.runCommand(command)));
            }
            player.sendMessage(message);
        }
    }

    private void maybeSendTicketHolderReminder(Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("notifications.ticket-holder-reminder.enabled", false)) {
            return;
        }

        ZonedDateTime nextDraw = getNextDrawAt();
        long minutesLeft = Duration.between(ZonedDateTime.now(getZoneId()), nextDraw).toMinutes();
        long threshold = Math.max(1L, plugin.getConfig().getLong("notifications.ticket-holder-reminder.minutes-before-draw", 10L));
        if (minutesLeft < 0L || minutesLeft > threshold) {
            return;
        }

        String key = getActiveLotteryId() + ":" + formatDrawKey(nextDraw);
        if (ticketHolderReminderSent.getOrDefault(key, false)) {
            return;
        }
        ticketHolderReminderSent.put(key, true);

        Map<String, String> reminderPlaceholders = new HashMap<>(placeholders);
        reminderPlaceholders.put("minutes", String.valueOf(Math.max(0L, minutesLeft)));
        for (UUID playerId : currentRound().getTicketsByPlayer().keySet()) {
            Player onlinePlayer = Bukkit.getPlayer(playerId);
            if (onlinePlayer != null && isNotificationEnabled(playerId, "draw")) {
                MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.ticket-holder-reminder", reminderPlaceholders);
            }
        }
    }

    private void clearRoundReminderState() {
        String lotteryId = getActiveLotteryId();
        potReminderSent.remove(lotteryId);
        ticketHolderReminderSent.keySet().removeIf(key -> key.startsWith(lotteryId + ":"));
    }

    private void updateHolograms() {
        removeMissingHolograms();
        ConfigurationSection hologramsSection = plugin.getHologramsConfig().getConfigurationSection("holograms");
        if (hologramsSection == null) {
            return;
        }

        for (String id : hologramsSection.getKeys(false)) {
            updateHologram(id, "holograms." + id);
        }
    }

    private void updateHologram(String id, String path) {
        Location location = getHologramLocation(path + ".location");
        if (location == null || location.getWorld() == null) {
            return;
        }

        TextDisplay hologram = hologramEntities.get(id);
        if (hologram == null || hologram.isDead()) {
            hologram = location.getWorld().spawn(location, TextDisplay.class);
            hologram.setPersistent(false);
            hologram.setBillboard(Display.Billboard.CENTER);
            hologram.setSeeThrough(plugin.getHologramsConfig().getBoolean("settings.see-through", false));
            hologram.setShadowed(plugin.getHologramsConfig().getBoolean("settings.shadowed", true));
            hologramEntities.put(id, hologram);
        } else if (!hologram.getLocation().getWorld().equals(location.getWorld())
            || hologram.getLocation().distanceSquared(location) > 0.01D) {
            hologram.teleport(location);
        }

        String lotteryId = plugin.getHologramsConfig().getString(path + ".lottery", getActiveLotteryId());
        hologram.setText(withLotteryContext(lotteryId, () ->
            MessageUtil.format(null, buildHologramText(path), createCommonPlaceholders(null))));
    }

    private String buildHologramText(String path) {
        String type = plugin.getHologramsConfig().getString(path + ".type", "countdown");
        String statistic = normalizeStatisticKey(plugin.getHologramsConfig().getString(path + ".statistic", "tickets_bought"));
        List<String> configuredLines = plugin.getHologramsConfig().getStringList(path + ".lines");
        if (configuredLines.isEmpty()) {
            configuredLines = getHologramTemplateLines(type, statistic);
        }

        if (configuredLines.isEmpty()) {
            configuredLines = List.of("&6&lCraftplay Lotterie", "&7Nächste Ziehung in &e%time_left%");
        }

        Map<String, String> placeholders = createCommonPlaceholders(null);
        placeholders.put("statistic", getStatisticDisplayName(statistic));
        List<String> lines = configuredLines.stream()
            .map(line -> MessageUtil.format(null, line, placeholders))
            .toList();
        return String.join("\n", expandHologramLines(lines, statistic));
    }

    private List<String> getHologramTemplateLines(String type, String statistic) {
        if ("statistic".equalsIgnoreCase(type)) {
            List<String> statisticLines = plugin.getHologramsConfig()
                .getStringList("templates.statistics." + normalizeStatisticKey(statistic) + ".lines");
            if (!statisticLines.isEmpty()) {
                return statisticLines;
            }

            List<String> defaultStatisticLines = plugin.getHologramsConfig()
                .getStringList("templates.statistics.default.lines");
            if (!defaultStatisticLines.isEmpty()) {
                return defaultStatisticLines;
            }

            List<String> legacyStatisticLines = plugin.getHologramsConfig().getStringList("templates.statistic.lines");
            if (!legacyStatisticLines.isEmpty()) {
                return legacyStatisticLines;
            }
        }

        return plugin.getHologramsConfig().getStringList("templates." + type.toLowerCase(Locale.ROOT) + ".lines");
    }

    private Location getHologramLocation(String path) {
        String worldName = plugin.getHologramsConfig().getString(path + ".world", "");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            return null;
        }

        return new Location(
            world,
            plugin.getHologramsConfig().getDouble(path + ".x", 0.0D),
            plugin.getHologramsConfig().getDouble(path + ".y", 0.0D),
            plugin.getHologramsConfig().getDouble(path + ".z", 0.0D),
            (float) plugin.getHologramsConfig().getDouble(path + ".yaw", 0.0D),
            (float) plugin.getHologramsConfig().getDouble(path + ".pitch", 0.0D)
        );
    }

    private void removeHologram() {
        for (TextDisplay hologram : hologramEntities.values()) {
            if (hologram != null && !hologram.isDead()) {
                hologram.remove();
            }
        }
        hologramEntities.clear();
    }

    private void removeMissingHolograms() {
        ConfigurationSection hologramsSection = plugin.getHologramsConfig().getConfigurationSection("holograms");
        List<String> configuredIds = hologramsSection == null ? List.of() : new ArrayList<>(hologramsSection.getKeys(false));
        hologramEntities.entrySet().removeIf(entry -> {
            boolean missing = !configuredIds.contains(entry.getKey());
            if (missing && entry.getValue() != null && !entry.getValue().isDead()) {
                entry.getValue().remove();
            }
            return missing;
        });
    }

    private void setHologramLocation(String path, Location location) {
        plugin.getHologramsConfig().set(path + ".world", location.getWorld().getName());
        plugin.getHologramsConfig().set(path + ".x", location.getX());
        plugin.getHologramsConfig().set(path + ".y", location.getY());
        plugin.getHologramsConfig().set(path + ".z", location.getZ());
        plugin.getHologramsConfig().set(path + ".yaw", location.getYaw());
        plugin.getHologramsConfig().set(path + ".pitch", location.getPitch());
    }

    private String normalizeHologramId(String id) {
        return id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
    }

    private String normalizeConfigKey(String value) {
        if (value == null) {
            return "default";
        }
        String normalized = value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "");
        return normalized.isBlank() ? "default" : normalized;
    }

    private int getSeasonPoints(UUID playerId) {
        return Math.max(0, seasonPoints.getOrDefault(playerId, 0));
    }

    private DrawResult draw(CommandSender trigger, boolean forced, String drawKey) {
        MessageUtil.send(trigger, plugin.getMessagesConfig(trigger), "messages.draw-start");

        if (currentRound().getTotalTickets() <= 0) {
            currentRound().reset(LocalDateTime.now(getZoneId()), 0.0D);
            clearRoundReminderState();
            setLastDrawKey(drawKey);
            save();
            Map<String, String> placeholders = createCommonPlaceholders(null);
            placeholders.put("carryover", economyService.format(0.0D));
            placeholders.put("refunded", economyService.format(0.0D));
            placeholders.put("refunded_players", "0");
            placeholders.put("refunded_tickets", "0");
            placeholders.put("refund_failed", "0");
            if (plugin.getConfig().getBoolean("settings.broadcast-no-players", true)) {
                broadcastConfigured("messages.draw-no-players", placeholders);
            }
            sendWebhookEvent("draw-no-players", placeholders);
            appendLog("draw_no_players", placeholders);
            runAutoBackupAfterDraw();
            return new DrawResult(forced, "messages.admin-draw-no-players", placeholders);
        }

        if (currentRound().getUniquePlayers() < getMinimumPlayers()) {
            RefundSummary refundSummary = shouldRefundOnNotEnoughPlayers() ? refundCurrentRoundTickets() : RefundSummary.empty();
            currentRound().reset(LocalDateTime.now(getZoneId()), 0.0D);
            clearRoundReminderState();
            setLastDrawKey(drawKey);
            save();
            Map<String, String> placeholders = createCommonPlaceholders(null);
            addRefundPlaceholders(placeholders, refundSummary);
            placeholders.put("carryover", economyService.format(0.0D));
            broadcastConfigured("messages.draw-not-enough-players", placeholders);
            sendWebhookEvent("draw-not-enough-players", placeholders);
            appendLog("draw_not_enough_players", placeholders);
            runAutoBackupAfterDraw();
            return new DrawResult(forced, "messages.admin-draw-not-enough-players", placeholders);
        }

        double grossAmount = getDrawPayoutAmount();
        PayoutTaxResult payoutTax = applyPayoutTax(grossAmount);
        double amount = payoutTax.netAmount();
        int totalTickets = currentRound().getTotalTickets();
        FairDrawContext fairDraw = createFairDrawContext(drawKey);
        if (fairDraw.enabled() && plugin.getConfig().getBoolean("fairness.broadcast-hash-before-draw", true)) {
            broadcastConfigured("messages.fairness-hash", fairDraw.placeholders());
        }
        List<WinnerPayout> winnerPayouts = createWinnerPayouts(amount, fairDraw.random());

        setLastDrawKey(drawKey);
        for (UUID playerId : currentRound().getTicketsByPlayer().keySet()) {
            getOrCreateStats(playerId, getCachedPlayerName(playerId)).recordRoundPlayed(getCachedPlayerName(playerId));
            getOrCreateSeasonStats(playerId, getCachedPlayerName(playerId)).recordRoundPlayed(getCachedPlayerName(playerId));
            addSeasonPoints(playerId, getCachedPlayerName(playerId), plugin.getConfig().getInt("season-shop.points.round-played", 0));
        }

        for (WinnerPayout payout : winnerPayouts) {
            getOrCreateStats(payout.playerId(), payout.playerName()).recordWin(payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()));
            getOrCreateSeasonStats(payout.playerId(), payout.playerName()).recordWin(payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()));
            addSeasonPoints(payout.playerId(), payout.playerName(), plugin.getConfig().getInt("season-shop.points.win", 0));
        }
        for (int index = winnerPayouts.size() - 1; index >= 0; index--) {
            WinnerPayout payout = winnerPayouts.get(index);
            winnerHistory().add(0, new WinnerEntry(payout.playerId(), payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()), payout.tickets()));
        }
        trimWinnerHistory();

        Map<String, String> placeholders = createCommonPlaceholders(null);
        WinnerPayout mainWinner = winnerPayouts.get(0);
        placeholders.put("player", mainWinner.playerName());
        placeholders.put("amount", economyService.format(mainWinner.amount()));
        placeholders.put("pot_amount", economyService.format(amount));
        placeholders.put("gross_amount", economyService.format(grossAmount));
        placeholders.put("payout_tax", economyService.format(payoutTax.taxAmount()));
        placeholders.put("tickets", String.valueOf(mainWinner.tickets()));
        placeholders.put("chance", formatChance((double) mainWinner.tickets() / Math.max(1, totalTickets)));
        placeholders.put("winner_count", String.valueOf(winnerPayouts.size()));
        placeholders.put("winners", formatWinnerPayouts(winnerPayouts));
        placeholders.putAll(fairDraw.placeholders());
        Player onlineWinner = Bukkit.getPlayer(mainWinner.playerId());
        long winnerDelay = runDrawAnimation(placeholders);
        runGuiDrawAnimation(winnerPayouts, winnerDelay);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getConfig().getBoolean("draw-animation.enabled", false)) {
                broadcastConfigured("messages.draw-animation-reveal", placeholders, onlineWinner);
            }
            if (winnerPayouts.size() > 1) {
                broadcastConfigured("messages.draw-winners", placeholders, onlineWinner);
            } else {
                broadcastConfigured("messages.draw-winner", placeholders, onlineWinner);
            }
            if (fairDraw.enabled() && plugin.getConfig().getBoolean("fairness.broadcast-proof-after-draw", true)) {
                broadcastConfigured("messages.fairness-proof", placeholders, onlineWinner);
            }
            for (WinnerPayout payout : winnerPayouts) {
                broadcastConfigured("messages.winner-congratulation", createWinnerPlaceholders(payout, amount, totalTickets), Bukkit.getPlayer(payout.playerId()));
            }
            playWinEffects(mainWinner.playerId());
        }, winnerDelay);

        for (WinnerPayout payout : winnerPayouts) {
            Map<String, String> payoutPlaceholders = createWinnerPlaceholders(payout, amount, totalTickets);
            boolean payoutSuccess = economyService.deposit(Bukkit.getOfflinePlayer(payout.playerId()), payout.amount());
            payoutPlaceholders.put("payout_state", payoutSuccess ? "success" : "failed");
            if (!payoutSuccess) {
                queuePendingPayment(payout.playerId(), payout.amount(), "winner", payoutPlaceholders, "messages.winner-offline-notification");
            } else if (Bukkit.getPlayer(payout.playerId()) == null && isNotificationEnabled(payout.playerId(), "win")) {
                queuePendingNotification(payout.playerId(), "messages.winner-offline-notification", payoutPlaceholders);
            }
            executeWinCommands(payoutPlaceholders);
            sendWebhook(payout.playerName(), payout.amount(), payout.tickets());
            appendLog("draw_winner", payoutPlaceholders);
            appendTransaction("winner_payout", payout.playerName(), payout.amount(), Map.of(
                "rank", String.valueOf(payout.rank()),
                "tickets", String.valueOf(payout.tickets()),
                "lottery", getActiveLotteryId()
            ));
        }
        saveRollbackSnapshot(drawKey, fairDraw, winnerPayouts);
        currentRound().reset(LocalDateTime.now(getZoneId()), 0.0D);
        clearRoundReminderState();
        save();
        appendLog("draw_winner", placeholders);
        runAutoBackupAfterDraw();
        return new DrawResult(forced, "messages.admin-draw-success", placeholders);
    }

    private void saveRollbackSnapshot(String drawKey, FairDrawContext fairDraw, List<WinnerPayout> winnerPayouts) {
        String root = "rollback.last";
        dataConfig.set(root, null);
        dataConfig.set(root + ".lottery", getActiveLotteryId());
        dataConfig.set(root + ".draw-key", drawKey);
        dataConfig.set(root + ".created-at", LocalDateTime.now(getZoneId()).toString());
        dataConfig.set(root + ".fair.seed", fairDraw.seed());
        dataConfig.set(root + ".fair.hash", fairDraw.hash());
        dataConfig.set(root + ".round.jackpot", currentRound().getJackpot());
        dataConfig.set(root + ".round.started-at", currentRound().getStartedAt().toString());
        for (Map.Entry<UUID, Integer> entry : currentRound().getTicketsByPlayer().entrySet()) {
            dataConfig.set(root + ".round.tickets." + entry.getKey(), entry.getValue());
            dataConfig.set(root + ".round.spent." + entry.getKey(), currentRound().getSpentFor(entry.getKey()));
        }
        for (int index = 0; index < winnerPayouts.size(); index++) {
            WinnerPayout payout = winnerPayouts.get(index);
            String path = root + ".payouts." + index;
            dataConfig.set(path + ".uuid", payout.playerId().toString());
            dataConfig.set(path + ".name", payout.playerName());
            dataConfig.set(path + ".amount", payout.amount());
            dataConfig.set(path + ".tickets", payout.tickets());
        }
    }

    private void runAutoBackupAfterDraw() {
        if (!plugin.getConfig().getBoolean("backups.auto-after-draw.enabled", false)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> createBackup(Bukkit.getConsoleSender()));
    }

    private PayoutTaxResult applyPayoutTax(double grossAmount) {
        if (grossAmount <= 0.0D || isItemLottery() || !plugin.getConfig().getBoolean("payout-tax.enabled", false)) {
            return new PayoutTaxResult(grossAmount, 0.0D);
        }

        double rate = Math.max(0.0D, Math.min(1.0D, plugin.getConfig().getDouble("payout-tax.rate", 0.0D)));
        double taxAmount = grossAmount * rate;
        if (taxAmount <= 0.0D) {
            return new PayoutTaxResult(grossAmount, 0.0D);
        }

        String account = plugin.getConfig().getString("payout-tax.account", "");
        Map<String, String> placeholders = createCommonPlaceholders(null);
        placeholders.put("gross_amount", economyService.format(grossAmount));
        placeholders.put("payout_tax", economyService.format(taxAmount));
        placeholders.put("net_amount", economyService.format(grossAmount - taxAmount));
        if (account != null && !account.isBlank()) {
            economyService.deposit(Bukkit.getOfflinePlayer(account), taxAmount);
            appendTransaction("payout_tax", account, taxAmount, Map.of(
                "lottery", getActiveLotteryId(),
                "gross", economyService.format(grossAmount)
            ));
        }

        for (String command : plugin.getConfig().getStringList("payout-tax.commands")) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        return new PayoutTaxResult(grossAmount - taxAmount, taxAmount);
    }

    private void executeWinCommands(Map<String, String> placeholders) {
        for (String command : plugin.getConfig().getStringList("rewards.commands-on-win")) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        String rank = placeholders.getOrDefault("rank", "1");
        for (String command : plugin.getConfig().getStringList("rewards.commands-by-rank." + rank)) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        executeRewardPackage(placeholders);
        executeItemLotteryPrize(placeholders);
    }

    private void executeItemLotteryPrize(Map<String, String> placeholders) {
        if (!isItemLottery()) {
            return;
        }

        for (String command : plugin.getConfig().getStringList("item-lottery.prize.commands")) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }

        int amount = Math.max(0, plugin.getConfig().getInt("item-lottery.prize.item.amount", 0));
        if (amount <= 0) {
            return;
        }

        try {
            Player player = Bukkit.getPlayer(UUID.fromString(placeholders.getOrDefault("player_uuid", "")));
            if (player == null) {
                return;
            }
            Material material = parseMaterial(plugin.getConfig().getString("item-lottery.prize.item.material", "DIAMOND"), Material.DIAMOND);
            player.getInventory().addItem(new ItemStack(material, amount)).values().forEach(item ->
                player.getWorld().dropItemNaturally(player.getLocation(), item));
        } catch (IllegalArgumentException ignored) {
            plugin.getLogger().warning("Could not give item lottery prize: invalid winner UUID.");
        }
    }

    private void executeRewardPackage(Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("rewards.packages.enabled", false)) {
            return;
        }

        ConfigurationSection packagesSection = plugin.getConfig().getConfigurationSection("rewards.packages.items");
        if (packagesSection == null || packagesSection.getKeys(false).isEmpty()) {
            return;
        }

        int totalWeight = 0;
        for (String key : packagesSection.getKeys(false)) {
            totalWeight += Math.max(0, packagesSection.getInt(key + ".weight", 1));
        }
        if (totalWeight <= 0) {
            return;
        }

        int selected = random.nextInt(totalWeight) + 1;
        int cursor = 0;
        for (String key : packagesSection.getKeys(false)) {
            cursor += Math.max(0, packagesSection.getInt(key + ".weight", 1));
            if (cursor < selected) {
                continue;
            }

            Map<String, String> rewardPlaceholders = new HashMap<>(placeholders);
            rewardPlaceholders.put("reward_package", packagesSection.getString(key + ".name", key));
            for (String command : packagesSection.getStringList(key + ".commands")) {
                String resolvedCommand = MessageUtil.format(null, command, rewardPlaceholders);
                if (!resolvedCommand.isBlank()) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
                }
            }
            appendLog("reward_package", Map.of(
                "player", placeholders.getOrDefault("player", "?"),
                "package", rewardPlaceholders.get("reward_package")
            ));
            return;
        }
    }

    private long runDrawAnimation(Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("draw-animation.enabled", false)) {
            return 0L;
        }

        List<Integer> countdown = plugin.getConfig().getIntegerList("draw-animation.countdown");
        long delay = 0L;
        for (int value : countdown) {
            Map<String, String> animationPlaceholders = new HashMap<>(placeholders);
            animationPlaceholders.put("seconds", String.valueOf(value));
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                Bukkit.broadcastMessage(MessageUtil.prefixed(plugin.getMessagesConfig(), "messages.draw-animation-countdown", animationPlaceholders)), delay);
            delay += Math.max(1L, plugin.getConfig().getLong("draw-animation.step-seconds", 1L)) * 20L;
        }
        return delay;
    }

    private void runGuiDrawAnimation(List<WinnerPayout> winnerPayouts, long revealDelay) {
        if (!plugin.getConfig().getBoolean("draw-animation.gui.enabled", false) || winnerPayouts.isEmpty()) {
            return;
        }

        List<String> candidates = currentRound().getTicketsByPlayer().keySet().stream()
            .map(this::getCachedPlayerName)
            .toList();
        if (candidates.isEmpty()) {
            return;
        }

        int size = plugin.getConfig().getInt("draw-animation.gui.size", 27);
        int slot = Math.max(0, Math.min(size - 1, plugin.getConfig().getInt("draw-animation.gui.winner-slot", 13)));
        String title = MessageUtil.color(plugin.getConfig().getString("draw-animation.gui.title", "&6Lotterie Ziehung"));
        int spins = Math.max(3, plugin.getConfig().getInt("draw-animation.gui.spins", 12));
        long interval = Math.max(1L, plugin.getConfig().getLong("draw-animation.gui.interval-ticks", 4L));
        List<Player> viewers = new ArrayList<>(Bukkit.getOnlinePlayers());

        for (Player viewer : viewers) {
            Inventory inventory = Bukkit.createInventory(null, size, title);
            ItemStack filler = createItem(Material.BLACK_STAINED_GLASS_PANE, "&0");
            for (int index = 0; index < size; index++) {
                inventory.setItem(index, filler);
            }
            viewer.openInventory(inventory);
        }

        for (int spin = 0; spin < spins; spin++) {
            long delay = spin * interval;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                String candidate = candidates.get(random.nextInt(candidates.size()));
                for (Player viewer : viewers) {
                    if (viewer.isOnline() && viewer.getOpenInventory() != null) {
                        viewer.getOpenInventory().getTopInventory().setItem(slot, createItem(Material.NAME_TAG,
                            "&e" + candidate,
                            "&7Die Ziehung läuft..."));
                    }
                }
            }, delay);
        }

        long finalDelay = Math.max(revealDelay, spins * interval);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            WinnerPayout winner = winnerPayouts.get(0);
            for (Player viewer : viewers) {
                if (viewer.isOnline() && viewer.getOpenInventory() != null) {
                    viewer.getOpenInventory().getTopInventory().setItem(slot, createItem(Material.NETHER_STAR,
                        "&6" + winner.playerName(),
                        "&7Gewinn: &f" + economyService.format(winner.amount()),
                        "&7Tickets: &f" + winner.tickets()));
                }
            }
        }, finalDelay);
    }

    private RefundSummary refundCurrentRoundTickets() {
        double refunded = 0.0D;
        int refundedPlayers = 0;
        int refundedTickets = 0;
        int failedRefunds = 0;

        for (Map.Entry<UUID, Integer> entry : currentRound().getTicketsByPlayer().entrySet()) {
            int tickets = entry.getValue();
            if (tickets <= 0) {
                continue;
            }

            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
            if (isItemLottery()) {
                int itemAmount = getItemLotteryStakeAmount() * tickets;
                refundItemLotteryStake(entry.getKey(), itemAmount);
                refundedPlayers++;
                refundedTickets += tickets;
                appendTransaction("item_refund", player.getName() != null ? player.getName() : entry.getKey().toString(), 0.0D, Map.of(
                    "tickets", String.valueOf(tickets),
                    "items", String.valueOf(itemAmount),
                    "material", getItemLotteryStakeMaterial().name(),
                    "reason", "not_enough_players",
                    "lottery", getActiveLotteryId()
                ));
                notifyItemRefund(entry.getKey(), tickets, itemAmount);
                continue;
            }

            double amount = currentRound().getSpentFor(entry.getKey());
            if (amount <= 0.0D) {
                amount = tickets * getTicketPrice();
            }
            if (economyService.deposit(player, amount)) {
                refunded += amount;
                refundedPlayers++;
                refundedTickets += tickets;
                appendTransaction("refund", player.getName() != null ? player.getName() : entry.getKey().toString(), amount, Map.of(
                    "tickets", String.valueOf(tickets),
                    "reason", "not_enough_players",
                    "lottery", getActiveLotteryId()
                ));
                Player onlinePlayer = Bukkit.getPlayer(entry.getKey());
                if (onlinePlayer != null && isNotificationEnabled(entry.getKey(), "refund")) {
                    MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.draw-refund", Map.of(
                        "amount", economyService.format(amount),
                        "tickets", String.valueOf(tickets)
                    ));
                } else if (onlinePlayer == null && isNotificationEnabled(entry.getKey(), "refund")) {
                    queuePendingNotification(entry.getKey(), "messages.draw-refund-offline", Map.of(
                        "amount", economyService.format(amount),
                        "tickets", String.valueOf(tickets)
                    ));
                }
            } else {
                failedRefunds++;
                queuePendingPayment(entry.getKey(), amount, "refund", Map.of(
                    "amount", economyService.format(amount),
                    "tickets", String.valueOf(tickets)
                ), "messages.draw-refund-offline");
                plugin.getLogger().warning("Could not refund lottery tickets for " + entry.getKey() + ".");
            }
        }

        return new RefundSummary(refunded, refundedPlayers, refundedTickets, failedRefunds);
    }

    private void refundItemLotteryStake(UUID playerId, int itemAmount) {
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null) {
            onlinePlayer.getInventory().addItem(new ItemStack(getItemLotteryStakeMaterial(), itemAmount)).values().forEach(item ->
                onlinePlayer.getWorld().dropItemNaturally(onlinePlayer.getLocation(), item));
        }

        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("player", getCachedPlayerName(playerId));
        placeholders.put("player_uuid", playerId.toString());
        placeholders.put("item_amount", String.valueOf(itemAmount));
        placeholders.put("item", getItemLotteryStakeMaterial().name());
        placeholders.put("lottery", getActiveLotteryId());
        for (String command : plugin.getConfig().getStringList("item-lottery.refund.commands")) {
            String resolvedCommand = MessageUtil.format(onlinePlayer, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
    }

    private void notifyItemRefund(UUID playerId, int tickets, int itemAmount) {
        Map<String, String> placeholders = Map.of(
            "amount", "0",
            "tickets", String.valueOf(tickets),
            "item_amount", String.valueOf(itemAmount),
            "item", getItemLotteryStakeMaterial().name()
        );
        Player onlinePlayer = Bukkit.getPlayer(playerId);
        if (onlinePlayer != null && isNotificationEnabled(playerId, "refund")) {
            MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.draw-refund-items", placeholders);
        } else if (onlinePlayer == null && isNotificationEnabled(playerId, "refund")) {
            queuePendingNotification(playerId, "messages.draw-refund-items-offline", placeholders);
        }
    }

    private void addRefundPlaceholders(Map<String, String> placeholders, RefundSummary refundSummary) {
        placeholders.put("refunded", economyService.format(refundSummary.amount()));
        placeholders.put("refunded_players", String.valueOf(refundSummary.players()));
        placeholders.put("refunded_tickets", String.valueOf(refundSummary.tickets()));
        placeholders.put("refund_failed", String.valueOf(refundSummary.failed()));
    }

    private void addFileToZip(ZipOutputStream zipOutputStream, File file, String entryName) throws IOException {
        if (!file.exists() || !file.isFile()) {
            return;
        }

        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        Files.copy(file.toPath(), zipOutputStream);
        zipOutputStream.closeEntry();
    }

    private void addStringToZip(ZipOutputStream zipOutputStream, String entryName, String content) throws IOException {
        zipOutputStream.putNextEntry(new ZipEntry(entryName));
        zipOutputStream.write(content.getBytes(StandardCharsets.UTF_8));
        zipOutputStream.closeEntry();
    }

    public void appendLog(String action, Map<String, String> values) {
        if (!plugin.getConfig().getBoolean("admin-log.enabled", true)) {
            return;
        }

        String key = System.currentTimeMillis() + "-" + Math.abs(random.nextInt(999_999));
        String path = "entries." + key;
        logConfig.set(path + ".time", LocalDateTime.now(getZoneId()).toString());
        logConfig.set(path + ".action", action);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            logConfig.set(path + ".data." + entry.getKey(), entry.getValue());
        }
        trimAdminLog();

        try {
            logConfig.save(logFile);
            databaseStorage.saveSnapshot("admin-log", logFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save admin-log.yml: " + exception.getMessage());
        }
    }

    private void appendTransaction(String type, String player, double amount, Map<String, String> details) {
        if (!plugin.getConfig().getBoolean("transactions.enabled", true)) {
            return;
        }

        String key = System.currentTimeMillis() + "-" + Math.abs(random.nextInt(999_999));
        String path = "entries." + key;
        transactionConfig.set(path + ".time", LocalDateTime.now(getZoneId()).toString());
        transactionConfig.set(path + ".type", type);
        transactionConfig.set(path + ".player", player);
        transactionConfig.set(path + ".amount", economyService.format(amount));
        transactionConfig.set(path + ".raw-amount", amount);
        transactionConfig.set(path + ".details", formatTransactionDetails(details));
        trimTransactionLog();
        try {
            transactionConfig.save(transactionFile);
            databaseStorage.saveSnapshot("transactions", transactionFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save transactions.yml: " + exception.getMessage());
        }
    }

    private String formatTransactionDetails(Map<String, String> details) {
        List<String> entries = new ArrayList<>();
        for (Map.Entry<String, String> entry : details.entrySet()) {
            entries.add(entry.getKey() + "=" + entry.getValue());
        }
        return String.join(", ", entries);
    }

    private void trimTransactionLog() {
        ConfigurationSection entriesSection = transactionConfig.getConfigurationSection("entries");
        if (entriesSection == null) {
            return;
        }

        int maxEntries = Math.max(1, plugin.getConfig().getInt("transactions.max-entries", 1000));
        List<String> keys = entriesSection.getKeys(false).stream().sorted().toList();
        int entriesToRemove = keys.size() - maxEntries;
        for (int index = 0; index < entriesToRemove; index++) {
            transactionConfig.set("entries." + keys.get(index), null);
        }
    }

    private void trimAdminLog() {
        ConfigurationSection entriesSection = logConfig.getConfigurationSection("entries");
        if (entriesSection == null) {
            return;
        }

        int maxEntries = Math.max(1, plugin.getConfig().getInt("admin-log.max-entries", 500));
        List<String> keys = entriesSection.getKeys(false).stream().sorted().toList();
        int entriesToRemove = keys.size() - maxEntries;
        for (int index = 0; index < entriesToRemove; index++) {
            logConfig.set("entries." + keys.get(index), null);
        }
    }

    private void queuePendingNotification(UUID playerId, String messagePath, Map<String, String> placeholders) {
        pendingNotifications.computeIfAbsent(playerId, ignored -> new ArrayList<>())
            .add(new PendingNotification(messagePath, new HashMap<>(placeholders)));
    }

    private void queuePendingPayment(UUID playerId, double amount, String reason, Map<String, String> placeholders, String successMessagePath) {
        pendingPayments.add(new PendingPayment(
            UUID.randomUUID().toString(),
            playerId,
            amount,
            reason,
            successMessagePath,
            new HashMap<>(placeholders),
            0,
            LocalDateTime.now(getZoneId()).toString()
        ));
        appendLog("queue_pending_payment", Map.of(
            "player", getCachedPlayerName(playerId),
            "amount", economyService.format(amount),
            "reason", reason
        ));
    }

    public void retryAllPendingPayments() {
        if (pendingPayments.isEmpty()) {
            return;
        }
        retryPendingPayments(null);
    }

    public void retryPendingPayments(UUID onlyPlayerId) {
        if (pendingPayments.isEmpty()) {
            return;
        }

        List<PendingPayment> remaining = new ArrayList<>();
        boolean changed = false;
        for (PendingPayment payment : pendingPayments) {
            if (onlyPlayerId != null && !payment.playerId().equals(onlyPlayerId)) {
                remaining.add(payment);
                continue;
            }

            OfflinePlayer player = Bukkit.getOfflinePlayer(payment.playerId());
            if (economyService.deposit(player, payment.amount())) {
                queuePendingNotification(payment.playerId(), payment.successMessagePath(), payment.placeholders());
                appendLog("retry_payment_success", Map.of(
                    "player", getCachedPlayerName(payment.playerId()),
                    "amount", economyService.format(payment.amount()),
                    "reason", payment.reason()
                ));
                changed = true;
            } else {
                remaining.add(new PendingPayment(
                    payment.id(),
                    payment.playerId(),
                    payment.amount(),
                    payment.reason(),
                    payment.successMessagePath(),
                    payment.placeholders(),
                    payment.attempts() + 1,
                    LocalDateTime.now(getZoneId()).toString()
                ));
                changed = true;
            }
        }

        pendingPayments.clear();
        pendingPayments.addAll(remaining);
        if (changed) {
            save();
        }
    }

    private void playWinEffects(UUID winnerId) {
        if (!plugin.getConfig().getBoolean("notifications.win-effects.enabled", true)) {
            return;
        }

        String soundName = plugin.getConfig().getString("notifications.win-effects.sound", "ENTITY_PLAYER_LEVELUP");
        Sound sound;
        try {
            sound = Sound.valueOf(soundName);
        } catch (IllegalArgumentException exception) {
            sound = Sound.ENTITY_PLAYER_LEVELUP;
        }

        String title = MessageUtil.color(plugin.getConfig().getString("notifications.win-effects.title", "&6Lottery Gewinner!"));
        String subtitleTemplate = plugin.getConfig().getString("notifications.win-effects.subtitle", "&e%player% &7hat &6%amount% &7gewonnen.");

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            onlinePlayer.playSound(onlinePlayer.getLocation(), sound, 1.0F, 1.0F);
            onlinePlayer.sendTitle(title, MessageUtil.color(subtitleTemplate)
                .replace("%player%", Bukkit.getOfflinePlayer(winnerId).getName() != null ? Bukkit.getOfflinePlayer(winnerId).getName() : winnerId.toString())
                .replace("%amount%", economyService.format(winnerHistory().get(0).amount())), 10, 70, 20);
        }
    }

    private void sendWebhook(String winnerName, double amount, int winnerTickets) {
        if (!plugin.getConfig().getBoolean("webhook.enabled", false)) {
            return;
        }

        String webhookUrl = plugin.getConfig().getString("webhook.url", "");
        if (webhookUrl.isBlank()) {
            return;
        }

        String username = plugin.getConfig().getString("webhook.username", "Lottery");
        String content = plugin.getConfig().getString("webhook.message",
            "**Lottery:** %player% hat %amount% mit %tickets% Ticket(s) gewonnen.");
        content = applyWebhookPlaceholders(content, winnerName, amount, winnerTickets);

        String payload;
        if (plugin.getConfig().getBoolean("webhook.embed.enabled", false)) {
            Map<String, String> placeholders = createCommonPlaceholders(null);
            String title = applyWebhookPlaceholders(plugin.getConfig().getString("webhook.embed.title", "Lottery Gewinner"), winnerName, amount, winnerTickets);
            String description = applyWebhookPlaceholders(plugin.getConfig().getString("webhook.embed.description", "%player% hat %amount% gewonnen."), winnerName, amount, winnerTickets);
            int color = plugin.getConfig().getInt("webhook.embed.color", 16766720);
            payload = "{\"username\":\"" + escapeJson(username) + "\",\"content\":\"" + escapeJson(content)
                + "\",\"embeds\":[{\"title\":\"" + escapeJson(title)
                + "\",\"description\":\"" + escapeJson(description)
                + "\",\"color\":" + color
                + ",\"fields\":["
                + "{\"name\":\"Gewinner\",\"value\":\"" + escapeJson(winnerName) + "\",\"inline\":true},"
                + "{\"name\":\"Gewinn\",\"value\":\"" + escapeJson(economyService.format(amount)) + "\",\"inline\":true},"
                + "{\"name\":\"Tickets\",\"value\":\"" + winnerTickets + "\",\"inline\":true},"
                + "{\"name\":\"Lotterie\",\"value\":\"" + escapeJson(getActiveLotteryDisplayName()) + "\",\"inline\":true},"
                + "{\"name\":\"Topf\",\"value\":\"" + escapeJson(placeholders.getOrDefault("pot", "")) + "\",\"inline\":true},"
                + "{\"name\":\"Nächste Ziehung\",\"value\":\"" + escapeJson(formatNextDraw()) + "\",\"inline\":false}"
                + "]}]}";
        } else {
            payload = "{\"username\":\"" + escapeJson(username) + "\",\"content\":\"" + escapeJson(content) + "\"}";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .exceptionally(exception -> {
                plugin.getLogger().warning("Could not send lottery webhook: " + exception.getMessage());
                return null;
            });
    }

    private void sendWebhookEvent(String event, Map<String, String> placeholders) {
        if (!plugin.getConfig().getBoolean("webhook.enabled", false)
            || !plugin.getConfig().getBoolean("webhook.events." + event + ".enabled", false)) {
            return;
        }

        String webhookUrl = plugin.getConfig().getString("webhook.url", "");
        if (webhookUrl.isBlank()) {
            return;
        }

        String username = plugin.getConfig().getString("webhook.username", "Lottery");
        String content = MessageUtil.format(null, plugin.getConfig().getString("webhook.events." + event + ".message", ""), placeholders);
        String payload;
        if (plugin.getConfig().getBoolean("webhook.events." + event + ".embed.enabled", false)) {
            String title = MessageUtil.format(null, plugin.getConfig().getString("webhook.events." + event + ".embed.title", "Lottery"), placeholders);
            String description = MessageUtil.format(null, plugin.getConfig().getString("webhook.events." + event + ".embed.description", content), placeholders);
            int color = plugin.getConfig().getInt("webhook.events." + event + ".embed.color", plugin.getConfig().getInt("webhook.embed.color", 16766720));
            payload = "{\"username\":\"" + escapeJson(username) + "\",\"content\":\"" + escapeJson(content)
                + "\",\"embeds\":[{\"title\":\"" + escapeJson(title)
                + "\",\"description\":\"" + escapeJson(description)
                + "\",\"color\":" + color + "}]}";
        } else {
            payload = "{\"username\":\"" + escapeJson(username) + "\",\"content\":\"" + escapeJson(content) + "\"}";
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(webhookUrl))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload))
            .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.discarding())
            .exceptionally(exception -> {
                plugin.getLogger().warning("Could not send lottery webhook event " + event + ": " + exception.getMessage());
                return null;
            });
    }

    private String applyWebhookPlaceholders(String input, String winnerName, double amount, int winnerTickets) {
        return input
            .replace("%player%", winnerName)
            .replace("%amount%", economyService.format(amount))
            .replace("%tickets%", String.valueOf(winnerTickets))
            .replace("%pot%", economyService.format(amount))
            .replace("%next_draw%", formatNextDraw());
    }

    private void broadcastConfigured(String path, Map<String, String> placeholders) {
        Bukkit.broadcastMessage(MessageUtil.prefixed(plugin.getMessagesConfig(), path, placeholders));
    }

    private void broadcastConfigured(String path, Map<String, String> placeholders, Player placeholderPlayer) {
        Bukkit.broadcastMessage(MessageUtil.prefixed(placeholderPlayer, plugin.getMessagesConfig(), path, placeholders));
    }

    private UUID pickWinner() {
        int winningNumber = random.nextInt(currentRound().getTotalTickets()) + 1;
        int cursor = 0;
        for (Map.Entry<UUID, Integer> entry : currentRound().getTicketsByPlayer().entrySet()) {
            cursor += entry.getValue();
            if (cursor >= winningNumber) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Could not resolve lottery winner.");
    }

    private FairDrawContext createFairDrawContext(String drawKey) {
        if (!plugin.getConfig().getBoolean("fairness.enabled", true)) {
            return new FairDrawContext(false, "", "", drawKey, random);
        }

        String seed = UUID.randomUUID() + "-" + System.nanoTime();
        String hash = sha256(seed);
        String ticketSnapshot = currentRound().getTicketsByPlayer().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(entry -> entry.getKey() + ":" + entry.getValue())
            .reduce("", (left, right) -> left + "|" + right);
        long randomSeed = bytesToLong(sha256Bytes(seed + "|" + drawKey + "|" + ticketSnapshot));
        return new FairDrawContext(true, seed, hash, drawKey, new Random(randomSeed));
    }

    private static long bytesToLong(byte[] bytes) {
        long value = 0L;
        for (int index = 0; index < Math.min(8, bytes.length); index++) {
            value = (value << 8) | (bytes[index] & 0xffL);
        }
        return value;
    }

    private static String sha256(String value) {
        byte[] bytes = sha256Bytes(value);
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte current : bytes) {
            builder.append(String.format(Locale.ROOT, "%02x", current));
        }
        return builder.toString();
    }

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available.", exception);
        }
    }

    private List<WinnerPayout> createWinnerPayouts(double totalAmount) {
        return createWinnerPayouts(totalAmount, random);
    }

    private List<WinnerPayout> createWinnerPayouts(double totalAmount, Random drawRandom) {
        if (plugin.getConfig().getBoolean("winners.fixed-payouts-enabled", false)) {
            return createFixedWinnerPayouts(totalAmount, drawRandom);
        }

        List<Double> shares = getPrizeShares();
        List<UUID> winners = pickUniqueWinners(shares.size(), drawRandom);
        double totalShare = 0.0D;
        for (int index = 0; index < winners.size(); index++) {
            totalShare += shares.get(Math.min(index, shares.size() - 1));
        }
        List<WinnerPayout> payouts = new ArrayList<>();
        for (int index = 0; index < winners.size(); index++) {
            UUID winnerId = winners.get(index);
            double share = shares.get(Math.min(index, shares.size() - 1));
            double payoutAmount = totalAmount * (share / totalShare);
            String winnerName = getCachedPlayerName(winnerId);
            payouts.add(new WinnerPayout(index + 1, winnerId, winnerName, payoutAmount, currentRound().getTicketsFor(winnerId)));
        }
        return payouts;
    }

    private List<WinnerPayout> createFixedWinnerPayouts(double totalAmount, Random drawRandom) {
        List<Double> configuredPayouts = new ArrayList<>();
        for (Double payout : plugin.getConfig().getDoubleList("winners.fixed-payouts")) {
            if (payout != null && payout > 0.0D) {
                configuredPayouts.add(payout);
            }
        }
        if (configuredPayouts.isEmpty()) {
            configuredPayouts.add(totalAmount);
        }

        List<UUID> winners = pickUniqueWinners(configuredPayouts.size(), drawRandom);
        boolean capToPot = plugin.getConfig().getBoolean("winners.fixed-payouts-cap-to-pot", true);
        boolean addLeftoverToFirst = plugin.getConfig().getBoolean("winners.fixed-payouts-add-leftover-to-first", true);
        List<Double> payoutAmounts = new ArrayList<>();
        double remaining = totalAmount;
        for (int index = 0; index < winners.size(); index++) {
            double configured = configuredPayouts.get(Math.min(index, configuredPayouts.size() - 1));
            double payoutAmount = capToPot ? Math.min(configured, Math.max(0.0D, remaining)) : configured;
            payoutAmounts.add(payoutAmount);
            remaining -= payoutAmount;
        }

        if (addLeftoverToFirst && !payoutAmounts.isEmpty() && remaining > 0.0D) {
            payoutAmounts.set(0, payoutAmounts.get(0) + remaining);
        }

        List<WinnerPayout> payouts = new ArrayList<>();
        for (int index = 0; index < winners.size(); index++) {
            UUID winnerId = winners.get(index);
            payouts.add(new WinnerPayout(index + 1, winnerId, getCachedPlayerName(winnerId),
                payoutAmounts.get(index), currentRound().getTicketsFor(winnerId)));
        }
        return payouts;
    }

    private List<Double> getPrizeShares() {
        if (!plugin.getConfig().getBoolean("winners.multiple-enabled", false)) {
            return List.of(1.0D);
        }

        List<Double> shares = new ArrayList<>();
        for (Double share : plugin.getConfig().getDoubleList("winners.prize-shares")) {
            if (share != null && share > 0.0D) {
                shares.add(share);
            }
        }
        return shares.isEmpty() ? List.of(1.0D) : shares;
    }

    private List<UUID> pickUniqueWinners(int wantedWinners) {
        return pickUniqueWinners(wantedWinners, random);
    }

    private List<UUID> pickUniqueWinners(int wantedWinners, Random drawRandom) {
        Map<UUID, Integer> candidates = new HashMap<>(currentRound().getTicketsByPlayer());
        List<UUID> winners = new ArrayList<>();
        int limit = Math.min(Math.max(1, wantedWinners), candidates.size());
        for (int index = 0; index < limit; index++) {
            UUID winner = pickWinnerFromCandidates(candidates, drawRandom);
            winners.add(winner);
            candidates.remove(winner);
        }
        return winners;
    }

    private UUID pickWinnerFromCandidates(Map<UUID, Integer> candidates) {
        return pickWinnerFromCandidates(candidates, random);
    }

    private UUID pickWinnerFromCandidates(Map<UUID, Integer> candidates, Random drawRandom) {
        int totalTickets = candidates.values().stream().mapToInt(Integer::intValue).sum();
        int winningNumber = drawRandom.nextInt(totalTickets) + 1;
        int cursor = 0;
        for (Map.Entry<UUID, Integer> entry : candidates.entrySet()) {
            cursor += entry.getValue();
            if (cursor >= winningNumber) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Could not resolve lottery winner.");
    }

    private Map<String, String> createWinnerPlaceholders(WinnerPayout payout, double totalPot, int totalTickets) {
        Map<String, String> placeholders = createCommonPlaceholders(null);
        placeholders.put("rank", String.valueOf(payout.rank()));
        placeholders.put("player", payout.playerName());
        placeholders.put("player_name", payout.playerName());
        placeholders.put("player_uuid", payout.playerId().toString());
        placeholders.put("amount", economyService.format(payout.amount()));
        placeholders.put("pot_amount", economyService.format(totalPot));
        placeholders.put("tickets", String.valueOf(payout.tickets()));
        placeholders.put("chance", formatChance((double) payout.tickets() / Math.max(1, totalTickets)));
        return placeholders;
    }

    private String formatWinnerPayouts(List<WinnerPayout> payouts) {
        List<String> entries = new ArrayList<>();
        for (WinnerPayout payout : payouts) {
            entries.add("#" + payout.rank() + " " + payout.playerName() + " (" + economyService.format(payout.amount()) + ")");
        }
        return String.join(", ", entries);
    }

    private void loadRound() {
        lotteryRounds.clear();
        loadRound("default", "round");

        ConfigurationSection roundsSection = dataConfig.getConfigurationSection("rounds");
        if (roundsSection != null) {
            for (String lotteryId : roundsSection.getKeys(false)) {
                loadRound(lotteryId, "rounds." + lotteryId);
            }
        }

        for (String lotteryId : getLotteryProfileIds()) {
            roundFor(lotteryId);
        }
        roundFor("default");
    }

    private void loadRound(String lotteryId, String path) {
        LotteryRound round = roundFor(lotteryId);
        round.reset(LocalDateTime.now(getZoneId()), 0.0D);
        round.setJackpot(dataConfig.getDouble(path + ".jackpot", 0.0D));

        String startedAt = dataConfig.getString(path + ".started-at");
        if (startedAt != null && !startedAt.isBlank()) {
            round.setStartedAt(LocalDateTime.parse(startedAt));
        }

        ConfigurationSection ticketsSection = dataConfig.getConfigurationSection(path + ".tickets");
        if (ticketsSection != null) {
            for (String key : ticketsSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    int amount = ticketsSection.getInt(key, 0);
                    if (amount > 0) {
                        round.getTicketsByPlayer().put(playerId, amount);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + key);
                }
            }
        }

        ConfigurationSection spentSection = dataConfig.getConfigurationSection(path + ".spent");
        if (spentSection != null) {
            for (String key : spentSection.getKeys(false)) {
                try {
                    UUID playerId = UUID.fromString(key);
                    double amount = spentSection.getDouble(key, 0.0D);
                    if (amount > 0.0D) {
                        round.getSpentByPlayer().put(playerId, amount);
                    }
                } catch (IllegalArgumentException ignored) {
                    plugin.getLogger().warning("Skipping invalid spent UUID in data.yml: " + key);
                }
            }
        }
    }

    private void loadHistory() {
        winnerHistories.clear();
        loadHistory("default", "history");

        ConfigurationSection historiesSection = dataConfig.getConfigurationSection("histories");
        if (historiesSection != null) {
            for (String lotteryId : historiesSection.getKeys(false)) {
                loadHistory(lotteryId, "histories." + lotteryId);
            }
        }

        for (String lotteryId : getLotteryProfileIds()) {
            winnerHistoryFor(lotteryId);
        }
        winnerHistoryFor("default");
        trimWinnerHistory();
    }

    private void loadHistory(String lotteryId, String path) {
        List<WinnerEntry> history = winnerHistoryFor(lotteryId);
        history.clear();
        ConfigurationSection historySection = dataConfig.getConfigurationSection(path);
        if (historySection == null) {
            return;
        }

        for (String key : historySection.getKeys(false)) {
            ConfigurationSection entrySection = historySection.getConfigurationSection(key);
            if (entrySection == null) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(entrySection.getString("uuid", ""));
                String playerName = entrySection.getString("name", "Unknown");
                double amount = entrySection.getDouble("amount", 0.0D);
                LocalDateTime wonAt = LocalDateTime.parse(entrySection.getString("won-at"));
                int ticketsBought = entrySection.getInt("tickets-bought", 0);
                history.add(new WinnerEntry(playerId, playerName, amount, wonAt, ticketsBought));
            } catch (Exception exception) {
                plugin.getLogger().warning("Skipping invalid winner history entry " + key + ": " + exception.getMessage());
            }
        }

        history.sort(Comparator.comparing(WinnerEntry::wonAt).reversed());
    }

    private void loadStatistics() {
        playerStats.clear();
        ConfigurationSection statisticsSection = dataConfig.getConfigurationSection("statistics");
        loadStatsInto(statisticsSection, playerStats, "statistics");
    }

    private void loadSeasonStatistics() {
        seasonStats.clear();
        seasonId = dataConfig.getString("season.id", plugin.getConfig().getString("seasons.current-id", defaultSeasonId()));
        ConfigurationSection statisticsSection = dataConfig.getConfigurationSection("season.statistics");
        loadStatsInto(statisticsSection, seasonStats, "season statistics");
    }

    private void loadStatsInto(ConfigurationSection statisticsSection, Map<UUID, PlayerLotteryStats> target, String label) {
        if (statisticsSection == null) {
            return;
        }

        for (String key : statisticsSection.getKeys(false)) {
            ConfigurationSection playerSection = statisticsSection.getConfigurationSection(key);
            if (playerSection == null) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(key);
                PlayerLotteryStats stats = new PlayerLotteryStats(playerId, playerSection.getString("name", key));
                stats.setTicketsBought(playerSection.getInt("tickets-bought", 0));
                stats.setMoneySpent(playerSection.getDouble("money-spent", 0.0D));
                stats.setWins(playerSection.getInt("wins", 0));
                stats.setHighestWin(playerSection.getDouble("highest-win", 0.0D));
                stats.setTotalWon(playerSection.getDouble("total-won", 0.0D));
                stats.setRoundsPlayed(playerSection.getInt("rounds-played", 0));
                String lastPurchaseAt = playerSection.getString("last-purchase-at");
                if (lastPurchaseAt != null && !lastPurchaseAt.isBlank()) {
                    stats.setLastPurchaseAt(LocalDateTime.parse(lastPurchaseAt));
                }
                String lastWinAt = playerSection.getString("last-win-at");
                if (lastWinAt != null && !lastWinAt.isBlank()) {
                    stats.setLastWinAt(LocalDateTime.parse(lastWinAt));
                }
                target.put(playerId, stats);
            } catch (Exception exception) {
                plugin.getLogger().warning("Skipping invalid lottery " + label + " entry " + key + ": " + exception.getMessage());
            }
        }
    }

    private void loadDailyUsage() {
        dailyUsage.clear();
        ConfigurationSection usageSection = dataConfig.getConfigurationSection("daily-usage");
        if (usageSection == null) {
            return;
        }

        for (String key : usageSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                dailyUsage.put(playerId, new DailyUsage(
                    usageSection.getString(key + ".date", ""),
                    usageSection.getInt(key + ".tickets", 0),
                    usageSection.getDouble(key + ".spent", 0.0D)
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid daily usage UUID in data.yml: " + key);
            }
        }
    }

    private void loadFreeTicketClaims() {
        freeTicketClaims.clear();
        ConfigurationSection claimsSection = dataConfig.getConfigurationSection("free-ticket-claims");
        if (claimsSection == null) {
            return;
        }

        for (String playerKey : claimsSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(playerKey);
                ConfigurationSection playerSection = claimsSection.getConfigurationSection(playerKey);
                if (playerSection == null) {
                    continue;
                }
                Map<String, Long> claims = new HashMap<>();
                for (String reason : playerSection.getKeys(false)) {
                    claims.put(reason.toLowerCase(Locale.ROOT), playerSection.getLong(reason, 0L));
                }
                freeTicketClaims.put(playerId, claims);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid free ticket claim UUID in data.yml: " + playerKey);
            }
        }
    }

    private void loadSeasonPoints() {
        seasonPoints.clear();
        ConfigurationSection pointsSection = dataConfig.getConfigurationSection("season.points");
        if (pointsSection == null) {
            return;
        }

        for (String key : pointsSection.getKeys(false)) {
            try {
                int points = pointsSection.getInt(key, 0);
                if (points > 0) {
                    seasonPoints.put(UUID.fromString(key), points);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid season points UUID in data.yml: " + key);
            }
        }
    }

    private void loadPlayerLotterySelections() {
        playerLotterySelections.clear();
        ConfigurationSection selectionsSection = dataConfig.getConfigurationSection("player-lotteries");
        if (selectionsSection == null) {
            return;
        }

        for (String key : selectionsSection.getKeys(false)) {
            try {
                playerLotterySelections.put(UUID.fromString(key), normalizeLotteryId(selectionsSection.getString(key, "default")));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid player lottery selection UUID in data.yml: " + key);
            }
        }
    }

    private void loadNotificationPreferences() {
        notificationPreferences.clear();
        ConfigurationSection preferencesSection = dataConfig.getConfigurationSection("notification-preferences");
        if (preferencesSection == null) {
            return;
        }

        for (String playerKey : preferencesSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(playerKey);
                ConfigurationSection playerSection = preferencesSection.getConfigurationSection(playerKey);
                if (playerSection == null) {
                    continue;
                }
                Map<String, Boolean> preferences = new HashMap<>();
                for (String type : playerSection.getKeys(false)) {
                    preferences.put(type.toLowerCase(Locale.ROOT), playerSection.getBoolean(type, true));
                }
                notificationPreferences.put(playerId, preferences);
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid notification preference UUID in data.yml: " + playerKey);
            }
        }
    }

    private void loadMetaStats() {
        totalTaxCollected = dataConfig.getDouble("meta.total-tax-collected", 0.0D);
        if (seasonId == null || seasonId.isBlank()) {
            seasonId = plugin.getConfig().getString("seasons.current-id", defaultSeasonId());
        }
    }

    private void loadPendingNotifications() {
        pendingNotifications.clear();
        ConfigurationSection notificationsSection = dataConfig.getConfigurationSection("pending-notifications");
        if (notificationsSection == null) {
            return;
        }

        for (String playerKey : notificationsSection.getKeys(false)) {
            ConfigurationSection playerSection = notificationsSection.getConfigurationSection(playerKey);
            if (playerSection == null) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(playerKey);
                List<PendingNotification> notifications = new ArrayList<>();
                for (String notificationKey : playerSection.getKeys(false)) {
                    ConfigurationSection notificationSection = playerSection.getConfigurationSection(notificationKey);
                    if (notificationSection == null) {
                        continue;
                    }

                    String messagePath = notificationSection.getString("message", "");
                    if (messagePath.isBlank()) {
                        continue;
                    }

                    Map<String, String> placeholders = new HashMap<>();
                    ConfigurationSection placeholdersSection = notificationSection.getConfigurationSection("placeholders");
                    if (placeholdersSection != null) {
                        for (String key : placeholdersSection.getKeys(false)) {
                            placeholders.put(key, placeholdersSection.getString(key, ""));
                        }
                    }
                    notifications.add(new PendingNotification(messagePath, placeholders));
                }

                if (!notifications.isEmpty()) {
                    pendingNotifications.put(playerId, notifications);
                }
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid pending notification UUID in data.yml: " + playerKey);
            }
        }
    }

    private void loadPendingPayments() {
        pendingPayments.clear();
        ConfigurationSection paymentsSection = dataConfig.getConfigurationSection("pending-payments");
        if (paymentsSection == null) {
            return;
        }

        for (String key : paymentsSection.getKeys(false)) {
            ConfigurationSection paymentSection = paymentsSection.getConfigurationSection(key);
            if (paymentSection == null) {
                continue;
            }

            try {
                UUID playerId = UUID.fromString(paymentSection.getString("uuid", ""));
                Map<String, String> placeholders = new HashMap<>();
                ConfigurationSection placeholdersSection = paymentSection.getConfigurationSection("placeholders");
                if (placeholdersSection != null) {
                    for (String placeholder : placeholdersSection.getKeys(false)) {
                        placeholders.put(placeholder, placeholdersSection.getString(placeholder, ""));
                    }
                }
                pendingPayments.add(new PendingPayment(
                    paymentSection.getString("id", key),
                    playerId,
                    paymentSection.getDouble("amount", 0.0D),
                    paymentSection.getString("reason", "unknown"),
                    paymentSection.getString("success-message", "messages.payment-retry-success"),
                    placeholders,
                    paymentSection.getInt("attempts", 0),
                    paymentSection.getString("last-attempt", "")
                ));
            } catch (IllegalArgumentException exception) {
                plugin.getLogger().warning("Skipping invalid pending payment in data.yml: " + key);
            }
        }
    }

    private void trimWinnerHistory() {
        int historySize = plugin.getConfig().getInt("settings.history-size", 10);
        for (List<WinnerEntry> history : winnerHistories.values()) {
            while (history.size() > historySize) {
                history.remove(history.size() - 1);
            }
        }
    }

    private void saveRound(String path, LotteryRound round) {
        dataConfig.set(path + ".jackpot", round.getJackpot());
        dataConfig.set(path + ".started-at", round.getStartedAt().toString());
        dataConfig.set(path + ".tickets", null);
        for (Map.Entry<UUID, Integer> entry : round.getTicketsByPlayer().entrySet()) {
            dataConfig.set(path + ".tickets." + entry.getKey(), entry.getValue());
        }
        dataConfig.set(path + ".spent", null);
        for (Map.Entry<UUID, Double> entry : round.getSpentByPlayer().entrySet()) {
            dataConfig.set(path + ".spent." + entry.getKey(), entry.getValue());
        }
    }

    private void saveHistory(String path, List<WinnerEntry> history) {
        dataConfig.set(path, null);
        for (int index = 0; index < history.size(); index++) {
            WinnerEntry entry = history.get(index);
            String entryPath = path + "." + index;
            dataConfig.set(entryPath + ".uuid", entry.playerId().toString());
            dataConfig.set(entryPath + ".name", entry.playerName());
            dataConfig.set(entryPath + ".amount", entry.amount());
            dataConfig.set(entryPath + ".won-at", entry.wonAt().toString());
            dataConfig.set(entryPath + ".tickets-bought", entry.ticketsBought());
        }
    }

    private void save() {
        saveRound("round", roundFor("default"));
        dataConfig.set("rounds", null);
        for (Map.Entry<String, LotteryRound> entry : lotteryRounds.entrySet()) {
            saveRound("rounds." + entry.getKey(), entry.getValue());
        }

        lastDrawKey = lastDrawKeys.getOrDefault("default", lastDrawKey);
        dataConfig.set("last-draw-key", lastDrawKey);
        dataConfig.set("last-draw-date", lastDrawKey != null && lastDrawKey.length() >= 10 ? lastDrawKey.substring(0, 10) : null);
        dataConfig.set("last-draw-keys", null);
        for (Map.Entry<String, String> entry : lastDrawKeys.entrySet()) {
            dataConfig.set("last-draw-keys." + entry.getKey(), entry.getValue());
        }

        saveHistory("history", winnerHistoryFor("default"));
        dataConfig.set("histories", null);
        for (Map.Entry<String, List<WinnerEntry>> entry : winnerHistories.entrySet()) {
            saveHistory("histories." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("statistics", null);
        for (PlayerLotteryStats stats : playerStats.values()) {
            String path = "statistics." + stats.getPlayerId();
            dataConfig.set(path + ".name", stats.getPlayerName());
            dataConfig.set(path + ".tickets-bought", stats.getTicketsBought());
            dataConfig.set(path + ".money-spent", stats.getMoneySpent());
            dataConfig.set(path + ".wins", stats.getWins());
            dataConfig.set(path + ".highest-win", stats.getHighestWin());
            dataConfig.set(path + ".total-won", stats.getTotalWon());
            dataConfig.set(path + ".rounds-played", stats.getRoundsPlayed());
            dataConfig.set(path + ".last-purchase-at", stats.getLastPurchaseAt() != null ? stats.getLastPurchaseAt().toString() : null);
            dataConfig.set(path + ".last-win-at", stats.getLastWinAt() != null ? stats.getLastWinAt().toString() : null);
        }

        dataConfig.set("season.id", getSeasonId());
        dataConfig.set("season.statistics", null);
        for (PlayerLotteryStats stats : seasonStats.values()) {
            String path = "season.statistics." + stats.getPlayerId();
            dataConfig.set(path + ".name", stats.getPlayerName());
            dataConfig.set(path + ".tickets-bought", stats.getTicketsBought());
            dataConfig.set(path + ".money-spent", stats.getMoneySpent());
            dataConfig.set(path + ".wins", stats.getWins());
            dataConfig.set(path + ".highest-win", stats.getHighestWin());
            dataConfig.set(path + ".total-won", stats.getTotalWon());
            dataConfig.set(path + ".rounds-played", stats.getRoundsPlayed());
            dataConfig.set(path + ".last-purchase-at", stats.getLastPurchaseAt() != null ? stats.getLastPurchaseAt().toString() : null);
            dataConfig.set(path + ".last-win-at", stats.getLastWinAt() != null ? stats.getLastWinAt().toString() : null);
        }
        dataConfig.set("daily-usage", null);
        for (Map.Entry<UUID, DailyUsage> entry : dailyUsage.entrySet()) {
            String path = "daily-usage." + entry.getKey();
            dataConfig.set(path + ".date", entry.getValue().date());
            dataConfig.set(path + ".tickets", entry.getValue().tickets());
            dataConfig.set(path + ".spent", entry.getValue().spent());
        }

        dataConfig.set("free-ticket-claims", null);
        for (Map.Entry<UUID, Map<String, Long>> entry : freeTicketClaims.entrySet()) {
            for (Map.Entry<String, Long> claim : entry.getValue().entrySet()) {
                dataConfig.set("free-ticket-claims." + entry.getKey() + "." + claim.getKey(), claim.getValue());
            }
        }

        dataConfig.set("season.points", null);
        for (Map.Entry<UUID, Integer> entry : seasonPoints.entrySet()) {
            if (entry.getValue() > 0) {
                dataConfig.set("season.points." + entry.getKey(), entry.getValue());
            }
        }

        dataConfig.set("player-lotteries", null);
        for (Map.Entry<UUID, String> entry : playerLotterySelections.entrySet()) {
            dataConfig.set("player-lotteries." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("notification-preferences", null);
        for (Map.Entry<UUID, Map<String, Boolean>> entry : notificationPreferences.entrySet()) {
            for (Map.Entry<String, Boolean> preference : entry.getValue().entrySet()) {
                dataConfig.set("notification-preferences." + entry.getKey() + "." + preference.getKey(), preference.getValue());
            }
        }

        dataConfig.set("meta.total-tax-collected", totalTaxCollected);

        dataConfig.set("pending-notifications", null);
        for (Map.Entry<UUID, List<PendingNotification>> entry : pendingNotifications.entrySet()) {
            for (int index = 0; index < entry.getValue().size(); index++) {
                PendingNotification notification = entry.getValue().get(index);
                String path = "pending-notifications." + entry.getKey() + "." + index;
                dataConfig.set(path + ".message", notification.messagePath());
                for (Map.Entry<String, String> placeholder : notification.placeholders().entrySet()) {
                    dataConfig.set(path + ".placeholders." + placeholder.getKey(), placeholder.getValue());
                }
            }
        }

        dataConfig.set("pending-payments", null);
        for (int index = 0; index < pendingPayments.size(); index++) {
            PendingPayment payment = pendingPayments.get(index);
            String path = "pending-payments." + index;
            dataConfig.set(path + ".id", payment.id());
            dataConfig.set(path + ".uuid", payment.playerId().toString());
            dataConfig.set(path + ".amount", payment.amount());
            dataConfig.set(path + ".reason", payment.reason());
            dataConfig.set(path + ".success-message", payment.successMessagePath());
            dataConfig.set(path + ".attempts", payment.attempts());
            dataConfig.set(path + ".last-attempt", payment.lastAttempt());
            for (Map.Entry<String, String> placeholder : payment.placeholders().entrySet()) {
                dataConfig.set(path + ".placeholders." + placeholder.getKey(), placeholder.getValue());
            }
        }

        try {
            dataConfig.save(dataFile);
            databaseStorage.saveSnapshot("data", dataFile);
            databaseStorage.saveStructuredData(dataConfig);
        } catch (IOException exception) {
            plugin.getLogger().severe("Could not save data.yml: " + exception.getMessage());
        }
    }

    private double getTicketPrice() {
        double profileValue = getActiveProfileDouble("ticket-price", -1.0D);
        return profileValue > 0.0D ? profileValue : plugin.getConfig().getDouble("settings.ticket-price", 250.0D);
    }

    private double calculateTicketCost(int amount) {
        if (isItemLottery()) {
            return 0.0D;
        }
        if (!plugin.getConfig().getBoolean("pricing.tiered-prices.enabled", false)) {
            return amount * getTicketPrice();
        }

        ConfigurationSection tiersSection = plugin.getConfig().getConfigurationSection("pricing.tiered-prices.tiers");
        if (tiersSection == null) {
            return amount * getTicketPrice();
        }

        if (tiersSection.isDouble(String.valueOf(amount)) || tiersSection.isInt(String.valueOf(amount))) {
            return Math.max(0.0D, tiersSection.getDouble(String.valueOf(amount), amount * getTicketPrice()));
        }

        if (!plugin.getConfig().getBoolean("pricing.tiered-prices.best-fit", true)) {
            return amount * getTicketPrice();
        }

        List<Integer> tiers = tiersSection.getKeys(false).stream()
            .map(value -> parsePositiveInt(value, 0))
            .filter(value -> value > 0)
            .sorted(Comparator.reverseOrder())
            .toList();
        int remaining = amount;
        double cost = 0.0D;
        for (int tier : tiers) {
            int packages = remaining / tier;
            if (packages <= 0) {
                continue;
            }
            cost += packages * tiersSection.getDouble(String.valueOf(tier), tier * getTicketPrice());
            remaining -= packages * tier;
        }
        return cost + remaining * getTicketPrice();
    }

    private double getConfiguredBasePot() {
        double profileValue = getActiveProfileDouble("additional-pot-amount", -1.0D);
        double configured = profileValue >= 0.0D ? profileValue : plugin.getConfig().getDouble("settings.additional-pot-amount", 0.0D);
        return Math.max(0.0D, configured);
    }

    private double getTotalPot() {
        return currentRound().getJackpot() + getConfiguredBasePot();
    }

    private double getDrawPayoutAmount() {
        return switch (getLotteryType()) {
            case "fifty_fifty" -> getTotalPot() * 0.5D;
            case "fixed_prize" -> {
                double fixedPrize = Math.max(0.0D, plugin.getConfig().getDouble("settings.fixed-prize-amount", getTotalPot()));
                if (plugin.getConfig().getBoolean("settings.fixed-prize-cap-to-pot", true)) {
                    yield Math.min(fixedPrize, getTotalPot());
                }
                yield fixedPrize;
            }
            default -> getTotalPot();
        };
    }

    private String getLotteryType() {
        String profileType = getActiveProfileString("lottery-type", "");
        String configuredType = profileType == null || profileType.isBlank()
            ? plugin.getConfig().getString("settings.lottery-type", "jackpot")
            : profileType;
        return configuredType.toLowerCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
    }

    private double getTaxRate() {
        return Math.max(0.0D, Math.min(1.0D, plugin.getConfig().getDouble("settings.tax-rate", 0.0D)));
    }

    private double getJackpotBoostMultiplier() {
        if (!plugin.getConfig().getBoolean("jackpot-boost.enabled", false)) {
            return 1.0D;
        }
        return Math.max(1.0D, plugin.getConfig().getDouble("jackpot-boost.multiplier", 1.0D));
    }

    private int getMinimumPlayers() {
        int profileValue = getActiveProfileInt("minimum-players-to-draw", -1);
        int configured = profileValue >= 0 ? profileValue : plugin.getConfig().getInt("settings.minimum-players-to-draw", 1);
        return Math.max(0, configured);
    }

    private boolean areLotteryProfilesEnabled() {
        return plugin.getLotteriesConfig().getBoolean("settings.enabled", false);
    }

    private String getActiveLotteryId() {
        if (operationLotteryId != null && !operationLotteryId.isBlank()) {
            return operationLotteryId;
        }
        if (!areLotteryProfilesEnabled()) {
            return "default";
        }
        String configured = plugin.getLotteriesConfig().getString("settings.active-profile", "default");
        if (configured == null || configured.isBlank()
            || !plugin.getLotteriesConfig().isConfigurationSection("profiles." + configured)) {
            return "default";
        }
        return configured.toLowerCase(Locale.ROOT);
    }

    private String getSelectedLotteryId(Player player) {
        if (!areLotteryProfilesEnabled() || player == null) {
            return "default";
        }

        String selected = playerLotterySelections.get(player.getUniqueId());
        if (selected != null && isUsableLotteryProfile(player, selected)) {
            return normalizeLotteryId(selected);
        }

        String active = getActiveLotteryId();
        if (isUsableLotteryProfile(player, active)) {
            return active;
        }

        return getSelectableLotteryProfileIds(player).stream().findFirst().orElse("default");
    }

    private boolean isUsableLotteryProfile(Player player, String id) {
        String normalizedId = normalizeLotteryId(id);
        return plugin.getLotteriesConfig().isConfigurationSection("profiles." + normalizedId)
            && plugin.getLotteriesConfig().getBoolean("profiles." + normalizedId + ".enabled", true)
            && canUseLotteryProfile(player, normalizedId);
    }

    private String getLastDrawKey() {
        return lastDrawKeys.getOrDefault(getActiveLotteryId(), "");
    }

    private void setLastDrawKey(String drawKey) {
        String lotteryId = getActiveLotteryId();
        lastDrawKeys.put(lotteryId, drawKey);
        if ("default".equals(lotteryId)) {
            lastDrawKey = drawKey;
        }
    }

    private String getActiveLotteryDisplayName() {
        String activeId = getActiveLotteryId();
        return getLotteryDisplayName(activeId);
    }

    private String getLotteryDisplayName(String lotteryId) {
        String id = normalizeLotteryId(lotteryId);
        return plugin.getLotteriesConfig().getString("profiles." + id + ".display-name", id);
    }

    private String getActiveProfileString(String key, String fallback) {
        if (!areLotteryProfilesEnabled()) {
            return fallback;
        }
        return plugin.getLotteriesConfig().getString("profiles." + getActiveLotteryId() + "." + key, fallback);
    }

    private double getActiveProfileDouble(String key, double fallback) {
        if (!areLotteryProfilesEnabled()) {
            return fallback;
        }
        return plugin.getLotteriesConfig().getDouble("profiles." + getActiveLotteryId() + "." + key, fallback);
    }

    private int getActiveProfileInt(String key, int fallback) {
        if (!areLotteryProfilesEnabled()) {
            return fallback;
        }
        return plugin.getLotteriesConfig().getInt("profiles." + getActiveLotteryId() + "." + key, fallback);
    }

    private String getSeasonId() {
        if (seasonId == null || seasonId.isBlank()) {
            seasonId = plugin.getConfig().getString("seasons.current-id", defaultSeasonId());
        }
        return seasonId;
    }

    private String defaultSeasonId() {
        return LocalDate.now(getZoneId()).format(DateTimeFormatter.ofPattern("yyyy-MM"));
    }

    private boolean shouldRefundOnNotEnoughPlayers() {
        return plugin.getConfig().getBoolean("settings.refund-on-not-enough-players", true);
    }

    private String validateEligibility(Player player) {
        String profilePermission = getActiveProfileString("permission", "");
        if (profilePermission != null && !profilePermission.isBlank() && !player.hasPermission(profilePermission)) {
            return "messages.eligibility-denied";
        }

        String playerName = player.getName().toLowerCase(Locale.ROOT);
        String playerUuid = player.getUniqueId().toString().toLowerCase(Locale.ROOT);
        List<String> blockedPlayers = plugin.getConfig().getStringList("eligibility.players.blacklist").stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .toList();
        if (blockedPlayers.contains(playerName) || blockedPlayers.contains(playerUuid)) {
            return "messages.eligibility-denied";
        }

        if (plugin.getConfig().getBoolean("eligibility.players.whitelist.enabled", false)) {
            List<String> allowedPlayers = plugin.getConfig().getStringList("eligibility.players.whitelist.players").stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .toList();
            if (!allowedPlayers.contains(playerName) && !allowedPlayers.contains(playerUuid)) {
                return "messages.eligibility-denied";
            }
        }

        if (plugin.getConfig().getBoolean("eligibility.permission.enabled", false)) {
            String permission = plugin.getConfig().getString("eligibility.permission.node", "lottery.participate");
            if (!player.hasPermission(permission)) {
                return "messages.eligibility-denied";
            }
        }

        if (plugin.getConfig().getBoolean("eligibility.worlds.enabled", false)) {
            List<String> worlds = plugin.getConfig().getStringList("eligibility.worlds.allowed");
            if (!worlds.isEmpty() && worlds.stream().noneMatch(world -> world.equalsIgnoreCase(player.getWorld().getName()))) {
                return "messages.eligibility-denied";
            }
        }

        int minPlaytimeMinutes = plugin.getConfig().getInt("eligibility.min-playtime-minutes", 0);
        if (minPlaytimeMinutes > 0 && player.getStatistic(Statistic.PLAY_ONE_MINUTE) < minPlaytimeMinutes * 60 * 20) {
            return "messages.eligibility-denied";
        }

        if (plugin.getConfig().getBoolean("eligibility.groups.enabled", false)) {
            List<String> blockedGroups = plugin.getConfig().getStringList("eligibility.groups.blocked");
            for (String group : blockedGroups) {
                if (hasGroupPermission(player, group)) {
                    return "messages.eligibility-denied";
                }
            }

            List<String> allowedGroups = plugin.getConfig().getStringList("eligibility.groups.allowed");
            if (!allowedGroups.isEmpty() && allowedGroups.stream().noneMatch(group -> hasGroupPermission(player, group))) {
                return "messages.eligibility-denied";
            }
        }
        return null;
    }

    private boolean hasGroupPermission(Player player, String group) {
        String normalizedGroup = group.toLowerCase(Locale.ROOT);
        return player.hasPermission("group." + normalizedGroup)
            || player.hasPermission("luckperms.group." + normalizedGroup)
            || player.hasPermission("cmi.rank." + normalizedGroup)
            || hasLuckPermsGroup(player, normalizedGroup);
    }

    private boolean hasLuckPermsGroup(Player player, String normalizedGroup) {
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return false;
        }

        try {
            Class<?> providerClass = Class.forName("net.luckperms.api.LuckPermsProvider");
            Object luckPerms = providerClass.getMethod("get").invoke(null);
            Object userManager = luckPerms.getClass().getMethod("getUserManager").invoke(luckPerms);
            Object user = userManager.getClass().getMethod("getUser", UUID.class).invoke(userManager, player.getUniqueId());
            if (user == null) {
                return false;
            }
            Object primaryGroup = user.getClass().getMethod("getPrimaryGroup").invoke(user);
            return normalizedGroup.equals(String.valueOf(primaryGroup).toLowerCase(Locale.ROOT));
        } catch (ReflectiveOperationException | LinkageError exception) {
            return false;
        }
    }

    private LocalTime getDrawTime() {
        int hour = plugin.getConfig().getInt("settings.draw-time.hour", 20);
        int minute = plugin.getConfig().getInt("settings.draw-time.minute", 0);
        return LocalTime.of(hour, minute);
    }

    private ZoneId getZoneId() {
        return ZoneId.of(plugin.getConfig().getString("settings.timezone", "Europe/Vienna"));
    }

    private ZonedDateTime getNextDrawAt() {
        ZonedDateTime now = ZonedDateTime.now(getZoneId());
        for (int dayOffset = 0; dayOffset <= 14; dayOffset++) {
            LocalDate date = now.toLocalDate().plusDays(dayOffset);
            if (!isDrawDayAllowed(date)) {
                continue;
            }

            for (LocalTime drawTime : getDrawTimes()) {
                ZonedDateTime candidate = date.atTime(drawTime).atZone(getZoneId());
                if (candidate.isAfter(now) && !formatDrawKey(candidate).equals(getLastDrawKey())) {
                    return candidate;
                }
            }
        }
        return now.plusDays(1).withHour(getDrawTime().getHour()).withMinute(getDrawTime().getMinute()).withSecond(0).withNano(0);
    }

    private String formatNextDraw() {
        return getNextDrawAt().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    private List<LocalTime> getDrawTimes() {
        if (areLotteryProfilesEnabled()) {
            List<LocalTime> profileTimes = parseDrawTimes(plugin.getLotteriesConfig()
                .getStringList("profiles." + getActiveLotteryId() + ".draw-times"));
            if (!profileTimes.isEmpty()) {
                return profileTimes;
            }
        }

        if (!plugin.getConfig().getBoolean("settings.draw-schedule.multiple-draws-enabled", false)) {
            return List.of(getDrawTime());
        }

        List<LocalTime> drawTimes = parseDrawTimes(plugin.getConfig().getStringList("settings.draw-schedule.times"));
        if (drawTimes.isEmpty()) {
            drawTimes.add(getDrawTime());
        }
        return drawTimes.stream().distinct().sorted().toList();
    }

    private List<LocalTime> parseDrawTimes(List<String> values) {
        List<LocalTime> drawTimes = new ArrayList<>();
        for (String value : values) {
            try {
                drawTimes.add(LocalTime.parse(value));
            } catch (Exception exception) {
                plugin.getLogger().warning("Invalid draw time in config.yml: " + value);
            }
        }
        return drawTimes.stream().distinct().sorted().toList();
    }

    private String formatDrawTimes() {
        return getDrawTimes().stream()
            .map(TimeUtil::formatLocalTime)
            .toList()
            .toString()
            .replace("[", "")
            .replace("]", "");
    }

    private ZonedDateTime getLatestDueDrawAt(ZonedDateTime now) {
        ZonedDateTime latest = null;
        for (LocalTime drawTime : getDrawTimes()) {
            ZonedDateTime candidate = now.withHour(drawTime.getHour()).withMinute(drawTime.getMinute()).withSecond(0).withNano(0);
            if (!candidate.isAfter(now)) {
                latest = candidate;
            }
        }
        return latest;
    }

    private boolean isDrawDayAllowed(LocalDate date) {
        if (!plugin.getConfig().getBoolean("settings.draw-schedule.weekdays.enabled", false)) {
            return true;
        }

        List<String> configuredDays = plugin.getConfig().getStringList("settings.draw-schedule.weekdays.days");
        if (configuredDays.isEmpty()) {
            return true;
        }

        DayOfWeek dayOfWeek = date.getDayOfWeek();
        for (String configuredDay : configuredDays) {
            try {
                if (DayOfWeek.valueOf(configuredDay.toUpperCase(Locale.ROOT)) == dayOfWeek) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Invalid weekday in config.yml: " + configuredDay);
            }
        }
        return false;
    }

    private String formatDrawKey(ZonedDateTime drawAt) {
        return drawAt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    private String formatChance(double chance) {
        return String.format(Locale.US, "%.2f%%", chance * 100.0D);
    }

    private String formatDecimal(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private Material parseMaterial(String materialName, Material fallback) {
        String normalized = materialName.toUpperCase(Locale.ROOT).replace("-", "_").replace(" ", "_");
        if (normalized.equals("HEAD") || normalized.equals("PLAYERHEAD") || normalized.equals("PLAYER_HEADS")
            || normalized.equals("PLAYERHEADS") || normalized.equals("PLAYERHEAS") || normalized.equals("CMI_HEAD")
            || normalized.equals("CMI_PLAYER_HEAD")) {
            return Material.PLAYER_HEAD;
        }

        try {
            return Material.valueOf(normalized);
        } catch (IllegalArgumentException exception) {
            return fallback;
        }
    }

    private ItemStack createItem(Material material, String name, String... loreLines) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            applyItemMeta(meta, name, List.of(loreLines));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void applyItemMeta(ItemMeta meta, String name, List<String> loreLines) {
        meta.setDisplayName(MessageUtil.color(name));
        List<String> lore = new ArrayList<>();
        for (String line : loreLines) {
            lore.add(MessageUtil.color(line));
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
    }

    private void setConfiguredItem(Inventory inventory, Player player, String path) {
        if (!isGuiItemVisible(player, path)) {
            return;
        }

        FileConfiguration gui = gui(player);
        Map<String, String> placeholders = createItemPlaceholders(player, path);
        List<Integer> slots = getGuiSlots(player, path);
        if (slots.isEmpty()) {
            return;
        }

        Material material = parseMaterial(gui.getString(path + ".material", "STONE"), Material.STONE);
        String name = MessageUtil.raw(player, gui, path + ".name", placeholders);
        List<String> lore = expandStatsLore(MessageUtil.rawList(player, gui, path + ".lore", placeholders));
        ItemStack item = createHeadDatabaseItem(player, path, placeholders, name, lore);
        if (item == null) {
            item = createItem(material, name, lore.toArray(String[]::new));
            applySkullOwner(item, player, path, placeholders);
        }

        for (int slot : slots) {
            if (slot >= 0 && slot < inventory.getSize()) {
                inventory.setItem(slot, item);
            }
        }
    }

    private Map<String, String> createItemPlaceholders(Player player, String path) {
        Map<String, String> placeholders = createCommonPlaceholders(player);
        int buyAmount = getConfiguredBuyAmount(player, path);
        placeholders.put("buy_amount", String.valueOf(buyAmount));
        placeholders.put("buy_price", economyService.format(calculateTicketCost(buyAmount)));
        return placeholders;
    }

    private List<Integer> getGuiSlots(Player player, String path) {
        return getGuiSlots(gui(player), path);
    }

    private List<Integer> getGuiSlots(String path) {
        return getGuiSlots(plugin.getGuiConfig(), path);
    }

    private List<Integer> getGuiSlots(FileConfiguration gui, String path) {
        List<Integer> slots = new ArrayList<>();
        if (gui.isInt(path + ".slot")) {
            slots.add(gui.getInt(path + ".slot"));
        }

        for (Object entry : gui.getList(path + ".slots", List.of())) {
            if (entry instanceof Number number) {
                slots.add(number.intValue());
                continue;
            }

            String value = String.valueOf(entry).trim();
            if (value.contains("-")) {
                String[] bounds = value.split("-", 2);
                try {
                    int start = Integer.parseInt(bounds[0].trim());
                    int end = Integer.parseInt(bounds[1].trim());
                    for (int slot = Math.min(start, end); slot <= Math.max(start, end); slot++) {
                        slots.add(slot);
                    }
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid GUI slot range in " + path + ": " + value);
                }
            } else {
                try {
                    slots.add(Integer.parseInt(value));
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("Invalid GUI slot in " + path + ": " + value);
                }
            }
        }
        return slots;
    }

    private List<String> expandStatsLore(List<String> lore) {
        List<String> expanded = new ArrayList<>();
        for (String line : lore) {
            String stripped = org.bukkit.ChatColor.stripColor(line);
            if ("%top_rounds_played%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopLongStats(PlayerLotteryStats::getRoundsPlayed, value -> value + " Runden"));
            } else if ("%top_tickets_bought%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopLongStats(PlayerLotteryStats::getTicketsBought, value -> value + " Tickets"));
            } else if ("%top_money_spent%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopDoubleStats(PlayerLotteryStats::getMoneySpent, economyService::format));
            } else if ("%top_wins%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopLongStats(PlayerLotteryStats::getWins, value -> value + " Gewinne"));
            } else if ("%top_highest_win%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopDoubleStats(PlayerLotteryStats::getHighestWin, economyService::format));
            } else if ("%top_total_won%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatTopDoubleStats(PlayerLotteryStats::getTotalWon, economyService::format));
            } else if ("%top_current_tickets%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatCurrentTicketTop());
            } else if ("%last_winners%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatLastWinners());
            } else {
                expanded.add(line);
            }
        }
        return expanded;
    }

    private List<String> expandHologramLines(List<String> lines, String statistic) {
        List<String> expanded = new ArrayList<>();
        for (String line : expandStatsLore(lines)) {
            String stripped = org.bukkit.ChatColor.stripColor(line);
            if ("%selected_top%".equalsIgnoreCase(stripped)) {
                expanded.addAll(formatStatisticTop(statistic));
            } else {
                expanded.add(line);
            }
        }
        return expanded;
    }

    private List<String> formatStatisticTop(String statistic) {
        String normalizedStatistic = normalizeStatisticKey(statistic);
        return switch (normalizedStatistic) {
            case "rounds_played" -> formatTopLongStats(PlayerLotteryStats::getRoundsPlayed, value -> value + " Runden");
            case "tickets_bought" -> formatTopLongStats(PlayerLotteryStats::getTicketsBought, value -> value + " Tickets");
            case "money_spent" -> formatTopDoubleStats(PlayerLotteryStats::getMoneySpent, economyService::format);
            case "wins" -> formatTopLongStats(PlayerLotteryStats::getWins, value -> value + " Gewinne");
            case "highest_win" -> formatTopDoubleStats(PlayerLotteryStats::getHighestWin, economyService::format);
            case "total_won" -> formatTopDoubleStats(PlayerLotteryStats::getTotalWon, economyService::format);
            case "current_tickets" -> formatCurrentTicketTop();
            default -> List.of(MessageUtil.color("&cUnbekannte Statistik: " + statistic));
        };
    }

    private String normalizeStatisticKey(String statistic) {
        if (statistic == null || statistic.isBlank()) {
            return "tickets_bought";
        }

        String normalized = statistic.toLowerCase(Locale.ROOT)
            .replace("\u00e4", "ae")
            .replace("\u00f6", "oe")
            .replace("\u00fc", "ue")
            .replace("\u00df", "ss")
            .replace("-", "_")
            .replace(" ", "_");

        return switch (normalized) {
            case "am_meisten_mitgespielt", "meiste_mitgespielt", "mitgespielt", "played" -> "rounds_played";
            case "meiste_tickets", "tickets", "gekaufte_tickets" -> "tickets_bought";
            case "gesamtausgaben", "ausgaben", "gesamt_ausgegeben", "money_spent", "spent" -> "money_spent";
            case "am_haeufigsten_gewonnen", "am_haufigsten_gewonnen", "haeufigsten_gewonnen", "haufigsten_gewonnen", "meiste_gewinne", "gewinne", "wins" -> "wins";
            case "hoechster_gewinn", "hochster_gewinn", "hoechster_einzelgewinn", "hochster_einzelgewinn", "highest_win" -> "highest_win";
            case "gesamt_gewonnen", "gesammt_gewonnen", "alle_gewinne", "total_won" -> "total_won";
            case "aktuelle_tickets", "aktuelle_runde", "current_tickets" -> "current_tickets";
            default -> normalized;
        };
    }

    private String getStatisticDisplayName(String statistic) {
        String normalized = normalizeStatisticKey(statistic);
        String configuredName = plugin.getHologramsConfig()
            .getString("templates.statistics." + normalized + ".display-name");
        if (configuredName != null && !configuredName.isBlank()) {
            return configuredName;
        }

        return switch (normalized) {
            case "rounds_played" -> "Am meisten mitgespielt";
            case "tickets_bought" -> "Meiste gekaufte Tickets";
            case "money_spent" -> "Gesamtausgaben";
            case "wins" -> "Am h\u00e4ufigsten gewonnen";
            case "highest_win" -> "H\u00f6chster Gewinn";
            case "total_won" -> "Gesamt gewonnen";
            case "current_tickets" -> "Aktuelle Tickets";
            default -> statistic;
        };
    }

    private List<String> formatTopLongStats(ToLongFunction<PlayerLotteryStats> extractor, LongFunction<String> formatter) {
        List<String> lines = playerStats.values().stream()
            .filter(stats -> extractor.applyAsLong(stats) > 0)
            .sorted(Comparator.comparingLong(extractor).reversed())
            .limit(10)
            .map(stats -> "&e" + stats.getPlayerName() + " &7- &f" + formatter.apply(extractor.applyAsLong(stats)))
            .map(MessageUtil::color)
            .toList();
        return lines.isEmpty() ? List.of(MessageUtil.color("&7Noch keine Daten.")) : lines;
    }

    private List<TopEntry> getTopLongEntries(ToLongFunction<PlayerLotteryStats> extractor, LongFunction<String> formatter) {
        return getTopLongEntries(playerStats, extractor, formatter);
    }

    private List<TopEntry> getTopLongEntries(Map<UUID, PlayerLotteryStats> source,
                                             ToLongFunction<PlayerLotteryStats> extractor,
                                             LongFunction<String> formatter) {
        return source.values().stream()
            .filter(stats -> extractor.applyAsLong(stats) > 0)
            .sorted(Comparator.comparingLong(extractor).reversed())
            .limit(10)
            .map(stats -> new TopEntry(stats.getPlayerName(), formatter.apply(extractor.applyAsLong(stats))))
            .toList();
    }

    private List<String> formatTopDoubleStats(ToDoubleFunction<PlayerLotteryStats> extractor, DoubleFunction<String> formatter) {
        List<String> lines = playerStats.values().stream()
            .filter(stats -> extractor.applyAsDouble(stats) > 0.0D)
            .sorted(Comparator.comparingDouble(extractor).reversed())
            .limit(10)
            .map(stats -> "&e" + stats.getPlayerName() + " &7- &f" + formatter.apply(extractor.applyAsDouble(stats)))
            .map(MessageUtil::color)
            .toList();
        return lines.isEmpty() ? List.of(MessageUtil.color("&7Noch keine Daten.")) : lines;
    }

    private List<TopEntry> getTopDoubleEntries(ToDoubleFunction<PlayerLotteryStats> extractor, DoubleFunction<String> formatter) {
        return getTopDoubleEntries(playerStats, extractor, formatter);
    }

    private List<TopEntry> getTopDoubleEntries(Map<UUID, PlayerLotteryStats> source,
                                               ToDoubleFunction<PlayerLotteryStats> extractor,
                                               DoubleFunction<String> formatter) {
        return source.values().stream()
            .filter(stats -> extractor.applyAsDouble(stats) > 0.0D)
            .sorted(Comparator.comparingDouble(extractor).reversed())
            .limit(10)
            .map(stats -> new TopEntry(stats.getPlayerName(), formatter.apply(extractor.applyAsDouble(stats))))
            .toList();
    }

    private List<String> formatCurrentTicketTop() {
        List<String> lines = currentRound().getTicketsByPlayer().entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .limit(10)
            .map(entry -> "&e" + getCachedPlayerName(entry.getKey()) + " &7- &f" + entry.getValue() + " Tickets")
            .map(MessageUtil::color)
            .toList();
        return lines.isEmpty() ? List.of(MessageUtil.color("&7Noch keine Tickets in dieser Runde.")) : lines;
    }

    private List<TopEntry> getCurrentTicketTopEntries() {
        return currentRound().getTicketsByPlayer().entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .limit(10)
            .map(entry -> new TopEntry(getCachedPlayerName(entry.getKey()), entry.getValue() + " Tickets"))
            .toList();
    }

    private List<TopEntry> getLastWinnerEntries() {
        return winnerHistory().stream()
            .limit(10)
            .map(entry -> new TopEntry(entry.playerName(), economyService.format(entry.amount()) + " - " + entry.wonAt().format(WINNER_DATE_FORMAT)))
            .toList();
    }

    private List<String> formatLastWinners() {
        if (winnerHistory().isEmpty()) {
            return List.of(MessageUtil.color("&7Noch keine Gewinner."));
        }

        List<String> lines = new ArrayList<>();
        int limit = Math.min(10, winnerHistory().size());
        for (int index = 0; index < limit; index++) {
            WinnerEntry entry = winnerHistory().get(index);
            lines.add(MessageUtil.color("&e#" + (index + 1) + " " + entry.playerName()
                + " &7- &f" + economyService.format(entry.amount())
                + " &8(" + entry.wonAt().format(WINNER_DATE_FORMAT)
                + ", " + entry.ticketsBought() + " Tickets)"));
        }
        return lines;
    }

    private String getCachedPlayerName(UUID playerId) {
        PlayerLotteryStats stats = playerStats.get(playerId);
        if (stats != null && stats.getPlayerName() != null && !stats.getPlayerName().isBlank()) {
            return stats.getPlayerName();
        }

        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() != null ? player.getName() : playerId.toString();
    }

    private PlayerLotteryStats getOrCreateStats(UUID playerId, String playerName) {
        PlayerLotteryStats stats = playerStats.computeIfAbsent(playerId, id -> new PlayerLotteryStats(id, playerName));
        if (playerName != null && !playerName.isBlank()) {
            stats.setPlayerName(playerName);
        }
        return stats;
    }

    private PlayerLotteryStats getOrCreateSeasonStats(UUID playerId, String playerName) {
        PlayerLotteryStats stats = seasonStats.computeIfAbsent(playerId, id -> new PlayerLotteryStats(id, playerName));
        if (playerName != null && !playerName.isBlank()) {
            stats.setPlayerName(playerName);
        }
        return stats;
    }

    private PlayerLotteryStats getStatsFor(UUID playerId) {
        return playerStats.getOrDefault(playerId, new PlayerLotteryStats(playerId, getCachedPlayerName(playerId)));
    }

    private PlayerLotteryStats getSeasonStatsFor(UUID playerId) {
        return seasonStats.getOrDefault(playerId, new PlayerLotteryStats(playerId, getCachedPlayerName(playerId)));
    }

    private String findItemPathBySlot(Player player, String itemsPath, int clickedSlot) {
        FileConfiguration gui = gui(player);
        ConfigurationSection itemsSection = gui.getConfigurationSection(itemsPath);
        if (itemsSection == null) {
            return null;
        }

        String matchingPath = null;
        for (String key : itemsSection.getKeys(false)) {
            String path = itemsPath + "." + key;
            if (!isGuiItemVisible(player, path)) {
                continue;
            }
            if (getGuiSlots(gui, path).contains(clickedSlot)) {
                matchingPath = path;
            }
        }
        return matchingPath;
    }

    private boolean isGuiItemVisible(Player player, String path) {
        FileConfiguration gui = gui(player);
        String permission = gui.getString(path + ".permission",
            gui.getString(path + ".view-permission", ""));
        if (permission == null || permission.isBlank()) {
            permission = inferGuiItemPermission(player, path);
        }

        if (permission == null || permission.isBlank() || player.hasPermission(permission)) {
            return true;
        }
        return !gui.getBoolean(path + ".hide-without-permission", true);
    }

    private String inferGuiItemPermission(Player player, String path) {
        for (String action : gui(player).getStringList(path + ".actions")) {
            String normalizedAction = action.trim().toLowerCase(Locale.ROOT);
            if (normalizedAction.equals("open-admin")
                || normalizedAction.equals("player:/lottery admin")
                || normalizedAction.equals("player-command:/lottery admin")
                || normalizedAction.equals("command:/lottery admin")
                || normalizedAction.equals("/lottery admin")) {
                return "lottery.admin";
            }
        }
        return "";
    }

    private boolean handleItemActions(Player player, String path) {
        FileConfiguration gui = gui(player);
        Map<String, String> placeholders = createItemPlaceholders(player, path);
        boolean refresh = gui.getBoolean(path + ".refresh-after-click", true);
        boolean handledAction = false;

        List<String> actions = gui.getStringList(path + ".actions");
        if (actions.isEmpty() && getConfiguredBuyAmount(player, path) > 0) {
            actions = List.of("buy:%buy_amount%");
        }

        for (String action : actions) {
            handledAction = true;
            String resolvedAction = MessageUtil.format(player, action, placeholders);
            String lowerAction = resolvedAction.toLowerCase(Locale.ROOT);

            if (lowerAction.equals("close")) {
                player.closeInventory();
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-stats")) {
                openStatsMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-personal-stats")) {
                openPersonalStatsMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-winner-wall") || lowerAction.equals("open-winners")) {
                openWinnerWall(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-language")) {
                openLanguageMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-admin")) {
                openAdminMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("open-main")) {
                openMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.equals("force-draw")) {
                if (player.hasPermission("lottery.admin")) {
                    if (requireAdminConfirmation(player, "force-draw")) {
                        refresh = false;
                        continue;
                    }
                    DrawResult result = forceDraw(player);
                    MessageUtil.send(player, plugin.getMessagesConfig(player), result.adminMessagePath(), result.placeholders());
                }
                refresh = false;
                continue;
            }

            if (lowerAction.equals("reload-plugin")) {
                if (player.hasPermission("lottery.admin")) {
                    plugin.reloadPlugin();
                    MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.reload-success");
                }
                refresh = false;
                continue;
            }

            if (lowerAction.equals("reset-round")) {
                if (player.hasPermission("lottery.admin")) {
                    if (requireAdminConfirmation(player, "reset-round")) {
                        refresh = false;
                        continue;
                    }
                    resetRound();
                    MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.reset-success");
                }
                refresh = false;
                continue;
            }

            if (lowerAction.startsWith("language:") || lowerAction.startsWith("set-language:")) {
                String prefix = lowerAction.startsWith("set-language:") ? "set-language:" : "language:";
                String language = stripActionPrefix(resolvedAction, prefix).toLowerCase(Locale.ROOT);
                plugin.setPlayerLanguage(player.getUniqueId(), language);
                MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.language-changed");
                openMenu(player);
                refresh = false;
                continue;
            }

            if (lowerAction.startsWith("buy:")) {
                int amount = parsePositiveInt(resolvedAction.substring("buy:".length()), 0);
                if (amount > 0) {
                    PurchaseResult result = buyTickets(player, amount);
                    MessageUtil.send(player, plugin.getMessagesConfig(player), result.messagePath(), result.placeholders());
                    refresh = refresh && result.success();
                }
                continue;
            }

            if (lowerAction.startsWith("player-command:") || lowerAction.startsWith("player:")
                || lowerAction.startsWith("command:") || lowerAction.startsWith("/")) {
                String command = extractPlayerCommand(resolvedAction, lowerAction);
                runPlayerCommand(player, command);
                refresh = false;
                continue;
            }

            if (lowerAction.startsWith("console-command:") || lowerAction.startsWith("console:")) {
                String command = stripActionPrefix(resolvedAction, lowerAction.startsWith("console-command:") ? "console-command:" : "console:");
                runConsoleCommand(player, command);
                refresh = false;
                continue;
            }

            if (lowerAction.startsWith("message:")) {
                player.sendMessage(MessageUtil.format(player, stripActionPrefix(resolvedAction, "message:"), placeholders));
            }
        }

        return handledAction && refresh;
    }

    private int getConfiguredBuyAmount(Player player, String path) {
        int configuredAmount = gui(player).getInt(path + ".buy-amount", 0);
        if (configuredAmount > 0) {
            return configuredAmount;
        }

        String key = path.substring(path.lastIndexOf('.') + 1);
        if (key.startsWith("buy-")) {
            return parsePositiveInt(key.substring("buy-".length()), 0);
        }
        return 0;
    }

    private int getConfiguredBuyAmount(String path) {
        int configuredAmount = plugin.getGuiConfig().getInt(path + ".buy-amount", 0);
        if (configuredAmount > 0) {
            return configuredAmount;
        }

        String key = path.substring(path.lastIndexOf('.') + 1);
        if (key.startsWith("buy-")) {
            return parsePositiveInt(key.substring("buy-".length()), 0);
        }
        return 0;
    }

    private void applySkullOwner(ItemStack item, Player player, String path, Map<String, String> placeholders) {
        if (!(item.getItemMeta() instanceof SkullMeta skullMeta)) {
            return;
        }

        String owner = gui(player).getString(path + ".skull-owner", "%player_name%");
        owner = MessageUtil.format(player, owner, placeholders);
        if (!owner.isBlank()) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(owner));
            item.setItemMeta(skullMeta);
        }
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String stripActionPrefix(String action, String prefix) {
        return action.substring(prefix.length()).trim();
    }

    private String extractPlayerCommand(String resolvedAction, String lowerAction) {
        if (lowerAction.startsWith("player-command:")) {
            return stripActionPrefix(resolvedAction, "player-command:");
        }
        if (lowerAction.startsWith("player:")) {
            return stripActionPrefix(resolvedAction, "player:");
        }
        if (lowerAction.startsWith("command:")) {
            return stripActionPrefix(resolvedAction, "command:");
        }
        return resolvedAction;
    }

    private void runPlayerCommand(Player player, String command) {
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(stripLeadingSlash(command)));
    }

    private void runConsoleCommand(Player player, String command) {
        player.closeInventory();
        Bukkit.getScheduler().runTask(plugin, () ->
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(command)));
    }

    private String stripLeadingSlash(String command) {
        String trimmedCommand = command.trim();
        return trimmedCommand.startsWith("/") ? trimmedCommand.substring(1) : trimmedCommand;
    }

    private ItemStack createHeadDatabaseItem(Player player, String path, Map<String, String> placeholders, String name, List<String> lore) {
        FileConfiguration gui = gui(player);
        String headId = gui.getString(path + ".head-database-id",
            gui.getString(path + ".hdb-id", ""));
        headId = MessageUtil.format(player, headId, placeholders);
        if (headId.isBlank()) {
            return null;
        }

        if (!Bukkit.getPluginManager().isPluginEnabled("HeadDatabase")) {
            plugin.getLogger().warning("GUI item " + path + " uses HeadDatabase id " + headId
                + ", but HeadDatabase is not enabled.");
            return null;
        }

        try {
            Class<?> apiClass = Class.forName("me.arcaniax.hdb.api.HeadDatabaseAPI");
            Object api = apiClass.getConstructor().newInstance();
            Object head = apiClass.getMethod("getItemHead", String.class).invoke(api, headId);
            if (!(head instanceof ItemStack stack)) {
                return null;
            }

            ItemStack item = stack.clone();
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                applyItemMeta(meta, name, lore);
                item.setItemMeta(meta);
            }
            return item;
        } catch (ReflectiveOperationException | ClassCastException exception) {
            plugin.getLogger().warning("Could not load HeadDatabase head " + headId + " for " + path
                + ": " + exception.getMessage());
            return null;
        }
    }

    private String escapeJson(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    public record PurchaseResult(boolean success, String messagePath, Map<String, String> placeholders) {
    }

    public record DrawResult(boolean forced, String adminMessagePath, Map<String, String> placeholders) {
    }

    private record PendingNotification(String messagePath, Map<String, String> placeholders) {
    }

    private record PendingPurchase(int amount, double cost, long expiresAtMillis) {
    }

    private record PendingAdminAction(String action, long expiresAtMillis) {
    }

    private record DailyUsage(String date, int tickets, double spent) {
    }

    private record WinnerPayout(int rank, UUID playerId, String playerName, double amount, int tickets) {
    }

    private record FairDrawContext(boolean enabled, String seed, String hash, String drawKey, Random random) {
        private Map<String, String> placeholders() {
            return Map.of(
                "fair_seed", seed == null ? "" : seed,
                "fair_hash", hash == null ? "" : hash,
                "fair_draw_key", drawKey == null ? "" : drawKey
            );
        }
    }

    private record PendingPayment(
        String id,
        UUID playerId,
        double amount,
        String reason,
        String successMessagePath,
        Map<String, String> placeholders,
        int attempts,
        String lastAttempt
    ) {
    }

    private record RefundSummary(double amount, int players, int tickets, int failed) {
        private static RefundSummary empty() {
            return new RefundSummary(0.0D, 0, 0, 0);
        }
    }

    private record PayoutTaxResult(double netAmount, double taxAmount) {
    }

    private record TopEntry(String name, String value) {
    }

    private record AdminStatsSnapshot(
        double ticketRevenueToday,
        double ticketRevenueWeek,
        double ticketRevenueMonth,
        double payoutsMonth,
        double refundsMonth,
        double ticketTaxesAllTime,
        double payoutTaxesAllTime,
        int purchasesToday,
        double payoutRatioMonth
    ) {
        private double taxesAllTime() {
            return ticketTaxesAllTime + payoutTaxesAllTime;
        }
    }

    private static final class PurchaseWindow {
        private long startedAtMillis;
        private int tickets;
        private double spent;
        private boolean warned;

        private PurchaseWindow(long startedAtMillis) {
            this.startedAtMillis = startedAtMillis;
        }
    }
}
