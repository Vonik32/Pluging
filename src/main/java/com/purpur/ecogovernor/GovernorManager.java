package com.purpur.ecogovernor;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Merchant;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

/**
 * GovernorManager — ядро логики плагина.
 *
 * <p>Отвечает за:
 * <ul>
 *   <li>Сканирование загруженных чанков и поиск чанков с высокой плотностью мобов.</li>
 *   <li>Безопасный троттлинг AI (Mob#setAware(false)) у не-важных мобов.</li>
 *   <li>Постепенное восстановление AI при стабилизации MSPT.</li>
 *   <li>Алерты о перегрузке тайл-сущностями и предметами на земле.</li>
 *   <li>Принудительную чистку Item-сущностей по команде.</li>
 *   <li>Корректное восстановление состояния при выгрузке плагина.</li>
 * </ul>
 *
 * <p><b>Потокобезопасность:</b> Все операции с сущностями шифтятся на главный
 * поток через {@link Bukkit#getGlobalRegionScheduler()}. Множество затроттленных
 * мобов хранится в {@link ConcurrentHashMap}-key set'е, что исключает
 * {@code ConcurrentModificationException} при асинхронных обращениях из
 * {@link LagMonitorTask} и {@link PerformanceGUI}.
 */
public final class GovernorManager {

    private final PurpurEcoGovernor plugin;

    private final Set<UUID> throttledMobs = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean throttlePassInFlight = new AtomicBoolean(false);
    private final AtomicBoolean restorePassInFlight = new AtomicBoolean(false);
    private final AtomicBoolean forceThrottle = new AtomicBoolean(false);

    private final AtomicInteger lastThrottledMobs = new AtomicInteger(0);
    private final AtomicInteger lastThrottledChunks = new AtomicInteger(0);
    private final AtomicInteger lastRestoredMobs = new AtomicInteger(0);
    private final AtomicInteger lastTileAlerts = new AtomicInteger(0);
    private final AtomicInteger lastItemAlerts = new AtomicInteger(0);

    // Snapshot конфигурации (volatile, перечитывается через reload()).
    private volatile Set<EntityType> typeBlacklist = EnumSet.noneOf(EntityType.class);
    private volatile boolean skipNamed = true;
    private volatile boolean skipTamed = true;
    private volatile boolean skipTraders = true;
    private volatile int minMobsPerChunk = 25;
    private volatile int maxChunksPerPass = 50;
    private volatile int maxMobsPerPass = 500;
    private volatile int tileAlertThreshold = 80;
    private volatile int itemAlertThreshold = 60;
    private volatile int restoreBatchSize = 25;
    private volatile LogLevel cullingLogLevel = LogLevel.WARN;

