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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
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
    private final LotteryRound currentRound = new LotteryRound();
    private final List<WinnerEntry> winnerHistory = new ArrayList<>();
    private final Map<UUID, PlayerLotteryStats> playerStats = new HashMap<>();
    private final Map<UUID, PlayerLotteryStats> seasonStats = new HashMap<>();
    private final Map<UUID, List<PendingNotification>> pendingNotifications = new HashMap<>();
    private final Map<UUID, PendingPurchase> pendingPurchases = new HashMap<>();
    private final Map<UUID, DailyUsage> dailyUsage = new HashMap<>();
    private final Map<UUID, Long> purchaseCooldowns = new HashMap<>();
    private final List<PendingPayment> pendingPayments = new ArrayList<>();
    private final Random random = new Random();
    private final File dataFile;
    private final File logFile;
    private final DatabaseStorageService databaseStorage;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    private YamlConfiguration dataConfig;
    private YamlConfiguration logConfig;
    private BukkitTask schedulerTask;
    private BukkitTask hologramTask;
    private BukkitTask announcementTask;
    private BukkitTask paymentRetryTask;
    private final Map<String, TextDisplay> hologramEntities = new HashMap<>();
    private String lastDrawKey;
    private String seasonId;
    private double totalTaxCollected;

    public LotteryManager(LotteryPlugin plugin, EconomyService economyService) {
        this.plugin = plugin;
        this.economyService = economyService;
        this.dataFile = new File(plugin.getDataFolder(), "data.yml");
        this.logFile = new File(plugin.getDataFolder(), "admin-log.yml");
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

        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        logConfig = YamlConfiguration.loadConfiguration(logFile);
        loadRound();
        loadHistory();
        loadStatistics();
        loadSeasonStatistics();
        loadDailyUsage();
        loadPendingNotifications();
        loadPendingPayments();
        loadMetaStats();
        lastDrawKey = dataConfig.getString("last-draw-key");

        String storedDate = dataConfig.getString("last-draw-date");
        if ((lastDrawKey == null || lastDrawKey.isBlank()) && storedDate != null && !storedDate.isBlank()) {
            lastDrawKey = storedDate + " 00:00";
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

    public void showStatus(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.status", createCommonPlaceholders(sender instanceof Player player ? player : null));
        if (sender instanceof Player player) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.your-tickets", createCommonPlaceholders(player));
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.your-chance", createCommonPlaceholders(player));
        }
    }

    public void showWinners(CommandSender sender) {
        if (winnerHistory.isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-winners");
            return;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.winners-header");
        int limit = Math.min(winnerHistory.size(), plugin.getConfig().getInt("settings.history-size", 10));
        for (int index = 0; index < limit; index++) {
            WinnerEntry entry = winnerHistory.get(index);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.winner-entry", Map.of(
                "player", entry.playerName(),
                "amount", economyService.format(entry.amount()),
                "date", entry.wonAt().format(WINNER_DATE_FORMAT),
                "tickets", String.valueOf(entry.ticketsBought())
            ));
        }
    }

    public void showStats(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.stats", createCommonPlaceholders(sender instanceof Player player ? player : null));
    }

    public void showNextDraw(CommandSender sender) {
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
            addFileToZip(zipOutputStream, new File(plugin.getDataFolder(), "holograms.yml"), "holograms.yml");
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
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.backup-created", Map.of(
            "file", backupFile.getAbsolutePath()
        ));
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
            loadPendingNotifications();
            loadPendingPayments();
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
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Tickets", "value", String.valueOf(currentRound.getTotalTickets())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Winner shares", "value", getPrizeShares().toString()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Season", "value", getSeasonId()));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Tax collected", "value", economyService.format(totalTaxCollected)));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending notifications", "value", String.valueOf(getPendingNotificationCount())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending payments", "value", String.valueOf(pendingPayments.size())));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.debug-entry", Map.of("key", "Pending confirmations", "value", String.valueOf(pendingPurchases.size())));
    }

    public void listAdminLog(CommandSender sender, int requestedPage) {
        ConfigurationSection entriesSection = logConfig.getConfigurationSection("entries");
        if (entriesSection == null || entriesSection.getKeys(false).isEmpty()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.log-empty");
            return;
        }

        List<String> keys = entriesSection.getKeys(false).stream().sorted(Comparator.reverseOrder()).toList();
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
        sendAdminLogNavigation(sender, page, maxPage);
    }

    private void sendAdminLogNavigation(CommandSender sender, int page, int maxPage) {
        if (maxPage <= 1) {
            return;
        }

        Component navigation = Component.empty();
        boolean hasButton = false;
        if (page > 1) {
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-previous", Map.of("page", String.valueOf(page - 1))))
                .clickEvent(ClickEvent.runCommand("/lottery log " + (page - 1))));
            hasButton = true;
        }

        if (page < maxPage) {
            if (hasButton) {
                navigation = navigation.append(Component.space());
            }
            navigation = navigation.append(legacy(MessageUtil.raw(sender instanceof Player player ? player : null,
                    plugin.getMessagesConfig(sender), "messages.log-list-next", Map.of("page", String.valueOf(page + 1))))
                .clickEvent(ClickEvent.runCommand("/lottery log " + (page + 1))));
        }

        sender.sendMessage(navigation);
    }

    public void runDoctor(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.doctor-header");
        doctorLine(sender, "Vault", Bukkit.getPluginManager().isPluginEnabled("Vault"), "Vault Economy Provider");
        doctorLine(sender, "Ticketpreis", getTicketPrice() > 0.0D, economyService.format(getTicketPrice()));
        doctorLine(sender, "GUI Größe", plugin.getGuiConfig().getInt("gui.size", 27) % 9 == 0, String.valueOf(plugin.getGuiConfig().getInt("gui.size", 27)));
        doctorLine(sender, "Mindestspieler", getMinimumPlayers() >= 0, String.valueOf(getMinimumPlayers()));
        doctorLine(sender, "Storage", !databaseStorage.getStatus().startsWith("unknown") && !databaseStorage.getStatus().contains("failed"), databaseStorage.getStatus());
        doctorLine(sender, "Gewinnanteile", getPrizeShares().stream().mapToDouble(Double::doubleValue).sum() > 0.0D, getPrizeShares().toString());
        doctorLine(sender, "HeadDatabase", !plugin.getGuiConfig().saveToString().contains("head-database-id") || Bukkit.getPluginManager().isPluginEnabled("HeadDatabase"), "optional");
        appendLog("doctor", Map.of("sender", sender.getName()));
    }

    private void doctorLine(CommandSender sender, String check, boolean ok, String detail) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), ok ? "messages.doctor-ok" : "messages.doctor-warn", Map.of(
            "check", check,
            "detail", detail
        ));
    }

    public void resetSeason(CommandSender sender, String requestedSeasonId) {
        seasonStats.clear();
        seasonId = requestedSeasonId == null || requestedSeasonId.isBlank() ? defaultSeasonId() : requestedSeasonId;
        plugin.getConfig().set("seasons.current-id", seasonId);
        plugin.saveConfig();
        save();
        appendLog("season_reset", Map.of("season", seasonId));
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.season-reset", Map.of("season", seasonId));
    }

    public PurchaseResult buyTickets(Player player, int amount) {
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

        double fullCost = amount * getTicketPrice();
        PurchaseResult securityResult = validatePurchaseSecurity(player, amount, fullCost);
        if (securityResult != null) {
            return securityResult;
        }

        PurchaseResult confirmationResult = requirePurchaseConfirmation(player, amount, fullCost);
        if (confirmationResult != null) {
            return confirmationResult;
        }

        if (!economyService.has(player, fullCost) || !economyService.withdraw(player, fullCost)) {
            return new PurchaseResult(false, "messages.not-enough-money", Map.of(
                "cost", economyService.format(fullCost)
            ));
        }

        double taxRate = getTaxRate();
        double netTicketAmount = fullCost * (1.0D - taxRate);
        double jackpotIncrease = netTicketAmount * getJackpotBoostMultiplier();
        double taxAmount = fullCost - netTicketAmount;
        currentRound.addTickets(player.getUniqueId(), amount, jackpotIncrease);
        totalTaxCollected += taxAmount;
        recordDailyUsage(player.getUniqueId(), amount, fullCost);
        purchaseCooldowns.put(player.getUniqueId(), System.currentTimeMillis());
        getOrCreateStats(player.getUniqueId(), player.getName())
            .recordPurchase(player.getName(), amount, fullCost, LocalDateTime.now(getZoneId()));
        getOrCreateSeasonStats(player.getUniqueId(), player.getName())
            .recordPurchase(player.getName(), amount, fullCost, LocalDateTime.now(getZoneId()));
        appendLog("ticket_purchase", Map.of(
            "player", player.getName(),
            "amount", String.valueOf(amount),
            "cost", economyService.format(fullCost)
        ));
        save();

        Map<String, String> placeholders = createCommonPlaceholders(player);
        placeholders.put("player", player.getName());
        placeholders.put("amount", String.valueOf(amount));
        placeholders.put("cost", economyService.format(fullCost));
        placeholders.put("tax", economyService.format(taxAmount));
        placeholders.put("boost_multiplier", formatDecimal(getJackpotBoostMultiplier()));
        if (plugin.getConfig().getBoolean("settings.broadcast-ticket-purchases", true)) {
            broadcastConfigured("messages.ticket-purchase-broadcast", placeholders, player);
        }
        sendWebhookEvent("ticket-purchase", placeholders);
        return new PurchaseResult(true, "messages.buy-success", placeholders);
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
        if (currentRound.getTotalTickets() <= 0) {
            return new DrawResult(true, "messages.simulate-no-players", placeholders);
        }

        if (currentRound.getUniquePlayers() < getMinimumPlayers()) {
            placeholders.put("needed", String.valueOf(getMinimumPlayers() - currentRound.getUniquePlayers()));
            return new DrawResult(true, "messages.simulate-not-enough-players", placeholders);
        }

        List<WinnerPayout> payouts = createWinnerPayouts(getTotalPot());
        WinnerPayout mainWinner = payouts.get(0);
        placeholders.put("player", mainWinner.playerName());
        placeholders.put("amount", economyService.format(mainWinner.amount()));
        placeholders.put("tickets", String.valueOf(mainWinner.tickets()));
        placeholders.put("chance", formatChance((double) mainWinner.tickets() / Math.max(1, currentRound.getTotalTickets())));
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
        return currentRound.getTicketsFor(playerId);
    }

    public double getWinChance(UUID playerId) {
        int totalTickets = currentRound.getTotalTickets();
        if (totalTickets <= 0) {
            return 0.0D;
        }
        return (double) currentRound.getTicketsFor(playerId) / totalTickets;
    }

    public Map<String, String> createCommonPlaceholders(Player player) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("jackpot", economyService.format(getTotalPot()));
        placeholders.put("pot", economyService.format(getTotalPot()));
        placeholders.put("ticket_pot", economyService.format(currentRound.getJackpot()));
        placeholders.put("base_pot", economyService.format(getConfiguredBasePot()));
        placeholders.put("ticket_price", economyService.format(getTicketPrice()));
        placeholders.put("draw_time", TimeUtil.formatLocalTime(getNextDrawAt().toLocalTime()));
        placeholders.put("draw_times", formatDrawTimes());
        placeholders.put("next_draw", formatNextDraw());
        placeholders.put("time_left", TimeUtil.formatDurationCompact(Duration.between(ZonedDateTime.now(getZoneId()), getNextDrawAt())));
        placeholders.put("tickets_total", String.valueOf(currentRound.getTotalTickets()));
        placeholders.put("players", String.valueOf(currentRound.getUniquePlayers()));
        placeholders.put("min_players", String.valueOf(getMinimumPlayers()));
        placeholders.put("pending_notifications", String.valueOf(getPendingNotificationCount()));
        placeholders.put("pending_payments", String.valueOf(pendingPayments.size()));
        placeholders.put("tax_collected_total", economyService.format(totalTaxCollected));
        placeholders.put("season_id", getSeasonId());
        placeholders.put("round_started", currentRound.getStartedAt().format(WINNER_DATE_FORMAT));
        placeholders.put("tax_percent", String.valueOf((int) Math.round(getTaxRate() * 100.0D)));
        placeholders.put("boost_multiplier", formatDecimal(getJackpotBoostMultiplier()));
        placeholders.put("ticket_price_1", economyService.format(getTicketPrice()));
        placeholders.put("ticket_price_5", economyService.format(getTicketPrice() * 5));
        placeholders.put("ticket_price_10", economyService.format(getTicketPrice() * 10));
        placeholders.put("ticket_price_25", economyService.format(getTicketPrice() * 25));
        placeholders.put("winner_count", String.valueOf(getPrizeShares().size()));
        if (player != null) {
            placeholders.put("player", player.getName());
            placeholders.put("player_name", player.getName());
            placeholders.put("player_uuid", player.getUniqueId().toString());
            placeholders.put("tickets", String.valueOf(getTicketsFor(player.getUniqueId())));
            placeholders.put("player_tickets", String.valueOf(getTicketsFor(player.getUniqueId())));
            placeholders.put("chance", formatChance(getWinChance(player.getUniqueId())));
            placeholders.put("player_chance", formatChance(getWinChance(player.getUniqueId())));
            applyPersonalStatsPlaceholders(placeholders, player.getUniqueId());
            applySeasonStatsPlaceholders(placeholders, player.getUniqueId());
        } else {
            placeholders.put("player", "");
            placeholders.put("player_name", "");
            placeholders.put("player_uuid", "");
            placeholders.put("tickets", "0");
            placeholders.put("player_tickets", "0");
            placeholders.put("chance", "0.00%");
            placeholders.put("player_chance", "0.00%");
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
        currentRound.setJackpot(Math.max(0.0D, amount - getConfiguredBasePot()));
        appendLog("set_jackpot", Map.of("amount", economyService.format(amount)));
        save();
    }

    public void addJackpot(double amount) {
        currentRound.setJackpot(currentRound.getJackpot() + amount);
        appendLog("add_jackpot", Map.of("amount", economyService.format(amount), "jackpot", economyService.format(getTotalPot())));
        save();
    }

    public void resetRound() {
        currentRound.reset(LocalDateTime.now(getZoneId()), 0.0D);
        appendLog("reset_round", Map.of());
        save();
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

    public void openMenu(Player player) {
        if (!plugin.getGuiConfig().getBoolean("gui.enabled", true)) {
            showStatus(player);
            return;
        }

        Map<String, String> placeholders = createCommonPlaceholders(player);
        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("gui.title", "&6Lottery")));
        Material fillerMaterial = parseMaterial(plugin.getGuiConfig().getString("gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection("gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public boolean isLotteryMenu(String title) {
        return title.equals(MessageUtil.color(plugin.getGuiConfig().getString("gui.title", "&6Lottery")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("stats-gui.title", "&6Lottery Statistik")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("personal-stats-gui.title", "&6Deine Statistik")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("language-gui.title", "&6Sprache")))
            || title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-gui.title", "&cLotterie Admin")));
    }

    public void handleMenuClick(Player player, String title, int slot) {
        boolean statsPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("stats-gui.title", "&6Lottery Statistik")));
        boolean personalStatsPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("personal-stats-gui.title", "&6Deine Statistik")));
        boolean languagePage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("language-gui.title", "&6Sprache")));
        boolean adminPage = title.equals(MessageUtil.color(plugin.getGuiConfig().getString("admin-gui.title", "&cLotterie Admin")));
        String itemRoot = statsPage ? "stats-gui.items" : personalStatsPage ? "personal-stats-gui.items" : languagePage ? "language-gui.items" : adminPage ? "admin-gui.items" : "gui.items";
        String itemPath = findItemPathBySlot(itemRoot, slot);
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
            } else {
                openMenu(player);
            }
        }
    }

    public void openStatsMenu(Player player) {
        if (!plugin.getGuiConfig().getBoolean("stats-gui.enabled", true)) {
            openMenu(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("stats-gui.size", 54),
            MessageUtil.color(plugin.getGuiConfig().getString("stats-gui.title", "&6Lottery Statistik")));
        Material fillerMaterial = parseMaterial(plugin.getGuiConfig().getString("stats-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection("stats-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "stats-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openPersonalStatsMenu(Player player) {
        if (!plugin.getGuiConfig().getBoolean("personal-stats-gui.enabled", true)) {
            openMenu(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("personal-stats-gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("personal-stats-gui.title", "&6Deine Statistik")));
        Material fillerMaterial = parseMaterial(plugin.getGuiConfig().getString("personal-stats-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection("personal-stats-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "personal-stats-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openLanguageMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("language-gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("language-gui.title", "&6Sprache")));
        Material fillerMaterial = parseMaterial(plugin.getGuiConfig().getString("language-gui.filler-material", "BLACK_STAINED_GLASS_PANE"), Material.BLACK_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection("language-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "language-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public void openAdminMenu(Player player) {
        if (!player.hasPermission("lottery.admin")) {
            MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.no-permission");
            return;
        }
        if (!plugin.getGuiConfig().getBoolean("admin-gui.enabled", true)) {
            showStatus(player);
            return;
        }

        Inventory inventory = Bukkit.createInventory(null, plugin.getGuiConfig().getInt("admin-gui.size", 27),
            MessageUtil.color(plugin.getGuiConfig().getString("admin-gui.title", "&cLotterie Admin")));
        Material fillerMaterial = parseMaterial(plugin.getGuiConfig().getString("admin-gui.filler-material", "RED_STAINED_GLASS_PANE"), Material.RED_STAINED_GLASS_PANE);
        ItemStack filler = createItem(fillerMaterial, "&0");
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }

        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection("admin-gui.items");
        if (itemsSection != null) {
            for (String key : itemsSection.getKeys(false)) {
                setConfiguredItem(inventory, player, "admin-gui.items." + key);
            }
        }

        player.openInventory(inventory);
    }

    public String resolvePlaceholder(OfflinePlayer player, String params) {
        String normalizedParams = params.toLowerCase(Locale.ROOT);
        String topValue = resolveTopPlaceholder(normalizedParams);
        if (topValue != null) {
            return topValue;
        }

        return switch (normalizedParams) {
            case "jackpot", "pot" -> economyService.format(getTotalPot());
            case "ticket_pot" -> economyService.format(currentRound.getJackpot());
            case "base_pot" -> economyService.format(getConfiguredBasePot());
            case "ticket_price" -> economyService.format(getTicketPrice());
            case "draw_time" -> TimeUtil.formatLocalTime(getNextDrawAt().toLocalTime());
            case "draw_times" -> formatDrawTimes();
            case "next_draw" -> formatNextDraw();
            case "time_left" -> TimeUtil.formatDurationCompact(Duration.between(ZonedDateTime.now(getZoneId()), getNextDrawAt()));
            case "total_tickets" -> String.valueOf(currentRound.getTotalTickets());
            case "players" -> String.valueOf(currentRound.getUniquePlayers());
            case "min_players" -> String.valueOf(getMinimumPlayers());
            case "pending_notifications" -> String.valueOf(getPendingNotificationCount());
            case "pending_payments" -> String.valueOf(pendingPayments.size());
            case "tax_collected_total" -> economyService.format(totalTaxCollected);
            case "season_id" -> getSeasonId();
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
        List<TopEntry> entries = switch (statistic) {
            case "rounds_played" -> getTopLongEntries(PlayerLotteryStats::getRoundsPlayed, value -> value + " Runden");
            case "tickets_bought" -> getTopLongEntries(PlayerLotteryStats::getTicketsBought, value -> value + " Tickets");
            case "money_spent" -> getTopDoubleEntries(PlayerLotteryStats::getMoneySpent, economyService::format);
            case "wins" -> getTopLongEntries(PlayerLotteryStats::getWins, value -> value + " Gewinne");
            case "highest_win" -> getTopDoubleEntries(PlayerLotteryStats::getHighestWin, economyService::format);
            case "total_won" -> getTopDoubleEntries(PlayerLotteryStats::getTotalWon, economyService::format);
            case "current_tickets" -> getCurrentTicketTopEntries();
            default -> List.of();
        };
        return rank <= entries.size() ? entries.get(rank - 1) : null;
    }

    private int getPendingNotificationCount() {
        return pendingNotifications.values().stream().mapToInt(List::size).sum();
    }

    private void checkDraw() {
        ZonedDateTime now = ZonedDateTime.now(getZoneId());
        if (!isDrawDayAllowed(now.toLocalDate())) {
            return;
        }

        ZonedDateTime dueDraw = getLatestDueDrawAt(now);
        if (dueDraw == null) {
            return;
        }

        String drawKey = formatDrawKey(dueDraw);
        if (drawKey.equals(lastDrawKey)) {
            return;
        }
        draw(Bukkit.getConsoleSender(), false, drawKey);
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
        Map<String, String> placeholders = createCommonPlaceholders(null);
        Bukkit.getConsoleSender().sendMessage(MessageUtil.prefixed(plugin.getMessagesConfig(), "messages.draw-announcement", placeholders));

        boolean buttonEnabled = plugin.getConfig().getBoolean("announcements.draw.buy-button.enabled", true);
        String command = plugin.getConfig().getString("announcements.draw.buy-button.command", "/lottery gui");
        if (command == null || command.isBlank()) {
            command = "/lottery gui";
        }
        if (!command.startsWith("/")) {
            command = "/" + command;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
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

        hologram.setText(MessageUtil.format(null, buildHologramText(path), createCommonPlaceholders(null)));
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

    private DrawResult draw(CommandSender trigger, boolean forced, String drawKey) {
        MessageUtil.send(trigger, plugin.getMessagesConfig(trigger), "messages.draw-start");

        if (currentRound.getTotalTickets() <= 0) {
            currentRound.reset(LocalDateTime.now(getZoneId()), 0.0D);
            lastDrawKey = drawKey;
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

        if (currentRound.getUniquePlayers() < getMinimumPlayers()) {
            RefundSummary refundSummary = shouldRefundOnNotEnoughPlayers() ? refundCurrentRoundTickets() : RefundSummary.empty();
            currentRound.reset(LocalDateTime.now(getZoneId()), 0.0D);
            lastDrawKey = drawKey;
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

        double amount = getTotalPot();
        int totalTickets = currentRound.getTotalTickets();
        List<WinnerPayout> winnerPayouts = createWinnerPayouts(amount);

        lastDrawKey = drawKey;
        for (UUID playerId : currentRound.getTicketsByPlayer().keySet()) {
            getOrCreateStats(playerId, getCachedPlayerName(playerId)).recordRoundPlayed(getCachedPlayerName(playerId));
            getOrCreateSeasonStats(playerId, getCachedPlayerName(playerId)).recordRoundPlayed(getCachedPlayerName(playerId));
        }

        for (WinnerPayout payout : winnerPayouts) {
            getOrCreateStats(payout.playerId(), payout.playerName()).recordWin(payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()));
            getOrCreateSeasonStats(payout.playerId(), payout.playerName()).recordWin(payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()));
        }
        for (int index = winnerPayouts.size() - 1; index >= 0; index--) {
            WinnerPayout payout = winnerPayouts.get(index);
            winnerHistory.add(0, new WinnerEntry(payout.playerId(), payout.playerName(), payout.amount(), LocalDateTime.now(getZoneId()), payout.tickets()));
        }
        trimWinnerHistory();

        Map<String, String> placeholders = createCommonPlaceholders(null);
        WinnerPayout mainWinner = winnerPayouts.get(0);
        placeholders.put("player", mainWinner.playerName());
        placeholders.put("amount", economyService.format(mainWinner.amount()));
        placeholders.put("pot_amount", economyService.format(amount));
        placeholders.put("tickets", String.valueOf(mainWinner.tickets()));
        placeholders.put("chance", formatChance((double) mainWinner.tickets() / Math.max(1, totalTickets)));
        placeholders.put("winner_count", String.valueOf(winnerPayouts.size()));
        placeholders.put("winners", formatWinnerPayouts(winnerPayouts));
        Player onlineWinner = Bukkit.getPlayer(mainWinner.playerId());
        long winnerDelay = runDrawAnimation(placeholders);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (plugin.getConfig().getBoolean("draw-animation.enabled", false)) {
                broadcastConfigured("messages.draw-animation-reveal", placeholders, onlineWinner);
            }
            if (winnerPayouts.size() > 1) {
                broadcastConfigured("messages.draw-winners", placeholders, onlineWinner);
            } else {
                broadcastConfigured("messages.draw-winner", placeholders, onlineWinner);
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
            } else if (Bukkit.getPlayer(payout.playerId()) == null) {
                queuePendingNotification(payout.playerId(), "messages.winner-offline-notification", payoutPlaceholders);
            }
            executeWinCommands(payoutPlaceholders);
            sendWebhook(payout.playerName(), payout.amount(), payout.tickets());
            appendLog("draw_winner", payoutPlaceholders);
        }
        currentRound.reset(LocalDateTime.now(getZoneId()), 0.0D);
        save();
        appendLog("draw_winner", placeholders);
        runAutoBackupAfterDraw();
        return new DrawResult(forced, "messages.admin-draw-success", placeholders);
    }

    private void runAutoBackupAfterDraw() {
        if (!plugin.getConfig().getBoolean("backups.auto-after-draw.enabled", false)) {
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> createBackup(Bukkit.getConsoleSender()));
    }

    private void executeWinCommands(Map<String, String> placeholders) {
        for (String command : plugin.getConfig().getStringList("rewards.commands-on-win")) {
            String resolvedCommand = MessageUtil.format(null, command, placeholders);
            if (!resolvedCommand.isBlank()) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), stripLeadingSlash(resolvedCommand));
            }
        }
        executeRewardPackage(placeholders);
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

    private RefundSummary refundCurrentRoundTickets() {
        double refunded = 0.0D;
        int refundedPlayers = 0;
        int refundedTickets = 0;
        int failedRefunds = 0;

        for (Map.Entry<UUID, Integer> entry : currentRound.getTicketsByPlayer().entrySet()) {
            int tickets = entry.getValue();
            if (tickets <= 0) {
                continue;
            }

            OfflinePlayer player = Bukkit.getOfflinePlayer(entry.getKey());
            double amount = tickets * getTicketPrice();
            if (economyService.deposit(player, amount)) {
                refunded += amount;
                refundedPlayers++;
                refundedTickets += tickets;
                Player onlinePlayer = Bukkit.getPlayer(entry.getKey());
                if (onlinePlayer != null) {
                    MessageUtil.send(onlinePlayer, plugin.getMessagesConfig(onlinePlayer), "messages.draw-refund", Map.of(
                        "amount", economyService.format(amount),
                        "tickets", String.valueOf(tickets)
                    ));
                } else {
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
                .replace("%amount%", economyService.format(winnerHistory.get(0).amount())), 10, 70, 20);
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
        String payload = "{\"username\":\"" + escapeJson(username) + "\",\"content\":\"" + escapeJson(content) + "\"}";
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
        int winningNumber = random.nextInt(currentRound.getTotalTickets()) + 1;
        int cursor = 0;
        for (Map.Entry<UUID, Integer> entry : currentRound.getTicketsByPlayer().entrySet()) {
            cursor += entry.getValue();
            if (cursor >= winningNumber) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Could not resolve lottery winner.");
    }

    private List<WinnerPayout> createWinnerPayouts(double totalAmount) {
        List<Double> shares = getPrizeShares();
        List<UUID> winners = pickUniqueWinners(shares.size());
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
            payouts.add(new WinnerPayout(index + 1, winnerId, winnerName, payoutAmount, currentRound.getTicketsFor(winnerId)));
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
        Map<UUID, Integer> candidates = new HashMap<>(currentRound.getTicketsByPlayer());
        List<UUID> winners = new ArrayList<>();
        int limit = Math.min(Math.max(1, wantedWinners), candidates.size());
        for (int index = 0; index < limit; index++) {
            UUID winner = pickWinnerFromCandidates(candidates);
            winners.add(winner);
            candidates.remove(winner);
        }
        return winners;
    }

    private UUID pickWinnerFromCandidates(Map<UUID, Integer> candidates) {
        int totalTickets = candidates.values().stream().mapToInt(Integer::intValue).sum();
        int winningNumber = random.nextInt(totalTickets) + 1;
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
        currentRound.reset(LocalDateTime.now(getZoneId()), 0.0D);
        currentRound.setJackpot(dataConfig.getDouble("round.jackpot", 0.0D));

        String startedAt = dataConfig.getString("round.started-at");
        if (startedAt != null && !startedAt.isBlank()) {
            currentRound.setStartedAt(LocalDateTime.parse(startedAt));
        }

        ConfigurationSection ticketsSection = dataConfig.getConfigurationSection("round.tickets");
        if (ticketsSection == null) {
            return;
        }

        for (String key : ticketsSection.getKeys(false)) {
            try {
                UUID playerId = UUID.fromString(key);
                int amount = ticketsSection.getInt(key, 0);
                if (amount > 0) {
                    currentRound.getTicketsByPlayer().put(playerId, amount);
                }
            } catch (IllegalArgumentException ignored) {
                plugin.getLogger().warning("Skipping invalid UUID in data.yml: " + key);
            }
        }
    }

    private void loadHistory() {
        winnerHistory.clear();
        ConfigurationSection historySection = dataConfig.getConfigurationSection("history");
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
                winnerHistory.add(new WinnerEntry(playerId, playerName, amount, wonAt, ticketsBought));
            } catch (Exception exception) {
                plugin.getLogger().warning("Skipping invalid winner history entry " + key + ": " + exception.getMessage());
            }
        }

        winnerHistory.sort(Comparator.comparing(WinnerEntry::wonAt).reversed());
        trimWinnerHistory();
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
        while (winnerHistory.size() > historySize) {
            winnerHistory.remove(winnerHistory.size() - 1);
        }
    }

    private void save() {
        dataConfig.set("round.jackpot", currentRound.getJackpot());
        dataConfig.set("round.started-at", currentRound.getStartedAt().toString());
        dataConfig.set("round.tickets", null);
        for (Map.Entry<UUID, Integer> entry : currentRound.getTicketsByPlayer().entrySet()) {
            dataConfig.set("round.tickets." + entry.getKey(), entry.getValue());
        }

        dataConfig.set("last-draw-key", lastDrawKey);
        dataConfig.set("last-draw-date", lastDrawKey != null && lastDrawKey.length() >= 10 ? lastDrawKey.substring(0, 10) : null);
        dataConfig.set("history", null);
        for (int index = 0; index < winnerHistory.size(); index++) {
            WinnerEntry entry = winnerHistory.get(index);
            String path = "history." + index;
            dataConfig.set(path + ".uuid", entry.playerId().toString());
            dataConfig.set(path + ".name", entry.playerName());
            dataConfig.set(path + ".amount", entry.amount());
            dataConfig.set(path + ".won-at", entry.wonAt().toString());
            dataConfig.set(path + ".tickets-bought", entry.ticketsBought());
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
        return plugin.getConfig().getDouble("settings.ticket-price", 250.0D);
    }

    private double getConfiguredBasePot() {
        return Math.max(0.0D, plugin.getConfig().getDouble("settings.additional-pot-amount", 0.0D));
    }

    private double getTotalPot() {
        return currentRound.getJackpot() + getConfiguredBasePot();
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
        return Math.max(0, plugin.getConfig().getInt("settings.minimum-players-to-draw", 1));
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
        return null;
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
                if (candidate.isAfter(now) && !formatDrawKey(candidate).equals(lastDrawKey)) {
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
        if (!plugin.getConfig().getBoolean("settings.draw-schedule.multiple-draws-enabled", false)) {
            return List.of(getDrawTime());
        }

        List<LocalTime> drawTimes = new ArrayList<>();
        for (String value : plugin.getConfig().getStringList("settings.draw-schedule.times")) {
            try {
                drawTimes.add(LocalTime.parse(value));
            } catch (Exception exception) {
                plugin.getLogger().warning("Invalid draw time in config.yml: " + value);
            }
        }

        if (drawTimes.isEmpty()) {
            drawTimes.add(getDrawTime());
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
        Map<String, String> placeholders = createItemPlaceholders(player, path);
        List<Integer> slots = getGuiSlots(path);
        if (slots.isEmpty()) {
            return;
        }

        Material material = parseMaterial(plugin.getGuiConfig().getString(path + ".material", "STONE"), Material.STONE);
        String name = MessageUtil.raw(player, plugin.getGuiConfig(), path + ".name", placeholders);
        List<String> lore = expandStatsLore(MessageUtil.rawList(player, plugin.getGuiConfig(), path + ".lore", placeholders));
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
        int buyAmount = getConfiguredBuyAmount(path);
        placeholders.put("buy_amount", String.valueOf(buyAmount));
        placeholders.put("buy_price", economyService.format(getTicketPrice() * buyAmount));
        return placeholders;
    }

    private List<Integer> getGuiSlots(String path) {
        List<Integer> slots = new ArrayList<>();
        if (plugin.getGuiConfig().isInt(path + ".slot")) {
            slots.add(plugin.getGuiConfig().getInt(path + ".slot"));
        }

        for (Object entry : plugin.getGuiConfig().getList(path + ".slots", List.of())) {
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
        return playerStats.values().stream()
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
        return playerStats.values().stream()
            .filter(stats -> extractor.applyAsDouble(stats) > 0.0D)
            .sorted(Comparator.comparingDouble(extractor).reversed())
            .limit(10)
            .map(stats -> new TopEntry(stats.getPlayerName(), formatter.apply(extractor.applyAsDouble(stats))))
            .toList();
    }

    private List<String> formatCurrentTicketTop() {
        List<String> lines = currentRound.getTicketsByPlayer().entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .limit(10)
            .map(entry -> "&e" + getCachedPlayerName(entry.getKey()) + " &7- &f" + entry.getValue() + " Tickets")
            .map(MessageUtil::color)
            .toList();
        return lines.isEmpty() ? List.of(MessageUtil.color("&7Noch keine Tickets in dieser Runde.")) : lines;
    }

    private List<TopEntry> getCurrentTicketTopEntries() {
        return currentRound.getTicketsByPlayer().entrySet().stream()
            .filter(entry -> entry.getValue() > 0)
            .sorted(Map.Entry.<UUID, Integer>comparingByValue().reversed())
            .limit(10)
            .map(entry -> new TopEntry(getCachedPlayerName(entry.getKey()), entry.getValue() + " Tickets"))
            .toList();
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

    private String findItemPathBySlot(String itemsPath, int clickedSlot) {
        ConfigurationSection itemsSection = plugin.getGuiConfig().getConfigurationSection(itemsPath);
        if (itemsSection == null) {
            return null;
        }

        String matchingPath = null;
        for (String key : itemsSection.getKeys(false)) {
            String path = itemsPath + "." + key;
            if (getGuiSlots(path).contains(clickedSlot)) {
                matchingPath = path;
            }
        }
        return matchingPath;
    }

    private boolean handleItemActions(Player player, String path) {
        Map<String, String> placeholders = createItemPlaceholders(player, path);
        boolean refresh = plugin.getGuiConfig().getBoolean(path + ".refresh-after-click", true);
        boolean handledAction = false;

        List<String> actions = plugin.getGuiConfig().getStringList(path + ".actions");
        if (actions.isEmpty() && getConfiguredBuyAmount(path) > 0) {
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

        String owner = plugin.getGuiConfig().getString(path + ".skull-owner", "%player_name%");
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
        String headId = plugin.getGuiConfig().getString(path + ".head-database-id",
            plugin.getGuiConfig().getString(path + ".hdb-id", ""));
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

    private record DailyUsage(String date, int tickets, double spent) {
    }

    private record WinnerPayout(int rank, UUID playerId, String playerName, double amount, int tickets) {
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

    private record TopEntry(String name, String value) {
    }
}
