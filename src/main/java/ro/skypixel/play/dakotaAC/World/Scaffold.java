package ro.skypixel.play.dakotaAC.World; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Scaffold implements Listener {

    // Inner class to store player-specific data for Scaffold detection
    private static class PlayerScaffoldData {
        long lastScaffoldPlaceTimeMs = 0L;
        float lastPlayerYawAtScaffold = 0.0f;
        Location lastPlayerLocationAtScaffold; // Store last location for potential future speed checks

        PlayerScaffoldData(Player player) {
            this.lastPlayerYawAtScaffold = player.getLocation().getYaw();
            this.lastPlayerLocationAtScaffold = player.getLocation().clone();
        }
    }

    private final Map<UUID, PlayerScaffoldData> playerDataMap = new HashMap<>();
    private final DakotaAC plugin; // Instance of your main plugin

    // Configuration Constants
    // Minimum time interval (ms) between consecutive scaffold block placements.
    // Vanilla placement cooldown is 4 game ticks (200ms). 100ms is very fast.
    private static final long MIN_SCAFFOLD_INTERVAL_MS = 100L;

    // Max angle (degrees) player can be looking "away" (horizontally) from where they place a scaffold block.
    // If angle > MAX_ANGLE_LOOK_AWAY_DEGREES, they are looking significantly away from the placement.
    // An angle of 180 means looking directly opposite to the block placement.
    private static final double MAX_ANGLE_LOOK_AWAY_DEGREES = 135.0; // For non-sprinting
    private static final double MAX_ANGLE_LOOK_AWAY_SPRINT_DEGREES = 95.0; // Stricter if sprinting

    // Max yaw change (degrees) between consecutive scaffold placements to detect "snap" aiming.
    private static final float MAX_YAW_SNAP_DEGREES_SCAFFOLD = 120.0f;

    /**
     * Constructor to inject the main plugin instance.
     * @param plugin Your main plugin class instance.
     */
    public Scaffold(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Exemptions: GameMode, flying, gliding, vehicle.
        if (player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() ||
                player.isGliding() || player.isInsideVehicle()) {
            playerDataMap.remove(playerUUID); // Clear data if player enters an exempt state
            return;
        }

        Block placedBlock = event.getBlockPlaced();
        Location playerFeetLocation = player.getLocation(); // Player's current feet location

        // 2. Is it a Scaffold-like Placement?
        // Condition: Block is placed 1 to 2 full blocks below the player's current feet Y,
        // AND the space directly above the newly placed block is not solid (allowing player to stand/move there).
        boolean isPotentialScaffold =
                (placedBlock.getY() < playerFeetLocation.getY() - 0.9 && // At least one block below feet level
                        placedBlock.getY() > playerFeetLocation.getY() - 2.5) && // Not more than 2 blocks below (prevents flagging tower down)
                        !placedBlock.getRelative(0, 1, 0).getType().isSolid();

        if (!isPotentialScaffold) {
            // Not a typical scaffold placement (e.g., normal building, placing on a wall).
            // We could reset parts of PlayerScaffoldData here if needed, e.g., if they stop scaffolding.
            return;
        }

        // --- It's a potential scaffold placement, proceed with checks ---
        PlayerScaffoldData data = playerDataMap.computeIfAbsent(playerUUID, k -> new PlayerScaffoldData(player));
        long currentTimeMs = System.currentTimeMillis();
        float currentPlayerYaw = player.getLocation().getYaw();

        // 3. Fast Placement Check (Interval between scaffold blocks)
        if (data.lastScaffoldPlaceTimeMs > 0) { // Check only if there's a previous scaffold placement time
            long interval = currentTimeMs - data.lastScaffoldPlaceTimeMs;
            if (interval < MIN_SCAFFOLD_INTERVAL_MS) {
                Alert.getInstance().alert("Scaffold", player);
                event.setCancelled(true);
                // Update time even if cancelled to catch continued attempts and prevent alert spam for the same tick
                data.lastScaffoldPlaceTimeMs = currentTimeMs;
                data.lastPlayerYawAtScaffold = currentPlayerYaw;
                return; // Stop further checks for this event
            }
        }

        // 4. Look Direction Check (Placing blocks behind/sideways without looking appropriately)
        Location playerEyeLocation = player.getEyeLocation();
        Vector playerHorizontalLookDirection = playerEyeLocation.getDirection().setY(0).normalize();

        // Vector from player's eyes to the center of the *top surface* of the placed block
        Location placedBlockTopCenter = placedBlock.getLocation().add(0.5, 1.0, 0.5);
        Vector vectorToPlacedBlockHorizontal = placedBlockTopCenter.toVector()
                .subtract(playerEyeLocation.toVector())
                .setY(0);

        if (vectorToPlacedBlockHorizontal.lengthSquared() > 0.001) { // Ensure there's a horizontal component
            vectorToPlacedBlockHorizontal.normalize();
            double dotProduct = playerHorizontalLookDirection.dot(vectorToPlacedBlockHorizontal);
            // acos returns angle in radians, convert to degrees. Angle is between 0 (looking at) and 180 (looking away).
            double angleDegrees = Math.toDegrees(Math.acos(dotProduct));

            double currentMaxAngleThreshold = player.isSprinting() ? MAX_ANGLE_LOOK_AWAY_SPRINT_DEGREES : MAX_ANGLE_LOOK_AWAY_DEGREES;

            if (angleDegrees > currentMaxAngleThreshold) {
                Alert.getInstance().alert("Scaffold", player);
                event.setCancelled(true);
                data.lastScaffoldPlaceTimeMs = currentTimeMs; // Update time
                data.lastPlayerYawAtScaffold = currentPlayerYaw;
                return; // Stop further checks
            }
        }

        // 5. Rapid Head Movement / "Snap" Aiming Check
        if (data.lastScaffoldPlaceTimeMs > 0) { // Requires a previous scaffold placement
            float yawChange = Math.abs(currentPlayerYaw - data.lastPlayerYawAtScaffold);
            if (yawChange > 180.0f) {
                yawChange = 360.0f - yawChange; // Normalize angle difference
            }

            long intervalForSnapCheck = currentTimeMs - data.lastScaffoldPlaceTimeMs;
            // Consider a snap if yaw changes significantly AND the placement was also quick
            if (yawChange > MAX_YAW_SNAP_DEGREES_SCAFFOLD && intervalForSnapCheck < (MIN_SCAFFOLD_INTERVAL_MS + 100L)) { // e.g., < 200ms
                Alert.getInstance().alert("Scaffold", player);
                event.setCancelled(true);
                data.lastScaffoldPlaceTimeMs = currentTimeMs; // Update time
                data.lastPlayerYawAtScaffold = currentPlayerYaw;
                return; // Stop further checks
            }
        }

        // Update data for the next scaffold event if no flags were triggered
        data.lastScaffoldPlaceTimeMs = currentTimeMs;
        data.lastPlayerYawAtScaffold = currentPlayerYaw;
        data.lastPlayerLocationAtScaffold = player.getLocation().clone();
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDataMap.remove(event.getPlayer().getUniqueId());
    }
}
