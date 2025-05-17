package ro.skypixel.play.dakotaAC.Combat;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.scheduler.BukkitRunnable;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Assuming this is your main plugin class

public class Velocity implements Listener {

    // Threshold for squared distance. If a player moves less than this distance (squared)
    // after being hit, they are considered to have taken no/minimal knockback.
    // Example: (0.08 blocks)^2 is approximately 0.0064.
    // This means if total movement (horizontal and vertical combined) is less than ~0.08 blocks.
    // Adjust this value based on testing and server specifics.
    private static final double NO_KNOCKBACK_MOVEMENT_SQUARED_THRESHOLD = 0.0064;

    // If you prefer to inject the plugin instance via constructor:
    // private final DakotaAC plugin;
    // public Velocity(DakotaAC plugin) {
    //     this.plugin = plugin;
    // }

    /**
     * Handles entity damage events to detect if a player is not receiving proper knockback.
     *
     * @param event The EntityDamageEvent.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        // We are primarily interested in damage that should cause noticeable knockback,
        // typically from another entity.
        if (!(event instanceof EntityDamageByEntityEvent)) {
            return;
        }

        // The victim must be a player.
        if (!(event.getEntity() instanceof Player)) {
            return;
        }

        Player victim = (Player) event.getEntity();
        // EntityDamageByEntityEvent entityDamageByEntityEvent = (EntityDamageByEntityEvent) event;
        // Entity attacker = entityDamageByEntityEvent.getDamager(); // Attacker info can be used for more context if needed

        // Check if the damage actually occurred and was significant enough to cause knockback.
        // Very low damage amounts (e.g., less than half a heart) might result in negligible knockback.
        if (event.getFinalDamage() < 0.1) { // Ignore very tiny damage amounts
            return;
        }

        // Store the victim's location at the point of damage.
        // Cloning is important as Location objects are mutable.
        final Location locationAtDamage = victim.getLocation().clone();

        new BukkitRunnable() {
            @Override
            public void run() {
                // Ensure the victim is still online and alive.
                if (!victim.isOnline() || victim.isDead()) {
                    return;
                }

                Location currentLocation = victim.getLocation();

                // Calculate the squared distance the player has moved since taking damage.
                // distanceSquared is used for efficiency (avoids Math.sqrt which is computationally more expensive).
                double distanceMovedSquared = currentLocation.distanceSquared(locationAtDamage);

                // If the player moved a very small distance (or not at all).
                if (distanceMovedSquared < NO_KNOCKBACK_MOVEMENT_SQUARED_THRESHOLD) {
                    // The player took damage but barely moved, indicating potential velocity modification (anti-knockback).
                    Alert.getInstance().alert("Velocity", victim);

                    // IMPORTANT: Do NOT call event.setCancelled(true) here.
                    // The EntityDamageEvent has already been processed by the server by this point (1 tick later).
                    // Cancelling it here would have no effect on the damage already dealt and may cause errors.
                    // The purpose of this check is detection and alerting.
                }
            }
            // Schedule this check to run 1 tick after the damage event.
            // This gives the server a moment to apply vanilla knockback.
        }.runTaskLater(DakotaAC.getPlugin(DakotaAC.class), 1L);
    }
}
