package dev.codex.lottery.hook;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.service.BedrockFormBridge;
import dev.codex.lottery.service.BedrockFormButton;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.cumulus.form.SimpleForm;
import org.geysermc.cumulus.util.FormImage;
import org.geysermc.floodgate.api.FloodgateApi;

public final class FloodgateBedrockFormBridge implements BedrockFormBridge {

    private final LotteryPlugin plugin;

    public FloodgateBedrockFormBridge(LotteryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean isAvailable() {
        return floodgateApi() != null;
    }

    @Override
    public boolean isBedrockPlayer(Player player) {
        FloodgateApi api = floodgateApi();
        return api != null && api.isFloodgatePlayer(player.getUniqueId());
    }

    @Override
    public boolean sendSimpleForm(Player player, String title, String content, List<BedrockFormButton> buttons,
                                  IntConsumer clickHandler) {
        FloodgateApi api = floodgateApi();
        if (api == null || !api.isFloodgatePlayer(player.getUniqueId())) {
            return false;
        }

        SimpleForm.Builder builder = SimpleForm.builder()
            .title(title)
            .content(content)
            .validResultHandler(response -> Bukkit.getScheduler().runTask(plugin, () ->
                clickHandler.accept(response.clickedButtonId())));

        for (BedrockFormButton button : buttons) {
            FormImage.Type imageType = parseImageType(button.imageType());
            if (imageType != null && button.imageData() != null && !button.imageData().isBlank()) {
                builder.button(button.text(), imageType, button.imageData());
            } else {
                builder.button(button.text());
            }
        }

        return api.sendForm(player.getUniqueId(), builder);
    }

    private FloodgateApi floodgateApi() {
        try {
            return FloodgateApi.getInstance();
        } catch (IllegalStateException | LinkageError exception) {
            return null;
        }
    }

    private FormImage.Type parseImageType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "url" -> FormImage.Type.URL;
            case "path", "texture" -> FormImage.Type.PATH;
            default -> null;
        };
    }
}
