package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.util.Vector;
import ro.skypixel.play.dakotaAC.Alert; // Assuming this class exists and works

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Sneak implements Listener {

    // Threshold for horizontal speed while sneaking (units per tick).
    // Normal player sneak speed is ~0.065 blocks/tick.
    // 0.20 is quite generous, allowing for minor variations due to server lag or client-side prediction.
    private static final double SNEAK_SPEED_THRESHOLD = 0.20;
    // Using squared length for comparison is a micro-optimization, avoiding Math.sqrt().
    private static final double SNEAK_SPEED_THRESHOLD_SQUARED = SNEAK_SPEED_THRESHOLD * SNEAK_SPEED_THRESHOLD;

    // Delay in milliseconds after starting to sneak before the speed check becomes active.
    private static final long SNEAK_ACTIVATION_DELAY_MS = 500; // 0.5 seconds

    // Stores the timestamp (System.currentTimeMillis()) when a player started sneaking.
    // This is used to implement the delay before checks become active.
    private final Map<UUID, Long> sneakStartTimes = new HashMap<>();

    // Note: This Listener class must be registered in the plugin manager
    // of your main plugin (e.g., in the onEnable method).

    /**
     * Handles the event when a player starts or stops sneaking.
     * This is used to record when sneaking begins, to implement the activation delay.
     *
     * @param event The PlayerToggleSneakEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerToggleSneak(PlayerToggleSneakEvent event) {
        Player player = event.getPlayer();
        if (event.isSneaking()) {
            // Player started sneaking, record the current time.
            sneakStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
        } else {
            // Player stopped sneaking, remove them from the map.
            sneakStartTimes.remove(player.getUniqueId());
        }
    }

    /**
     * Handles player movement to check for suspicious speeds while sneaking.
     * A check is performed only if the player is sneaking and a configured delay
     * has passed since they started sneaking.
     *
     * @param event The PlayerMoveEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        // Check if the event was cancelled by another plugin.
        // ignoreCancelled = true already does this, but an explicit check doesn't hurt.
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        // Only proceed if the player is currently sneaking.
        if (!player.isSneaking()) {
            // If not sneaking, but was in sneakStartTimes (e.g., plugin reload or missed PlayerToggleSneakEvent),
            // ensure they are removed. This is mainly a fallback; PlayerToggleSneakEvent should handle this.
            if (sneakStartTimes.containsKey(player.getUniqueId())) {
                sneakStartTimes.remove(player.getUniqueId());
            }
            return;
        }

        // Check if the player is in our map of sneak start times.
        // If not, it might be due to a plugin reload or sneaking before this listener was active.
        // In this case, we can add them now, effectively starting the "delay" from this move event.
        if (!sneakStartTimes.containsKey(player.getUniqueId())) {
            sneakStartTimes.put(player.getUniqueId(), System.currentTimeMillis());
            return; // Don't check immediately, wait for the next move event after the delay.
        }

        long sneakStartTime = sneakStartTimes.get(player.getUniqueId());
        long currentTime = System.currentTimeMillis();

        // Check if the activation delay has passed since the player started sneaking.
        if (currentTime - sneakStartTime < SNEAK_ACTIVATION_DELAY_MS) {
            // The delay hasn't passed yet, so we don't perform the speed check.
            return;
        }

        // --- Speed Check Logic ---
        Location fromLoc = event.getFrom();
        Location toLoc = event.getTo();

        // Ignore Y-axis movement to calculate horizontal speed.
        // Location.toVector() creates a new Vector instance.
        Vector movement = toLoc.toVector().subtract(fromLoc.toVector());
        movement.setY(0); // Nullify the vertical component of movement.

        // lengthSquared() is used for a slight performance gain over length(),
        // as it avoids a square root calculation.
        double horizontalSpeedSquared = movement.lengthSquared();

        // Alert condition: speed is too high OR the player is sprinting while sneaking.
        // player.isSprinting() being true while player.isSneaking() is also true
        // is an anomaly and should be flagged.
        if (horizontalSpeedSquared > SNEAK_SPEED_THRESHOLD_SQUARED || player.isSprinting()) {
            // Alert and cancel the event.
            Alert.getInstance().alert("Sneak", player);
            event.setCancelled(true);

            // Optional: Reset the sneak start time to re-apply the delay if suspicious movement continues,
            // or remove them to stop further checks until they stop and restart sneaking.
            // For now, we leave them in the map. If flagged, they're flagged.
            // If they stop sneaking, PlayerToggleSneakEvent will remove them.
        }
    }

    /**
     * Cleans up player data when they leave the server.
     *
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        sneakStartTimes.remove(event.getPlayer().getUniqueId());
    }

    // A similar handler for PlayerKickEvent could be added if necessary,
    // though PlayerQuitEvent often covers kicks as well.
}
