package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;

public class Speed implements Listener {

    // Adjusted Vanilla speeds (blocks per tick)
    private static final double MAX_GROUND_WALK_BPT = 0.23;  // Approx. 4.6 m/s
    private static final double MAX_GROUND_SPRINT_BPT = 0.30; // Approx. 6.0 m/s
    private static final double MAX_AIR_WALK_BPT = 0.24;
    private static final double MAX_AIR_SPRINT_BPT = 0.31;
    private static final double JUMP_ASCENT_HORIZONTAL_BONUS = 0.03; // Increased slightly

    private static final int ANALYSIS_WINDOW_TICKS = 20;
    private static final double SPEED_TOLERANCE = 1.20;   // Increased to 20% tolerance

    // Multipliers for specific blocks
    private static final double ICE_GROUND_MULTIPLIER = 1.4; // Speed on ice can be higher
    private static final double SOUL_SAND_MULTIPLIER = 0.4;  // Without Soul Speed enchantment
    private static final double HONEY_BLOCK_MULTIPLIER = 0.4;

    private static class PlayerSpeedData {
        Queue<Double> recentDistances = new LinkedList<>();
        Queue<Double> recentMaxSpeedsForTick = new LinkedList<>();
        double sumDistances = 0.0;
        double sumMaxSpeedsForTick = 0.0;
        Location lastLocation;
        boolean wasOnGroundInPreviousTick = true;

        PlayerSpeedData(Location initialLocation) {
            this.lastLocation = initialLocation.clone();
        }
    }

