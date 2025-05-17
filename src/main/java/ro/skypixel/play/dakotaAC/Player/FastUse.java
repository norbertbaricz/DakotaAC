package ro.skypixel.play.dakotaAC.Player; // Assuming this is the correct package

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

public class FastUse implements Listener {

    // Maximum allowed interval (in milliseconds) between the FINISH times of consecutive item consumptions
    // for the sequence to be considered a "fast chain-use".
    // Vanilla food consumption is ~1600ms. If intervals are consistently <= 1500ms, it's faster than normal.
    private static final long MAX_VALID_INTERVAL_BETWEEN_CONSUMPTIONS_MS = 1500L;

    // Number of consecutive consumptions to analyze for fast chaining.
    private static final int CONSUMPTION_COUNT_THRESHOLD = 3;

    // Stores the timestamps of when items were finished being consumed.
    private final Map<UUID, Deque<Long>> consumptionTimestamps = new HashMap<>();

    /**
     * Handles the PlayerItemConsumeEvent to detect abnormally fast item consumption.
     * This event fires *after* the item has been successfully consumed.
     *
     * @param event The PlayerItemConsumeEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerItemConsume(PlayerItemConsumeEvent event) {
        // Event is already checked for cancellation by ignoreCancelled = true.
        // Player instance is guaranteed by the event type.
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        long currentTimeMs = System.currentTimeMillis(); // Timestamp when this item's consumption finished.

        // Get or create the deque for the player's consumption timestamps.
        Deque<Long> timestamps = consumptionTimestamps.computeIfAbsent(playerUUID, k -> new LinkedList<>());
        timestamps.addLast(currentTimeMs); // Add the finish time of the current item.

        // Maintain the sliding window of recent consumption timestamps.
        while (timestamps.size() > CONSUMPTION_COUNT_THRESHOLD) {
            timestamps.removeFirst(); // Remove the oldest timestamp.
        }

        // If we have enough consumption events in our window to analyze for rapid chaining.
        if (timestamps.size() == CONSUMPTION_COUNT_THRESHOLD) {
            if (isSuspiciouslyFastChainConsumption(timestamps)) {
                Alert.getInstance().alert("FastUse", player);
                event.setCancelled(true); // Cancel the consumption of the LATEST item in the fast sequence.
                // Optionally, clear timestamps for this player to prevent immediate re-flagging
                // if they manage another (potentially legitimate) click right after.
                // timestamps.clear();
            }
        }
    }

    /**
     * Checks if the sequence of consumption finish times indicates a suspiciously fast chain-consumption.
     *
     * @param timestamps A deque containing the finish timestamps of the last CONSUMPTION_COUNT_THRESHOLD items.
     * The deque must contain exactly CONSUMPTION_COUNT_THRESHOLD elements.
     * @return True if all intervals between consecutive consumption finishes are less than or equal to
     * MAX_VALID_INTERVAL_BETWEEN_CONSUMPTIONS_MS, false otherwise.
     */
    private boolean isSuspiciouslyFastChainConsumption(Deque<Long> timestamps) {
        // This method assumes timestamps.size() == CONSUMPTION_COUNT_THRESHOLD.
        // Convert to array for easier indexed access to calculate intervals.
        Long[] timesArray = timestamps.toArray(new Long[0]);

        // Iterate through the intervals between consumption finish times.
        // For 3 items (threshold), there are 2 intervals: (time[1]-time[0]) and (time[2]-time[1]).
        for (int i = 1; i < timesArray.length; i++) {
            long intervalMs = timesArray[i] - timesArray[i - 1];
            // If any interval is LONGER than our max allowed for a "fast chain",
            // then the sequence is not consistently fast enough to be suspicious.
            if (intervalMs > MAX_VALID_INTERVAL_BETWEEN_CONSUMPTIONS_MS) {
                return false;
            }
        }
        // If all intervals were less than or equal to the threshold, the chain is suspiciously fast.
        return true;
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        consumptionTimestamps.remove(event.getPlayer().getUniqueId());
    }
}
