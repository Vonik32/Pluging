package com.purpur.ecogovernor;

import org.bukkit.Bukkit;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LagMonitorTask — асинхронный движок мониторинга производительности.
 *
 * Раз в N тиков (конфигурируемо) семплирует MSPT и TPS, поддерживает скользящее
 * окно из последних K семплов и предоставляет усреднённые значения, которые
 * сглаживают временные всплески нагрузки. Когда сглажённый MSPT пересекает
 * порог троттлинга, монитор делегирует выполнение на главный поток
 * через {@link GovernorManager#requestThrottlePass()} (внутри менеджер уже
 * шифтит выполнение на корректный регион/планировщик).
 *
 * <b>Thread-safety:</b> вся внутренняя коллекция семплов защищена
 * {@link ReentrantLock}. Никакая Bukkit-операция, требующая main-thread, не
 * выполняется внутри tick().
 */
public final class LagMonitorTask {

    private final PurpurEcoGovernor plugin;
    private final GovernorManager governor;

    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<Sample> samples = new ArrayDeque<>();

    private volatile int windowSize;
    private volatile double throttleThreshold;
    private volatile double restoreThreshold;

    private volatile double lastMspt = 0.0;
    private volatile double lastTps = 20.0;
    private volatile double avgMspt = 0.0;
    private volatile double avgTps = 20.0;
    private volatile long ticksObserved = 0L;

    public LagMonitorTask(PurpurEcoGovernor plugin, GovernorManager governor) {
        this.plugin = plugin;
        this.governor = governor;
        reloadThresholds();
    }

    public void reloadThresholds() {
        this.windowSize = Math.max(2, plugin.getConfig().getInt("performance.rolling-window-size", 10));
        this.throttleThreshold = plugin.getConfig().getDouble("performance.mspt-throttle-threshold", 45.0);
        this.restoreThreshold = plugin.getConfig().getDouble("performance.mspt-restore-threshold", 35.0);

        lock.lock();
        try {
            while (samples.size() > windowSize) {
                samples.pollFirst();
            }
        } finally {
            lock.unlock();
        }
    }

    /**
     * Вызывается асинхронным планировщиком. Безопасно для любого потока.
     */
    public void tick() {
        double mspt;
        double tps1m;
        try {
            mspt = Bukkit.getServer().getAverageTickTime();
            double[] tps = Bukkit.getServer().getTPS();
            tps1m = tps.length > 0 ? tps[0] : 20.0;
        } catch (Throwable t) {
            return;
        }

        Sample sample = new Sample(mspt, tps1m, System.nanoTime());

        double computedMspt;
        double computedTps;

        lock.lock();
        try {
            samples.addLast(sample);
            while (samples.size() > windowSize) {
                samples.pollFirst();
            }
            double mSum = 0.0;
            double tSum = 0.0;
            for (Sample s : samples) {
                mSum += s.mspt();
                tSum += s.tps();
            }
            int n = samples.size();
            computedMspt = mSum / n;
            computedTps = tSum / n;
        } finally {
            lock.unlock();
        }

        this.lastMspt = mspt;
        this.lastTps = tps1m;
        this.avgMspt = computedMspt;
        this.avgTps = computedTps;
        this.ticksObserved++;

        // Гистерезис: вход — выше throttleThreshold, выход — ниже restoreThreshold.
        if (computedMspt >= throttleThreshold || governor.isForceThrottle()) {
            governor.requestThrottlePass(computedMspt);
        } else if (computedMspt <= restoreThreshold && !governor.isForceThrottle()) {
            governor.requestRestorePass(computedMspt);
        }
    }

    public double getLastMspt() {
        return lastMspt;
    }

    public double getLastTps() {
        return lastTps;
    }

    public double getAverageMspt() {
        return avgMspt;
    }

    public double getAverageTps() {
        return avgTps;
    }

    public long getTicksObserved() {
        return ticksObserved;
    }

    public double getThrottleThreshold() {
        return throttleThreshold;
    }

    public double getRestoreThreshold() {
        return restoreThreshold;
    }

    public int getWindowFill() {
        lock.lock();
        try {
            return samples.size();
        } finally {
            lock.unlock();
        }
    }

    public int getWindowSize() {
        return windowSize;
    }

    private record Sample(double mspt, double tps, long ts) { }
}
