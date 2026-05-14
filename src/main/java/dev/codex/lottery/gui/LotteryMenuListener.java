package dev.codex.lottery.gui;

import dev.codex.lottery.service.LotteryManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;

public final class LotteryMenuListener implements Listener {

    private final LotteryManager lotteryManager;

    public LotteryMenuListener(LotteryManager lotteryManager) {
        this.lotteryManager = lotteryManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        String title = event.getView().getTitle();
        if (!lotteryManager.isLotteryMenu(title)) {
            return;
        }

        event.setCancelled(true);

        if (event.getCurrentItem() == null) {
            return;
        }

        lotteryManager.handleMenuClick(player, title, event.getSlot());
    }
}
