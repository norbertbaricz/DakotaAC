package ro.skypixel.play.dakotaAC.Render; // Assuming this is the correct package

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap; // Using ConcurrentHashMap for thread safety if needed

public class ESP implements Listener {

    // Visibility range in blocks. Players outside this range from the viewer will be hidden.
    private static final double VISIBILITY_RANGE = 30.0;
    // Squared visibility range for efficient distance checking (avoids Math.sqrt)
    private static final double VISIBILITY_RANGE_SQUARED = VISIBILITY_RANGE * VISIBILITY_RANGE;

    // Stores which players (value Set<UUID>) are currently hidden from a specific viewer (key UUID).
    private final Map<UUID, Set<UUID>> hiddenPlayersMap = new ConcurrentHashMap<>(); // Thread-safe map

    private final DakotaAC plugin; // Instance of your main plugin

    /**
     * Constructor to inject the main plugin instance.
     * @param plugin Your main plugin class instance.
     */
    public ESP(DakotaAC plugin) {
        this.plugin = plugin;
    }

    /**
     * Handles player movement to update visibility of other players for the moving player.
     *
     * @param event The PlayerMoveEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Check if the player actually moved to a new block, not just rotated their head.
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getBlockX() == to.getBlockX() &&
                from.getBlockY() == to.getBlockY() &&
                from.getBlockZ() == to.getBlockZ()) {
            // Player only looked around, no need to update visibility based on block position.
            return;
        }

        Player viewer = event.getPlayer();
        UUID viewerId = viewer.getUniqueId();
        Location viewerLocation = viewer.getLocation(); // Use current location for checks

        // Ensure the viewer has an entry in the map
        hiddenPlayersMap.putIfAbsent(viewerId, new HashSet<>());
        Set<UUID> currentlyHiddenByViewer = hiddenPlayersMap.get(viewerId);

        for (Player target : Bukkit.getOnlinePlayers()) {
            if (target.equals(viewer)) {
                continue; // Skip self
            }

            UUID targetId = target.getUniqueId();

            // Ensure target is valid and in the same world
            if (!target.isValid() || target.getWorld() != viewerLocation.getWorld()) {
                // If target is invalid or in a different world, ensure it's shown (or handled by Bukkit)
                // and remove from our tracking if it was there.
                if (currentlyHiddenByViewer.contains(targetId)) {
                    viewer.showPlayer(plugin, target);
                    currentlyHiddenByViewer.remove(targetId);
                }
                continue;
            }

            double distanceSquared = viewerLocation.distanceSquared(target.getLocation());

            if (distanceSquared <= VISIBILITY_RANGE_SQUARED) {
                // Target is WITHIN visibility range
                if (currentlyHiddenByViewer.contains(targetId)) {
                    // Target was hidden, now needs to be shown
                    viewer.showPlayer(plugin, target);
                    currentlyHiddenByViewer.remove(targetId);
                }
            } else {
                // Target is OUTSIDE visibility range
                if (!currentlyHiddenByViewer.contains(targetId)) {
                    // Target was visible, now needs to be hidden
                    viewer.hidePlayer(plugin, target);
                    currentlyHiddenByViewer.add(targetId);
                }
            }
        }
    }

    /**
     * Handles player joining to set initial visibility states.
     *
     * @param event The PlayerJoinEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player joiningPlayer = event.getPlayer();
        UUID joiningPlayerId = joiningPlayer.getUniqueId();
        Location joiningPlayerLocation = joiningPlayer.getLocation();

        // Initialize the hidden set for the joining player
        Set<UUID> hiddenForJoiningPlayer = new HashSet<>();
        hiddenPlayersMap.put(joiningPlayerId, hiddenForJoiningPlayer);

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.equals(joiningPlayer)) {
                continue;
            }

            UUID onlinePlayerId = onlinePlayer.getUniqueId();
            Location onlinePlayerLocation = onlinePlayer.getLocation();

            // Update visibility of onlinePlayer for the joiningPlayer
            if (joiningPlayerLocation.getWorld().equals(onlinePlayerLocation.getWorld()) &&
                    joiningPlayerLocation.distanceSquared(onlinePlayerLocation) <= VISIBILITY_RANGE_SQUARED) {
                joiningPlayer.showPlayer(plugin, onlinePlayer); // Should be default, but explicit
                hiddenForJoiningPlayer.remove(onlinePlayerId);
            } else {
                joiningPlayer.hidePlayer(plugin, onlinePlayer);
                hiddenForJoiningPlayer.add(onlinePlayerId);
            }

            // Update visibility of joiningPlayer for the onlinePlayer
            Set<UUID> hiddenForOnlinePlayer = hiddenPlayersMap.computeIfAbsent(onlinePlayerId, k -> new HashSet<>());
            if (onlinePlayerLocation.getWorld().equals(joiningPlayerLocation.getWorld()) &&
                    onlinePlayerLocation.distanceSquared(joiningPlayerLocation) <= VISIBILITY_RANGE_SQUARED) {
                onlinePlayer.showPlayer(plugin, joiningPlayer);
                hiddenForOnlinePlayer.remove(joiningPlayerId);
            } else {
                onlinePlayer.hidePlayer(plugin, joiningPlayer);
                hiddenForOnlinePlayer.add(joiningPlayerId);
            }
        }
    }

    /**
     * Handles player quitting to clean up visibility states.
     *
     * @param event The PlayerQuitEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player quittingPlayer = event.getPlayer();
        UUID quittingPlayerId = quittingPlayer.getUniqueId();

        // Remove the quitting player's own entry from the map
        hiddenPlayersMap.remove(quittingPlayerId);

        // Make the quitting player visible to all other players who might have been hiding them
        // (Bukkit usually handles entity removal, but this cleans our specific state)
        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.equals(quittingPlayer)) {
                continue;
            }
            Set<UUID> hiddenByOnlinePlayer = hiddenPlayersMap.get(onlinePlayer.getUniqueId());
            if (hiddenByOnlinePlayer != null && hiddenByOnlinePlayer.contains(quittingPlayerId)) {
                onlinePlayer.showPlayer(plugin, quittingPlayer); // Make them "visible" just before they despawn
                hiddenByOnlinePlayer.remove(quittingPlayerId);
            }
        }
    }
}
