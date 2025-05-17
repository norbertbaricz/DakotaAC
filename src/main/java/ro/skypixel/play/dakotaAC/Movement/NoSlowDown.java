package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

public class NoSlowDown implements Listener {

    // Vanilla speed when slowed (sneaking, blocking with shield, eating food/potions, drawing bow) is ~0.065 blocks/tick.
    // (0.065 blocks/tick)^2 = 0.004225 (blocks/tick)^2.
    // We set a threshold slightly above this to allow for minor server-client discrepancies or tiny drifts.
    // 0.08 blocks/tick squared is 0.0064. This corresponds to 1.6 m/s.
    private static final double SLOWDOWN_ACTION_SPEED_THRESHOLD_SQUARED = 0.0064;

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        // 1. Exemptions: GameMode, specific movement states
        if (player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() ||       // Server-side flying state (elytra, creative flight)
                player.getAllowFlight() ||  // /fly command enabled by server/plugin
                player.isGliding() ||       // Specifically elytra gliding state
                player.isInsideVehicle()) {
            return;
        }

        // 2. Potion Effect Exemption:
        // As per user request: "fara sa fie afectat de efecte atunci sa fie detectat"
        // This implies we should NOT run this check if they have Speed, as it modifies their base speed
        // and makes the "slowdown" relative to a higher speed, complicating the threshold.
        if (player.hasPotionEffect(PotionEffectType.SPEED)) {
            return;
        }
        // Note: Effects like Slowness will naturally make them slower and won't trigger this check.

        boolean performingSlowdownAction = false;
        String actionDescription = ""; // For more descriptive alerts

        // 3. Check for Shield Blocking
        // player.isHandRaised() is true when the shield is actively raised.
        if (player.isHandRaised()) {
            ItemStack mainHandItem = player.getInventory().getItemInMainHand();
            ItemStack offHandItem = player.getInventory().getItemInOffHand();

            if (mainHandItem.getType() == Material.SHIELD || offHandItem.getType() == Material.SHIELD) {
                performingSlowdownAction = true;
                actionDescription = "Shielding";
            }
        }
        // 4. Check for Item Consuming/Using (Eating, Drinking, Bow Drawing, etc.)
        // player.isBlocking() is true when the player is "using" an item like food, potions, OR drawing a bow/crossbow, using spyglass.
        // We use 'else if' to ensure this check doesn't run if already identified as shield blocking.
        else if (player.isBlocking()) {
            ItemStack itemInUse = player.getItemInUse(); // Gets the item stack the player is currently "using".
            if (itemInUse != null && itemCausesSlowdownOnUse(itemInUse.getType())) {
                performingSlowdownAction = true;
                actionDescription = "Using " + itemInUse.getType().name().toLowerCase().replace("_", " ");
            }
        }

        // 5. If a slowdown action is being performed, check player's speed
        if (performingSlowdownAction) {
            Location from = event.getFrom();
            Location to = event.getTo();

            // Calculate horizontal speed squared (ignoring Y movement as slowdown primarily affects horizontal speed)
            double deltaX = to.getX() - from.getX();
            double deltaZ = to.getZ() - from.getZ();
            double horizontalSpeedSquared = (deltaX * deltaX) + (deltaZ * deltaZ);

            // Flag 1: Sprinting while performing a slowdown action is a clear violation.
            if (player.isSprinting()) {
                Alert.getInstance().alert("NoSlowDown", player);
                event.setCancelled(true);
                // Teleport player back to 'from' XZ, but keep 'to' Y and rotation to avoid glitches.
                player.teleport(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
                return; // Stop further processing for this event
            }

            // Flag 2: Moving faster than the allowed threshold for slowed actions.
            if (horizontalSpeedSquared > SLOWDOWN_ACTION_SPEED_THRESHOLD_SQUARED) {
                Alert.getInstance().alert("NoSlowDown", player);
                event.setCancelled(true);
                player.teleport(new Location(from.getWorld(), from.getX(), to.getY(), from.getZ(), to.getYaw(), to.getPitch()));
            }
        }
    }

    /**
     * Helper method to determine if using a material should cause a slowdown.
     * @param material The material of the item being used.
     * @return True if using this item type should slow the player, false otherwise.
     */
    private boolean itemCausesSlowdownOnUse(Material material) {
        if (material.isEdible()) { // Covers all food items (apple, steak, bread, etc.)
            return true;
        }
        switch (material) {
            case POTION:         // Drinking any potion
            case MILK_BUCKET:    // Drinking milk
            case HONEY_BOTTLE:   // Drinking honey
            case BOW:            // Drawing a bow
            case CROSSBOW:       // Loading a crossbow
            case SPYGLASS:       // Looking through a spyglass
                // TRIDENT (throwing animation) could be added, but Riptide makes it complex.
                // For now, focusing on common, clear-cut slowdowns.
                return true;
            default:
                return false;
        }
    }
}
