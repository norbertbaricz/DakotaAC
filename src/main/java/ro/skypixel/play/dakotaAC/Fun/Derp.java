package ro.skypixel.play.dakotaAC.Fun; // Assuming this is the correct package for "Fun" exploits/detections

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class Derp implements Listener {

    // Threshold for significant yaw change in a single tick (degrees)
    private static final float YAW_CHANGE_THRESHOLD = 45.0f;
    // Threshold for significant pitch change in a single tick (degrees)
    private static final float PITCH_CHANGE_THRESHOLD = 30.0f;
    // How many past PlayerMoveEvents to keep in history (20 ticks = 1 second)
    private static final int MAX_EVENT_HISTORY = 20;
    // How many "derp instances" (ticks with large rotation changes) in the history are needed to trigger an alert
    private static final int MIN_DERP_INSTANCES_TO_FLAG = 10;

    // Stores the history of derp-like movements for each player
    // True in the queue means a derp-like rotation occurred in that tick
    private final Map<UUID, Queue<Boolean>> playerDerpHistory = new HashMap<>();
    // Stores the last recorded yaw for each player
    private final Map<UUID, Float> lastYaw = new HashMap<>();
    // Stores the last recorded pitch for each player
    private final Map<UUID, Float> lastPitch = new HashMap<>();

    /**
     * Handles player movement to detect "Derp" behavior (rapid, erratic head movements).
     *
     * @param event The PlayerMoveEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        Location from = event.getFrom();
        Location to = event.getTo();

        // Basic checks: if 'to' is null or world changes (teleport event should handle world changes)
        if (to == null || from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            // If world is null or changes, reset history for safety, as context is lost.
            lastYaw.remove(uuid);
            lastPitch.remove(uuid);
            playerDerpHistory.remove(uuid);
            return;
        }

        // If only yaw/pitch changed but not position (common)
        // or if very minor positional change alongside rotation.
        // This check ensures we are processing rotational changes.
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ() &&
                from.getYaw() == to.getYaw() && from.getPitch() == to.getPitch()) {
            // Player hasn't moved or rotated at all.
            return;
        }

        float currentYaw = to.getYaw();
        float currentPitch = to.getPitch();

        // Get previous yaw and pitch, defaulting to the 'from' location's yaw/pitch if not found (first event)
        float previousYaw = lastYaw.getOrDefault(uuid, from.getYaw());
        float previousPitch = lastPitch.getOrDefault(uuid, from.getPitch());

        // Calculate the absolute difference in yaw and pitch since the last processed move event's 'to' state.
        float yawDelta = Math.abs(currentYaw - previousYaw);
        float pitchDelta = Math.abs(currentPitch - previousPitch);

        // Normalize yaw difference (e.g., change from 350 to 10 degrees is 20 degrees, not 340)
        if (yawDelta > 180.0f) {
            yawDelta = 360.0f - yawDelta;
        }

        // Update the last known yaw and pitch to the current 'to' state for the next event
        lastYaw.put(uuid, currentYaw);
        lastPitch.put(uuid, currentPitch);

        // Determine if this tick's rotation constitutes a "derp instance"
        boolean isDerpInstanceThisTick = (yawDelta >= YAW_CHANGE_THRESHOLD) || (pitchDelta >= PITCH_CHANGE_THRESHOLD);

        // Get or create the history queue for the player
        Queue<Boolean> historyQueue = playerDerpHistory.computeIfAbsent(uuid, k -> new LinkedList<>());
        historyQueue.add(isDerpInstanceThisTick);

        // Maintain the size of the history queue
        while (historyQueue.size() > MAX_EVENT_HISTORY) {
            historyQueue.poll(); // Remove the oldest entry
        }

        // Count derp instances in the current history window
        int derpInstanceCount = 0;
        for (boolean didDerp : historyQueue) {
            if (didDerp) {
                derpInstanceCount++;
            }
        }

        // If the number of derp instances meets or exceeds the threshold
        if (derpInstanceCount >= MIN_DERP_INSTANCES_TO_FLAG) {
            Alert.getInstance().alert("Derp", player);
            event.setCancelled(true); // Cancel the move to potentially stop/rubberband the player

            // Clear the history for this player to prevent immediate re-flagging in the next tick.
            // They will need to exhibit sustained derp behavior again to be flagged.
            historyQueue.clear();
            // Optionally, also clear lastYaw/Pitch to reset state fully, though not strictly necessary
            // as they will be re-evaluated against current 'from' if history is empty.
            // lastYaw.remove(uuid);
            // lastPitch.remove(uuid);
        }
    }
}
