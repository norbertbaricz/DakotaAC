package ro.skypixel.play.dakotaAC.Combat;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.LivingEntity; // Import LivingEntity
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.bukkit.FluidCollisionMode;
import ro.skypixel.play.dakotaAC.Alert; // Assuming this class exists and works

public class Killaura implements Listener {

    // Maximum angle (in degrees) the attacker can be looking away from the victim.
    private static final double MAX_ATTACK_ANGLE_DEGREES = 60.0;
    // Pre-calculated cosine of the maximum attack angle for performance.
    private static final double COS_MAX_ATTACK_ANGLE = Math.cos(Math.toRadians(MAX_ATTACK_ANGLE_DEGREES));

    // Maximum distance for the line-of-sight check for blocks.
    private static final double MAX_LOS_CHECK_DISTANCE = 7.0;

    /**
     * Handles entity damage events to detect Killaura-like behavior when a player attacks any living entity.
     *
     * @param event The EntityDamageByEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Ensure the damager is a player and the entity is a living entity.
        if (!(event.getDamager() instanceof Player) || !(event.getEntity() instanceof LivingEntity)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        LivingEntity victim = (LivingEntity) event.getEntity(); // Victim is now LivingEntity

        // Prevent self-damage checks if the victim is also the attacker (though less common for LivingEntity).
        if (attacker.equals(victim)) {
            return;
        }

        // Check 1: Is the attacker looking reasonably towards the victim?
        if (!isLookingAtVictim(attacker, victim)) {
            Alert.getInstance().alert("Killaura", attacker);
            event.setCancelled(true);
            return; // Stop further checks if aim is off.
        }

        // Check 2: Is there a solid block obstructing the line of sight between attacker and victim?
        if (hasSolidBlockInLineOfSight(attacker, victim)) {
            Alert.getInstance().alert("Killaura", attacker);
            event.setCancelled(true);
            // Event is cancelled, no further action needed.
        }
    }

    /**
     * Checks if the attacker is looking sufficiently towards the victim entity.
     *
     * @param attacker The attacking player.
     * @param victim   The victim LivingEntity.
     * @return True if the attacker is looking towards the victim within the defined angle, false otherwise.
     */
    private boolean isLookingAtVictim(Player attacker, LivingEntity victim) {
        Location attackerEyeLocation = attacker.getEyeLocation();
        // Target the center of the victim's body for more accurate angle calculation.
        // LivingEntity has getHeight() and getLocation().
        Location victimCenterLocation = victim.getLocation().add(0, victim.getHeight() / 2.0, 0);

        if (attackerEyeLocation.getWorld().equals(victimCenterLocation.getWorld()) && attackerEyeLocation.distanceSquared(victimCenterLocation) < 0.0001) {
            return true;
        }

        Vector directionToVictim = victimCenterLocation.toVector().subtract(attackerEyeLocation.toVector()).normalize();
        Vector attackerLookDirection = attackerEyeLocation.getDirection().normalize();

        double dotProduct = attackerLookDirection.dot(directionToVictim);
        return dotProduct >= COS_MAX_ATTACK_ANGLE;
    }

    /**
     * Checks if there is a solid, non-passable block in the line of sight
     * between the attacker's eyes and the center of the victim's body.
     *
     * @param attacker The attacking player.
     * @param victim   The victim LivingEntity.
     * @return True if a solid block obstructs the line of sight, false otherwise.
     */
    private boolean hasSolidBlockInLineOfSight(Player attacker, LivingEntity victim) {
        Location attackerEyeLocation = attacker.getEyeLocation();
        Location victimCenterLocation = victim.getLocation().add(0, victim.getHeight() / 2.0, 0);
        World world = attacker.getWorld();

        double distanceToVictim = attackerEyeLocation.distance(victimCenterLocation);

        if (distanceToVictim > MAX_LOS_CHECK_DISTANCE) {
            return false;
        }

        if (attackerEyeLocation.getWorld().equals(victimCenterLocation.getWorld()) && distanceToVictim < 0.0001) {
            return false;
        }

        Vector direction = victimCenterLocation.toVector().subtract(attackerEyeLocation.toVector()).normalize();

        RayTraceResult rayTraceResult = world.rayTraceBlocks(
                attackerEyeLocation,
                direction,
                distanceToVictim,
                FluidCollisionMode.NEVER,
                true // ignorePassableBlocks - true means it stops on solid blocks
        );

        return rayTraceResult != null && rayTraceResult.getHitBlock() != null;
    }
}
