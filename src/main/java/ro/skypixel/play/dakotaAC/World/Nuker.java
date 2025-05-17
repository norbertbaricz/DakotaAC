package ro.skypixel.play.dakotaAC.World; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent; // Import for handling player quit
import ro.skypixel.play.dakotaAC.Alert;

import java.util.ArrayDeque; // Efficient Deque implementation
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Nuker implements Listener {

    // Time window in milliseconds to check for rapid block breaks.
    private static final long TIME_WINDOW_MS = 1000L; // 1 second
    // Max number of blocks a player can break *around them* within TIME_WINDOW_MS.
    // If > MAX_NEARBY_BLOCKS_IN_WINDOW, it's flagged. So, 6th block in 1s is a flag.
    private static final int MAX_NEARBY_BLOCKS_IN_WINDOW = 5;
    // Maximum radius squared from the player within which broken blocks are counted for nuker detection.
    // Example: 5 blocks radius -> 5*5 = 25.
    private static final double MAX_NUKER_RADIUS_SQUARED = 5.0 * 5.0;

    // Stores timestamps of recent block breaks (that were nearby) for each player.
    private final Map<UUID, Deque<Long>> breakTimestamps = new HashMap<>();

    /**
     * Handles the BlockBreakEvent to detect rapid block breaking in an area around the player.
     *
     * @param event The BlockBreakEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Exemptions:
        if (player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR) {
            // Clear data if they switch to an exempt mode while being tracked
            breakTimestamps.remove(playerUUID);
            return;
        }
        // InstaBreak might be legitimate (e.g., efficiency V on dirt).
        // Nuker is about breaking *many* blocks quickly in an area,
        // even if each is instabroken. So, not exempting event.isInstaBreak() here.

        Block brokenBlock = event.getBlock();
        Location playerLocation = player.getLocation();
        Location blockLocation = brokenBlock.getLocation();

        // Ensure block and player are in the same world before distance check
        if (playerLocation.getWorld() == null || blockLocation.getWorld() == null ||
                !playerLocation.getWorld().equals(blockLocation.getWorld())) {
            return; // Should not happen in BlockBreakEvent normally
        }

        // 2. Check if the broken block is within the defined nuker radius of the player.
        // Using distanceSquared for efficiency.
        if (playerLocation.distanceSquared(blockLocation) > MAX_NUKER_RADIUS_SQUARED) {
            // Block broken is too far from the player to be considered for nuker check.
            return;
        }

        // --- Block broken is nearby, proceed with rate check ---
        long currentTimeMs = System.currentTimeMillis();

        // Get or create the deque for the player's break timestamps
        Deque<Long> timestamps = breakTimestamps.computeIfAbsent(playerUUID, k -> new ArrayDeque<>());

        // Add current break timestamp
        timestamps.addLast(currentTimeMs);

        // Remove timestamps that are older than TIME_WINDOW_MS from the current time (sliding window)
        while (!timestamps.isEmpty() && timestamps.peekFirst() < currentTimeMs - TIME_WINDOW_MS) {
            timestamps.pollFirst();
        }

        // 3. Check if the number of recent nearby breaks exceeds the threshold
        if (timestamps.size() > MAX_NEARBY_BLOCKS_IN_WINDOW) {
            Alert.getInstance().alert("Nuker", player);
            event.setCancelled(true); // Cancel the block break

            // Clear the timestamps for this player to reset the count after flagging.
            // This prevents immediate re-flagging for subsequent breaks in the same hacked burst
            // and requires a new sequence of rapid breaks to trigger again.
            timestamps.clear();
        }
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        breakTimestamps.remove(event.getPlayer().getUniqueId());
    }
}
