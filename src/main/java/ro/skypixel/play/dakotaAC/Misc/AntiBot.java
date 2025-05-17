package ro.skypixel.play.dakotaAC.Misc;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AntiBot implements Listener {

    private static class PlayerMonitor {
        Location joinLocation;
        float joinYaw;
        float joinPitch;
        BukkitTask checkTask;
        boolean hasPerformedAction = false; // Flag to track interactions other than initial join
    }

    private final Map<UUID, PlayerMonitor> monitoredPlayers = new HashMap<>();
    // Check after 1 second (20 server ticks)
    private static final long WAIT_TIME_TICKS = 120L;

    private final DakotaAC plugin; // Instance of your main plugin class

    // Constructor to inject the main plugin instance
    public AntiBot(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR) // Monitor priority to observe the final state of join
    public void onPlayerJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UUID uuid = player.getUniqueId();

        // If player is already being monitored (e.g., quick rejoin), cancel old task
        PlayerMonitor existingMonitor = monitoredPlayers.get(uuid);
        if (existingMonitor != null && existingMonitor.checkTask != null) {
            existingMonitor.checkTask.cancel();
        }

        final Location joinLocation = player.getLocation();
        PlayerMonitor newMonitor = new PlayerMonitor();
        newMonitor.joinLocation = joinLocation.clone(); // Clone to prevent modification
        newMonitor.joinYaw = joinLocation.getYaw();
        newMonitor.joinPitch = joinLocation.getPitch();

        newMonitor.checkTask = Bukkit.getScheduler().runTaskLater(
                this.plugin, // Use the injected plugin instance
                () -> {
                    // This code runs after WAIT_TIME_TICKS (1 second)
                    PlayerMonitor currentMonitorState = monitoredPlayers.get(uuid);

                    // If monitor was removed (e.g., player quit, or action detected and removed early - though not implemented here)
                    if (currentMonitorState == null) {
                        return;
                    }

                    // Double-check if player is still online
                    if (!player.isOnline()) {
                        monitoredPlayers.remove(uuid); // Ensure cleanup if quit event was somehow missed for this
                        return;
                    }

                    // If player performed any tracked action (chat, interact)
                    if (currentMonitorState.hasPerformedAction) {
                        monitoredPlayers.remove(uuid); // Player is active, remove from monitoring
                        return;
                    }

                    // If no other actions, check if they are still at the exact join spot & rotation
                    Location currentLocation = player.getLocation();
                    float currentYaw = currentLocation.getYaw();
                    float currentPitch = currentLocation.getPitch();

                    // Strict check for position and rotation
                    boolean noPositionChange = isStillSamePosition(currentMonitorState.joinLocation, currentLocation);
                    // Using a small epsilon for floating point comparisons of yaw/pitch
                    boolean noRotationChange = Math.abs(currentMonitorState.joinYaw - currentYaw) < 0.001f &&
                            Math.abs(currentMonitorState.joinPitch - currentPitch) < 0.001f;

                    if (noPositionChange && noRotationChange) {
                        // This means: no chat, no item use/click, AND no change in pos/rot since join.
                        Alert.getInstance().alert("AntiBot", player);
                        // You might want to add further actions here (e.g., kick, tempban)
                    }

                    monitoredPlayers.remove(uuid); // Always remove after the check
                },
                WAIT_TIME_TICKS
        );

        monitoredPlayers.put(uuid, newMonitor);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChatWhileMonitored(AsyncPlayerChatEvent event) {
        PlayerMonitor monitor = monitoredPlayers.get(event.getPlayer().getUniqueId());
        if (monitor != null) {
            monitor.hasPerformedAction = true;
            // Optionally, once an action is detected, we could cancel the scheduled check early
            // and remove from monitoring to save resources, but the check itself will handle it.
            // if (monitor.checkTask != null) monitor.checkTask.cancel();
            // monitoredPlayers.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteractWhileMonitored(PlayerInteractEvent event) {
        PlayerMonitor monitor = monitoredPlayers.get(event.getPlayer().getUniqueId());
        if (monitor != null) {
            monitor.hasPerformedAction = true;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        PlayerMonitor monitor = monitoredPlayers.remove(uuid); // Remove and get the monitor

        if (monitor != null && monitor.checkTask != null) {
            if (!monitor.checkTask.isCancelled()) {
                monitor.checkTask.cancel();
            }
        }
    }

    /**
     * Checks if two locations are effectively the same (within a very small tolerance).
     * @param loc1 The first location.
     * @param loc2 The second location.
     * @return True if positions are considered identical, false otherwise.
     */
    private boolean isStillSamePosition(Location loc1, Location loc2) {
        if (loc1 == null || loc2 == null) return false; // Should not happen if player is online
        if (loc1.getWorld() == null || loc2.getWorld() == null || !loc1.getWorld().equals(loc2.getWorld())) {
            return false; // Different worlds or invalid world
        }
        // Using a very small epsilon for floating point comparison
        double epsilon = 0.001;
        if (Math.abs(loc1.getX() - loc2.getX()) > epsilon) return false;
        if (Math.abs(loc1.getY() - loc2.getY()) > epsilon) return false; // Check Y too, though less likely for static bots
        if (Math.abs(loc1.getZ() - loc2.getZ()) > epsilon) return false;
        return true;
    }
}
