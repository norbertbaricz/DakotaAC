package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import ro.skypixel.play.dakotaAC.Alert;

public class Sprint implements Listener {

    // Maximum allowed angle (in radians) between player's facing direction and movement direction while sprinting.
    // 80 degrees: Allows for forward and diagonal-forward sprinting.
    // Angles greater than this (e.g., 90 for sideways, 180 for backwards) are flagged.
    private static final double MAX_ALLOWED_SPRINT_DIRECTION_ANGLE_RAD = Math.toRadians(80.0);

    // Minimum horizontal distance squared the player must move in a tick for the check to apply.
    // This avoids flagging on very minor movements or jitters. (0.05 blocks)^2
    private static final double MIN_MOVEMENT_DISTANCE_SQUARED = 0.0025;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();

        // 1. Check for exemptions:
        if (event.isCancelled() ||
                player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() ||           // Covers creative flight, elytra in flight mode
                player.getAllowFlight() ||      // Covers /fly command
                player.isGliding() ||           // Specifically elytra gliding state
                player.isInsideVehicle() ||
                !player.isOnGround()) {         // Sprint check is primarily for on-ground movement
            return;
        }

        // 2. Proceed only if the player is sprinting
        if (player.isSprinting()) {
            Location from = event.getFrom();
            Location to = event.getTo();

            // Should not happen with the above checks, but good practice
            if (to == null || from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
                return;
            }

            double deltaX = to.getX() - from.getX();
            double deltaZ = to.getZ() - from.getZ();

            // Calculate horizontal distance squared
            double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;

            // 3. Ignore very small movements
            if (distanceSquared < MIN_MOVEMENT_DISTANCE_SQUARED) {
                return;
            }

            // 4. Calculate player's facing angle (direction they are looking)
            // Player's current yaw is obtained from the 'to' location (or player.getLocation())
            float playerYawDegrees = to.getYaw();
            // Convert Minecraft yaw to standard mathematical angle (radians, 0 along +X, counter-clockwise)
            // Minecraft yaw: 0 is +Z, 90 is -X, 180 is -Z, 270 is +X.
            // Standard angle: atan2(y,x). Player's facing vector components: dx = -sin(yawRad), dz = cos(yawRad).
            // The original code's conversion works:
            // facingAngleRad is the angle of the direction vector (0,1) rotated by yaw + 90 deg.
            double facingAngleRad = Math.toRadians(playerYawDegrees) + (Math.PI / 2.0);
            // Normalize to [0, 2*PI)
            facingAngleRad = (facingAngleRad % (2.0 * Math.PI) + (2.0 * Math.PI)) % (2.0 * Math.PI);

            // 5. Calculate movement angle
            double moveAngleRad = Math.atan2(deltaZ, deltaX);
            // Normalize to [0, 2*PI)
            moveAngleRad = (moveAngleRad % (2.0 * Math.PI) + (2.0 * Math.PI)) % (2.0 * Math.PI);

            // 6. Calculate the smallest angle difference between facing and movement
            double angleDifferenceRad = Math.abs(facingAngleRad - moveAngleRad);
            angleDifferenceRad = Math.min(angleDifferenceRad, (2.0 * Math.PI) - angleDifferenceRad);

            // 7. Check if the angle difference exceeds the allowed threshold
            if (angleDifferenceRad > MAX_ALLOWED_SPRINT_DIRECTION_ANGLE_RAD) {
                Alert.getInstance().alert("Sprint", player);
                event.setCancelled(true); // Cancel the move event
            }
        }
    }
}
