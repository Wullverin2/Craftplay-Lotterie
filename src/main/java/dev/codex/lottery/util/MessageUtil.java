package dev.codex.lottery.util;

import java.util.Map;
import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

public final class MessageUtil {

    private MessageUtil() {
    }

    public static void send(CommandSender sender, FileConfiguration config, String path) {
        send(sender, config, path, Map.of());
    }

    public static void send(CommandSender sender, FileConfiguration config, String path, Map<String, String> values) {
        sender.sendMessage(applyExternalPlaceholders(sender instanceof Player player ? player : null, prefixed(config, path, values)));
    }

    public static String prefixed(FileConfiguration config, String path, Map<String, String> values) {
        String prefix = config.getString("messages.prefix", config.getString("prefix", ""));
        return color(replace(config.getString(path, path), values, prefix));
    }

    public static String prefixed(Player player, FileConfiguration config, String path, Map<String, String> values) {
        return applyExternalPlaceholders(player, prefixed(config, path, values));
    }

    public static String raw(FileConfiguration config, String path, Map<String, String> values) {
        return color(replace(config.getString(path, path), values, ""));
    }

    public static String format(Player player, String message, Map<String, String> values) {
        return applyExternalPlaceholders(player, color(replace(message, values, "")));
    }

    public static String raw(Player player, FileConfiguration config, String path, Map<String, String> values) {
        return applyExternalPlaceholders(player, raw(config, path, values));
    }

    public static List<String> rawList(FileConfiguration config, String path, Map<String, String> values) {
        return config.getStringList(path).stream()
            .map(line -> color(replace(line, values, "")))
            .toList();
    }

    public static List<String> rawList(Player player, FileConfiguration config, String path, Map<String, String> values) {
        return rawList(config, path, values).stream()
            .map(line -> applyExternalPlaceholders(player, line))
            .toList();
    }

    public static String color(String value) {
        return ChatColor.translateAlternateColorCodes('&', value);
    }

    private static String replace(String message, Map<String, String> values, String prefix) {
        String result = message;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            result = result.replace("%" + entry.getKey() + "%", entry.getValue());
        }
        return prefix + result;
    }

    private static String applyExternalPlaceholders(Player player, String text) {
        if (player == null || !Bukkit.getPluginManager().isPluginEnabled("PlaceholderAPI")) {
            return text;
        }

        try {
            Class<?> placeholderApi = Class.forName("me.clip.placeholderapi.PlaceholderAPI");
            return (String) placeholderApi
                .getMethod("setPlaceholders", org.bukkit.OfflinePlayer.class, String.class)
                .invoke(null, player, text);
        } catch (ReflectiveOperationException exception) {
            return text;
        }
    }
}
