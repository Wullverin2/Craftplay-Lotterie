package dev.codex.lottery.hook;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.service.LotteryManager;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

public final class LotteryPlaceholderExpansion extends PlaceholderExpansion {

    private final LotteryPlugin plugin;
    private final LotteryManager lotteryManager;

    public LotteryPlaceholderExpansion(LotteryPlugin plugin, LotteryManager lotteryManager) {
        this.plugin = plugin;
        this.lotteryManager = lotteryManager;
    }

    @Override
    public String getIdentifier() {
        return "lottery";
    }

    @Override
    public String getAuthor() {
        return "Codex";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, String params) {
        return lotteryManager.resolvePlaceholder(player, params);
    }
}
