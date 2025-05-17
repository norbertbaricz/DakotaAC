package ro.skypixel.play.dakotaAC.Player; // Assuming this is the correct package

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AntiVoid implements Listener {

    // Inner class to store data for each monitored player
    private static class PlayerVoidData {
        double initialVoidContactY;    // Y-level when void damage first occurred
        BukkitTask checkTask;          // Reference to the scheduled check task
        boolean isBeingMonitored;      // Is the player currently being watched for void save?

        PlayerVoidData() {
            this.isBeingMonitored = false;
        }
    }

    private final Map<UUID, PlayerVoidData> monitoredPlayers = new HashMap<>();
    private final DakotaAC plugin; // Instance of your main plugin

    // Configuration Constants
    private static final long CHECK_DELAY_TICKS = 40L; // 2 seconds (2000ms / 50ms/tick)
    // How much higher player needs to be than their initial void contact Y to be flagged
    private static final double Y_REAPPEAR_THRESHOLD = 0.5; // e.g., 0.5 blocks higher

    /**
     * Constructor to inject the main plugin instance.
     * @param plugin Your main plugin class instance.
     */
    public AntiVoid(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        if (event.getCause() != EntityDamageEvent.DamageCause.VOID) {
            return;
        }

        Player player = (Player) event.getEntity();
        UUID playerUUID = player.getUniqueId();

        // Ignore players in creative or spectator mode
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            clearPlayerData(playerUUID); // Ensure no lingering monitoring
            return;
        }

        PlayerVoidData data = monitoredPlayers.computeIfAbsent(playerUUID, k -> new PlayerVoidData());

        // If player is not already being monitored for this fall sequence
        if (!data.isBeingMonitored) {
            data.initialVoidContactY = player.getLocation().getY();
            data.isBeingMonitored = true;

            // Cancel any previous task for this player (shouldn't happen if logic is correct, but as a safeguard)
            if (data.checkTask != null && !data.checkTask.isCancelled()) {
                data.checkTask.cancel();
            }

            data.checkTask = Bukkit.getScheduler().runTaskLater(
                    this.plugin, // Use the injected plugin instance
                    () -> {
                        Player onlinePlayer = Bukkit.getPlayer(playerUUID);
                        PlayerVoidData currentData = monitoredPlayers.get(playerUUID); // Re-fetch, might have been cleared

                        // If player is offline, dead, or no longer being monitored (e.g., teleported, died)
                        if (onlinePlayer == null || !onlinePlayer.isOnline() || onlinePlayer.isDead() || currentData == null || !currentData.isBeingMonitored) {
                            clearPlayerData(playerUUID); // Ensure cleanup
                            return;
                        }

                        Location currentLocation = onlinePlayer.getLocation();
                        // Check if player is now significantly above where they first took void damage
                        if (currentLocation.getY() > currentData.initialVoidContactY + Y_REAPPEAR_THRESHOLD) {
                            Alert.getInstance().alert("AntiVoid", onlinePlayer);
                            // Action: Instead of event.setCancelled(), which is too late,
                            // you might teleport them back down, apply damage, or other sanctions.
                            // For now, just alerting.
                            // Example: onlinePlayer.teleport(new Location(onlinePlayer.getWorld(), currentLocation.getX(), currentData.initialVoidContactY - 2, currentLocation.getZ()));
                        }
                        // Reset monitoring state after the check, regardless of outcome, for this fall sequence.
                        currentData.isBeingMonitored = false;
                        currentData.checkTask = null; // Task has run
                    },
                    CHECK_DELAY_TICKS
            );
        }
        // If data.isBeingMonitored is true, it means they are continuing to take void damage,
        // which is normal. The scheduled task will handle the check.
    }

    /**
     * Clears monitoring data and cancels the scheduled task for a player.
     * @param playerUUID The UUID of the player.
     */
    private void clearPlayerData(UUID playerUUID) {
        PlayerVoidData data = monitoredPlayers.remove(playerUUID);
        if (data != null && data.checkTask != null && !data.checkTask.isCancelled()) {
            data.checkTask.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearPlayerData(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        // If player dies (for any reason, including void), stop monitoring.
        clearPlayerData(event.getEntity().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // If a player is teleported, the context of their fall into the void changes.
        // Stop monitoring to prevent false positives.
        // A more nuanced check could see if they are teleported *out* of the void.
        clearPlayerData(event.getPlayer().getUniqueId());
    }
}
