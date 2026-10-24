package skypixel.Movement;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Speed. */
public class Speed implements Listener {

    @Option(value = "max-world-coordinate", min = 0.0, max = 1.0E9)
    private static volatile double MAX_WORLD_COORDINATE = 3.0E7;
    @Option(value = "max-tracked-movement", min = 0.0, max = 1.0E9)
    private static volatile double MAX_TRACKED_MOVEMENT = 10.0;
    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "velocity-exemption", min = 0.0, max = 1.0E9)
    private static volatile double VELOCITY_EXEMPTION = 0.05;
    @Option(value = "speed-potion-allowance", min = 0.0, max = 1.0E9)
    private static volatile double SPEED_POTION_ALLOWANCE = 0.06;
    @Option(value = "instant-speed-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double INSTANT_SPEED_MULTIPLIER = 2.5;
    @Option(value = "post-flag-buffer", min = 0.0, max = 1.0E9)
    private static volatile double POST_FLAG_BUFFER = 0.5;
    @Option(value = "teleport-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long TELEPORT_IMMUNITY_MS = 1000L;

    @Option(value = "base-speed-limit", min = 0.0, max = 1.0E9)
    private static volatile double BASE_SPEED_LIMIT = 0.36; 
    @Option(value = "max-buffer", min = 0.0, max = 1.0E9)
    private static volatile double MAX_BUFFER = 1.5; 
    @Option(value = "buffer-decay", min = 0.0, max = 1.0E9)
    private static volatile double BUFFER_DECAY = 0.05; 

    @Option(value = "ice-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double ICE_MULTIPLIER = 1.8;
    @Option(value = "slab-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double SLAB_MULTIPLIER = 1.3;
    @Option(value = "headhitter-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double HEADHITTER_MULTIPLIER = 1.5;

    private final ConcurrentHashMap<UUID, Double> speedBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportGrace = new ConcurrentHashMap<>();

    public Speed() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Speed")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Speed")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

                            if (Double.isNaN(toX) || Double.isNaN(toZ) || Math.abs(toX) > MAX_WORLD_COORDINATE || Math.abs(toZ) > MAX_WORLD_COORDINATE) {
                                if (CheckPolicy.cancel("Speed")) event.setCancelled(true);
                                return;
                            }

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaZ = toZ - fromPos[2];
                            final double deltaXZ = Math.hypot(deltaX, deltaZ);

                            if (deltaXZ > MAX_TRACKED_MOVEMENT || deltaXZ == 0.0) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (teleportGrace.containsKey(uuid) && teleportGrace.get(uuid) > System.currentTimeMillis()) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                if (player.isFlying() || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                                        player.isInsideVehicle() || player.isSwimming() || player.isGliding() || player.isSleeping()) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS || Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ()) > VELOCITY_EXEMPTION) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2]);
                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                double speedLimit = BASE_SPEED_LIMIT;

                                for (PotionEffect effect : player.getActivePotionEffects()) {
                                    if (effect.getType().equals(PotionEffectType.SPEED)) {
                                        speedLimit += (SPEED_POTION_ALLOWANCE * (effect.getAmplifier() + 1));
                                    }
                                }

                                boolean onIce = isBlockIce(fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType()) ||
                                        isBlockIce(fromLoc.clone().subtract(0, 1.0, 0).getBlock().getType());
                                boolean underBlock = !fromLoc.clone().add(0, 2.0, 0).getBlock().getType().isAir();
                                boolean onStairs = isStairOrSlab(fromLoc) || isStairOrSlab(toLoc);

                                if (onIce) {
                                    speedLimit *= ICE_MULTIPLIER;
                                } else if (onStairs) {
                                    speedLimit *= SLAB_MULTIPLIER;
                                } else if (underBlock) {
                                    speedLimit *= HEADHITTER_MULTIPLIER;
                                }

                                double buffer = speedBuffer.getOrDefault(uuid, 0.0);

                                if (deltaXZ > speedLimit) {
                                    buffer += (deltaXZ - speedLimit);

                                    if (deltaXZ > speedLimit * INSTANT_SPEED_MULTIPLIER) {
                                        flagPlayer.addFlag(player, "Speed", Lang.text("checks.Speed.name-speed-instant"), Lang.text("checks.Speed.details-absurd-momentum", "delta-x-z", String.format(java.util.Locale.ROOT, "%.2f", deltaXZ)));
                                        if (CheckPolicy.setbacks("Speed")) player.teleport(lastSafeLocation.getOrDefault(uuid, fromLoc), PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        speedBuffer.put(uuid, 0.0);
                                        return;
                                    }

                                    if (buffer > MAX_BUFFER) {
                                        flagPlayer.addFlag(player, "Speed", Lang.text("checks.Speed.name-speed-strafe-bhop"), Lang.text("checks.Speed.details-sustained-unnatural-speed-buffer", "buffer", String.format(java.util.Locale.ROOT, "%.2f", buffer)));
                                        if (CheckPolicy.setbacks("Speed")) player.teleport(lastSafeLocation.getOrDefault(uuid, fromLoc), PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        buffer = POST_FLAG_BUFFER; 
                                    }
                                } else {

                                    buffer = Math.max(0.0, buffer - BUFFER_DECAY);
                                    lastSafeLocation.put(uuid, toLoc); 
                                }

                                speedBuffer.put(uuid, buffer);
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Speed", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (event.getTo() != null) {
            teleportGrace.put(uuid, System.currentTimeMillis() + TELEPORT_IMMUNITY_MS);
            lastSafeLocation.put(uuid, event.getTo());
            lastPosMap.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        teleportGrace.put(uuid, System.currentTimeMillis() + TELEPORT_IMMUNITY_MS);
        lastSafeLocation.put(uuid, event.getRespawnLocation());
        lastPosMap.remove(uuid);
        speedBuffer.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        speedBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
    }

    private boolean isStairOrSlab(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    String name = type.name();

                    if (name.contains("STAIRS") || name.contains("SLAB") || name.contains("STEP")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isBlockIce(Material material) {
        return material.name().contains("ICE");
    }
}