    private final Map<UUID, PlayerSpeedData> playerDataMap = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (event.isCancelled() ||
                player.getGameMode() == GameMode.CREATIVE ||
                player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() ||
                player.isGliding() ||
                player.isInsideVehicle()) {
            playerDataMap.remove(playerUUID);
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to == null || (from.getX() == to.getX() && from.getY() == to.getY() && from.getZ() == to.getZ())) {
            return;
        }
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            playerDataMap.remove(playerUUID);
            return;
        }

        PlayerSpeedData data = playerDataMap.computeIfAbsent(playerUUID, k -> new PlayerSpeedData(from));

        double deltaX = to.getX() - data.lastLocation.getX();
        double deltaZ = to.getZ() - data.lastLocation.getZ();
        double horizontalDistanceMoved = Math.sqrt(deltaX * deltaX + deltaZ * deltaZ);

        double baseSpeedForTick;
        boolean currentlyOnGround = player.isOnGround();
        // Minimal Y increase to be considered a jump, not just micro-adjustments
        boolean justJumped = !currentlyOnGround && data.wasOnGroundInPreviousTick && (to.getY() - data.lastLocation.getY() > 0.005);
        boolean underLowCeiling = isPlayerUnderLowCeiling(player, to);

        if (currentlyOnGround) {
            baseSpeedForTick = player.isSprinting() ? MAX_GROUND_SPRINT_BPT : MAX_GROUND_WALK_BPT;
        } else { // In Air
            if (justJumped) {
                // Initial tick of a jump
                baseSpeedForTick = (player.isSprinting() ? MAX_GROUND_SPRINT_BPT : MAX_GROUND_WALK_BPT);
                baseSpeedForTick += JUMP_ASCENT_HORIZONTAL_BONUS;
                if (underLowCeiling) {
                    // Head-hitter jump: allow at least ground sprint speed + jump bonus
                    // This scenario often results in higher horizontal speed due to restricted vertical movement.
                    // We ensure the base speed considers the ground sprint potential.
                    baseSpeedForTick = Math.max(baseSpeedForTick, MAX_GROUND_SPRINT_BPT + JUMP_ASCENT_HORIZONTAL_BONUS);
                }
            } else {
                // Sustained air movement
                baseSpeedForTick = player.isSprinting() ? MAX_AIR_SPRINT_BPT : MAX_AIR_WALK_BPT;
            }
        }

        // Apply block surface modifiers (primarily for ground, but ice can affect air control too)
        Material blockBelowPlayer = player.getLocation().getBlock().getRelative(BlockFace.DOWN).getType();
        Material blockAtFeet = player.getLocation().getBlock().getType(); // For honey block side stickiness

        if (currentlyOnGround) {
            if (isIce(blockBelowPlayer)) {
                baseSpeedForTick *= ICE_GROUND_MULTIPLIER;
            } else if (blockBelowPlayer == Material.SOUL_SAND && !hasSoulSpeed(player)) {
                baseSpeedForTick *= SOUL_SAND_MULTIPLIER;
            } else if (blockBelowPlayer == Material.HONEY_BLOCK) { // Standing on honey
                baseSpeedForTick *= HONEY_BLOCK_MULTIPLIER;
            }
        }
        // Honey block also slows if touching sides, even in air. This is a simplified check.
        if (blockAtFeet == Material.HONEY_BLOCK && !currentlyOnGround) {
            baseSpeedForTick *= HONEY_BLOCK_MULTIPLIER; // Slow if moving through honey block sides
        }


        double potionMultiplier = 1.0;
        for (PotionEffect effect : player.getActivePotionEffects()) {
            if (effect.getType().equals(PotionEffectType.SPEED)) {
                potionMultiplier *= (1.0 + (0.2 * (effect.getAmplifier() + 1)));
            } else if (effect.getType().equals(PotionEffectType.SLOWNESS)) {
                potionMultiplier *= (1.0 - (0.15 * (effect.getAmplifier() + 1)));
            }
        }
        potionMultiplier = Math.max(0.01, potionMultiplier); // Prevent speed from being zero or negative

        double maxSpeedForThisTick = baseSpeedForTick * potionMultiplier;

        data.recentDistances.add(horizontalDistanceMoved);
        data.sumDistances += horizontalDistanceMoved;
        data.recentMaxSpeedsForTick.add(maxSpeedForThisTick);
        data.sumMaxSpeedsForTick += maxSpeedForThisTick;

        if (data.recentDistances.size() > ANALYSIS_WINDOW_TICKS) {
            data.sumDistances -= data.recentDistances.poll();
            data.sumMaxSpeedsForTick -= data.recentMaxSpeedsForTick.poll();
        }

        if (data.recentDistances.size() == ANALYSIS_WINDOW_TICKS) {
            if (data.sumDistances > (data.sumMaxSpeedsForTick * SPEED_TOLERANCE)) {
                Alert.getInstance().alert("Speed", player);
                event.setTo(from);
                // Consider resetting or reducing sums upon detection to avoid spamming alerts
                // data.sumDistances = 0; data.sumMaxSpeedsForTick = 0; data.recentDistances.clear(); data.recentMaxSpeedsForTick.clear();
            }
        }

        data.lastLocation = to.clone();
        data.wasOnGroundInPreviousTick = currentlyOnGround;
    }

    private boolean isIce(Material material) {
        return material == Material.ICE || material == Material.PACKED_ICE || material == Material.BLUE_ICE || material == Material.FROSTED_ICE;
    }

    private boolean hasSoulSpeed(Player player) {
        ItemStack boots = player.getInventory().getBoots();
        return boots != null && boots.containsEnchantment(Enchantment.SOUL_SPEED);
    }

    private boolean isPlayerUnderLowCeiling(Player player, Location currentLocation) {
        // Check a block that would be just above the player's head if they were standing fully.
        // Player's eye height is ~1.62, total height ~1.8.
        // A block at Y + 1.9 relative to feet location would be a low ceiling.
        Location ceilingCheckLoc = currentLocation.clone().add(0, 1.9, 0);
        return ceilingCheckLoc.getBlock().getType().isSolid() && ceilingCheckLoc.getBlock().getType().isOccluding();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerDataMap.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        // Reset speed data on teleport as the context changes significantly
        playerDataMap.remove(event.getPlayer().getUniqueId());
        // Re-initialize with the new location for the next move event
        playerDataMap.put(event.getPlayer().getUniqueId(), new PlayerSpeedData(event.getTo()));
    }
}
