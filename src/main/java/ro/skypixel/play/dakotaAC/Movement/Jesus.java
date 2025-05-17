package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.Map;
import java.util.UUID;

public class Jesus implements Listener {

    // Inner class to store player-specific data for Jesus detection
    private static class PlayerWaterData {
        Location lastCheckedLocation;
        int ticksOnWaterSurfaceAndLevel = 0; // Consecutive ticks on water surface and relatively level
        long lastTeleportTime = 0L;          // Timestamp of the last teleport

        PlayerWaterData(Location initialLocation) {
            this.lastCheckedLocation = initialLocation.clone();
        }
    }

    private final Map<UUID, PlayerWaterData> playerData = new HashMap<>();
    private final DakotaAC plugin; // Instance of your main plugin

    // Configuration Constants
    // Max horizontal speed squared allowed when "walking" on water.
    // Normal walking speed is ~0.1 blocks/tick. (0.11)^2 = 0.0121. This is slightly above walking.
    private static final double MAX_HORIZONTAL_SPEED_SQUARED_ON_WATER = 0.0121;
    // Max Y change per tick to be considered "level" on water (not jumping/falling significantly).
    private static final double Y_LEVEL_THRESHOLD_FOR_JESUS = 0.15; // Allows for minor bobbing
    // Min consecutive ticks on water surface and level before speed check applies.
    private static final int MIN_TICKS_ON_SURFACE_FOR_FLAG = 7; // Approx 0.35 seconds
    // Grace period after teleport to avoid false flags (in server ticks)
    private static final long TELEPORT_GRACE_PERIOD_TICKS = 40L; // 2 seconds

    private static final Material LILY_PAD_MATERIAL = Material.LILY_PAD;

    /**
     * Constructor to inject the main plugin instance.
     * @param plugin Your main plugin class instance.
     */
    public Jesus(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        PlayerWaterData data = playerData.computeIfAbsent(playerUUID, k -> new PlayerWaterData(event.getFrom()));

        // 1. Check for teleport grace period
        if (System.currentTimeMillis() - data.lastTeleportTime < (TELEPORT_GRACE_PERIOD_TICKS * 50)) {
            return;
        }

        // 2. Exemptions
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() || player.isGliding() ||
                player.isInsideVehicle() || player.hasPotionEffect(PotionEffectType.LEVITATION) ||
                player.hasPotionEffect(PotionEffectType.DOLPHINS_GRACE)) { // Dolphins Grace allows fast swimming
            data.ticksOnWaterSurfaceAndLevel = 0; // Reset counter if exempt
            data.lastCheckedLocation = player.getLocation().clone();
            return;
        }

        Location from = data.lastCheckedLocation; // Use the location from the last valid check
        Location to = event.getTo();

        // Basic sanity checks
        if (to == null || from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            playerData.remove(playerUUID); // Invalid state, reset
            return;
        }
        // If no actual positional change
        if (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ()) {
            return;
        }

        double deltaX = to.getX() - from.getX();
        double deltaY = to.getY() - from.getY();
        double deltaZ = to.getZ() - from.getZ();
        double horizontalSpeedSquared = (deltaX * deltaX) + (deltaZ * deltaZ);

        boolean isOnWaterSurface = isPlayerExploitingWaterSurface(player, to);
        boolean isRelativelyLevel = Math.abs(deltaY) < Y_LEVEL_THRESHOLD_FOR_JESUS;

        if (isOnWaterSurface) {
            if (isRelativelyLevel) {
                data.ticksOnWaterSurfaceAndLevel++;

                if (data.ticksOnWaterSurfaceAndLevel >= MIN_TICKS_ON_SURFACE_FOR_FLAG) {
                    if (horizontalSpeedSquared > MAX_HORIZONTAL_SPEED_SQUARED_ON_WATER) {
                        // Check if player is swimming (vanilla mechanic for moving in water)
                        // If swimming, they might be legitimately fast with Dolphin's Grace (already exempted)
                        // or just sprint-swimming. This check is for non-swimming "walking on water".
                        if (player.isSwimming()) {
                            data.ticksOnWaterSurfaceAndLevel = 0; // Reset, as swimming is different from "Jesus"
                        } else {
                            Alert.getInstance().alert("Jesus", player);
                            event.setCancelled(true);
                            // Optional: Teleport player slightly down or to 'from' location
                            // player.teleport(from.clone().subtract(0,0.2,0));
                            data.ticksOnWaterSurfaceAndLevel = 0; // Reset after flagging
                        }
                    }
                }
            } else {
                // Player is on water surface but moving up/down significantly (jumping/falling into water)
                data.ticksOnWaterSurfaceAndLevel = 0;
            }
        } else {
            // Player is not on the water surface
            data.ticksOnWaterSurfaceAndLevel = 0;
        }

        data.lastCheckedLocation = to.clone(); // Update location for the next check
    }

    /**
     * Checks if the player is in a state consistent with "walking on water" exploits.
     * This means their feet are at/near water level, but their head is above water,
     * and they are not on a lily pad.
     */
    private boolean isPlayerExploitingWaterSurface(Player player, Location currentLocation) {
        Location feetLoc = currentLocation.clone();
        Block blockAtFeet = feetLoc.getBlock();
        Block blockBelowFeet = feetLoc.clone().subtract(0, 0.1, 0).getBlock(); // Check slightly below feet
        Block blockAtHead = player.getEyeLocation().getBlock(); // Eye location at current 'to'

        // Check for lily pads directly at feet or one block below (if feet are in air over lily pad)
        if (blockAtFeet.getType() == LILY_PAD_MATERIAL || blockBelowFeet.getType() == LILY_PAD_MATERIAL) {
            return false; // Standing on a lily pad is legitimate
        }

        boolean feetEffectivelyOnWater;
        if (isWater(blockAtFeet.getType())) {
            // Feet are in a water block
            feetEffectivelyOnWater = true;
        } else if (blockAtFeet.isPassable() && !blockAtFeet.isLiquid() && isWater(blockBelowFeet.getType())) {
            // Feet are in air/passable block, with water directly below
            feetEffectivelyOnWater = true;
        } else {
            feetEffectivelyOnWater = false;
        }

        // Head must not be in water for it to be "walking on water" rather than swimming/submerged
        return feetEffectivelyOnWater && !isWater(blockAtHead.getType());
    }

    private boolean isWater(Material material) {
        return material == Material.WATER;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerData.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        PlayerWaterData data = playerData.computeIfAbsent(event.getPlayer().getUniqueId(), k -> new PlayerWaterData(event.getTo()));
        data.lastTeleportTime = System.currentTimeMillis();
        data.lastCheckedLocation = event.getTo().clone(); // Update location on teleport
        data.ticksOnWaterSurfaceAndLevel = 0; // Reset water ticks
    }
}
