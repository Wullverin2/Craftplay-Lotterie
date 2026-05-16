package dev.codex.lottery.service;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.util.MessageUtil;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public final class UpdateChecker {

    private static final Pattern VERSION_PARTS = Pattern.compile("[^0-9.]+");
    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private final LotteryPlugin plugin;
    private final HttpClient httpClient;
    private volatile UpdateResult lastResult;
    private volatile long lastCheckedAtMillis;
    private volatile boolean checking;

    public UpdateChecker(LotteryPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(Math.max(2, plugin.getConfig().getInt("update-checker.timeout-seconds", 10))))
            .build();
    }

    public void checkOnStartup() {
        if (!isEnabled() || !plugin.getConfig().getBoolean("update-checker.check-on-startup", true)) {
            return;
        }
        checkNow(Bukkit.getConsoleSender(), false);
    }

    public void checkNow(CommandSender sender, boolean force) {
        if (!isEnabled()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.update-check-disabled");
            return;
        }
        if (checking) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.update-check-running");
            return;
        }
        if (!force && isCacheFresh()) {
            sendResult(sender, lastResult);
            return;
        }

        checking = true;
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.update-check-started", Map.of(
            "source", getApiUrl()
        ));

        try {
            fetchLatestRelease()
                .whenComplete((result, throwable) -> Bukkit.getScheduler().runTask(plugin, () -> {
                    checking = false;
                    if (throwable != null) {
                        lastResult = UpdateResult.failed(throwable.getMessage());
                    } else {
                        lastResult = result;
                    }
                    lastCheckedAtMillis = System.currentTimeMillis();
                    sendResult(sender, lastResult);
                }));
        } catch (RuntimeException exception) {
            checking = false;
            lastResult = UpdateResult.failed(exception.getMessage());
            lastCheckedAtMillis = System.currentTimeMillis();
            sendResult(sender, lastResult);
        }
    }

    public void notifyPlayerOnJoin(Player player) {
        if (!isEnabled()
            || !plugin.getConfig().getBoolean("update-checker.notify-admins-on-join", true)
            || !player.hasPermission("lottery.admin")) {
            return;
        }

        UpdateResult result = lastResult;
        if (result == null || !result.updateAvailable()) {
            return;
        }
        MessageUtil.send(player, plugin.getMessagesConfig(player), "messages.update-check-available", result.placeholders());
    }

    private CompletableFuture<UpdateResult> fetchLatestRelease() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(getApiUrl()))
            .timeout(Duration.ofSeconds(Math.max(2, plugin.getConfig().getInt("update-checker.timeout-seconds", 10))))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CraftplayLotterie/" + getCurrentVersion())
            .GET()
            .build();

        return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
            .thenApply(response -> {
                if (response.statusCode() == 404) {
                    return UpdateResult.noRelease();
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    return UpdateResult.failed("HTTP " + response.statusCode());
                }

                ReleaseInfo releaseInfo = readReleaseInfo(response.body(), allowsPrereleases());
                String latestVersion = stripVersionPrefix(releaseInfo.version());
                String releaseUrl = releaseInfo.url();
                String releaseName = releaseInfo.name();
                if (latestVersion.isBlank()) {
                    return UpdateResult.noRelease();
                }

                int comparison = compareVersions(getCurrentVersion(), latestVersion);
                return new UpdateResult(
                    true,
                    comparison < 0,
                    getCurrentVersion(),
                    latestVersion,
                    releaseUrl,
                    releaseName == null || releaseName.isBlank() ? latestVersion : releaseName,
                    getReleaseChannel(),
                    ""
                );
            })
            .exceptionally(exception -> UpdateResult.failed(exception.getMessage()));
    }

    private void sendResult(CommandSender sender, UpdateResult result) {
        if (result == null) {
            return;
        }
        if (!result.success()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.update-check-failed", result.placeholders());
            return;
        }
        if (result.latestVersion().isBlank()) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.update-check-no-release", result.placeholders());
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender),
            result.updateAvailable() ? "messages.update-check-available" : "messages.update-check-current",
            result.placeholders());
        if (result.updateAvailable() && sender instanceof Player player
            && plugin.getConfig().getBoolean("update-checker.clickable-download.enabled", true)
            && !result.releaseUrl().isBlank()) {
            String button = MessageUtil.raw(player, plugin.getMessagesConfig(player), "messages.update-check-download-button", result.placeholders());
            player.sendMessage(Component.empty()
                .append(LEGACY_SERIALIZER.deserialize(MessageUtil.color(button)))
                .clickEvent(ClickEvent.openUrl(result.releaseUrl())));
        }
    }

    private boolean isCacheFresh() {
        long cacheMillis = Math.max(1L, plugin.getConfig().getLong("update-checker.cache-minutes", 60L)) * 60L * 1000L;
        return lastResult != null && System.currentTimeMillis() - lastCheckedAtMillis < cacheMillis;
    }

    private boolean isEnabled() {
        return plugin.getConfig().getBoolean("update-checker.enabled", true);
    }

    private String getApiUrl() {
        String configuredUrl = plugin.getConfig().getString("update-checker.api-url", "");
        if (configuredUrl != null && !configuredUrl.isBlank()) {
            return configuredUrl;
        }
        String repository = plugin.getConfig().getString("update-checker.repository", "Wullverin2/Craftplay-Lotterie");
        String suffix = allowsPrereleases() ? "/releases" : "/releases/latest";
        return "https://api.github.com/repos/" + repository + suffix;
    }

    private String getReleaseChannel() {
        return plugin.getConfig().getString("update-checker.channel", "stable").toLowerCase(Locale.ROOT);
    }

    private boolean allowsPrereleases() {
        String channel = getReleaseChannel();
        return channel.equals("beta") || channel.equals("dev") || channel.equals("development");
    }

    private String getCurrentVersion() {
        return stripVersionPrefix(plugin.getDescription().getVersion());
    }

    private static String readJsonString(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"").matcher(json);
        if (!matcher.find()) {
            return "";
        }
        return matcher.group(1)
            .replace("\\\"", "\"")
            .replace("\\/", "/")
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t");
    }

    private static boolean readJsonBoolean(String json, String key) {
        Matcher matcher = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(true|false)").matcher(json);
        return matcher.find() && Boolean.parseBoolean(matcher.group(1));
    }

    private static ReleaseInfo readReleaseInfo(String json, boolean allowPrerelease) {
        if (!json.trim().startsWith("[")) {
            return new ReleaseInfo(readJsonString(json, "tag_name"), readJsonString(json, "html_url"), readJsonString(json, "name"));
        }

        Matcher matcher = Pattern.compile("\\{[^{}]*\"tag_name\"\\s*:\\s*\"(?:\\\\.|[^\"])*\"[^{}]*}").matcher(json);
        while (matcher.find()) {
            String releaseJson = matcher.group();
            if (!allowPrerelease && readJsonBoolean(releaseJson, "prerelease")) {
                continue;
            }
            if (readJsonBoolean(releaseJson, "draft")) {
                continue;
            }
            return new ReleaseInfo(
                readJsonString(releaseJson, "tag_name"),
                readJsonString(releaseJson, "html_url"),
                readJsonString(releaseJson, "name")
            );
        }
        return new ReleaseInfo("", "", "");
    }

    static String stripVersionPrefix(String version) {
        if (version == null) {
            return "";
        }
        return version.trim().replaceFirst("^[vV]", "");
    }

    public static int compareVersions(String currentVersion, String latestVersion) {
        String[] current = VERSION_PARTS.matcher(stripVersionPrefix(currentVersion).toLowerCase(Locale.ROOT)).replaceAll("").split("\\.");
        String[] latest = VERSION_PARTS.matcher(stripVersionPrefix(latestVersion).toLowerCase(Locale.ROOT)).replaceAll("").split("\\.");
        int length = Math.max(current.length, latest.length);
        for (int index = 0; index < length; index++) {
            int currentPart = index < current.length && !current[index].isBlank() ? parseInt(current[index]) : 0;
            int latestPart = index < latest.length && !latest[index].isBlank() ? parseInt(latest[index]) : 0;
            if (currentPart != latestPart) {
                return Integer.compare(currentPart, latestPart);
            }
        }
        return 0;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private record UpdateResult(
        boolean success,
        boolean updateAvailable,
        String currentVersion,
        String latestVersion,
        String releaseUrl,
        String releaseName,
        String channel,
        String error
    ) {
        private static UpdateResult failed(String error) {
            return new UpdateResult(false, false, "", "", "", "", "", error == null ? "unknown" : error);
        }

        private static UpdateResult noRelease() {
            return new UpdateResult(true, false, "", "", "", "", "", "");
        }

        private Map<String, String> placeholders() {
            return Map.of(
                "current_version", currentVersion == null ? "" : currentVersion,
                "latest_version", latestVersion == null ? "" : latestVersion,
                "release_url", releaseUrl == null ? "" : releaseUrl,
                "release_name", releaseName == null ? "" : releaseName,
                "channel", channel == null ? "" : channel,
                "error", error == null ? "" : error
            );
        }
    }

    private record ReleaseInfo(String version, String url, String name) {
    }
}
