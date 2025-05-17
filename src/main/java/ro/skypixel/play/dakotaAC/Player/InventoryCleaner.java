package ro.skypixel.play.dakotaAC.Player; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType; // Required for checking open inventory view
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent; // Required for cleaning up player data
import ro.skypixel.play.dakotaAC.Alert;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

public class InventoryCleaner implements Listener {

    // Stores timestamps of recent item drops for each player
    private final Map<UUID, Deque<Long>> playerDropTimestamps = new HashMap<>();

    // Max number of items a player can drop within TIME_WINDOW_MS when no inventory GUI is open
    private static final int MAX_DROPS_IN_WINDOW = 3; // e.g., 3 items
    // Time window in milliseconds to check for rapid drops
    private static final long TIME_WINDOW_MS = 500L;  // e.g., 0.5 seconds

    /**
     * Handles the PlayerDropItemEvent to detect rapid item dropping when no GUI is open.
     *
     * @param event The PlayerDropItemEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Exemptions: Ignore players in creative or spectator mode.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            // Optionally clear data if they switch to an exempt mode while being tracked
            // playerDropTimestamps.remove(playerUUID);
            return;
        }

        // 2. Critical Check: Ensure NO external or player inventory GUI is open.
        // InventoryType.CRAFTING for the view means the default game screen (only hotbar visible,
        // no main inventory GUI, chest, etc., is open).
        if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
            // A GUI (like player's own inventory, a chest, etc.) is open.
            // This specific "InventoryCleaner" check is for dropping items *without* such a GUI.
            // Other modules (e.g., for fast looting from chests) would handle actions within GUIs.
            return;
        }

        // --- Player is dropping items, and no GUI (other than default game view) is open ---
        long currentTimeMs = System.currentTimeMillis();

        // Get or create the deque for the player's drop timestamps
        Deque<Long> timestamps = playerDropTimestamps.computeIfAbsent(playerUUID, k -> new LinkedList<>());

        // Add current drop timestamp
        timestamps.addLast(currentTimeMs);

        // Remove timestamps that are older than TIME_WINDOW_MS from the current time (sliding window)
        while (!timestamps.isEmpty() && timestamps.getFirst() < currentTimeMs - TIME_WINDOW_MS) {
            timestamps.removeFirst();
        }

        // 3. Check if the number of drops within the time window exceeds the threshold
        if (timestamps.size() >= MAX_DROPS_IN_WINDOW) {
            Alert.getInstance().alert("InventoryCleaner", player);
            event.setCancelled(true); // Cancel the item drop

            // Clear the timestamps for this player to reset the count after flagging.
            // This prevents immediate re-flagging for subsequent drops in the same hacked burst
            // and requires a new sequence of rapid drops to trigger again.
            timestamps.clear();
        }
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDropTimestamps.remove(event.getPlayer().getUniqueId());
    }
}
