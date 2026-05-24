package com.purpur.ecogovernor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.logger.slf4j.ComponentLogger;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * PurpurEcoGovernor — главный класс плагина.
 *
 * Управляет жизненным циклом, регистрирует менеджер логики, асинхронный
 * монитор производительности и GUI. Все операции с сущностями проксируются
 * через {@link Bukkit#getGlobalRegionScheduler()} либо {@link Bukkit#getRegionScheduler()},
 * чтобы гарантировать потокобезопасность на Paper/Purpur.
 */
public final class PurpurEcoGovernor extends JavaPlugin implements CommandExecutor, TabCompleter {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private ComponentLogger componentLogger;
    private GovernorManager governorManager;
    private LagMonitorTask lagMonitorTask;
    private ScheduledTask monitorHandle;
    private ScheduledTask cullingHandle;
    private ScheduledTask restoreHandle;

    @Override
    public void onEnable() {
        this.componentLogger = getComponentLogger();
        saveDefaultConfig();
        reloadConfig();

        this.governorManager = new GovernorManager(this);
        this.lagMonitorTask = new LagMonitorTask(this, governorManager);

        FileConfiguration cfg = getConfig();
        long sampleInterval = cfg.getLong("performance.sample-interval-ticks", 20L);
        long restorePeriod = cfg.getLong("performance.restore-period-ticks", 40L);
        long cullingPeriod = cfg.getLong("culling.scan-period-ticks", 200L);

        long sampleIntervalMs = Math.max(50L, sampleInterval * 50L);

        this.monitorHandle = Bukkit.getAsyncScheduler().runAtFixedRate(
                this,
                task -> lagMonitorTask.tick(),
                sampleIntervalMs,
                sampleIntervalMs,
                TimeUnit.MILLISECONDS
        );

        this.cullingHandle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> governorManager.runCullingScan(),
                cullingPeriod,
                cullingPeriod
        );

        this.restoreHandle = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                this,
                task -> governorManager.tickRestoration(),
                restorePeriod,
                restorePeriod
        );

        PluginCommand cmd = getCommand("purpureco");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        } else {
            componentLogger.warn("Команда 'purpureco' не найдена в plugin.yml.");
        }

        Bukkit.getPluginManager().registerEvents(new PerformanceGUI.ClickGuard(), this);

        componentLogger.info("PurpurEcoGovernor успешно загружен. Threshold MSPT: {} мс / restore: {} мс.",
                cfg.getDouble("performance.mspt-throttle-threshold", 45.0),
                cfg.getDouble("performance.mspt-restore-threshold", 35.0));
    }

    @Override
    public void onDisable() {
        if (monitorHandle != null) monitorHandle.cancel();
        if (cullingHandle != null) cullingHandle.cancel();
        if (restoreHandle != null) restoreHandle.cancel();

        if (governorManager != null) {
            governorManager.shutdownRestoreAll();
        }
        componentLogger.info("PurpurEcoGovernor выгружен. Все мобы возвращены в нормальное состояние.");
    }

    public GovernorManager governor() {
        return governorManager;
    }

    public LagMonitorTask monitor() {
        return lagMonitorTask;
    }

    /**
     * Возвращает MiniMessage-форматтер.
     */
    public static MiniMessage mm() {
        return MINI;
    }

    /**
     * Форматирует сообщение из конфига с подстановкой плейсхолдеров.
     */
    public Component msg(String key, TagResolver... resolvers) {
        String prefix = getConfig().getString("messages.prefix", "");
        String raw = getConfig().getString("messages." + key, key);
        return MINI.deserialize(prefix + raw, resolvers);
    }

    public Component mmDeserialize(String raw, TagResolver... resolvers) {
        return MINI.deserialize(raw, resolvers);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("purpureco.command")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }

        if (args.length == 0) {
            return openGui(sender);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "reload" -> {
                if (!sender.hasPermission("purpureco.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                reloadConfig();
                lagMonitorTask.reloadThresholds();
                governorManager.reload();
                sender.sendMessage(msg("reload-success"));
            }
            case "throttle" -> {
                if (!sender.hasPermission("purpureco.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                boolean enabled = governorManager.toggleForceThrottle();
                sender.sendMessage(msg(enabled ? "force-throttle-on" : "force-throttle-off"));
            }
            case "purge" -> {
                if (!sender.hasPermission("purpureco.admin")) {
                    sender.sendMessage(msg("no-permission"));
                    return true;
                }
                governorManager.purgeGroundItems(count ->
                        sender.sendMessage(msg("purge-result",
                                Placeholder.unparsed("count", Integer.toString(count)))));
            }
            case "gui" -> {
                return openGui(sender);
            }
            default -> sender.sendMessage(msg("unknown-subcommand"));
        }
        return true;
    }

    private boolean openGui(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(msg("player-only"));
            return true;
        }
        if (!sender.hasPermission("purpureco.gui")) {
            sender.sendMessage(msg("no-permission"));
            return true;
        }
        PerformanceGUI gui = new PerformanceGUI(this, player);
        gui.open();
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return Collections.emptyList();
        List<String> base = new ArrayList<>(List.of("reload", "throttle", "purge", "gui"));
        String prefix = args[0].toLowerCase(Locale.ROOT);
        base.removeIf(s -> !s.startsWith(prefix));
        return base;
    }
}
