package ro.skypixel.play.dakotaAC.Misc;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.LinkedList; // Changed from ArrayList for better queue performance
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Spammer implements Listener {

    // Enum to differentiate action types
    private enum ActionType {
        CHAT,
        COMMAND
    }

    // Stores timestamps of actions for each player, categorized by action type
    // Player UUID -> ActionType -> List of timestamps
    private final Map<UUID, Map<ActionType, List<Long>>> playerActionTimestamps = new HashMap<>();

    // Maximum number of actions (chat or command) allowed within the time frame
    private static final int MAX_ACTIONS_IN_TIMEFRAME = 5;
    // The time frame in milliseconds to check for spam
    private static final long TIME_FRAME_MS = 5000L; // 5 seconds

    /**
     * Handles player chat events to check for spam.
     * @param event The AsyncPlayerChatEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        // Do not process if the event is already cancelled by another plugin.
        // ignoreCancelled = true in @EventHandler handles this, but an explicit check is fine.
        if (event.isCancelled()) {
            return;
        }
        handlePlayerAction(event.getPlayer(), ActionType.CHAT);
    }

    /**
     * Handles player command preprocess events to check for command spam.
     * @param event The PlayerCommandPreprocessEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        if (event.isCancelled()) {
            return;
        }
        handlePlayerAction(event.getPlayer(), ActionType.COMMAND);
    }

    /**
     * Checks if a player is spamming a specific type of action.
     * @param player The player performing the action.
     * @param type The type of action (CHAT or COMMAND).
     */
    private void handlePlayerAction(Player player, ActionType type) {
        UUID playerUUID = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Get the map of action types for the player, or create it if it doesn't exist
        Map<ActionType, List<Long>> playerActions = playerActionTimestamps.computeIfAbsent(playerUUID, k -> new HashMap<>());

        // Get the list of timestamps for the specific action type, or create it
        List<Long> timestamps = playerActions.computeIfAbsent(type, k -> new LinkedList<>()); // Using LinkedList

        // Add the current timestamp
        timestamps.add(currentTime);

        // Remove timestamps older than the defined TIME_FRAME_MS
        // Iterating and removing from the front is efficient with LinkedList
        while (!timestamps.isEmpty() && timestamps.get(0) < currentTime - TIME_FRAME_MS) {
            timestamps.remove(0);
        }

        // Check if the number of actions exceeds the maximum allowed
        if (timestamps.size() > MAX_ACTIONS_IN_TIMEFRAME) {
            String spamTypeDetail = type == ActionType.CHAT ? "Chat" : "Command";
            Alert.getInstance().alert("Spammer", player);

            // Clear the timestamps for this action type for this player to reset the count after flagging.
            // This means they can be flagged again if they continue spamming.
            timestamps.clear();
        }
    }

    // Consider adding a cleanup task for playerActionTimestamps if players leave,
    // or if the map grows too large with inactive players over a very long server uptime.
    // For instance, onPlayerQuit, remove playerUUID from playerActionTimestamps.
    // @EventHandler
    // public void onPlayerQuit(PlayerQuitEvent event) {
    //     playerActionTimestamps.remove(event.getPlayer().getUniqueId());
    // }
}
