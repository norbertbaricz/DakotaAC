package ro.skypixel.play.dakotaAC.Combat;

import org.bukkit.Location;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
// Import PotionEffectType if you uncomment the blindness check
// import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

public class Criticals implements Listener {

    // No instance variables like lastLocation or lastAttackTime are needed for this revised check,
    // as we evaluate conditions at the moment of the critical hit.

    /**
     * Handles entity damage events to detect suspicious critical hits.
     * A critical hit is suspicious if the attacker is on the ground, not falling significantly,
     * or under other conditions that normally prevent criticals.
     *
     * @param event The EntityDamageByEntityEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        // Ensure the damager is a Player and the entity is a LivingEntity
        if (!(event.getDamager() instanceof Player)) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity)) {
            // Critical hits typically apply to living entities.
            return;
        }

        Player attacker = (Player) event.getDamager();

        // Check if the server has flagged this damage event as a critical hit
        if (event.isCritical()) {
            // --- Start of checks for illegitimate criticals ---

            // 1. Critical while on ground: Vanilla criticals require the player to NOT be on ground.
            // This is the primary check for "criticals without jumping" or "same Y".
            if (attacker.isOnGround()) {
                Alert.getInstance().alert("Criticals", attacker);
                event.setCancelled(true);
                return; // Flagged, no need for further checks for this event
            }

            // 2. Critical without significant fall distance:
            // Even if not strictly "onGround" (e.g., on a slab edge, stair),
            // a negligible fall distance is suspicious. Vanilla criticals require falling.
            // A small threshold like 0.1F can catch these. Legit criticals usually have fallDistance > 0.
            if (attacker.getFallDistance() < 0.1F) {
                Alert.getInstance().alert("Criticals", attacker);
                event.setCancelled(true);
                return; // Flagged
            }

            // 3. Critical while climbing (ladders, vines):
            if (attacker.isClimbing()) {
                Alert.getInstance().alert("Criticals", attacker);
                event.setCancelled(true);
                return; // Flagged
            }

            // 4. Critical while in liquid:
            if (attacker.isInWater() || attacker.isInLava()) {
                Alert.getInstance().alert("Criticals", attacker);
                event.setCancelled(true);
                return; // Flagged
            }

            // 5. Critical while in a vehicle:
            if (attacker.getVehicle() != null) {
                Alert.getInstance().alert("Criticals", attacker);
                event.setCancelled(true);
                return; // Flagged
            }

            // 6. Critical while having Blindness effect (Optional, as it's a vanilla mechanic,
            // but some cheats might try to bypass all conditions):
            /*
            if (attacker.hasPotionEffect(PotionEffectType.BLINDNESS)) {
                Alert.getInstance().alert("Criticals (Blindness Effect)", attacker);
                event.setCancelled(true);
                return; // Flagged
            }
            */

            // If none of the above invalid conditions were met for a critical hit,
            // it's considered legitimate by these checks.
        }
    }
}
