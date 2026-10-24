package skypixel.compat;

import org.bukkit.Bukkit;
import skypixel.config.Lang;
import skypixel.dakotaAC;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** Reports incompatibilities at a bounded rate instead of leaving failed checks invisible. */
public final class Diagnostics {
    private static final Map<String, Long> LAST_LOG = new ConcurrentHashMap<>();
    private static final Map<String, AtomicInteger> FAILURES = new ConcurrentHashMap<>();
    private static volatile long logIntervalMs = 30_000L;
    private static volatile boolean stackTraces;

    private Diagnostics() { }

    public static void configure(long interval, boolean traces) {
        logIntervalMs = Math.max(1000L, interval);
        stackTraces = traces;
        LAST_LOG.clear();
        FAILURES.clear();
    }

    public static int failureCount() {
        return FAILURES.values().stream().mapToInt(AtomicInteger::get).sum();
    }

    public static void report(String component, Throwable error) {
        FAILURES.computeIfAbsent(component, ignored -> new AtomicInteger()).incrementAndGet();
        long now = System.currentTimeMillis();
        Long previous = LAST_LOG.putIfAbsent(component, now);
        if (previous != null && (now - previous < logIntervalMs || !LAST_LOG.replace(component, previous, now))) return;
        dakotaAC plugin = dakotaAC.getInstance();
        if (plugin == null) return;
        plugin.getLogger().warning(Lang.text("system.component-error", "component", component,
                "error", error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage())));
        if (stackTraces) plugin.getLogger().log(java.util.logging.Level.WARNING, component, error);
    }

    /** Preserves packet timing while running Bukkit actions immediately when already on the server thread. */
    public static void sync(Runnable task) {
        dakotaAC plugin = dakotaAC.getInstance();
        if (plugin == null || !plugin.isEnabled()) return;
        Runnable guarded = () -> {
            if (!plugin.isEnabled()) return;
            try { task.run(); } catch (RuntimeException | LinkageError ex) { report("task", ex); }
        };
        if (Bukkit.isPrimaryThread()) guarded.run();
        else Bukkit.getScheduler().runTask(plugin, guarded);
    }
}
