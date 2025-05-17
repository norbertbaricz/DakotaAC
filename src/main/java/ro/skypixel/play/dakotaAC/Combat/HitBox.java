package ro.skypixel.play.dakotaAC.Combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity; // Import LivingEntity
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox; // Import BoundingBox
import org.bukkit.util.RayTraceResult; // Import RayTraceResult
import org.bukkit.util.Vector;
import ro.skypixel.play.dakotaAC.Alert; // Assuming this class exists and works

public class HitBox implements Listener {

    // The comments explain the logic for dynamic distance calculation.
    // No fixed max distance is used here as the check focuses on aim precision
    // relative to the server-validated hit.

    /**
     * Handles entity damage events to detect suspicious hits where the attacker's line of sight
     * might not have intersected the victim's legitimate hitbox at the moment of impact.
     * This is key for detecting hacks that enlarge client-side hitboxes.
     *
     * @param event The EntityDamageByEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Ensure the damager is a Player.
        if (!(event.getDamager() instanceof Player)) {
            return;
        }

        // Ensure the entity being damaged is a LivingEntity, as hitbox checks are most relevant for them.
        if (!(event.getEntity() instanceof LivingEntity)) {
            return;
        }

        Player attacker = (Player) event.getDamager();
        LivingEntity victim = (LivingEntity) event.getEntity();

        // Don't check for self-damage.
        if (attacker.equals(victim)) {
            return;
        }

        // Check if the attacker's actual line of sight (crosshair direction on server)
        // intersects the victim's actual server-side hitbox.
        if (!isAttackerLookingAtVictimHitbox(attacker, victim)) {
            // If it does not intersect, but a hit was registered by the game,
            // it's suspicious and could indicate a hitbox manipulation hack.
            Alert.getInstance().alert("HitBox", attacker);
            event.setCancelled(true);
        }
    }

    /**
     * Checks if the attacker's line of sight (ray) intersects the victim's server-side bounding box (hitbox).
     * The ray's maximum distance is dynamically calculated to be long enough to ensure it can reach
     * and pass through the victim if the aim is correct.
     *
     * @param attacker The attacking player.
     * @param victim   The victim LivingEntity.
     * @return True if the attacker's line of sight intersects the victim's hitbox, false otherwise.
     */
    private boolean isAttackerLookingAtVictimHitbox(Player attacker, LivingEntity victim) {
        Location attackerEyeLocation = attacker.getEyeLocation();
        Vector attackerDirection = attackerEyeLocation.getDirection(); // This is already normalized by Bukkit.

        // Get the victim's precise, server-side bounding box.
        BoundingBox victimBoundingBox = victim.getBoundingBox();

        // Calculate a dynamic maximum distance for the ray trace.
        // This needs to be long enough for the ray to reach the victim from the attacker's position.
        // We use the distance to the center of the victim's bounding box and add a generous buffer.
        // This buffer ensures the ray can traverse the entire bounding box if aimed correctly,
        // regardless of the hit occurring at an edge or corner.
        double distanceToVictimCenter = attackerEyeLocation.distance(victimBoundingBox.getCenter().toLocation(victim.getWorld()));

        // A buffer of 5.0 blocks (as in your provided code) is ample to ensure the ray is cast
        // sufficiently far. This value ensures that the ray is long enough to check for intersection
        // without prematurely terminating.
        double rayTraceMaxDistance = distanceToVictimCenter + 5.0;

        // Perform a raytrace from the attacker's eyes along their look direction
        // against the victim's server-side bounding box, using the dynamically calculated max distance.
        RayTraceResult rayTraceResult = victimBoundingBox.rayTrace(
                attackerEyeLocation.toVector(),       // Start of the ray (from attacker's eyes)
                attackerDirection,                    // Direction of the ray (where attacker is looking)
                rayTraceMaxDistance                   // Dynamic maximum distance for this ray cast
        );

        // If rayTraceResult is null, it means the attacker's line of sight (crosshair)
        // did NOT intersect the victim's server-side bounding box within the cast ray.
        // This is the condition that flags a potential hitbox hack, as the client registered a hit
        // but the server-side aim verification shows a miss on the vanilla hitbox.
        // If it's not null, an intersection occurred, meaning the hit is legitimate from an aiming perspective.
        return rayTraceResult != null;
    }
}
