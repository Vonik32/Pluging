package com.purpur.ecogovernor;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * PerformanceGUI — модерн-стайл инвентарь для администраторов.
 *
 * <p>Особенности:
 * <ul>
 *   <li>Реализует {@link InventoryHolder} — это и метка для распознавания GUI
 *       в обработчике кликов, и контейнер инвентаря.</li>
 *   <li>Анти-дюп защита: ВСЕ клики и драги в верхнем инвентаре отменяются.</li>
 *   <li>Живое обновление: каждые N тиков (см. config gui.refresh-period-ticks)
 *       пересчитывает MSPT/TPS/счётчики и обновляет иконки.</li>
 *   <li>Авто-остановка обновления при закрытии инвентаря последним зрителем.</li>
 * </ul>
 */
public final class PerformanceGUI implements InventoryHolder {

    private static final MiniMessage MINI = MiniMessage.miniMessage();
    private static final int SIZE = 27;
    private static final int SLOT_HEADER = 4;
    private static final int SLOT_MSPT = 11;
    private static final int SLOT_TPS = 13;
    private static final int SLOT_BAR = 15;
    private static final int SLOT_PURGE = 20;
    private static final int SLOT_THROTTLE = 22;
    private static final int SLOT_INFO = 24;

    /** Реестр открытых GUI: позволяет статичному ClickGuard'у роутить клики. */
    private static final Map<Inventory, PerformanceGUI> OPEN = new WeakHashMap<>();

    private final PurpurEcoGovernor plugin;
    private final Player viewer;
    private final Inventory inventory;
    private ScheduledTask refreshTask;

