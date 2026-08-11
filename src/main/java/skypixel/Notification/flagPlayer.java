package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.UUID;

public class flagPlayer {

    // ==========================================
    // EASY TO TUNE SETTINGS
    // ==========================================

    // Permission required to see in-game alerts
    private static final String NOTIFY_PERMISSION = "dakotaac.notification";

    // Cooldown between alerts for the same player (in milliseconds)
    // 3000ms = 3 seconds. Prevents chat/console spam.
    private static final long ALERT_COOLDOWN_MS = 1500L;

    // Message formats (Use %player%, %hack%, and %details% as placeholders)
    private static final String CONSOLE_ALERT_FORMAT = "&8[&cDakotaAC&8] &e%player% &7was flagged for &c%hack% &8(&7%details%&8)";
    private static final String STAFF_ALERT_FORMAT = "&8[&cDakotaAC&8] &e%player% &7is suspected of &c%hack% &8(&7%details%&8)";

    // Global variable to toggle chat alerts on/off via commands
    public static boolean alertsEnabled = true;

    // ==========================================
    // CORE LOGIC
    // ==========================================

    // UNIFIED memory to prevent spam in both console AND chat (stores the time of the last message)
    private static final HashMap<UUID, Long> lastAlertTime = new HashMap<>();

    public static void addFlag(Player player, String hackType, String details) {
        UUID uuid = player.getUniqueId();

        // --------------------------------------------------------
        // 1. PUNISHMENT SYSTEM (Always executes when a hack is detected)
        // --------------------------------------------------------
        Violation.addViolation(player, hackType, details);

        // --------------------------------------------------------
        // 2. ANTI-SPAM ALERT SYSTEM (Console + Chat)
        // --------------------------------------------------------
        long currentTime = System.currentTimeMillis();

        // If the player has no recent alert, or the cooldown has passed, allow new messages
        if (!lastAlertTime.containsKey(uuid) || (currentTime - lastAlertTime.get(uuid)) > ALERT_COOLDOWN_MS) {

            // Prepare the messages by replacing placeholders
            String consoleMsg = CONSOLE_ALERT_FORMAT
                    .replace("%player%", player.getName())
                    .replace("%hack%", hackType)
                    .replace("%details%", details);

            String staffMsg = STAFF_ALERT_FORMAT
                    .replace("%player%", player.getName())
                    .replace("%hack%", hackType)
                    .replace("%details%", details);

            // --- A. Console Logic ---
            Bukkit.getConsoleSender().sendMessage(ChatColor.translateAlternateColorCodes('&', consoleMsg));

            // --- B. In-Game Chat Logic ---
            // Check if the admin left the alerts enabled via command
            if (alertsEnabled) {
                String formattedStaffMsg = ChatColor.translateAlternateColorCodes('&', staffMsg);

                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission(NOTIFY_PERMISSION)) {
                        onlinePlayer.sendMessage(formattedStaffMsg);
                    }
                }
            }

            // Save the new time (applies to both systems)
            lastAlertTime.put(uuid, currentTime);
        }
    }

    /**
     * Utility method to prevent memory leaks when a player leaves the server.
     * Ensure this is called in your PlayerQuitEvent!
     */
    public static void clearCache(UUID uuid) {
        lastAlertTime.remove(uuid);
    }
}