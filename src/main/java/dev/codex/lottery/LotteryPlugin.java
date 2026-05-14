package dev.codex.lottery;

import dev.codex.lottery.command.LotteryCommand;
import dev.codex.lottery.gui.LotteryMenuListener;
import dev.codex.lottery.hook.LotteryPlaceholderExpansion;
import dev.codex.lottery.listener.PlayerJoinListener;
import dev.codex.lottery.service.EconomyService;
import dev.codex.lottery.service.LotteryManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

public final class LotteryPlugin extends JavaPlugin {

    private static final int CONFIG_VERSION = 3;

    private EconomyService economyService;
    private LotteryManager lotteryManager;
    private FileConfiguration guiConfig;
    private FileConfiguration hologramsConfig;
    private FileConfiguration lotteriesConfig;
    private FileConfiguration playerLanguagesConfig;
    private File playerLanguagesFile;
    private File hologramsFile;
    private File lotteriesFile;
    private final Map<String, FileConfiguration> languageConfigs = new HashMap<>();

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
        runStartupChecks();

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
        if (lotteryManager != null) {
            lotteryManager.reload();
            lotteryManager.startScheduler();
        }
    }

    public EconomyService getEconomyService() {
        return economyService;
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
        return guiConfig;
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

    private void loadCustomConfigs() {
        guiConfig = loadCustomConfigWithDefaults("gui/gui.yml", new File(getDataFolder(), "gui/gui.yml"));
        hologramsFile = new File(getDataFolder(), "holograms.yml");
        hologramsConfig = loadCustomConfigWithDefaults("holograms.yml", hologramsFile);
        lotteriesFile = new File(getDataFolder(), "lotteries.yml");
        lotteriesConfig = loadCustomConfigWithDefaults("lotteries.yml", lotteriesFile);
        playerLanguagesFile = new File(getDataFolder(), "player-languages.yml");
        playerLanguagesConfig = YamlConfiguration.loadConfiguration(playerLanguagesFile);
        loadLanguageConfigs();
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
            languageConfigs.put(language, loadCustomConfigWithDefaults("lang/" + fileName, languageFile));
        }
    }

    private YamlConfiguration loadCustomConfigWithDefaults(String resourcePath, File file) {
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        try (InputStream inputStream = getResource(resourcePath)) {
            if (inputStream == null) {
                return config;
            }

            YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            config.setDefaults(defaults);
            config.options().copyDefaults(true);
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
