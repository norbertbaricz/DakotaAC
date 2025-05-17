package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Assuming this is your main plugin class for scheduling

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class Step implements Listener {

    // Inner class to store player-specific data for Step detection
    private static class PlayerStepData {
        Location lastValidLocation;     // Last location considered valid
        boolean wasOnGroundLastTick;    // Was the player on ground in the previous processed tick?
        long lastTeleportTimeMs = 0L;   // Timestamp of the last teleport

        PlayerStepData(Location initialLocation) {
            this.lastValidLocation = initialLocation.clone();
            // Initialize based on blocks below the initial location
            this.wasOnGroundLastTick = initialLocation.getBlock().getRelative(BlockFace.DOWN).getType().isSolid() ||
                    initialLocation.getBlock().getRelative(BlockFace.DOWN, 2).getType().isSolid();
        }
    }

    private final Map<UUID, PlayerStepData> playerDataMap = new HashMap<>();
    private final DakotaAC plugin; // Main plugin instance

    // Configuration Constants
    // Max height a player can "step" up in one tick without it being considered a jump-like ascent.
    // Slabs are 0.5. Stairs can be up to ~0.75 in some interactions.
    // If yChange > this, we expect some jump velocity.
    private static final double MAX_LEGIT_VERTICAL_STEP_THRESHOLD = 0.50; // Adjusted: Allows slabs and most stair steps
    // Minimum Y velocity expected if a player legitimately jumps to achieve a significant height increase
    // (i.e., if yChangeThisTick > MAX_LEGIT_VERTICAL_STEP_THRESHOLD).
    // A full block jump has an initial Y velocity of ~0.42.
    private static final double MIN_EXPECTED_JUMP_VELOCITY_Y = 0.18; // Kept from user's code, reasonable
    // Grace period after teleportation in server ticks.
    private static final long TELEPORT_GRACE_PERIOD_TICKS = 30L; // 1.5 seconds

    public Step(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Standard Exemptions
        if (event.isCancelled() ||
                player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() ||
                player.isGliding() || player.isInsideVehicle() ||
                player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            playerDataMap.remove(playerUUID);
            return;
        }

        PlayerStepData data = playerDataMap.computeIfAbsent(playerUUID, k -> new PlayerStepData(event.getFrom()));

        // 2. Teleport Grace Period
        if (System.currentTimeMillis() < data.lastTeleportTimeMs + (TELEPORT_GRACE_PERIOD_TICKS * 50)) {
            data.lastValidLocation = event.getTo().clone();
            data.wasOnGroundLastTick = player.isOnGround();
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        // 3. Basic Sanity Checks
        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
            // Only rotation changed, or 'to' is null
            return;
        }
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            playerDataMap.remove(playerUUID); // World changed or invalid, reset data
            return;
        }

        boolean onGroundBeforeThisMove = data.wasOnGroundLastTick;
        double yChangeThisTick = to.getY() - from.getY();

        // 4. Core Step Detection Logic
        // Check if player was on ground and moved upwards significantly (more than a normal slab/stair step-up)
        if (onGroundBeforeThisMove && yChangeThisTick > MAX_LEGIT_VERTICAL_STEP_THRESHOLD) {
            double playerVelocityY = player.getVelocity().getY();

            // If the upward movement is significant AND the player's current Y velocity
            // doesn't reflect a legitimate jump for that kind of height gain from ground.
            if (playerVelocityY < MIN_EXPECTED_JUMP_VELOCITY_Y) {
                // Ensure they weren't launched by a slime block or honey block from their 'from' position
                Material blockBelowFrom = from.getBlock().getRelative(BlockFace.DOWN).getType();
                if (blockBelowFrom != Material.SLIME_BLOCK && blockBelowFrom != Material.HONEY_BLOCK) {
                    Alert.getInstance().alert("Step", player);
                    event.setCancelled(true);
                    // Teleport player back to the location they were at *before* this suspicious move.
                    player.teleport(from, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    // Reset player data as their position is being corrected.
                    playerDataMap.put(playerUUID, new PlayerStepData(from)); // Re-initialize with 'from'
                    return; // Stop further processing for this event
                }
            }
        }

        // 5. Update player data for the next tick
        data.lastValidLocation = to.clone();
        data.wasOnGroundLastTick = player.isOnGround(); // Current onGround status for the *next* tick's "wasOnGround"
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDataMap.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        // Re-initialize data on teleport to ensure fresh state
        PlayerStepData newData = new PlayerStepData(event.getTo());
        newData.lastTeleportTimeMs = System.currentTimeMillis();
        playerDataMap.put(playerUUID, newData);
    }
}
