package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

public class NoWeb implements Listener {

    // Vanilla speed in cobweb is extremely slow, around 0.02 blocks/tick or less.
    // (0.04 blocks/tick)^2 = 0.0016 (blocks/tick)^2.
    // This threshold is very low, allowing only minimal movement.
    private static final double COBWEB_SPEED_THRESHOLD_SQUARED = 0.0016; // Corresponds to 0.04 blocks/tick or 0.8 m/s

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        // 1. Exemptions: GameMode, specific movement states
        if (player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() ||       // Server-side flying state
                player.getAllowFlight() ||  // /fly command enabled
                player.isGliding() ||       // Elytra gliding
                player.isInsideVehicle()) {
            return;
        }

        // 2. Potion Effect Exemption:
        // If player has Speed effect, their base speed is higher, making this check less reliable
        // without adjusting thresholds based on potion level. For simplicity, we exempt them.
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            return;
        }

        // 3. Check if the player is in a cobweb
        if (isInCobweb(player, event.getTo())) { // Pass 'to' location for accurate check
            Location from = event.getFrom();
            Location to = event.getTo();

            // Calculate horizontal speed squared
            double deltaX = to.getX() - from.getX();
            double deltaZ = to.getZ() - from.getZ();
            double horizontalSpeedSquared = (deltaX * deltaX) + (deltaZ * deltaZ);

            // Flag 1: Sprinting while in a cobweb (and not exempt) is a clear violation.
            if (player.isSprinting()) {
                Alert.getInstance().alert("NoWeb", player);
                event.setCancelled(true);
                teleportBack(player, from, to);
                return; // Stop further processing for this event
            }

            // Flag 2: Moving faster than the allowed threshold while in a cobweb.
            if (horizontalSpeedSquared > COBWEB_SPEED_THRESHOLD_SQUARED) {
                Alert.getInstance().alert("NoWeb", player);
                event.setCancelled(true);
                teleportBack(player, from, to);
            }
        }
    }

    /**
     * Checks if any relevant part of the player's hitbox is inside a cobweb at the given location.
     * @param player The player to check.
     * @param location The location to check (usually event.getTo()).
     * @return True if the player is considered to be in a cobweb, false otherwise.
     */
    private boolean isInCobweb(Player player, Location location) {
        // Check at feet, mid-body, and head level to ensure robust detection.
        // Player height is approx 1.8 blocks.
        Location feetLoc = location.clone();
        Location midLoc = location.clone().add(0, player.getHeight() * 0.5, 0); // Approx player center
        Location headLoc = location.clone().add(0, player.getHeight() * 0.9, 0); // Near top of head

        return isCobwebMaterial(feetLoc.getBlock().getType()) ||
                isCobwebMaterial(midLoc.getBlock().getType()) ||
                isCobwebMaterial(headLoc.getBlock().getType());
    }

    /**
     * Helper method to check if a material is COBWEB.
     * @param material The material to check.
     * @return True if the material is COBWEB.
     */
    private boolean isCobwebMaterial(Material material) {
        return material == Material.COBWEB;
    }

    /**
     * Teleports the player back to their previous XZ coordinates, maintaining current Y and rotation.
     * @param player The player to teleport.
     * @param from The location the player moved from.
     * @param to The location the player moved to (used for Y and rotation).
     */
    private void teleportBack(Player player, Location from, Location to) {
        if (from.getWorld() != null && to.getWorld() != null && from.getWorld().equals(to.getWorld())) {
            player.teleport(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
        }
    }
}
