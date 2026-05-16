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
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LotteryPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 8;

    private EconomyService economyService;
    private LotteryManager lotteryManager;
    private FileConfiguration guiConfig;
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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        migrateConfigIfNeeded();
        saveResource("gui/gui.yml", false);
        saveResource("holograms.yml", false);
        saveResource("lotteries.yml", false);
        saveResource("lang/de.yml", false);
        saveResource("lang/en.yml", false);
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

        getConfig().set("config-version", CONFIG_VERSION);
        saveConfig();
        getLogger().info("Config migrated to version " + CONFIG_VERSION + ".");
    }

    public int updateConfigDefaults() {
        int updatedFiles = 0;
        List<String> changedFiles = new ArrayList<>();
        updatedFiles += updateConfigDefaults("config.yml", new File(getDataFolder(), "config.yml"), changedFiles) ? 1 : 0;
        updatedFiles += updateConfigDefaults("gui/gui.yml", new File(getDataFolder(), "gui/gui.yml"), changedFiles) ? 1 : 0;
        for (String language : getAvailableLanguages()) {
            updatedFiles += updateConfigDefaults("gui/gui.yml", new File(getDataFolder(), "gui/" + language + ".yml"), changedFiles) ? 1 : 0;
        }
        updatedFiles += updateConfigDefaults("holograms.yml", hologramsFile, changedFiles) ? 1 : 0;
        updatedFiles += updateConfigDefaults("lotteries.yml", lotteriesFile, changedFiles) ? 1 : 0;
        File languageFolder = new File(getDataFolder(), "lang");
        File[] languageFiles = languageFolder.listFiles((directory, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (languageFiles != null) {
            for (File languageFile : languageFiles) {
                String fileName = languageFile.getName();
                String bundledResource = resourceExists("lang/" + fileName) ? "lang/" + fileName : "lang/de.yml";
                updatedFiles += updateConfigDefaults(bundledResource, languageFile, changedFiles) ? 1 : 0;
            }
        }
        writeConfigUpdateReport(changedFiles);
        reloadConfig();
        loadCustomConfigs();
        writeDefaultReferenceFiles();
        return updatedFiles;
    }

    public File getLastConfigUpdateReportFile() {
        return lastConfigUpdateReportFile;
    }

    private boolean updateConfigDefaults(String resourcePath, File file, List<String> changedFiles) {
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                return false;
            }
            if (file.getParentFile() != null && !file.getParentFile().exists() && !file.getParentFile().mkdirs()) {
                getLogger().warning("Could not create folder for " + file.getName());
                return false;
            }
            YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
            String before = config.saveToString();
            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
            if (!before.equals(config.saveToString())) {
                File backup = new File(getDataFolder(), "migrations/" + file.getName() + "-defaults-" + System.currentTimeMillis() + ".yml");
                if (file.exists()) {
                    if (backup.getParentFile() != null && !backup.getParentFile().exists()) {
                        backup.getParentFile().mkdirs();
                    }
                    Files.copy(file.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                config.save(file);
                changedFiles.add(resourcePath);
                return true;
            }
        } catch (IOException exception) {
            getLogger().warning("Could not update defaults for " + resourcePath + ": " + exception.getMessage());
        }
        return false;
    }

    private void writeConfigUpdateReport(List<String> changedFiles) {
        File reportFolder = new File(getDataFolder(), "migrations");
        if (!reportFolder.exists() && !reportFolder.mkdirs()) {
            getLogger().warning("Could not create migrations folder for config update report.");
            return;
        }

        lastConfigUpdateReportFile = new File(reportFolder, "config-update-"
            + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".txt");
        String content = changedFiles.isEmpty()
            ? "No config defaults were missing.\n"
            : "Updated config defaults:\n- " + String.join("\n- ", changedFiles) + "\n";
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
            if ("gui".equals(language)) {
                continue;
            }
            guiConfigs.put(language, loadCustomConfigWithDefaults("gui/gui.yml", guiFile));
        }

        if (!guiConfigs.containsKey(getDefaultLanguage())) {
            guiConfigs.put(getDefaultLanguage(), guiConfig);
        }
    }

    private void ensureGuiFilesForLanguages(File guiFolder, File legacyGuiFile) {
        for (String language : getAvailableLanguages()) {
            File languageGuiFile = new File(guiFolder, language + ".yml");
            if (languageGuiFile.exists()) {
                continue;
            }
            try {
                if (legacyGuiFile.exists()) {
                    Files.copy(legacyGuiFile.toPath(), languageGuiFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } else {
                    copyDefaultReference("gui/gui.yml", languageGuiFile);
                }
                getLogger().info("Created GUI language file: gui/" + language + ".yml");
            } catch (IOException exception) {
                getLogger().warning("Could not create GUI language file for " + language + ": " + exception.getMessage());
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
                if (file.getParentFile() != null && !file.getParentFile().exists()) {
                    file.getParentFile().mkdirs();
                }
                config.save(file);
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
}
