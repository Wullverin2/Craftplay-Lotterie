package dev.codex.lottery.command;

import dev.codex.lottery.LotteryPlugin;
import dev.codex.lottery.service.LotteryManager;
import dev.codex.lottery.service.LotteryManager.DrawResult;
import dev.codex.lottery.service.LotteryManager.PurchaseResult;
import dev.codex.lottery.util.MessageUtil;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public final class LotteryCommand implements CommandExecutor, TabCompleter {

    private static final LegacyComponentSerializer LEGACY_SERIALIZER = LegacyComponentSerializer.legacySection();

    private static final List<String> SUBCOMMANDS = List.of(
        "help", "buy", "free", "shop", "gui", "jackpot", "winners", "stats", "nextdraw",
        "draw", "reload", "setjackpot", "addjackpot", "reset", "info", "hologram", "admin",
        "notifications", "payments", "backup", "export", "import", "debug", "doctor", "log", "setup", "simulate",
        "season", "preview", "editor", "lotteries", "transactions"
    );

    private static final List<String> HOLOGRAM_SUBCOMMANDS = List.of("create", "move", "delete", "list");
    private static final List<String> HOLOGRAM_TYPES = List.of("countdown", "statistic");
    private static final List<String> STATISTICS = List.of(
        "am_meisten_mitgespielt",
        "meiste_tickets",
        "gesamtausgaben",
        "am_haeufigsten_gewonnen",
        "hoechster_gewinn",
        "gesamt_gewonnen",
        "aktuelle_tickets"
    );

    private final LotteryPlugin plugin;
    private final LotteryManager lotteryManager;

    public LotteryCommand(LotteryPlugin plugin, LotteryManager lotteryManager) {
        this.plugin = plugin;
        this.lotteryManager = lotteryManager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                if (!requireUsePermission(player)) {
                    return true;
                }
                lotteryManager.openMenu(player);
                return true;
            }

            sendHelp(sender);
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "help" -> handleHelp(sender);
            case "buy" -> handleBuy(sender, args);
            case "free" -> handleFree(sender, args);
            case "shop" -> handleShop(sender, args);
            case "gui" -> handleGui(sender);
            case "jackpot", "status" -> handleStatus(sender);
            case "winners" -> handleWinners(sender);
            case "stats" -> handleStats(sender);
            case "nextdraw" -> handleNextDraw(sender);
            case "draw" -> handleDraw(sender);
            case "simulate" -> handleSimulate(sender);
            case "reload" -> handleReload(sender);
            case "setjackpot" -> handleSetJackpot(sender, args);
            case "addjackpot" -> handleAddJackpot(sender, args);
            case "reset" -> handleReset(sender);
            case "info" -> handleInfo(sender, args);
            case "hologram" -> handleHologram(sender, args);
            case "admin" -> handleAdmin(sender);
            case "notifications" -> handleNotifications(sender, args);
            case "payments" -> handlePayments(sender);
            case "backup" -> handleBackup(sender);
            case "export" -> handleExport(sender, args);
            case "import" -> handleImport(sender, args);
            case "debug" -> handleDebug(sender);
            case "doctor" -> handleDoctor(sender);
            case "log" -> handleLog(sender, args);
            case "setup" -> handleSetup(sender, args);
            case "season" -> handleSeason(sender, args);
            case "preview" -> handlePreview(sender, args);
            case "editor" -> handleEditor(sender);
            case "lotteries" -> handleLotteries(sender, args);
            case "transactions" -> handleTransactions(sender, args);
            default -> {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.unknown-subcommand");
                yield true;
            }
        };
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> suggestions = new ArrayList<>();

        if (args.length == 1) {
            for (String subcommand : SUBCOMMANDS) {
                if (isSubcommandAllowed(sender, subcommand) && subcommand.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    suggestions.add(subcommand);
                }
            }
            return suggestions;
        }

        if (args.length == 2) {
            String subcommand = args[0].toLowerCase(Locale.ROOT);
            if ("buy".equals(subcommand)) {
                suggestions.addAll(List.of("1", "5", "10", "25"));
            } else if ("free".equals(subcommand)) {
                addMatching(suggestions, args[1], List.of("default", "playtime", "jobs", "quests", "votes"));
            } else if ("shop".equals(subcommand)) {
                addMatching(suggestions, args[1], List.of("buy"));
            } else if ("info".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    String name = onlinePlayer.getName();
                    if (name.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        suggestions.add(name);
                    }
                }
            } else if ("hologram".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], HOLOGRAM_SUBCOMMANDS);
            } else if ("notifications".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("list", "clear"));
            } else if ("payments".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("retry"));
            } else if ("setup".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("price", "minplayers", "drawtime", "adddrawtime", "multipledraws", "cooldown", "dailytickets", "dailyspend", "winners", "shares", "autobackup", "type", "fixedprize", "profile"));
            } else if ("season".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("reset"));
            } else if ("preview".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("gui", "draw", "holograms"));
            } else if ("lotteries".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("list", "select"));
            } else if ("transactions".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("1", "2", "3", "filter"));
            } else if ("log".equals(subcommand) && sender.hasPermission("lottery.admin")) {
                addMatching(suggestions, args[1], List.of("player", "action", "date"));
            }
        } else if (args.length == 3 && "shop".equalsIgnoreCase(args[0]) && "buy".equalsIgnoreCase(args[1])) {
            addMatching(suggestions, args[2], List.of("default"));
        } else if (args.length == 3 && "transactions".equalsIgnoreCase(args[0])
            && "filter".equalsIgnoreCase(args[1]) && sender.hasPermission("lottery.admin")) {
            addMatching(suggestions, args[2], List.of("player", "type", "date", "details"));
        } else if (args.length == 3 && "lotteries".equalsIgnoreCase(args[0])
            && "select".equalsIgnoreCase(args[1]) && sender.hasPermission("lottery.admin")) {
            addMatching(suggestions, args[2], lotteryManager.getLotteryProfileIds());
        } else if (args.length == 3 && "notifications".equalsIgnoreCase(args[0]) && sender.hasPermission("lottery.admin")) {
            if ("clear".equalsIgnoreCase(args[1])) {
                addMatching(suggestions, args[2], List.of("all"));
            }
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                String name = onlinePlayer.getName();
                if (name.toLowerCase(Locale.ROOT).startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    suggestions.add(name);
                }
            }
        } else if (args.length == 4 && "hologram".equalsIgnoreCase(args[0]) && "create".equalsIgnoreCase(args[1])) {
            addMatching(suggestions, args[3], HOLOGRAM_TYPES);
        } else if (args.length == 5 && "hologram".equalsIgnoreCase(args[0]) && "create".equalsIgnoreCase(args[1])
            && "statistic".equalsIgnoreCase(args[3])) {
            addMatching(suggestions, args[4], STATISTICS);
        }

        return suggestions;
    }

    private boolean handleHelp(CommandSender sender) {
        sendHelp(sender);
        return true;
    }

    private boolean handleBuy(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return true;
        }
        if (!requireUsePermission(player)) {
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(player, plugin.getMessagesConfig(sender), "messages.invalid-amount");
            return true;
        }

        try {
            int amount = Integer.parseInt(args[1]);
            PurchaseResult result = lotteryManager.buyTickets(player, amount);
            MessageUtil.send(player, plugin.getMessagesConfig(sender), result.messagePath(), result.placeholders());
        } catch (NumberFormatException exception) {
            MessageUtil.send(player, plugin.getMessagesConfig(sender), "messages.invalid-amount");
        }
        return true;
    }

    private boolean handleFree(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return true;
        }
        if (!requireUsePermission(player)) {
            return true;
        }

        String reason = args.length >= 2 ? args[1] : "default";
        PurchaseResult result = lotteryManager.claimFreeTickets(player, reason);
        MessageUtil.send(player, plugin.getMessagesConfig(player), result.messagePath(), result.placeholders());
        return true;
    }

    private boolean handleShop(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return true;
        }
        if (!requireUsePermission(player)) {
            return true;
        }

        if (args.length >= 3 && "buy".equalsIgnoreCase(args[1])) {
            lotteryManager.buySeasonShopReward(player, args[2]);
            return true;
        }

        lotteryManager.showSeasonShop(player);
        return true;
    }

    private boolean handleGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return true;
        }
        if (!requireUsePermission(player)) {
            return true;
        }

        lotteryManager.openMenu(player);
        return true;
    }

    private boolean handleAdmin(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
            return true;
        }

        lotteryManager.openAdminMenu(player);
        return true;
    }

    private boolean handleStatus(CommandSender sender) {
        if (!requireUsePermission(sender)) {
            return true;
        }
        lotteryManager.showStatus(sender);
        return true;
    }

    private boolean handleWinners(CommandSender sender) {
        if (!requireUsePermission(sender)) {
            return true;
        }
        lotteryManager.showWinners(sender);
        return true;
    }

    private boolean handleStats(CommandSender sender) {
        if (!requireUsePermission(sender)) {
            return true;
        }
        lotteryManager.showStats(sender);
        return true;
    }

    private boolean handleNextDraw(CommandSender sender) {
        if (!requireUsePermission(sender)) {
            return true;
        }
        lotteryManager.showNextDraw(sender);
        return true;
    }

    private boolean handleDraw(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (lotteryManager.requireAdminConfirmation(sender, "force-draw")) {
            return true;
        }

        DrawResult result = lotteryManager.forceDraw(sender);
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), result.adminMessagePath(), result.placeholders());
        return true;
    }

    private boolean handleSimulate(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        DrawResult result = lotteryManager.simulateDraw(sender);
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), result.adminMessagePath(), result.placeholders());
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        plugin.reloadPlugin();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.reload-success");
        return true;
    }

    private boolean handleSetJackpot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.invalid-jackpot");
            return true;
        }

        try {
            double amount = Double.parseDouble(args[1]);
            lotteryManager.setJackpot(amount);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.set-jackpot-success", Map.of(
                "amount", plugin.getEconomyService().format(amount)
            ));
        } catch (NumberFormatException exception) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.invalid-jackpot");
        }
        return true;
    }

    private boolean handleAddJackpot(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.invalid-jackpot");
            return true;
        }

        try {
            double amount = Double.parseDouble(args[1]);
            lotteryManager.addJackpot(amount);
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.add-jackpot-success", Map.of(
                "amount", plugin.getEconomyService().format(amount),
                "jackpot", plugin.getEconomyService().format(lotteryManager.getJackpot())
            ));
        } catch (NumberFormatException exception) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.invalid-jackpot");
        }
        return true;
    }

    private boolean handleReset(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (lotteryManager.requireAdminConfirmation(sender, "reset-round")) {
            return true;
        }
        lotteryManager.resetRound();
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.reset-success");
        return true;
    }

    private boolean handleInfo(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.info-player-required");
            return true;
        }

        OfflinePlayer target = Bukkit.getOfflinePlayer(args[1]);
        lotteryManager.showPlayerInfo(sender, target);
        return true;
    }

    private boolean handleNotifications(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        if (args.length < 2 || "list".equalsIgnoreCase(args[1])) {
            OfflinePlayer target = args.length >= 3 ? Bukkit.getOfflinePlayer(args[2]) : null;
            lotteryManager.listPendingNotifications(sender, target);
            return true;
        }

        if ("clear".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-usage");
                return true;
            }

            boolean all = "all".equalsIgnoreCase(args[2]);
            OfflinePlayer target = all ? null : Bukkit.getOfflinePlayer(args[2]);
            lotteryManager.clearPendingNotifications(sender, target, all);
            return true;
        }

        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.notifications-usage");
        return true;
    }

    private boolean handlePayments(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        lotteryManager.retryPayments(sender);
        return true;
    }

    private boolean handleBackup(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        lotteryManager.createBackup(sender);
        return true;
    }

    private boolean handleExport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length >= 2 && "csv".equalsIgnoreCase(args[1])) {
            lotteryManager.exportCsv(sender);
            return true;
        }
        lotteryManager.exportData(sender);
        return true;
    }

    private boolean handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.import-usage");
            return true;
        }
        if (lotteryManager.requireAdminConfirmation(sender, "import-data")) {
            return true;
        }
        lotteryManager.importData(sender, args[1]);
        return true;
    }

    private boolean handleDebug(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        lotteryManager.showDebug(sender);
        return true;
    }

    private boolean handleDoctor(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        lotteryManager.runDoctor(sender);
        return true;
    }

    private boolean handleLog(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        if (args.length >= 4) {
            int page = parsePositiveInt(args[3], 1);
            lotteryManager.searchAdminLog(sender, args[1], args[2], page);
            return true;
        }

        if (args.length >= 3 && !isInteger(args[1])) {
            lotteryManager.searchAdminLog(sender, args[1], args[2], 1);
            return true;
        }

        int page = args.length >= 2 ? parsePositiveInt(args[1], 1) : 1;
        lotteryManager.listAdminLog(sender, page);
        return true;
    }

    private boolean handleSetup(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 3) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setup-usage");
            return true;
        }

        String field = args[1].toLowerCase(Locale.ROOT);
        String value = args[2];
        try {
            switch (field) {
                case "price" -> plugin.getConfig().set("settings.ticket-price", Double.parseDouble(value));
                case "minplayers" -> plugin.getConfig().set("settings.minimum-players-to-draw", Integer.parseInt(value));
                case "drawtime" -> {
                    String[] parts = value.split(":", 2);
                    plugin.getConfig().set("settings.draw-time.hour", Integer.parseInt(parts[0]));
                    plugin.getConfig().set("settings.draw-time.minute", Integer.parseInt(parts[1]));
                }
                case "adddrawtime" -> {
                    List<String> times = new ArrayList<>(plugin.getConfig().getStringList("settings.draw-schedule.times"));
                    if (!times.contains(value)) {
                        times.add(value);
                    }
                    plugin.getConfig().set("settings.draw-schedule.times", times);
                }
                case "multipledraws" -> plugin.getConfig().set("settings.draw-schedule.multiple-draws-enabled", Boolean.parseBoolean(value));
                case "cooldown" -> plugin.getConfig().set("security.purchase-cooldown-seconds", Long.parseLong(value));
                case "dailytickets" -> plugin.getConfig().set("security.daily-ticket-limit", Integer.parseInt(value));
                case "dailyspend" -> plugin.getConfig().set("security.daily-spend-limit", Double.parseDouble(value));
                case "winners" -> plugin.getConfig().set("winners.multiple-enabled", Boolean.parseBoolean(value));
                case "shares" -> plugin.getConfig().set("winners.prize-shares", parseDoubleList(value));
                case "autobackup" -> plugin.getConfig().set("backups.auto-after-draw.enabled", Boolean.parseBoolean(value));
                case "type" -> plugin.getConfig().set("settings.lottery-type", value);
                case "fixedprize" -> plugin.getConfig().set("settings.fixed-prize-amount", Double.parseDouble(value));
                case "profile" -> {
                    if (lotteryManager.requireAdminConfirmation(sender, "profile-switch")) {
                        return true;
                    }
                    lotteryManager.setActiveLottery(sender, value);
                    return true;
                }
                default -> {
                    MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setup-usage");
                    return true;
                }
            }
            plugin.saveConfig();
            plugin.reloadPlugin();
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setup-updated");
        } catch (RuntimeException exception) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setup-invalid");
        }
        return true;
    }

    private boolean handleSeason(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2 || !"reset".equalsIgnoreCase(args[1])) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.season-usage");
            return true;
        }
        String seasonId = args.length >= 3 ? args[2] : "";
        if (lotteryManager.requireAdminConfirmation(sender, "season-reset")) {
            return true;
        }
        lotteryManager.resetSeason(sender, seasonId);
        return true;
    }

    private boolean handlePreview(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.preview-usage");
            return true;
        }

        String target = args[1].toLowerCase(Locale.ROOT);
        switch (target) {
            case "gui" -> {
                if (!(sender instanceof Player player)) {
                    MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.player-only");
                    return true;
                }
                lotteryManager.openMenu(player);
            }
            case "draw" -> {
                DrawResult result = lotteryManager.simulateDraw(sender);
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), result.adminMessagePath(), result.placeholders());
            }
            case "holograms" -> lotteryManager.previewHolograms(sender);
            default -> MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.preview-usage");
        }
        return true;
    }

    private boolean handleEditor(CommandSender sender) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.setup-usage");
            return true;
        }
        lotteryManager.openAdminMenu(player);
        return true;
    }

    private boolean handleLotteries(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        if (args.length >= 2 && "select".equalsIgnoreCase(args[1])) {
            if (args.length < 3) {
                MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-usage");
                return true;
            }
            if (lotteryManager.requireAdminConfirmation(sender, "profile-switch")) {
                return true;
            }
            lotteryManager.setActiveLottery(sender, args[2]);
            return true;
        }

        if (args.length >= 2 && !"list".equalsIgnoreCase(args[1])) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.lotteries-usage");
            return true;
        }
        lotteryManager.listLotteries(sender);
        return true;
    }

    private boolean handleTransactions(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        if (args.length >= 4 && "filter".equalsIgnoreCase(args[1])) {
            int page = args.length >= 5 ? parsePositiveInt(args[4], 1) : 1;
            lotteryManager.searchTransactions(sender, args[2], args[3], page);
            return true;
        }

        int page = args.length >= 2 ? parsePositiveInt(args[1], 1) : 1;
        lotteryManager.listTransactions(sender, page);
        return true;
    }

    private boolean handleHologram(CommandSender sender, String[] args) {
        if (!sender.hasPermission("lottery.admin")) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
            return true;
        }

        if (args.length < 2) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-usage");
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ("list".equals(action)) {
            int page = args.length >= 3 ? parsePositiveInt(args[2], 1) : 1;
            lotteryManager.listHolograms(sender, page);
            return true;
        }

        if (args.length < 3) {
            MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-usage");
            return true;
        }

        String id = args[2];
        switch (action) {
            case "create" -> {
                if (args.length < 4) {
                    MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-usage");
                    return true;
                }
                String type = args[3];
                String statistic = args.length >= 5 ? args[4] : "tickets_bought";
                lotteryManager.createHologram(sender, id, type, statistic);
            }
            case "move" -> lotteryManager.moveHologram(sender, id);
            case "delete" -> lotteryManager.deleteHologram(sender, id);
            default -> MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.hologram-usage");
        }
        return true;
    }

    private int parsePositiveInt(String value, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(value.trim()));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private List<Double> parseDoubleList(String value) {
        List<Double> values = new ArrayList<>();
        for (String part : value.split(",")) {
            values.add(Double.parseDouble(part.trim()));
        }
        return values;
    }

    private boolean isInteger(String value) {
        try {
            Integer.parseInt(value.trim());
            return true;
        } catch (NumberFormatException exception) {
            return false;
        }
    }

    private boolean requireUsePermission(CommandSender sender) {
        if (sender.hasPermission("lottery.use")) {
            return true;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.no-permission");
        return false;
    }

    private void sendHelp(CommandSender sender) {
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), "messages.help-header");
        sendHelpLine(sender, "messages.help-line-status", "/lottery");
        sendHelpLine(sender, "messages.help-line-buy", "/lottery buy 1");
        sendHelpLine(sender, "messages.help-line-free", "/lottery free");
        sendHelpLine(sender, "messages.help-line-shop", "/lottery shop");
        sendHelpLine(sender, "messages.help-line-gui", "/lottery gui");
        sendHelpLine(sender, "messages.help-line-winners", "/lottery winners");
        sendHelpLine(sender, "messages.help-line-nextdraw", "/lottery nextdraw");
        if (sender.hasPermission("lottery.admin")) {
            sendHelpLine(sender, "messages.help-line-admin", "/lottery admin");
        }
    }

    private void sendHelpLine(CommandSender sender, String path, String command) {
        if (sender instanceof Player player && plugin.getConfig().getBoolean("help.clickable.enabled", true)) {
            String message = MessageUtil.raw(player, plugin.getMessagesConfig(player), path, Map.of());
            player.sendMessage(LEGACY_SERIALIZER.deserialize(MessageUtil.color(message))
                .clickEvent(ClickEvent.runCommand(command)));
            return;
        }
        MessageUtil.send(sender, plugin.getMessagesConfig(sender), path);
    }

    private boolean isSubcommandAllowed(CommandSender sender, String subcommand) {
        return switch (subcommand) {
            case "draw", "simulate", "reload", "setjackpot", "addjackpot", "reset", "info", "hologram", "admin",
                "notifications", "payments", "backup", "export", "import", "debug", "doctor", "log", "setup", "season",
                "preview", "editor", "lotteries", "transactions" -> sender.hasPermission("lottery.admin");
            default -> sender.hasPermission("lottery.use");
        };
    }

    private void addMatching(List<String> suggestions, String input, List<String> values) {
        String normalizedInput = input.toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (value.startsWith(normalizedInput)) {
                suggestions.add(value);
            }
        }
    }
}
