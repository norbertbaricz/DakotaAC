package ro.skypixel.play.dakotaAC.Player;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import ro.skypixel.play.dakotaAC.Alert;
import ro.skypixel.play.dakotaAC.DakotaAC; // Your main plugin class

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class NoFall implements Listener {

    // Inner class to store player-specific fall detection data
    private static class PlayerFallState {
        boolean wasOnGroundLastTick = true;
        float fallDistanceAtLastGroundContact = 0f;
        BukkitTask expectedDamageCheckTask = null;
        long lastTeleportTimeMs = 0L;

        double lastYPositionForMidAirCheck;
        int ticksFallingFastWithoutFallDistance = 0;

        PlayerFallState(Location initialLocation) {
            this.lastYPositionForMidAirCheck = initialLocation.getY();
            this.wasOnGroundLastTick = initialLocation.getBlock().getRelative(BlockFace.DOWN).getType().isSolid() ||
                    initialLocation.getBlock().getRelative(BlockFace.DOWN, 2).getType().isSolid();
        }
    }

    private final Map<UUID, PlayerFallState> playerStates = new HashMap<>();
    private final DakotaAC plugin;

    // Configuration Constants
    private static final float NOFALL_FLAG_ACTUAL_FALL_DISTANCE = 3.75f;

    private static final double FAST_FALL_VELOCITY_Y_THRESHOLD = -0.65;
    private static final float MID_AIR_RESET_FALL_DISTANCE_MAX = 1.0f;
    private static final int MID_AIR_RESET_SUSPICIOUS_TICKS = 5;

    private static final EnumSet<Material> SAFE_LANDING_MATERIALS = EnumSet.of(
            Material.WATER, Material.LAVA,
            Material.COBWEB, Material.SLIME_BLOCK, Material.HAY_BLOCK,
            Material.HONEY_BLOCK, Material.POWDER_SNOW
    );

    private static final long TELEPORT_GRACE_PERIOD_MS = 1500L;

    public NoFall(DakotaAC plugin) {
        this.plugin = plugin;
        for (Material mat : Material.values()) {
            if (mat.name().contains("_BED")) {
                SAFE_LANDING_MATERIALS.add(mat);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                player.isFlying() || player.getAllowFlight() || player.isGliding() ||
                player.isInsideVehicle() ||
                player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            clearExpectedDamageTask(playerUUID);
            playerStates.remove(playerUUID);
            return;
        }

        PlayerFallState state = playerStates.computeIfAbsent(playerUUID, k -> new PlayerFallState(event.getFrom()));

        if (System.currentTimeMillis() < state.lastTeleportTimeMs + TELEPORT_GRACE_PERIOD_MS) {
            state.wasOnGroundLastTick = player.isOnGround();
            state.lastYPositionForMidAirCheck = event.getTo().getY();
            if (player.isOnGround()) state.fallDistanceAtLastGroundContact = 0f;
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();
        boolean currentOnGround = player.isOnGround();
        float currentFallDistance = player.getFallDistance();
        double currentVelocityY = player.getVelocity().getY();

        // --- Check 1: Mid-Air FallDistance Reset ---
        if (!currentOnGround && currentVelocityY < FAST_FALL_VELOCITY_Y_THRESHOLD) {
            if (currentFallDistance < MID_AIR_RESET_FALL_DISTANCE_MAX) {
                state.ticksFallingFastWithoutFallDistance++;
                if (state.ticksFallingFastWithoutFallDistance >= MID_AIR_RESET_SUSPICIOUS_TICKS) {
                    Alert.getInstance().alert("NoFall", player);
                    player.teleport(from, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    // Reset state after teleport to avoid immediate re-flag or issues
                    playerStates.put(playerUUID, new PlayerFallState(from));
                    return; // Return to avoid other checks after teleport
                }
            } else {
                state.ticksFallingFastWithoutFallDistance = 0;
            }
        } else if (currentOnGround) {
            state.ticksFallingFastWithoutFallDistance = 0;
        }

        // --- Check 2: Landing Without Taking Expected Fall Damage ---
        if (currentOnGround && !state.wasOnGroundLastTick) {
            clearExpectedDamageTask(playerUUID);

            float actualFallThisEpisode = currentFallDistance;

            if (actualFallThisEpisode >= NOFALL_FLAG_ACTUAL_FALL_DISTANCE) {
                Block blockLandedOn = player.getLocation().getBlock().getRelative(BlockFace.DOWN);
                if (!isSafeLandingMaterial(blockLandedOn.getType())) {
                    // Schedule a task to check if damage was taken
                    // Store the task itself so it can be identified and managed
                    final BukkitTask scheduledTask = new BukkitRunnable() {
                        @Override
                        public void run() {
                            // If this runnable is cancelled by Bukkit or our own logic before execution
                            if (this.isCancelled()) {
                                return;
                            }

                            PlayerFallState currentState = playerStates.get(playerUUID);
                            Player onlinePlayer = plugin.getServer().getPlayer(playerUUID);

                            // Check if player is still valid and state exists
                            if (onlinePlayer == null || !onlinePlayer.isOnline() || onlinePlayer.isDead() || currentState == null) {
                                clearExpectedDamageTask(playerUUID); // Clean up if player is gone
                                return;
                            }

                            // Crucial Check: Ensure this task is still the one expected to run.
                            // If state.expectedDamageCheckTask is null, it means damage was taken (or player teleported etc.)
                            // and this check is no longer needed.
                            // If task IDs don't match, it means a newer check was scheduled, and this one is stale.
                            if (currentState.expectedDamageCheckTask == null ||
                                    currentState.expectedDamageCheckTask.getTaskId() != this.getTaskId()) {
                                return;
                            }

                            // If this task runs, it means EntityDamageEvent(FALL) did NOT occur for the recent landing.
                            // Re-check fall distance at this moment for confirmation.
                            if (onlinePlayer.getFallDistance() >= NOFALL_FLAG_ACTUAL_FALL_DISTANCE - 0.5f) {
                                Alert.getInstance().alert("NoFall", onlinePlayer);
                            }

                            // This specific check is now complete, clear it from the state.
                            currentState.expectedDamageCheckTask = null;
                        }
                    }.runTaskLater(plugin, 2L); // Check after 2 ticks (0.1s)
                    state.expectedDamageCheckTask = scheduledTask; // Store the scheduled task
                }
            }
        }

        // Update state for the next PlayerMoveEvent
        if (currentOnGround) {
            state.fallDistanceAtLastGroundContact = 0f;
        } else if (!currentOnGround && state.wasOnGroundLastTick) {
            state.fallDistanceAtLastGroundContact = currentFallDistance;
        }
        state.wasOnGroundLastTick = currentOnGround;
        state.lastYPositionForMidAirCheck = to.getY();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.isCancelled()) return;
        if (!(event.getEntity() instanceof Player)) return;
        if (event.getCause() != EntityDamageEvent.DamageCause.FALL) return;

        Player player = (Player) event.getEntity();
        UUID playerUUID = player.getUniqueId();
        PlayerFallState state = playerStates.get(playerUUID);

        if (state != null) {
            clearExpectedDamageTask(playerUUID); // Fall damage was taken, cancel pending check
            state.fallDistanceAtLastGroundContact = 0f;
            state.ticksFallingFastWithoutFallDistance = 0;
        }
    }

    private boolean isSafeLandingMaterial(Material material) {
        return SAFE_LANDING_MATERIALS.contains(material);
    }

    private void clearExpectedDamageTask(UUID playerUUID) {
        PlayerFallState state = playerStates.get(playerUUID);
        if (state != null && state.expectedDamageCheckTask != null) {
            if (!state.expectedDamageCheckTask.isCancelled()) {
                state.expectedDamageCheckTask.cancel();
            }
            state.expectedDamageCheckTask = null; // Nullify the reference
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        clearExpectedDamageTask(event.getPlayer().getUniqueId());
        playerStates.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        clearExpectedDamageTask(playerUUID);

        PlayerFallState state = playerStates.computeIfAbsent(playerUUID, k -> new PlayerFallState(event.getTo()));
        state.lastTeleportTimeMs = System.currentTimeMillis();
        state.wasOnGroundLastTick = player.isOnGround(); // Check ground status at destination
        state.fallDistanceAtLastGroundContact = player.getFallDistance();
        state.lastYPositionForMidAirCheck = event.getTo().getY();
        state.ticksFallingFastWithoutFallDistance = 0;
        // Ensure expectedDamageCheckTask is null for the new state after teleport
        state.expectedDamageCheckTask = null;
    }

    // Helper method for onPlayerTeleport to check ground status at the target location
    // as player.isOnGround() might not be updated yet for the 'to' location within the event.
    private boolean isOnGround(Player player, Location location) {
        return location.getBlock().getRelative(BlockFace.DOWN).getType().isSolid();
    }
}
