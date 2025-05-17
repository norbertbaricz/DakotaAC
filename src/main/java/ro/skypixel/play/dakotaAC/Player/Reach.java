package ro.skypixel.play.dakotaAC.Player; // Assuming this is the correct package, was Combat before

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import ro.skypixel.play.dakotaAC.Alert;

public class Reach implements Listener {

    // Maximum allowed reach squared for Survival/Adventure mode.
    // (3.4 blocks)^2. This includes a small tolerance over vanilla ~3.0 blocks.
    private static final double MAX_REACH_SURVIVAL_SQUARED = 3.4 * 3.4; // 11.56

    // Maximum allowed reach squared for Creative mode.
    // (5.5 blocks)^2. Creative mode has a longer reach.
    private static final double MAX_REACH_CREATIVE_SQUARED = 5.5 * 5.5; // 30.25

    /**
     * Handles entity damage events to detect if the attacker is hitting from too far away.
     *
     * @param event The EntityDamageByEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Ensure the damager is a Player and the victim is also a Player (as per original logic).
        // If you want to check reach against any LivingEntity, change the second check.
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof Player)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        Player victim = (Player) event.getEntity(); // Victim is also a Player

        // 1. Exemptions:
        if (attacker.getGameMode() == GameMode.SPECTATOR || // Spectators don't interact
                attacker.isFlying() ||           // Covers creative flight, elytra in flight mode
                attacker.getAllowFlight() ||      // Covers /fly command
                attacker.isGliding() ||           // Specifically elytra gliding state
                attacker.isInsideVehicle()) {
            return;
        }

        // 2. Determine the appropriate reach threshold based on game mode
        double currentMaxReachSquared;
        if (attacker.getGameMode() == GameMode.CREATIVE) {
            currentMaxReachSquared = MAX_REACH_CREATIVE_SQUARED;
        } else {
            // Default to survival reach for Survival and Adventure modes
            currentMaxReachSquared = MAX_REACH_SURVIVAL_SQUARED;
        }

        // 3. Calculate squared distance between attacker's eyes and victim's base location (feet)
        // Using attacker's eye location is generally more accurate for "hitting" point.
        // Using victim's base location is a common approach for anti-cheats.
        Location attackerEyeLocation = attacker.getEyeLocation();
        Location victimLocation = victim.getLocation();

        // Ensure both locations are valid and in the same world before calculating distance
        if (attackerEyeLocation.getWorld() == null || victimLocation.getWorld() == null ||
                !attackerEyeLocation.getWorld().equals(victimLocation.getWorld())) {
            return; // Cannot compare distances if worlds are different or null
        }

        double distanceSquared = attackerEyeLocation.distanceSquared(victimLocation);

        // 4. Check if the distance exceeds the allowed threshold
        if (distanceSquared > currentMaxReachSquared) {
            // Calculate actual distance for the alert message (optional, sqrt is a bit expensive)
            double actualDistance = Math.sqrt(distanceSquared);
            Alert.getInstance().alert("Reach", attacker);
            event.setCancelled(true);
        }
    }

    // The original calculateDistance method is no longer needed as we use distanceSquared directly.
    // If it were needed for other purposes, it would look like:
    /*
    private double calculateDistance(Entity entity1, Entity entity2) {
        // This calculates distance between entity origins (feet)
        return entity1.getLocation().distance(entity2.getLocation());
    }
    */
}
