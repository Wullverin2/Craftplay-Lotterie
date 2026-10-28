package dev.codex.lottery;

import dev.codex.lottery.command.LotteryCommand;
import dev.codex.lottery.gui.LotteryMenuListener;
import dev.codex.lottery.hook.LotteryPlaceholderExpansion;
import dev.codex.lottery.listener.PlayerJoinListener;
import dev.codex.lottery.service.EconomyService;
import dev.codex.lottery.service.LotteryManager;
import dev.codex.lottery.service.UpdateChecker;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LotteryPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 12;

    private EconomyService economyService;
    private LotteryManager lotteryManager;
    private FileConfiguration guiConfig;
    private FileConfiguration bedrockGuiConfig;
    private FileConfiguration hologramsConfig;
    private FileConfiguration lotteriesConfig;
    private FileConfiguration playerLanguagesConfig;
    private File playerLanguagesFile;
    private File hologramsFile;
    private File lotteriesFile;
    private File lastConfigUpdateReportFile;
    private UpdateChecker updateChecker;
    private final Map<String, FileConfiguration> languageConfigs = new HashMap<>();
    private final Map<String, FileConfiguration> guiConfigs = new HashMap<>();
    private final Map<String, FileConfiguration> bedrockGuiConfigs = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfigIfNeeded();
        saveResource("gui/gui.yml", false);
        saveResource("gui/bedrock-gui.yml", false);
        saveResource("holograms.yml", false);
        saveResource("lotteries.yml", false);
        saveResource("lang/de.yml", false);
        saveResource("lang/en.yml", false);
        synchronizeManagedConfigsOnLoad("startup");
        loadCustomConfigs();

        if (!setupEconomy()) {
            getLogger().severe("Vault economy provider not found. Disabling plugin.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.lotteryManager = new LotteryManager(this, economyService);
        lotteryManager.load();
        lotteryManager.startScheduler();
        this.updateChecker = new UpdateChecker(this);
        runStartupChecks();
        updateChecker.checkOnStartup();

        LotteryCommand command = new LotteryCommand(this, lotteryManager);
        Objects.requireNonNull(getCommand("lottery"), "lottery command not defined").setExecutor(command);
        Objects.requireNonNull(getCommand("lottery"), "lottery command not defined").setTabCompleter(command);

        Bukkit.getPluginManager().registerEvents(new LotteryMenuListener(lotteryManager), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinListener(this, lotteryManager), this);

        if (Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            new LotteryPlaceholderExpansion(this, lotteryManager).register();
            getLogger().info("Hooked into PlaceholderAPI.");
        }
    }

    @Override
    public void onDisable() {
        if (lotteryManager != null) {
            lotteryManager.shutdown();
        }
    }

    public void reloadPlugin() {
        reloadConfig();
        migrateConfigIfNeeded();
        synchronizeManagedConfigsOnLoad("reload");
        loadCustomConfigs();
        updateChecker = new UpdateChecker(this);
        if (lotteryManager != null) {
            lotteryManager.reload();
            lotteryManager.startScheduler();
        }
    }

    public EconomyService getEconomyService() {
        return economyService;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public FileConfiguration getMessagesConfig() {
        return getLanguageConfig(getDefaultLanguage());
    }

    public Collection<FileConfiguration> getLanguageConfigs() {
        return Collections.unmodifiableCollection(languageConfigs.values());
    }

    public FileConfiguration getMessagesConfig(CommandSender sender) {
        if (sender instanceof Player player) {
            return getLanguageConfig(getPlayerLanguage(player.getUniqueId()));
        }
        return getMessagesConfig();
    }

    public FileConfiguration getGuiConfig() {
        return getGuiConfig(getDefaultLanguage());
    }

    public FileConfiguration getGuiConfig(CommandSender sender) {
        if (sender instanceof Player player) {
            return getGuiConfig(getPlayerLanguage(player.getUniqueId()));
        }
        return getGuiConfig();
    }

    public FileConfiguration getGuiConfig(Player player) {
        return getGuiConfig(getPlayerLanguage(player.getUniqueId()));
    }

    public FileConfiguration getGuiConfig(String language) {
        String normalizedLanguage = language == null ? getDefaultLanguage() : language.toLowerCase(Locale.ROOT);
        FileConfiguration config = guiConfigs.get(normalizedLanguage);
        if (config != null) {
            return config;
        }
        config = guiConfigs.get(getDefaultLanguage());
        if (config != null) {
            return config;
        }
        return guiConfig;
    }

    public Collection<FileConfiguration> getGuiConfigs() {
        return Collections.unmodifiableCollection(guiConfigs.values());
    }

    public FileConfiguration getBedrockGuiConfig() {
        return getBedrockGuiConfig(getDefaultLanguage());
    }

    public FileConfiguration getBedrockGuiConfig(CommandSender sender) {
        if (sender instanceof Player player) {
            return getBedrockGuiConfig(getPlayerLanguage(player.getUniqueId()));
        }
        return getBedrockGuiConfig();
    }

    public FileConfiguration getBedrockGuiConfig(Player player) {
        return getBedrockGuiConfig(getPlayerLanguage(player.getUniqueId()));
    }

    public FileConfiguration getBedrockGuiConfig(String language) {
        String normalizedLanguage = language == null ? getDefaultLanguage() : language.toLowerCase(Locale.ROOT);
        FileConfiguration config = bedrockGuiConfigs.get(normalizedLanguage);
        if (config != null) {
            return config;
        }
        config = bedrockGuiConfigs.get(getDefaultLanguage());
        if (config != null) {
            return config;
        }
        return bedrockGuiConfig != null ? bedrockGuiConfig : getGuiConfig(normalizedLanguage);
    }

    public Collection<FileConfiguration> getBedrockGuiConfigs() {
        return Collections.unmodifiableCollection(bedrockGuiConfigs.values());
    }

    public Set<String> getAvailableLanguages() {
        Set<String> languages = new LinkedHashSet<>(languageConfigs.keySet().stream().sorted().toList());
        if (languages.isEmpty()) {
            languages.add(getDefaultLanguage());
        }
        return Collections.unmodifiableSet(languages);
    }

    public String getLanguageDisplayName(String language) {
        return switch (language.toLowerCase(Locale.ROOT)) {
            case "de" -> "Deutsch";
            case "en" -> "English";
            default -> language.toUpperCase(Locale.ROOT);
        };
    }

    public void saveGuiConfig() {
        try {
            getGuiConfig().save(new File(getDataFolder(), "gui/" + getDefaultLanguage() + ".yml"));
        } catch (IOException exception) {
            getLogger().severe("Could not save default language GUI config: " + exception.getMessage());
        }
    }

    public FileConfiguration getHologramsConfig() {
        return hologramsConfig;
    }

    public FileConfiguration getLotteriesConfig() {
        return lotteriesConfig;
    }

    public void saveLotteriesConfig() {
        try {
            lotteriesConfig.save(lotteriesFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save lotteries.yml: " + exception.getMessage());
        }
    }

    public void saveHologramsConfig() {
        try {
            hologramsConfig.save(hologramsFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save holograms.yml: " + exception.getMessage());
        }
    }

    public String getDefaultLanguage() {
        return getConfig().getString("language.default", "de").toLowerCase(Locale.ROOT);
    }

    public String getPlayerLanguage(UUID playerId) {
        return playerLanguagesConfig.getString("players." + playerId, getDefaultLanguage()).toLowerCase(Locale.ROOT);
    }

    public void setPlayerLanguage(UUID playerId, String language) {
        String normalizedLanguage = language.toLowerCase(Locale.ROOT);
        if (!languageConfigs.containsKey(normalizedLanguage)) {
            normalizedLanguage = getDefaultLanguage();
        }
        playerLanguagesConfig.set("players." + playerId, normalizedLanguage);
        try {
            playerLanguagesConfig.save(playerLanguagesFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save player-languages.yml: " + exception.getMessage());
        }
    }

    private boolean setupEconomy() {
        this.economyService = EconomyService.create(this);
        return economyService != null;
    }

    private void runStartupChecks() {
        if (!Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            getLogger().warning("PlaceholderAPI is not enabled. External placeholders in GUI/lang will stay unresolved.");
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("HeadDatabase") && guiConfig.saveToString().contains("head-database-id")) {
            getLogger().warning("GUI uses head-database-id, but HeadDatabase is not enabled.");
        }
        if (!getConfig().getString("storage.type", "yaml").equalsIgnoreCase("yaml")) {
            getLogger().info("Database storage is enabled as " + getConfig().getString("storage.type") + ".");
        }
        if (getConfig().getBoolean("settings.refund-on-not-enough-players", true)) {
            getLogger().info("Refunds on failed draws are enabled. Make sure your economy supports OfflinePlayer deposits.");
        }
        generatePlaceholderDocumentation();
        writeDefaultReferenceFiles();
    }

    private void writeDefaultReferenceFiles() {
        File referenceFolder = new File(getDataFolder(), "reference");
        if (!referenceFolder.exists() && !referenceFolder.mkdirs()) {
            getLogger().warning("Could not create reference folder for commented default configs.");
            return;
        }

        copyDefaultReference("config.yml", new File(referenceFolder, "config.yml"));
        copyDefaultReference("gui/gui.yml", new File(referenceFolder, "gui.yml"));
        copyDefaultReference("gui/gui.yml", new File(referenceFolder, "gui-de.yml"));
        copyDefaultReference("gui/gui.yml", new File(referenceFolder, "gui-en.yml"));
        copyDefaultReference("gui/bedrock-gui.yml", new File(referenceFolder, "bedrock-gui.yml"));
        copyDefaultReference("gui/bedrock-gui.yml", new File(referenceFolder, "bedrock-de.yml"));
        copyDefaultReference("gui/bedrock-gui.yml", new File(referenceFolder, "bedrock-en.yml"));
        copyDefaultReference("holograms.yml", new File(referenceFolder, "holograms.yml"));
        copyDefaultReference("lotteries.yml", new File(referenceFolder, "lotteries.yml"));
        copyDefaultReference("lang/de.yml", new File(referenceFolder, "lang-de.yml"));
        copyDefaultReference("lang/en.yml", new File(referenceFolder, "lang-en.yml"));
    }

    private void copyDefaultReference(String resourcePath, File target) {
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                return;
            }
            Files.copy(inputStream, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException exception) {
            getLogger().warning("Could not write reference config " + target.getName() + ": " + exception.getMessage());
        }
    }

    private void generatePlaceholderDocumentation() {
        if (!getConfig().getBoolean("docs.generate-placeholder-docs", true)) {
            return;
        }

        File docsFile = new File(getDataFolder(), "PLACEHOLDERS.md");
        String content = """
            # Craftplay Lotterie Placeholder

            Diese Datei wird automatisch vom Plugin erstellt.

            ## Allgemein
            - `%lottery_pot%` / `%lottery_jackpot%`
            - `%lottery_ticket_pot%`
            - `%lottery_base_pot%`
            - `%lottery_payout_pot%`
            - `%lottery_ticket_price%`
            - `%lottery_draw_time%`
            - `%lottery_draw_times%`
            - `%lottery_next_draw%`
            - `%lottery_time_left%`
            - `%lottery_total_tickets%`
            - `%lottery_players%`
            - `%lottery_min_players%`
            - `%lottery_winner_count%`
            - `%lottery_lottery_id%`
            - `%lottery_lottery_name%`
            - `%lottery_lottery_type%`

            ## Spieler
            - `%lottery_player_tickets%`
            - `%lottery_player_chance%`
            - `%lottery_player_stats_tickets_bought%`
            - `%lottery_player_stats_money_spent%`
            - `%lottery_player_stats_wins%`
            - `%lottery_player_stats_highest_win%`
            - `%lottery_player_stats_total_won%`
            - `%lottery_player_stats_rounds_played%`
            - `%lottery_player_stats_losing_streak%`
            - `%lottery_player_stats_lucky_number_hits%`
            - `%lottery_player_stats_gifted_tickets%`
            - `%lottery_player_stats_profit%`
            - `%lottery_my_rank_tickets_bought%`
            - `%lottery_my_rank_money_spent%`
            - `%lottery_my_rank_wins%`
            - `%lottery_my_rank_highest_win%`
            - `%lottery_my_rank_total_won%`
            - `%lottery_my_rank_rounds_played%`
            - `%lottery_my_rank_current_tickets%`
            - `%lottery_season_points%`

            ## Saison
            - `%lottery_season_id%`
            - `%lottery_season_tickets_bought%`
            - `%lottery_season_money_spent%`
            - `%lottery_season_wins%`
            - `%lottery_season_total_won%`
            - `%lottery_season_profit%`
            - `%lottery_lucky_number%`
            - `%lottery_lottery_group%`
            - `%lottery_achievement_count%`
            - `%lottery_claim_payments%`
            - `%lottery_claim_notifications%`

            ## Toplisten
            Schema: `%lottery_top_<statistik>_<rang>%`, `%lottery_top_<statistik>_<rang>_name%`, `%lottery_top_<statistik>_<rang>_value%`

            Statistiken: `rounds_played`, `tickets_bought`, `money_spent`, `wins`, `highest_win`, `total_won`, `current_tickets`, `last_winners`.
            """;

        try {
            if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
                getLogger().warning("Could not create plugin data folder for placeholder docs.");
                return;
            }
            Files.writeString(docsFile.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            getLogger().warning("Could not write PLACEHOLDERS.md: " + exception.getMessage());
        }
    }

    private void migrateConfigIfNeeded() {
        int currentVersion = getConfig().getInt("config-version", 1);
        if (currentVersion >= CONFIG_VERSION) {
            return;
        }

        File configFile = new File(getDataFolder(), "config.yml");
        File migrationFolder = new File(getDataFolder(), "migrations");
        if (!migrationFolder.exists() && !migrationFolder.mkdirs()) {
            getLogger().warning("Could not create migrations folder for config backup.");
        } else if (configFile.exists()) {
            File backupFile = new File(migrationFolder, "config-v" + currentVersion + "-" + System.currentTimeMillis() + ".yml");
            try {
                Files.copy(configFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                getLogger().info("Created config migration backup: " + backupFile.getName());
            } catch (IOException exception) {
                getLogger().warning("Could not backup config before migration: " + exception.getMessage());
            }
        }

        applyConfigMigrations(currentVersion);
        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();
        getLogger().info("Config migrated to version " + CONFIG_VERSION + ".");
    }

    private void applyConfigMigrations(int currentVersion) {
        if (currentVersion < 10) {
            getConfig().set("draw-output.minimal-chat", true);
            getConfig().set("draw-output.notify-offline-losers", true);
            getConfig().set("fairness.broadcast-hash-before-draw", false);
            getConfig().set("fairness.broadcast-proof-after-draw", false);
            getConfig().set("lucky-number.enabled", false);
            getConfig().set("lucky-number.broadcast-drawn-number", false);
            getConfig().set("achievements.notify", false);
            getConfig().set("achievements.broadcast", false);
            getConfig().set("consolation-prizes.notify", false);
            getConfig().set("pity-bonus.notify", false);
            getConfig().set("draw-animation.enabled", false);
            getConfig().set("draw-animation.gui.enabled", false);
            getConfig().set("draw-animation.gui.pre-notice.enabled", false);
        }
        if (currentVersion < 11) {
            getConfig().set("web-server.enabled", true);
            getConfig().set("web-server.host", "127.0.0.1");
            getConfig().set("web-server.port", 8765);
            getConfig().set("web-server.update-interval-seconds", 5);
            getConfig().set("web-server.root-path", "/");
            getConfig().set("web-server.api-path", "/api/status");
            getConfig().set("web-server.health-path", "/api/health");
            getConfig().set("web-server.auth-token", "");
            getConfig().set("web-server.cors.enabled", true);
            getConfig().set("web-server.cors.allowed-origin", "*");
        }
        if (currentVersion < 12) {
            getConfig().set("web-server.page-title", "%server_name% Lotterie");
            getConfig().set("web-server.server-name", "");
        }
    }

    public int updateConfigDefaults() {
        List<ConfigSyncResult> results = synchronizeManagedConfigFiles("manual", true);
        reloadConfig();
        loadCustomConfigs();
        writeDefaultReferenceFiles();
        return (int) results.stream().filter(ConfigSyncResult::changed).count();
    }

    public File getLastConfigUpdateReportFile() {
        return lastConfigUpdateReportFile;
    }

    private void synchronizeManagedConfigsOnLoad(String reason) {
        List<ConfigSyncResult> results = synchronizeManagedConfigFiles(reason, false);
        if (results.stream().anyMatch(ConfigSyncResult::changed)) {
            reloadConfig();
        }
    }

    private List<ConfigSyncResult> synchronizeManagedConfigFiles(String reason, boolean writeReportWhenUnchanged) {
        List<ConfigSyncResult> results = new ArrayList<>();
        Set<String> languages = discoverLanguageCodes();

        File guiFolder = new File(getDataFolder(), "gui");
        File legacyGuiFile = new File(guiFolder, "gui.yml");
        File legacyBedrockGuiFile = new File(guiFolder, "bedrock-gui.yml");
        ensureGuiFilesForLanguages(guiFolder, legacyGuiFile, languages);
        ensureBedrockGuiFilesForLanguages(guiFolder, legacyBedrockGuiFile, languages);

        addSyncResult(results, "config.yml", new File(getDataFolder(), "config.yml"), reason);
        addSyncResult(results, "gui/gui.yml", legacyGuiFile, reason);
        addSyncResult(results, "gui/bedrock-gui.yml", legacyBedrockGuiFile, reason);
        for (String language : languages.stream().sorted().toList()) {
            addSyncResult(results, "gui/gui.yml", new File(guiFolder, language + ".yml"), reason);
            addSyncResult(results, "gui/bedrock-gui.yml", new File(guiFolder, "bedrock-" + language + ".yml"), reason);
        }

        addSyncResult(results, "holograms.yml", new File(getDataFolder(), "holograms.yml"), reason);
        addSyncResult(results, "lotteries.yml", new File(getDataFolder(), "lotteries.yml"), reason);

        File languageFolder = new File(getDataFolder(), "lang");
        for (String language : languages.stream().sorted().toList()) {
            String fileName = language + ".yml";
            String bundledResource = resourceExists("lang/" + fileName) ? "lang/" + fileName : "lang/de.yml";
            addSyncResult(results, bundledResource, new File(languageFolder, fileName), reason);
        }

        long changedFiles = results.stream().filter(ConfigSyncResult::changed).count();
        if (changedFiles > 0 || writeReportWhenUnchanged) {
            writeConfigUpdateReport(results);
        }
        if (changedFiles > 0) {
            getLogger().info("Synchronized " + changedFiles + " config file(s) during " + reason + ".");
        }
        return results;
    }

    private void addSyncResult(List<ConfigSyncResult> results, String resourcePath, File file, String reason) {
        ConfigSyncResult result = synchronizeConfigFile(resourcePath, file, reason);
        if (result != null) {
            results.add(result);
        }
    }

    private ConfigSyncResult synchronizeConfigFile(String resourcePath, File file, String reason) {
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                return null;
            }
            if (file.getParentFile() != null && !file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                getLogger().warning("Could not create folder for " + file.getName());
                return null;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String before = config.saveToString();
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));

            int removed = removeObsoleteKeys(config, defaults, resourcePath, "");
            int added = addMissingDefaults(config, defaults, "");
            if (!before.equals(config.saveToString())) {
                File backup = file.exists() ? backupConfigFile(file, reason) : null;
                config.save(file);
                return new ConfigSyncResult(resourcePath, file, added, removed, backup);
            }
        } catch (IOException exception) {
            getLogger().warning("Could not synchronize " + resourcePath + ": " + exception.getMessage());
        }
        return new ConfigSyncResult(resourcePath, file, 0, 0, null);
    }

    private int addMissingDefaults(YamlConfiguration config, YamlConfiguration defaults, String path) {
        ConfigurationSection defaultSection = getSection(defaults, path);
        if (defaultSection == null) {
            return 0;
        }

        int added = 0;
        for (String key : defaultSection.getKeys(false)) {
            String childPath = joinPath(path, key);
            if (defaults.isConfigurationSection(childPath)) {
                if (!config.isConfigurationSection(childPath)) {
                    config.set(childPath, null);
                    config.createSection(childPath);
                    added++;
                }
                added += addMissingDefaults(config, defaults, childPath);
            } else if (!config.contains(childPath, false) || config.isConfigurationSection(childPath)) {
                config.set(childPath, defaults.get(childPath));
                added++;
            }
        }
        return added;
    }

    private int removeObsoleteKeys(YamlConfiguration config, YamlConfiguration defaults, String resourcePath, String path) {
        ConfigurationSection currentSection = getSection(config, path);
        ConfigurationSection defaultSection = getSection(defaults, path);
        if (currentSection == null || defaultSection == null) {
            return 0;
        }

        int removed = 0;
        for (String key : new ArrayList<>(currentSection.getKeys(false))) {
            String childPath = joinPath(path, key);
            if (!defaults.contains(childPath, false)) {
                if (shouldPreserveUnknownKey(resourcePath, path)) {
                    continue;
                }
                config.set(childPath, null);
                removed++;
                continue;
            }

            boolean currentIsSection = config.isConfigurationSection(childPath);
            boolean defaultIsSection = defaults.isConfigurationSection(childPath);
            if (currentIsSection && defaultIsSection) {
                removed += removeObsoleteKeys(config, defaults, resourcePath, childPath);
            } else if (currentIsSection != defaultIsSection) {
                if (defaultIsSection) {
                    config.set(childPath, null);
                    config.createSection(childPath);
                } else {
                    config.set(childPath, defaults.get(childPath));
                }
                removed++;
            }
        }
        return removed;
    }

    private ConfigurationSection getSection(YamlConfiguration config, String path) {
        return path.isBlank() ? config : config.getConfigurationSection(path);
    }

    private String joinPath(String parent, String key) {
        return parent.isBlank() ? key : parent + "." + key;
    }

    private boolean shouldPreserveUnknownKey(String resourcePath, String parentPath) {
        String normalizedResource = resourcePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        if (normalizedResource.startsWith("gui/")) {
            return parentPath.endsWith(".items");
        }
        if ("config.yml".equals(normalizedResource)) {
            return Set.of(
                "pricing.tiered-prices.tiers",
                "free-tickets.reasons",
                "season-shop.rewards",
                "community-goals.goals",
                "achievements.items",
                "rewards.commands-by-rank",
                "rewards.packages.items",
                "webhook.events"
            ).contains(parentPath);
        }
        if ("holograms.yml".equals(normalizedResource)) {
            return "holograms".equals(parentPath) || "templates.statistics".equals(parentPath);
        }
        if ("lotteries.yml".equals(normalizedResource)) {
            return "profiles".equals(parentPath);
        }
        return false;
    }

    private File backupConfigFile(File file, String reason) throws IOException {
        File migrationFolder = new File(getDataFolder(), "migrations");
        if (!migrationFolder.exists() && !migrationFolder.mkdirs()) {
            throw new IOException("Could not create migrations folder for config backup.");
        }

        String safePath = relativePluginPath(file)
            .replace('\\', '_')
            .replace('/', '_')
            .replace(':', '_');
        String safeReason = reason.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "-");
        File backupFile = new File(migrationFolder, safePath + "-" + safeReason + "-" + System.currentTimeMillis() + ".yml");
        Files.copy(file.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        getLogger().info("Created config sync backup: " + backupFile.getName());
        return backupFile;
    }

    private String relativePluginPath(File file) {
        try {
            Path rootPath = getDataFolder().toPath().toAbsolutePath().normalize();
            Path filePath = file.toPath().toAbsolutePath().normalize();
            if (filePath.startsWith(rootPath)) {
                return rootPath.relativize(filePath).toString();
            }
        } catch (IllegalArgumentException exception) {
            // Fall back to the plain file name when a path cannot be relativized on this platform.
        }
        return file.getName();
    }

    private Set<String> discoverLanguageCodes() {
        Set<String> languages = new LinkedHashSet<>();
        collectLanguageCodes(new File(getDataFolder(), "lang"), languages, false);
        collectLanguageCodes(new File(getDataFolder(), "gui"), languages, true);
        languages.add(getDefaultLanguage());
        if (resourceExists("lang/de.yml")) {
            languages.add("de");
        }
        if (resourceExists("lang/en.yml")) {
            languages.add("en");
        }
        languages.remove("gui");
        languages.remove("");
        return languages;
    }

    private void collectLanguageCodes(File folder, Set<String> languages, boolean skipLegacyGuiFile) {
        File[] files = folder.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (files == null) {
            return;
        }

        for (File file : files) {
            String fileName = file.getName();
            String language = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            if (skipLegacyGuiFile && ("gui".equals(language) || "bedrock-gui".equals(language))) {
                continue;
            }
            if (skipLegacyGuiFile && language.startsWith("bedrock-")) {
                languages.add(language.substring("bedrock-".length()));
            } else {
                languages.add(language);
            }
        }
    }

    private void writeConfigUpdateReport(List<ConfigSyncResult> results) {
        File reportFolder = new File(getDataFolder(), "migrations");
        if (!reportFolder.exists() && !reportFolder.mkdirs()) {
            getLogger().warning("Could not create migrations folder for config update report.");
            return;
        }

        lastConfigUpdateReportFile = new File(reportFolder, "config-update-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        List<ConfigSyncResult> changedResults = results.stream().filter(ConfigSyncResult::changed).toList();
        String content;
        if (changedResults.isEmpty()) {
            content = "No managed config files required synchronization.\n";
        } else {
            StringBuilder builder = new StringBuilder("Synchronized managed config files:\n");
            for (ConfigSyncResult result : changedResults) {
                builder.append("- ")
                    .append(relativePluginPath(result.file()))
                    .append(" (template: ")
                    .append(result.resourcePath())
                    .append(", added: ")
                    .append(result.added())
                    .append(", removed: ")
                    .append(result.removed());
                if (result.backupFile() != null) {
                    builder.append(", backup: ").append(relativePluginPath(result.backupFile()));
                }
                builder.append(")\n");
            }
            content = builder.toString();
        }
        try {
            Files.writeString(lastConfigUpdateReportFile.toPath(), content, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            getLogger().warning("Could not write config update report: " + exception.getMessage());
        }
    }

    private void loadCustomConfigs() {
        hologramsFile = new File(getDataFolder(), "holograms.yml");
        hologramsConfig = loadCustomConfigWithDefaults("holograms.yml", hologramsFile);
        lotteriesFile = new File(getDataFolder(), "lotteries.yml");
        lotteriesConfig = loadCustomConfigWithDefaults("lotteries.yml", lotteriesFile);
        playerLanguagesFile = new File(getDataFolder(), "player-languages.yml");
        playerLanguagesConfig = YamlConfiguration.loadConfiguration(playerLanguagesFile);
        loadLanguageConfigs();
        loadGuiConfigs();
        loadBedrockGuiConfigs();
    }

    private void loadLanguageConfigs() {
        languageConfigs.clear();
        File languageFolder = new File(getDataFolder(), "lang");
        File[] languageFiles = languageFolder.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (languageFiles == null) {
            return;
        }

        for (File languageFile : languageFiles) {
            String fileName = languageFile.getName();
            String language = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            String bundledResource = resourceExists("lang/" + fileName) ? "lang/" + fileName : "lang/de.yml";
            languageConfigs.put(language, loadCustomConfigWithDefaults(bundledResource, languageFile));
        }
    }

    private boolean resourceExists(String resourcePath) {
        try (InputStream inputStream = getResource(resourcePath)) {
            return inputStream != null;
        } catch (IOException exception) {
            return false;
        }
    }

    private void loadGuiConfigs() {
        guiConfigs.clear();
        File guiFolder = new File(getDataFolder(), "gui");
        if (!guiFolder.exists() && !guiFolder.mkdirs()) {
            getLogger().warning("Could not create gui folder.");
            guiConfig = new YamlConfiguration();
            return;
        }

        File legacyGuiFile = new File(guiFolder, "gui.yml");
        guiConfig = loadCustomConfigWithDefaults("gui/gui.yml", legacyGuiFile);
        ensureGuiFilesForLanguages(guiFolder, legacyGuiFile);

        File[] guiFiles = guiFolder.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (guiFiles == null) {
            return;
        }

        for (File guiFile : guiFiles) {
            String fileName = guiFile.getName();
            String language = fileName.substring(0, fileName.length() - 4).toLowerCase(Locale.ROOT);
            if ("gui".equals(language) || isBedrockGuiFileName(language)) {
                continue;
            }
            guiConfigs.put(language, loadCustomConfigWithDefaults("gui/gui.yml", guiFile));
        }

        if (!guiConfigs.containsKey(getDefaultLanguage())) {
            guiConfigs.put(getDefaultLanguage(), guiConfig);
        }
    }

    private void loadBedrockGuiConfigs() {
        bedrockGuiConfigs.clear();
        File guiFolder = new File(getDataFolder(), "gui");
        if (!guiFolder.exists() && !guiFolder.mkdirs()) {
            getLogger().warning("Could not create gui folder for Bedrock GUI.");
            bedrockGuiConfig = new YamlConfiguration();
            return;
        }

        File legacyBedrockGuiFile = new File(guiFolder, "bedrock-gui.yml");
        bedrockGuiConfig = loadCustomConfigWithDefaults("gui/bedrock-gui.yml", legacyBedrockGuiFile);
        ensureBedrockGuiFilesForLanguages(guiFolder, legacyBedrockGuiFile, getAvailableLanguages());

        File[] guiFiles = guiFolder.listFiles((directory, name) ->
            name.toLowerCase(Locale.ROOT).startsWith("bedrock-")
                && name.toLowerCase(Locale.ROOT).endsWith(".yml")
                && !"bedrock-gui.yml".equalsIgnoreCase(name));
        if (guiFiles == null) {
            return;
        }

        for (File guiFile : guiFiles) {
            String fileName = guiFile.getName().toLowerCase(Locale.ROOT);
            String language = fileName.substring("bedrock-".length(), fileName.length() - 4);
            bedrockGuiConfigs.put(language, loadCustomConfigWithDefaults("gui/bedrock-gui.yml", guiFile));
        }

        if (!bedrockGuiConfigs.containsKey(getDefaultLanguage())) {
            bedrockGuiConfigs.put(getDefaultLanguage(), bedrockGuiConfig);
        }
    }

    private boolean isBedrockGuiFileName(String nameWithoutExtension) {
        return "bedrock-gui".equals(nameWithoutExtension) || nameWithoutExtension.startsWith("bedrock-");
    }

    private void ensureGuiFilesForLanguages(File guiFolder, File legacyGuiFile) {
        ensureGuiFilesForLanguages(guiFolder, legacyGuiFile, getAvailableLanguages());
    }

    private void ensureGuiFilesForLanguages(File guiFolder, File legacyGuiFile, Collection<String> languages) {
        ensureGuiFilesForLanguages(guiFolder, legacyGuiFile, languages, "", "gui/gui.yml", "GUI language file");
    }

    private void ensureBedrockGuiFilesForLanguages(File guiFolder, File legacyBedrockGuiFile, Collection<String> languages) {
        ensureGuiFilesForLanguages(guiFolder, legacyBedrockGuiFile, languages, "bedrock-", "gui/bedrock-gui.yml",
            "Bedrock GUI language file");
    }

    private void ensureGuiFilesForLanguages(File guiFolder, File sourceFile, Collection<String> languages,
                                            String prefix, String defaultResource, String logLabel) {
        if (!guiFolder.exists() && !guiFolder.mkdirs()) {
            getLogger().warning("Could not create gui folder.");
            return;
        }

        for (String language : languages) {
            File languageGuiFile = new File(guiFolder, prefix + language + ".yml");
            if (languageGuiFile.exists()) {
                continue;
            }
            try {
                if (sourceFile.exists()) {
                    Files.copy(sourceFile.toPath(), languageGuiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    copyDefaultReference(defaultResource, languageGuiFile);
                }
                getLogger().info("Created " + logLabel + ": gui/" + prefix + language + ".yml");
            } catch (IOException exception) {
                getLogger().warning("Could not create " + logLabel + " for " + language + ": " + exception.getMessage());
            }
        }
    }

    private YamlConfiguration loadCustomConfigWithDefaults(String resourcePath, File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                return config;
            }

            String before = config.saveToString();
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            if (!before.equals(config.saveToString())) {
                getLogger().fine("Applied in-memory defaults for " + file.getName() + "; persistent sync runs with backups.");
            }
        } catch (IOException exception) {
            getLogger().warning("Could not read defaults for " + file.getName() + ": " + exception.getMessage());
        }
        return config;
    }

    private FileConfiguration getLanguageConfig(String language) {
        FileConfiguration config = languageConfigs.get(language.toLowerCase(Locale.ROOT));
        if (config != null) {
            return config;
        }
        return languageConfigs.getOrDefault("de", new YamlConfiguration());
    }

    private record ConfigSyncResult(String resourcePath, File file, int added, int removed, File backupFile) {

        private boolean changed() {
            return added > 0 || removed > 0;
        }
    }
}
