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
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class HighJump implements Listener {

    // Stores the Y-coordinate of the last time the player was on solid ground.
    private final Map<UUID, Double> lastOnGroundY = new HashMap<>();
    // Stores the location of the block the player jumped from (block below their feet).
    private final Map<UUID, Location> jumpStartBlockLocation = new HashMap<>();
    // Grace period after teleport to avoid false flags
    private final Map<UUID, Long> teleportGracePeriod = new HashMap<>();
    private static final long TELEPORT_GRACE_MS = 1000L; // 1 second grace period

    // Base max jump height without any effects or special blocks.
    // Vanilla max jump height is 1.2522 blocks. We add a small tolerance.
    private static final double BASE_MAX_JUMP_HEIGHT = 1.26; // Slightly above vanilla max
    // Total jump heights for different levels of Jump Boost (amplifier = level - 1)
    private static final double[] JUMP_BOOST_TOTAL_HEIGHTS = {
            2.50,  // Jump Boost I (amplifier 0)
            4.06,  // Jump Boost II (amplifier 1)
            6.00,  // Jump Boost III (amplifier 2)
            8.20,  // Jump Boost IV (amplifier 3)
            10.80  // Jump Boost V (amplifier 4)
            // Values are approximate total heights.
    };
    private static final double JUMP_TOLERANCE = 0.25; // General tolerance for calculations and minor lag

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Ignore players in exempt modes or states
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.isGliding() || player.getAllowFlight()) {
            lastOnGroundY.remove(playerUUID);
            jumpStartBlockLocation.remove(playerUUID);
            teleportGracePeriod.remove(playerUUID); // Clear grace period too
            return;
        }

        // 2. Check for teleport grace period
        if (teleportGracePeriod.containsKey(playerUUID)) {
            if (System.currentTimeMillis() < teleportGracePeriod.get(playerUUID)) {
                return; // Still in grace period
            } else {
                teleportGracePeriod.remove(playerUUID); // Grace period expired
            }
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        // 3. Basic sanity checks & ignore minor movements or only head rotation
        if (to == null || from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            // Only rotation changed, not position
            return;
        }

        boolean isOnGroundCurrently = player.isOnGround(); // Bukkit's check

        // 4. Update last on-ground position
        if (isOnGroundCurrently) {
            lastOnGroundY.put(playerUUID, to.getY());
            jumpStartBlockLocation.put(playerUUID, to.getBlock().getRelative(BlockFace.DOWN).getLocation());
            return; // No jump height check needed if on ground
        }

        // --- Player is in the air ---

        // 5. If moving downwards, the jump ascent has finished.
        if (to.getY() <= from.getY()) {
            return;
        }

        // 6. We need a starting Y-coordinate from when they were last on ground.
        if (!lastOnGroundY.containsKey(playerUUID)) {
            // Player was likely already in air (e.g., fell off edge, plugin reload).
            // Record 'from.getY()' as a temporary baseline to avoid immediate false positives.
            // This baseline will be properly set once they touch ground.
            lastOnGroundY.put(playerUUID, from.getY());
            jumpStartBlockLocation.put(playerUUID, from.getBlock().getRelative(BlockFace.DOWN).getLocation());
            return;
        }

        double startY = lastOnGroundY.get(playerUUID);
        double currentJumpHeight = to.getY() - startY;

        // 7. If current height isn't positive relative to startY, it's not an upward jump from that point.
        if (currentJumpHeight <= 0.05) { // Small threshold to ensure actual upward movement
            return;
        }

        // 8. Calculate maximum allowed jump height for this player
        double maxAllowedJumpHeight = BASE_MAX_JUMP_HEIGHT;
        if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
            PotionEffect jumpEffect = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
            if (jumpEffect != null) {
                int amplifier = jumpEffect.getAmplifier(); // 0 for Level I, 1 for Level II, etc.
                if (amplifier >= 0 && amplifier < JUMP_BOOST_TOTAL_HEIGHTS.length) {
                    maxAllowedJumpHeight = JUMP_BOOST_TOTAL_HEIGHTS[amplifier];
                } else if (amplifier >= JUMP_BOOST_TOTAL_HEIGHTS.length) {
                    // Estimate for levels beyond our predefined array
                    maxAllowedJumpHeight = JUMP_BOOST_TOTAL_HEIGHTS[JUMP_BOOST_TOTAL_HEIGHTS.length - 1] +
                            (amplifier - (JUMP_BOOST_TOTAL_HEIGHTS.length - 1)) * 2.0; // Rough estimate
                }
            }
        }

        // 9. Check for special blocks at the jump start location
        Location jumpBlockLoc = jumpStartBlockLocation.get(playerUUID);
        if (jumpBlockLoc != null) {
            Material blockTypeUnderStart = jumpBlockLoc.getBlock().getType();
            if (blockTypeUnderStart == Material.SLIME_BLOCK) {
                // Slime blocks significantly increase jump height.
                // We can either ignore the check or set a much higher threshold.
                // For now, let's add a large buffer.
                maxAllowedJumpHeight += 7.0; // Slime blocks can launch ~7-8 blocks high.
            }
        }

        // 10. Perform the high jump check
        if (currentJumpHeight > (maxAllowedJumpHeight + JUMP_TOLERANCE)) {
            // Before alerting, ensure they are not legitimately climbing.
            // Check blocks at the 'to' location (feet and head) for climbable materials.
            Material blockAtToFeet = to.getBlock().getType();
            Material blockAtToHead = player.getEyeLocation().getBlock().getType(); // Eye location at 'to'

            if (isClimbable(blockAtToFeet) || isClimbable(blockAtToHead) || player.isClimbing()) {
                // Player is climbing. Update their lastOnGroundY to current Y to serve as a new baseline
                // for any jump *off* the climbable object.
                lastOnGroundY.put(playerUUID, to.getY());
                jumpStartBlockLocation.put(playerUUID, to.getBlock().getRelative(BlockFace.DOWN).getLocation());
                return;
            }

            Alert.getInstance().alert("HighJump", player);

            // Teleport player back to the XZ of 'from' location, at the Y where they started the jump.
            Location safeTeleportLocation = new Location(player.getWorld(),
                    from.getX(), // Use 'from' X for slight rubberband if they moved XZ too
                    startY,      // Y level of last ground contact
                    from.getZ(), // Use 'from' Z
                    to.getYaw(),   // Current yaw
                    to.getPitch());// Current pitch
            player.teleport(safeTeleportLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);

            // Reset jump data to prevent immediate re-flagging until they touch ground again.
            lastOnGroundY.remove(playerUUID);
            jumpStartBlockLocation.remove(playerUUID);
        }
    }

    /**
     * Checks if a material is climbable.
     * @param material The material to check.
     * @return True if the material is climbable, false otherwise.
     */
    private boolean isClimbable(Material material) {
        return material == Material.LADDER || material == Material.VINE ||
                material == Material.SCAFFOLDING || material == Material.TWISTING_VINES ||
                material == Material.TWISTING_VINES_PLANT || material == Material.WEEPING_VINES ||
                material == Material.WEEPING_VINES_PLANT;
    }

    /**
     * Clears player data on quit.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerUUID = event.getPlayer().getUniqueId();
        lastOnGroundY.remove(playerUUID);
        jumpStartBlockLocation.remove(playerUUID);
        teleportGracePeriod.remove(playerUUID);
    }

    /**
     * Applies a grace period and resets jump data on player teleport.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // Apply a grace period after any teleport to avoid false flags.
        teleportGracePeriod.put(playerUUID, System.currentTimeMillis() + TELEPORT_GRACE_MS);

        // Reset jump tracking data as their "ground" context has changed.
        // They will establish a new lastOnGroundY when they land or move.
        lastOnGroundY.remove(playerUUID);
        jumpStartBlockLocation.remove(playerUUID);
    }
}
