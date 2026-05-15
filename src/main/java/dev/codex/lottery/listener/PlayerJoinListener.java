package dev.codex.lottery.listener;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.service.LotteryManager;
import dev.codex.lottery.util.MessageUtil;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

public final class PlayerJoinListener implements Listener {

    private final LotteryPlugin plugin;
    private final LotteryManager lotteryManager;

    public PlayerJoinListener(LotteryPlugin plugin, LotteryManager lotteryManager) {
        this.plugin = plugin;
        this.lotteryManager = lotteryManager;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        lotteryManager.sendPendingNotifications(event.getPlayer());
        plugin.getUpdateChecker().notifyPlayerOnJoin(event.getPlayer());

        if (!plugin.getConfig().getBoolean("notifications.join-reminder.enabled", true)) {
            return;
        }

        if (!event.getPlayer().hasPermission("lottery.use")) {
            return;
        }

        MessageUtil.send(event.getPlayer(), plugin.getMessagesConfig(event.getPlayer()), "messages.join-reminder",
            lotteryManager.createCommonPlaceholders(event.getPlayer()));
    }
}
