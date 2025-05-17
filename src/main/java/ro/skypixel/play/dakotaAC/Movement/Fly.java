package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class Fly implements Listener {

    // Inner class to store player-specific flight detection data
    private static class PlayerFlyData {
        Location lastSignificantLocation; // Location before starting a potential flight sequence or last ground pos
        double lastY;                     // Last recorded Y coordinate
        int suspiciousAirTicks = 0;       // Consecutive ticks airborne suspiciously
        long lastTimeOnGroundOrLegitFlight = System.currentTimeMillis(); // Timestamp of last ground contact or legit flight

        PlayerFlyData(Location initialLocation) {
            this.lastSignificantLocation = initialLocation.clone(); // Clone to ensure it's a separate instance
            this.lastY = initialLocation.getY();
        }
    }

    private final Map<UUID, PlayerFlyData> playerData = new HashMap<>();
    private final Set<UUID> recentlyTeleported = new HashSet<>(); // Players recently teleported, to grant grace period
    private final DakotaAC plugin; // Instance of your main plugin

    // Configuration Constants for Fly Detection
    private static final double MAX_ASCEND_SPEED = 0.6;     // Max Y change upwards per tick considered "normal" for a jump burst
    private static final int MAX_SUSPICIOUS_AIR_TICKS = 7;  // Ticks hovering/ascending without support before flagging (~0.35 seconds)
    private static final double HOVER_Y_THRESHOLD = 0.001;  // Max Y change downwards to be considered "hovering" or stable
    private static final long TELEPORT_GRACE_PERIOD_TICKS = 40L; // Grace period (2 seconds) after a teleport

    /**
     * Constructor to inject the main plugin instance.
     * Required for scheduling tasks (e.g., for teleport grace period).
     * @param plugin Your main plugin class instance.
     */
    public Fly(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Ignore recently teleported players
        if (recentlyTeleported.contains(playerUUID)) {
            return;
        }

        // 2. Ignore players with legitimate flight capabilities or in specific states
        if (player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.getAllowFlight() ||      // Vanilla /fly enabled by server/plugin
                player.isFlying() ||            // Actual server-side flying state
                player.isGliding() ||           // Elytra flight
                player.isInsideVehicle() ||
                player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            playerData.remove(playerUUID); // Reset data as they are legitimately airborne/can fly
            return;
        }

        Location to = event.getTo();
        Location from = event.getFrom();

        // 3. Basic sanity checks for location data
        if (to == null || to.getWorld() == null || from.getWorld() == null || !to.getWorld().equals(from.getWorld())) {
            playerData.remove(playerUUID); // Invalid state, reset
            return;
        }

        // 4. Ignore if only head rotation changed (no actual X, Y, Z movement)
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        PlayerFlyData data = playerData.computeIfAbsent(playerUUID, k -> new PlayerFlyData(from));

        boolean isOnGroundNow = isPlayerOnGround(player, to);
        boolean isInLiquidOrClimbingNow = isInLiquid(player, to) || isClimbing(player, to);

        // 5. If player is on ground or in a state that explains being off-ground (liquid/climbing)
        if (isOnGroundNow || isInLiquidOrClimbingNow) {
            data.suspiciousAirTicks = 0;
            data.lastY = to.getY();
            data.lastSignificantLocation = to.clone(); // Update last safe location
            data.lastTimeOnGroundOrLegitFlight = System.currentTimeMillis();
            return;
        }

        // --- Player is airborne and not for any obvious legitimate reason checked above ---
        double deltaY = to.getY() - from.getY(); // Y change since last tick's 'to' location

        // 6. Check for overly rapid ascent (faster than a normal jump's initial burst)
        if (deltaY > MAX_ASCEND_SPEED) {
            // Allow if they were on solid ground very recently (e.g., the start of a jump)
            if (!wasOnSolidGroundRecently(player, from, 1)) { // Check 1 block below 'from' for solid ground
                Alert.getInstance().alert("Fly", player);
                attemptPullDown(player, data.lastSignificantLocation);
                playerData.remove(playerUUID); // Reset after flagging
                return;
            }
        }

        // 7. Check for hovering or slow/no descent when in mid-air without support
        // deltaY >= -HOVER_Y_THRESHOLD means player is not falling at a normal rate, or is ascending/hovering
        if (deltaY >= -HOVER_Y_THRESHOLD) {
            // If they are not near any solid block below them that could explain their position (e.g., edge of a cliff)
            if (!isNearSolidBlock(player.getLocation(), 3)) { // Check 3 blocks down
                data.suspiciousAirTicks++;
            } else {
                // Near a block, could be parkour, an edge case, or landing on something not caught by isPlayerOnGround.
                // Reset suspicious ticks for this specific scenario but don't update lastTimeOnGround.
                data.suspiciousAirTicks = 0;
            }
        } else {
            // Player is descending at a reasonable rate, this is normal for being airborne.
            data.suspiciousAirTicks = 0;
        }

        // 8. Check for sustained suspicious airtime
        if (data.suspiciousAirTicks > MAX_SUSPICIOUS_AIR_TICKS) {
            long airTimeSinceLastGround = System.currentTimeMillis() - data.lastTimeOnGroundOrLegitFlight;
            // If suspicious ticks are high AND they've been in this "suspicious air" state for a while
            // (e.g., more than MAX_SUSPICIOUS_AIR_TICKS worth of time + a buffer)
            if (airTimeSinceLastGround > (MAX_SUSPICIOUS_AIR_TICKS * 50L + 500L)) { // ~0.85s for 7 ticks + 0.5s buffer
                Alert.getInstance().alert("Fly", player);
                attemptPullDown(player, data.lastSignificantLocation);
                playerData.remove(playerUUID); // Reset after flagging
                return;
            }
        }
        data.lastY = to.getY(); // Update lastY for next tick's comparison if needed
    }

    /**
     * Checks if the player was on solid ground within the last few blocks below their reference location.
     * Used to differentiate a jump's initial burst from sustained ascent.
     */
    private boolean wasOnSolidGroundRecently(Player player, Location referenceLocation, int depth) {
        for (int i = 0; i <= depth; i++) {
            // Check block slightly below the Y level of referenceLocation - i
            Block blockBelow = referenceLocation.clone().subtract(0, i + 0.1, 0).getBlock();
            if (isSolid(blockBelow)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Checks if the player is considered to be on solid ground at the given location.
     */
    private boolean isPlayerOnGround(Player player, Location location) {
        Block blockBelow = location.clone().subtract(0, 0.05, 0).getBlock(); // Check slightly below feet
        return isSolid(blockBelow);
    }

    /**
     * Determines if a block is solid and not passable for standing purposes.
     */
    private boolean isSolid(Block block) {
        if (block == null) return false;
        Material type = block.getType();
        // isSolid() is true for many things, isOccluding() is stricter for full blocks.
        // We want blocks that can be stood upon.
        // Exclude specific non-collidable or special blocks explicitly if needed.
        return type.isSolid() && type.isOccluding() && type != Material.COBWEB && type != Material.POWDER_SNOW;
    }

    /**
     * Checks if a material is climbable.
     */
    private boolean isClimbable(Material material) {
        return material == Material.LADDER || material == Material.VINE ||
                material == Material.SCAFFOLDING || material == Material.TWISTING_VINES ||
                material == Material.TWISTING_VINES_PLANT || material == Material.WEEPING_VINES ||
                material == Material.WEEPING_VINES_PLANT;
    }

    /**
     * Checks if the player is currently in a liquid at the given location.
     */
    private boolean isInLiquid(Player player, Location location) {
        Material feetMaterial = location.getBlock().getType();
        // Eye location check is also important for full immersion
        Material headMaterial = player.getEyeLocation().getBlock().getType();
        return feetMaterial == Material.WATER || feetMaterial == Material.LAVA ||
                headMaterial == Material.WATER || headMaterial == Material.LAVA;
    }

    /**
     * Checks if the player is at a location with climbable blocks.
     */
    private boolean isClimbing(Player player, Location location) {
        Block currentBlock = location.getBlock();
        // Check block at feet and head for climbable materials
        return isClimbable(currentBlock.getType()) || isClimbable(player.getEyeLocation().getBlock().getType());
    }

    /**
     * Checks if there is any solid block within a certain vertical distance below the player's feet.
     * This helps determine if the player is near a ledge or floating in open air.
     */
    private boolean isNearSolidBlock(Location loc, int checkDepth) {
        World world = loc.getWorld();
        if (world == null) return true; // Should not happen, assume solid if world is invalid

        for (int i = 1; i <= checkDepth; i++) {
            Block block = world.getBlockAt(loc.getBlockX(), loc.getBlockY() - i, loc.getBlockZ());
            if (isSolid(block) || block.getType() == Material.WATER || block.getType() == Material.LAVA || block.getType() == Material.COBWEB) {
                // Consider water/lava/cobweb as "support" that would explain not falling
                return true;
            }
        }
        return false;
    }

    /**
     * Attempts to teleport the player down to a safe location.
     * Prefers the highest solid block directly below the player,
     * otherwise falls back to the last known significant (safe) location.
     */
    private void attemptPullDown(Player player, Location safeFallbackLocation) {
        Location currentLocation = player.getLocation().clone();
        World world = currentLocation.getWorld();
        if (world == null) return;

        // Try to find the highest solid ground directly below the player's current X/Z
        for (int y = currentLocation.getBlockY(); y >= world.getMinHeight(); y--) {
            Block blockAt = world.getBlockAt(currentLocation.getBlockX(), y, currentLocation.getBlockZ());
            Block blockAbove = world.getBlockAt(currentLocation.getBlockX(), y + 1, currentLocation.getBlockZ());
            Block blockFurtherAbove = world.getBlockAt(currentLocation.getBlockX(), y + 2, currentLocation.getBlockZ());

            if (isSolid(blockAt) && !blockAbove.getType().isSolid() && !blockFurtherAbove.getType().isSolid()) {
                // Found a solid block with clear space above for the player
                Location pullTo = new Location(world, currentLocation.getX(), y + 1.0, currentLocation.getZ(),
                        player.getLocation().getYaw(), player.getLocation().getPitch());
                player.teleport(pullTo, PlayerTeleportEvent.TeleportCause.PLUGIN);
                return;
            }
        }

        // Fallback: If no solid ground is found directly below (e.g., player is over the void),
        // teleport to the last known significant location.
        if (safeFallbackLocation != null) {
            player.teleport(safeFallbackLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        } else {
            // Extreme fallback: if safeLocation is somehow null, just pull down a bit from current.
            // This is less ideal as it might put them in a wall if they were already phasing.
            currentLocation.setY(currentLocation.getY() - 1.5);
            player.teleport(currentLocation, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    // Cleanup player data on quit
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerData.remove(event.getPlayer().getUniqueId());
        recentlyTeleported.remove(event.getPlayer().getUniqueId());
    }

    // Grant grace period after teleportation
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Check if the teleport was significant (more than 1 block distance)
        if (event.getFrom().getWorld().equals(event.getTo().getWorld()) &&
                event.getFrom().distanceSquared(event.getTo()) > 1.0 ||
                !event.getFrom().getWorld().equals(event.getTo().getWorld())) { // Or world change

            final UUID playerUUID = event.getPlayer().getUniqueId();
            recentlyTeleported.add(playerUUID);
            playerData.remove(playerUUID); // Reset fly data on any significant teleport

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                recentlyTeleported.remove(playerUUID);
            }, TELEPORT_GRACE_PERIOD_TICKS);
        }
    }
}