    public GovernorManager(PurpurEcoGovernor plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        var cfg = plugin.getConfig();

        this.skipNamed = cfg.getBoolean("exclusions.skip-named", true);
        this.skipTamed = cfg.getBoolean("exclusions.skip-tamed", true);
        this.skipTraders = cfg.getBoolean("exclusions.skip-traders", true);
        this.minMobsPerChunk = Math.max(1, cfg.getInt("density.min-mobs-per-chunk", 25));
        this.maxChunksPerPass = Math.max(1, cfg.getInt("density.max-chunks-per-pass", 50));
        this.maxMobsPerPass = Math.max(1, cfg.getInt("density.max-mobs-per-pass", 500));
        this.tileAlertThreshold = Math.max(1, cfg.getInt("culling.tile-entity-alert-threshold", 80));
        this.itemAlertThreshold = Math.max(1, cfg.getInt("culling.ground-item-alert-threshold", 60));
        this.restoreBatchSize = Math.max(1, cfg.getInt("performance.restore-batch-size", 25));

        String rawLevel = cfg.getString("culling.log-level", "WARN");
        this.cullingLogLevel = switch (rawLevel.toUpperCase(Locale.ROOT)) {
            case "NONE" -> LogLevel.NONE;
            case "INFO" -> LogLevel.INFO;
            default -> LogLevel.WARN;
        };

        Set<EntityType> bl = EnumSet.noneOf(EntityType.class);
        for (String raw : cfg.getStringList("exclusions.type-blacklist")) {
            try {
                bl.add(EntityType.valueOf(raw.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                plugin.getComponentLogger().warn("Неизвестный EntityType в blacklist: {}", raw);
            }
        }
        this.typeBlacklist = bl;
    }

    // ---------------------------------------------------------------------
    // Точки входа из LagMonitorTask (асинхронный поток).
    // ---------------------------------------------------------------------

    public void requestThrottlePass(double avgMspt) {
        if (!throttlePassInFlight.compareAndSet(false, true)) {
            return; // предыдущий проход ещё идёт — не стэкаемся.
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            try {
                doThrottlePass(avgMspt);
            } catch (Throwable t) {
                plugin.getComponentLogger().error("Ошибка прохода троттлинга", t);
            } finally {
                throttlePassInFlight.set(false);
            }
        });
    }

    public void requestRestorePass(double avgMspt) {
        if (!restorePassInFlight.compareAndSet(false, true)) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            try {
                doRestorePass(avgMspt);
            } catch (Throwable t) {
                plugin.getComponentLogger().error("Ошибка прохода восстановления", t);
            } finally {
                restorePassInFlight.set(false);
            }
        });
    }

    // ---------------------------------------------------------------------
    // Главный поток: проход троттлинга.
    // ---------------------------------------------------------------------

    private void doThrottlePass(double avgMspt) {
        List<ChunkDensity> candidates = collectDenseChunks();
        if (candidates.isEmpty()) {
            return;
        }
        candidates.sort((a, b) -> Integer.compare(b.mobs.size(), a.mobs.size()));

        int chunkLimit = Math.min(maxChunksPerPass, candidates.size());
        int mobBudget = maxMobsPerPass;
        int throttledNow = 0;
        int touchedChunks = 0;

        for (int i = 0; i < chunkLimit && mobBudget > 0; i++) {
            ChunkDensity cd = candidates.get(i);
            boolean touched = false;
            for (Mob mob : cd.mobs) {
                if (mobBudget <= 0) break;
                if (!mob.isValid()) continue;
                if (!mob.isAware()) {
                    // Уже затроттлен (возможно нами или ванилой). Просто помним UUID.
                    throttledMobs.add(mob.getUniqueId());
                    continue;
                }
                mob.setAware(false);
                throttledMobs.add(mob.getUniqueId());
                throttledNow++;
                mobBudget--;
                touched = true;
            }
            if (touched) touchedChunks++;
        }

        lastThrottledMobs.set(throttledNow);
        lastThrottledChunks.set(touchedChunks);

        if (throttledNow > 0) {
            broadcastAdmins(plugin.msg("throttle-activated",
                    Placeholder.unparsed("mobs", Integer.toString(throttledNow)),
                    Placeholder.unparsed("chunks", Integer.toString(touchedChunks)),
                    Placeholder.unparsed("mspt", String.format(Locale.US, "%.2f", avgMspt))));
        }
    }

    private List<ChunkDensity> collectDenseChunks() {
        List<ChunkDensity> out = new ArrayList<>();
        for (World world : Bukkit.getWorlds()) {
            Chunk[] loaded;
            try {
                loaded = world.getLoadedChunks();
            } catch (Throwable t) {
                continue;
            }
            for (Chunk chunk : loaded) {
                if (chunk == null) continue;
                // Защита от подгрузки: getLoadedChunks() уже даёт только загруженные.
                Entity[] entities;
                try {
                    entities = chunk.getEntities();
                } catch (Throwable t) {
                    continue;
                }
                if (entities == null || entities.length < minMobsPerChunk) continue;

                List<Mob> eligible = new ArrayList<>();
                for (Entity e : entities) {
                    if (e instanceof Mob mob && isEligibleForThrottle(mob)) {
                        eligible.add(mob);
                    }
                }
                if (eligible.size() >= minMobsPerChunk) {
                    out.add(new ChunkDensity(world, chunk.getX(), chunk.getZ(), eligible));
                }
            }
        }
        return out;
    }

    private boolean isEligibleForThrottle(Mob mob) {
        if (!mob.isValid()) return false;
        if (mob instanceof Player) return false;
        if (typeBlacklist.contains(mob.getType())) return false;
        if (skipNamed && mob.customName() != null) return false;
        if (skipTamed && mob instanceof Tameable t && t.isTamed()) return false;
        if (skipTraders && mob instanceof Merchant) return false;
        return true;
    }

    // ---------------------------------------------------------------------
    // Главный поток: проход восстановления (грубое восстановление при падении MSPT).
    // ---------------------------------------------------------------------

    private void doRestorePass(double avgMspt) {
        if (throttledMobs.isEmpty()) return;

        int budget = restoreBatchSize;
        int restored = 0;
        Iterator<UUID> it = throttledMobs.iterator();
        while (it.hasNext() && budget > 0) {
            UUID id = it.next();
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                it.remove();
                continue;
            }
            if (entity instanceof Mob mob) {
                if (!mob.isAware()) {
                    mob.setAware(true);
                }
                it.remove();
                restored++;
                budget--;
            } else {
                it.remove();
            }
        }

        lastRestoredMobs.set(restored);

        if (restored > 0) {
            broadcastAdmins(plugin.msg("throttle-restored",
                    Placeholder.unparsed("mobs", Integer.toString(restored)),
                    Placeholder.unparsed("mspt", String.format(Locale.US, "%.2f", avgMspt))));
        }
    }

    /**
     * Периодически вызывается из {@link PurpurEcoGovernor} (главный поток) для
     * плавного «отморозки» сущностей даже без явного триггера MSPT — это
     * предотвращает зависание мобов после кратковременных всплесков.
     */
    public void tickRestoration() {
        if (forceThrottle.get()) return;
        if (throttledMobs.isEmpty()) return;
        // Доверяем монитору, что MSPT уже под restoreThreshold; но даже если нет —
        // плавное восстановление по 1/4 батча предотвращает «вечно замороженных».
        int budget = Math.max(1, restoreBatchSize / 4);
        Iterator<UUID> it = throttledMobs.iterator();
        while (it.hasNext() && budget > 0) {
            UUID id = it.next();
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                it.remove();
                continue;
            }
            if (entity instanceof Mob mob) {
                if (!mob.isAware()) {
                    mob.setAware(true);
                }
                it.remove();
                budget--;
            } else {
                it.remove();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Сканер тайл-сущностей и предметов.
    // ---------------------------------------------------------------------

    public void runCullingScan() {
        int tileAlerts = 0;
        int itemAlerts = 0;

        for (World world : Bukkit.getWorlds()) {
            Chunk[] loaded;
            try {
                loaded = world.getLoadedChunks();
            } catch (Throwable t) {
                continue;
            }
            for (Chunk chunk : loaded) {
                if (chunk == null) continue;

                // Тайл-сущности.
                BlockState[] tiles;
                try {
                    tiles = chunk.getTileEntities();
                } catch (Throwable t) {
                    tiles = null;
                }
                if (tiles != null && tiles.length >= tileAlertThreshold) {
                    tileAlerts++;
                    Component msg = plugin.msg("tile-alert",
                            Placeholder.unparsed("world", world.getName()),
                            Placeholder.unparsed("x", Integer.toString(chunk.getX())),
                            Placeholder.unparsed("z", Integer.toString(chunk.getZ())),
                            Placeholder.unparsed("count", Integer.toString(tiles.length)));
                    broadcastAdmins(msg);
                    logCulling("Tile overload: {}/{},{} = {}",
                            world.getName(), chunk.getX(), chunk.getZ(), tiles.length);
                }

                // Item-сущности на земле.
                int itemCount = 0;
                Entity[] entities;
                try {
                    entities = chunk.getEntities();
                } catch (Throwable t) {
                    entities = null;
                }
                if (entities != null) {
                    for (Entity e : entities) {
                        if (e instanceof Item) itemCount++;
                    }
                    if (itemCount >= itemAlertThreshold) {
                        itemAlerts++;
                        Component msg = plugin.msg("item-alert",
                                Placeholder.unparsed("world", world.getName()),
                                Placeholder.unparsed("x", Integer.toString(chunk.getX())),
                                Placeholder.unparsed("z", Integer.toString(chunk.getZ())),
                                Placeholder.unparsed("count", Integer.toString(itemCount)));
                        broadcastAdmins(msg);
                        logCulling("Ground item overload: {}/{},{} = {}",
                                world.getName(), chunk.getX(), chunk.getZ(), itemCount);
                    }
                }
            }
        }

        lastTileAlerts.set(tileAlerts);
        lastItemAlerts.set(itemAlerts);
    }

    // ---------------------------------------------------------------------
    // Принудительные действия (команды / GUI).
    // ---------------------------------------------------------------------

    public void purgeGroundItems(IntConsumer onComplete) {
        Bukkit.getGlobalRegionScheduler().run(plugin, task -> {
            int removed = 0;
            for (World world : Bukkit.getWorlds()) {
                Chunk[] loaded;
                try {
                    loaded = world.getLoadedChunks();
                } catch (Throwable t) {
                    continue;
                }
                for (Chunk chunk : loaded) {
                    if (chunk == null) continue;
                    Entity[] entities;
                    try {
                        entities = chunk.getEntities();
                    } catch (Throwable t) {
                        continue;
                    }
                    for (Entity e : entities) {
                        if (e instanceof Item item && item.isValid()) {
                            item.remove();
                            removed++;
                        }
                    }
                }
            }
            if (onComplete != null) onComplete.accept(removed);
        });
    }

    public boolean toggleForceThrottle() {
        boolean now = !forceThrottle.get();
        forceThrottle.set(now);
        if (now) {
            requestThrottlePass(plugin.monitor().getAverageMspt());
        }
        return now;
    }

    public boolean isForceThrottle() {
        return forceThrottle.get();
    }

    /**
     * Полное синхронное восстановление при выгрузке плагина. Вызывается на
     * главном потоке из {@link PurpurEcoGovernor#onDisable()}.
     */
    public void shutdownRestoreAll() {
        Set<UUID> snapshot = new HashSet<>(throttledMobs);
        throttledMobs.clear();
        for (UUID id : snapshot) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Mob mob && mob.isValid() && !mob.isAware()) {
                mob.setAware(true);
            }
        }
    }

    // ---------------------------------------------------------------------
    // Геттеры для GUI.
    // ---------------------------------------------------------------------

    public int getThrottledMobsCount() {
        return throttledMobs.size();
    }

    public int getLastThrottledMobs() {
        return lastThrottledMobs.get();
    }

    public int getLastThrottledChunks() {
        return lastThrottledChunks.get();
    }

    public int getLastRestoredMobs() {
        return lastRestoredMobs.get();
    }

    public int getLastTileAlerts() {
        return lastTileAlerts.get();
    }

    public int getLastItemAlerts() {
        return lastItemAlerts.get();
    }

    // ---------------------------------------------------------------------
    // Вспомогательные.
    // ---------------------------------------------------------------------

    private void broadcastAdmins(Component msg) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("purpureco.alerts")) {
                p.sendMessage(msg);
            }
        }
    }

    private void logCulling(String fmt, Object... args) {
        switch (cullingLogLevel) {
            case NONE -> { /* no-op */ }
            case INFO -> plugin.getComponentLogger().info(fmt, args);
            case WARN -> plugin.getComponentLogger().warn(fmt, args);
        }
    }

    private record ChunkDensity(World world, int x, int z, List<Mob> mobs) { }

    private enum LogLevel { NONE, INFO, WARN }
}