    public PerformanceGUI(PurpurEcoGovernor plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        String rawTitle = plugin.getConfig().getString("gui.title",
                "<gradient:#7B2FF7:#F107A3><b>⚙ PurpurEco — Панель мониторинга</b></gradient>");
        Component title = MINI.deserialize(rawTitle);
        this.inventory = Bukkit.createInventory(this, SIZE, title);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public void open() {
        rebuild();
        viewer.openInventory(inventory);
        synchronized (OPEN) {
            OPEN.put(inventory, this);
        }
        long period = Math.max(1L, plugin.getConfig().getLong("gui.refresh-period-ticks", 10L));
        this.refreshTask = Bukkit.getGlobalRegionScheduler().runAtFixedRate(
                plugin, task -> {
                    if (inventory.getViewers().isEmpty()) {
                        task.cancel();
                        return;
                    }
                    rebuild();
                }, period, period);
    }

    private void close() {
        synchronized (OPEN) {
            OPEN.remove(inventory);
        }
        if (refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void rebuild() {
        LagMonitorTask m = plugin.monitor();
        GovernorManager g = plugin.governor();

        double mspt = m.getAverageMspt();
        double tps = Math.min(20.0, m.getAverageTps());
        double throttle = m.getThrottleThreshold();
        double restore = m.getRestoreThreshold();

        // Заполнение фоном.
        ItemStack filler = simpleItem(Material.GRAY_STAINED_GLASS_PANE,
                MINI.deserialize("<dark_gray>›"));
        for (int i = 0; i < SIZE; i++) {
            inventory.setItem(i, filler);
        }

        // Header.
        inventory.setItem(SLOT_HEADER, simpleItem(Material.BEACON,
                MINI.deserialize("<gradient:#7B2FF7:#F107A3><b>PurpurEcoGovernor</b></gradient>"),
                MINI.deserialize("<gray>Живой мониторинг производительности"),
                MINI.deserialize("<dark_gray>Окно: <yellow>" + m.getWindowFill() + "/" + m.getWindowSize() + "</yellow>"),
                MINI.deserialize("<dark_gray>Наблюдений: <yellow>" + m.getTicksObserved() + "</yellow>")));

        // MSPT.
        inventory.setItem(SLOT_MSPT, simpleItem(Material.CLOCK,
                MINI.deserialize("<gold>⏱ MSPT (среднее)"),
                MINI.deserialize("<white>" + fmt(mspt) + " мс"),
                MINI.deserialize("<gray>Текущий: <yellow>" + fmt(m.getLastMspt()) + "</yellow> мс"),
                MINI.deserialize("<dark_gray>Порог троттлинга: <red>" + fmt(throttle) + "</red> мс"),
                MINI.deserialize("<dark_gray>Порог восстановления: <green>" + fmt(restore) + "</green> мс")));

        // TPS.
        inventory.setItem(SLOT_TPS, simpleItem(Material.COMPASS,
                MINI.deserialize("<gold>♻ TPS (1m)"),
                MINI.deserialize(tpsColor(tps) + fmt(tps) + "</" + tpsColorTag(tps) + ">"),
                MINI.deserialize("<gray>Текущий: <yellow>" + fmt(m.getLastTps()) + "</yellow>")));

        // Bar.
        ItemStack bar = simpleItem(Material.LIME_DYE,
                MINI.deserialize("<gold>▮ Визуализация нагрузки"),
                buildMsptBar(mspt, throttle),
                buildTpsBar(tps),
                Component.empty(),
                MINI.deserialize("<dark_gray>MSPT-бар: 0…<red>" + fmt(throttle * 1.5) + "</red> мс"),
                MINI.deserialize("<dark_gray>TPS-бар: 0…<green>20.0</green>"));
        inventory.setItem(SLOT_BAR, bar);

        // Кнопка purge.
        inventory.setItem(SLOT_PURGE, simpleItem(Material.BARRIER,
                MINI.deserialize("<red><b>✖ Очистить предметы с земли"),
                MINI.deserialize("<gray>Удаляет ВСЕ Item-сущности"),
                MINI.deserialize("<gray>во всех загруженных чанках."),
                Component.empty(),
                MINI.deserialize("<yellow>▶ Клик для запуска")));

        // Кнопка force throttle.
        boolean forced = g.isForceThrottle();
        inventory.setItem(SLOT_THROTTLE, simpleItem(
                forced ? Material.REDSTONE_TORCH : Material.LEVER,
                MINI.deserialize("<gold><b>⚙ Принудительный троттлинг"),
                MINI.deserialize("<gray>Текущий режим: " +
                        (forced ? "<red>ВКЛ</red>" : "<green>ВЫКЛ</green>")),
                Component.empty(),
                MINI.deserialize("<yellow>▶ Клик для переключения")));

        // Инфо.
        inventory.setItem(SLOT_INFO, simpleItem(Material.BLAZE_ROD,
                MINI.deserialize("<aqua>ℹ Статистика троттлинга"),
                MINI.deserialize("<gray>Сейчас заморожено: <yellow>" + g.getThrottledMobsCount() + "</yellow>"),
                MINI.deserialize("<gray>В прошлом проходе: <yellow>" + g.getLastThrottledMobs() + "</yellow>"),
                MINI.deserialize("<gray>Затронуто чанков: <yellow>" + g.getLastThrottledChunks() + "</yellow>"),
                MINI.deserialize("<gray>Восстановлено: <yellow>" + g.getLastRestoredMobs() + "</yellow>"),
                Component.empty(),
                MINI.deserialize("<gray>Алерты тайл-сущностей: <red>" + g.getLastTileAlerts() + "</red>"),
                MINI.deserialize("<gray>Алерты предметов: <red>" + g.getLastItemAlerts() + "</red>")));
    }

    private Component buildMsptBar(double mspt, double throttle) {
        int width = 20;
        double max = throttle * 1.5;
        int filled = (int) Math.round(Math.min(width, Math.max(0.0, (mspt / max) * width)));
        int yellowStart = (int) Math.round(width * 0.5);
        int redStart = (int) Math.round(width * 0.75);

        StringBuilder sb = new StringBuilder("<white>MSPT </white>");
        for (int i = 0; i < width; i++) {
            String tag;
            if (i >= filled) {
                tag = "dark_gray";
            } else if (i < yellowStart) {
                tag = "green";
            } else if (i < redStart) {
                tag = "yellow";
            } else {
                tag = "red";
            }
            sb.append('<').append(tag).append(">|</").append(tag).append('>');
        }
        return MINI.deserialize(sb.toString());
    }

    private Component buildTpsBar(double tps) {
        int width = 20;
        double normalized = Math.max(0.0, Math.min(20.0, tps));
        int filled = (int) Math.round((normalized / 20.0) * width);

        StringBuilder sb = new StringBuilder("<white>TPS  </white>");
        for (int i = 0; i < width; i++) {
            String tag;
            if (i >= filled) {
                tag = "dark_gray";
            } else if (tps < 15.0 && i >= filled - 1) {
                tag = "red";
            } else if (tps < 18.0) {
                tag = "yellow";
            } else {
                tag = "green";
            }
            sb.append('<').append(tag).append(">|</").append(tag).append('>');
        }
        return MINI.deserialize(sb.toString());
    }

    private static String tpsColor(double tps) {
        return "<" + tpsColorTag(tps) + ">";
    }

    private static String tpsColorTag(double tps) {
        if (tps >= 19.0) return "green";
        if (tps >= 17.0) return "yellow";
        return "red";
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }

    private ItemStack simpleItem(Material material, Component name, Component... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(name.decoration(TextDecoration.ITALIC, false));
            if (lore.length > 0) {
                List<Component> list = new ArrayList<>(lore.length);
                for (Component c : lore) {
                    list.add(c.decoration(TextDecoration.ITALIC, false));
                }
                meta.lore(list);
            }
            stack.setItemMeta(meta);
        }
        return stack;
    }

    // ---------------------------------------------------------------------
    // Обработка кликов.
    // ---------------------------------------------------------------------

    private void handleClick(int rawSlot, Player clicker) {
        // Игнорируем клики в инвентаре игрока (rawSlot >= SIZE).
        if (rawSlot < 0 || rawSlot >= SIZE) return;

        switch (rawSlot) {
            case SLOT_PURGE -> {
                if (!clicker.hasPermission("purpureco.admin")) {
                    clicker.sendMessage(plugin.msg("no-permission"));
                    return;
                }
                plugin.governor().purgeGroundItems(count ->
                        clicker.sendMessage(plugin.msg("purge-result",
                                Placeholder.unparsed("count", Integer.toString(count)))));
            }
            case SLOT_THROTTLE -> {
                if (!clicker.hasPermission("purpureco.admin")) {
                    clicker.sendMessage(plugin.msg("no-permission"));
                    return;
                }
                boolean now = plugin.governor().toggleForceThrottle();
                clicker.sendMessage(plugin.msg(now ? "force-throttle-on" : "force-throttle-off"));
                rebuild();
            }
            default -> { /* декоративные слоты — клик уже отменён */ }
        }
    }

    // ---------------------------------------------------------------------
    // Глобальный обработчик кликов: один Listener на весь плагин.
    // ---------------------------------------------------------------------

    public static final class ClickGuard implements Listener {

        @EventHandler(ignoreCancelled = false)
        public void onClick(InventoryClickEvent event) {
            Inventory top = event.getView().getTopInventory();
            PerformanceGUI gui = lookup(top);
            if (gui == null) return;

            // Анти-дюп: отменяем абсолютно любые клики в верхнем инвентаре,
            // включая shift-клики из нижнего, чтобы предметы не утаскивались.
            event.setCancelled(true);

            // Если клик пришёл в нижний инвентарь (shift-click и т.п.) — никаких действий.
            if (event.getClickedInventory() == null || event.getClickedInventory() != top) {
                return;
            }
            if (!(event.getWhoClicked() instanceof Player player)) return;
            gui.handleClick(event.getRawSlot(), player);
        }

        @EventHandler
        public void onDrag(InventoryDragEvent event) {
            Inventory top = event.getView().getTopInventory();
            PerformanceGUI gui = lookup(top);
            if (gui == null) return;
            // Любые drag-операции, затрагивающие верхний инвентарь, запрещены.
            for (int raw : event.getRawSlots()) {
                if (raw < top.getSize()) {
                    event.setCancelled(true);
                    return;
                }
            }
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            Inventory top = event.getView().getTopInventory();
            PerformanceGUI gui = lookup(top);
            if (gui == null) return;
            // Закрываем, только если у инвентаря больше нет зрителей.
            // (getViewers() ещё содержит закрывающего на момент события — проверим.)
            int remaining = 0;
            for (HumanEntity h : top.getViewers()) {
                if (h != event.getPlayer()) remaining++;
            }
            if (remaining == 0) {
                gui.close();
            }
        }

        private static PerformanceGUI lookup(Inventory inv) {
            if (inv == null) return null;
            InventoryHolder holder = inv.getHolder();
            if (holder instanceof PerformanceGUI direct) return direct;
            synchronized (OPEN) {
                return OPEN.get(inv);
            }
        }
    }
}
