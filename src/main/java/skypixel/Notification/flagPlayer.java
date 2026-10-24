package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;
import skypixel.dakotaAC;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class flagPlayer {
    // Configurable alert settings. Global notification toggles retain the original behavior.
    @Option("notify-permission")
    private static volatile String NOTIFY_PERMISSION = "dakotaac.notification";
    @Option(value = "alert-cooldown-ms", min = 0, max = 3600000)
    private static volatile long ALERT_COOLDOWN_MS = 3000L;
    @Option("console")
    private static volatile boolean CONSOLE_ALERTS = true;
    public static boolean alertsEnabled = true;
    private static final Map<UUID, Long> lastAlertTime = new HashMap<>();

    public static void addFlag(Player player, String check, String hackType, String details) {
        Diagnostics.sync(() -> {
            dakotaAC plugin = dakotaAC.getInstance();
            if (!player.isOnline() || !dakotaAC.isCheckActive(check) || CheckPolicy.exempt(player, check)
                    || !plugin.getConfig().getBoolean("Modules." + check + ".flag-enabled", true)) return;
            // Punishment counts are independent of the staff/console alert cooldown.
            Violation.addViolation(player, check, hackType, details);
            long now = System.currentTimeMillis();
            if (now - lastAlertTime.getOrDefault(player.getUniqueId(), 0L) <= ALERT_COOLDOWN_MS) return;
            Object[] placeholders = {"player", player.getName(), "hack", hackType, "details", details,
                    "check", check, "flags", Violation.getFlags(player.getUniqueId())};
            if (CONSOLE_ALERTS) Bukkit.getConsoleSender().sendMessage(Lang.text("alerts.console", placeholders));
            if (alertsEnabled) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.hasPermission(NOTIFY_PERMISSION)) online.sendMessage(Lang.text("alerts.staff", placeholders));
                }
            }
            lastAlertTime.put(player.getUniqueId(), now);
        });
    }

    public static void clearCache(UUID uuid) { lastAlertTime.remove(uuid); }
    public static void clearAll() { lastAlertTime.clear(); }
}
