package dev.codex.lottery.service;

import dev.codex.lottery.LotteryPlugin;
import java.lang.reflect.Constructor;
import java.util.List;
import java.util.function.IntConsumer;
import org.bukkit.entity.Player;

public interface BedrockFormBridge {

    static BedrockFormBridge create(LotteryPlugin plugin) {
        if (!isFloodgateEnabled(plugin)) {
            return disabled();
        }

        try {
            Class<?> bridgeClass = Class.forName("dev.codex.lottery.hook.FloodgateBedrockFormBridge");
            Constructor<?> constructor = bridgeClass.getConstructor(LotteryPlugin.class);
            return (BedrockFormBridge) constructor.newInstance(plugin);
        } catch (ReflectiveOperationException | LinkageError exception) {
            plugin.getLogger().warning("Floodgate was detected, but Bedrock forms could not be enabled: "
                + exception.getMessage());
            return disabled();
        }
    }

    private static boolean isFloodgateEnabled(LotteryPlugin plugin) {
        return plugin.getServer().getPluginManager().isPluginEnabled("floodgate")
            || plugin.getServer().getPluginManager().isPluginEnabled("Floodgate");
    }

    static BedrockFormBridge disabled() {
        return DisabledBedrockFormBridge.INSTANCE;
    }

    boolean isAvailable();

    boolean isBedrockPlayer(Player player);

    boolean sendSimpleForm(Player player, String title, String content, List<BedrockFormButton> buttons,
                           IntConsumer clickHandler);

    final class DisabledBedrockFormBridge implements BedrockFormBridge {

        private static final DisabledBedrockFormBridge INSTANCE = new DisabledBedrockFormBridge();

        private DisabledBedrockFormBridge() {
        }

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public boolean isBedrockPlayer(Player player) {
            return false;
        }

        @Override
        public boolean sendSimpleForm(Player player, String title, String content, List<BedrockFormButton> buttons,
                                      IntConsumer clickHandler) {
            return false;
        }
    }
}
