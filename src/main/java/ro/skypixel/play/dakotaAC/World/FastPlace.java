package ro.skypixel.play.dakotaAC.World; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent; // Import for handling player quit
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FastPlace implements Listener {

    // Minimum interval in milliseconds allowed between two consecutive block placements.
    // Vanilla placement cooldown is 4 game ticks (200ms).
    // 180ms is slightly faster than vanilla, aiming to catch cooldown bypasses.
    private static final long MIN_PLACE_INTERVAL_MS = 180L;

    // Stores the timestamp of the last block placement attempt for each player.
    private final Map<UUID, Long> lastPlaceTimeMap = new HashMap<>();

    /**
     * Handles the BlockPlaceEvent to detect abnormally fast block placements.
     *
     * @param event The BlockPlaceEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        // Event is already checked for cancellation by ignoreCancelled = true.
        // Player instance is guaranteed by the event type.
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Exemptions: Ignore players in Creative or Spectator mode.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            // Optionally clear their last place time if they switch modes,
            // though it will naturally be old if they switch back and place slowly.
            // lastPlaceTimeMap.remove(playerUUID);
            return;
        }

        long currentTimeMs = System.currentTimeMillis();
        // Get the time of the last placement attempt, defaulting to 0 if no previous placement.
        long lastPlaceTimeMs = lastPlaceTimeMap.getOrDefault(playerUUID, 0L);
        long intervalMs = currentTimeMs - lastPlaceTimeMs;

        // 2. Check if the interval since the last placement is too short.
        if (intervalMs < MIN_PLACE_INTERVAL_MS) {
            Alert.getInstance().alert("FastPlace", player);
            event.setCancelled(true);
            // Even if cancelled, we update the lastPlaceTime to the current attempt time.
            // This ensures that if the player continues to attempt rapid placements,
            // each subsequent attempt will also be checked against this new (recent) timestamp.
        }

        // 3. Update the last placement time for this player to the current attempt time.
        lastPlaceTimeMap.put(playerUUID, currentTimeMs);
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        lastPlaceTimeMap.remove(event.getPlayer().getUniqueId());
    }
}
