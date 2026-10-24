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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.NoFall. */
public class NoFall implements Listener {

    @Option(value = "fall-damage-free-distance", min = 0.0, max = 1.0E9)
    private static volatile double FALL_DAMAGE_FREE_DISTANCE = 3.0;
    @Option(value = "forced-damage-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long FORCED_DAMAGE_IMMUNITY_MS = 500L;

    @Option(value = "min-fall-dist-to-check", min = 0.0, max = 1.0E9)
    private static volatile double MIN_FALL_DIST_TO_CHECK = 2.5;
    @Option(value = "upward-velocity-reset-normal", min = 0.0, max = 1.0E9)
    private static volatile double UPWARD_VELOCITY_RESET_NORMAL = 0.4;
    @Option(value = "upward-velocity-reset-jump-boost", min = 0.0, max = 1.0E9)
    private static volatile double UPWARD_VELOCITY_RESET_JUMP_BOOST = 0.2;

    @Option(value = "teleport-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long TELEPORT_IMMUNITY_MS = 1500L;
    @Option(value = "respawn-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long RESPAWN_IMMUNITY_MS = 2000L;
    @Option(value = "death-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long DEATH_IMMUNITY_MS = 3000L;

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> realFallDistanceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Double> pendingNoGroundDamage = new ConcurrentHashMap<>();

    public NoFall() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoFall")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "NoFall")) return;
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);
                            boolean clientOnGround = PacketAccess.onGround(event.getPacket());

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                realFallDistanceMap.put(uuid, 0.0);
                                pendingNoGroundDamage.remove(uuid);
                                return;
                            }

                            double deltaY = toY - fromPos[1];
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                                        player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                                        player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                boolean isPhysicallyOnGround = isNearGround(toLoc);

                                if (isSafeLandingBlock(toLoc)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                double realFallDist = realFallDistanceMap.getOrDefault(uuid, 0.0);
                                boolean hasJumpBoost = player.hasPotionEffect(PotionEffectType.JUMP_BOOST);

                                if (deltaY < 0.0) {
                                    realFallDist += Math.abs(deltaY);
                                } else if (deltaY > 0.0 && !isPhysicallyOnGround) {
                                    double resetThreshold = hasJumpBoost ? UPWARD_VELOCITY_RESET_JUMP_BOOST : UPWARD_VELOCITY_RESET_NORMAL;
                                    if (deltaY > resetThreshold) {
                                        realFallDist = 0.0;
                                    }
                                }

                                if (!isPhysicallyOnGround) {

                                    if (realFallDist > MIN_FALL_DIST_TO_CHECK) {
                                        if (clientOnGround) {

                                            flagPlayer.addFlag(player, "NoFall", Lang.text("checks.NoFall.name-nofall-spoof"), Lang.text("checks.NoFall.details-spoofed-onground-mid-air-realfall", "real-fall-dist", String.format(java.util.Locale.ROOT, "%.2f", realFallDist)));
                                            if (CheckPolicy.enforce("NoFall")) player.setFallDistance((float) realFallDist);
                                        }
                                    }
                                    pendingNoGroundDamage.remove(uuid);
                                } else {

                                    if (realFallDist >= FALL_DAMAGE_FREE_DISTANCE) {
                                        if (!clientOnGround) {

                                            Double pending = pendingNoGroundDamage.remove(uuid);
                                            if (pending != null) {
                                                flagPlayer.addFlag(player, "NoFall", Lang.text("checks.NoFall.name-nofall-noground"), Lang.text("checks.NoFall.details-landed-but-denied-onground-realfall", "pending", String.format(java.util.Locale.ROOT, "%.2f", pending)));
                                                forceFallDamage(player, pending);
                                            } else {

                                                pendingNoGroundDamage.put(uuid, realFallDist);
                                                realFallDistanceMap.put(uuid, realFallDist);
                                                return;
                                            }
                                        }
                                    }

                                    realFallDist = 0.0;
                                    pendingNoGroundDamage.remove(uuid);
                                }

                                realFallDistanceMap.put(uuid, realFallDist);
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("NoFall", ex);
                        }
                    }
                }
        );
    }

    private void forceFallDamage(Player player, double realFallDist) {
        double damage = realFallDist - FALL_DAMAGE_FREE_DISTANCE;
        if (damage > 0) {
            teleportImmunity.put(player.getUniqueId(), System.currentTimeMillis() + FORCED_DAMAGE_IMMUNITY_MS);
            if (CheckPolicy.enforce("NoFall")) player.damage(damage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            realFallDistanceMap.put(uuid, 0.0);
            teleportImmunity.put(uuid, System.currentTimeMillis() + TELEPORT_IMMUNITY_MS);
            pendingNoGroundDamage.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + DEATH_IMMUNITY_MS);
        pendingNoGroundDamage.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + RESPAWN_IMMUNITY_MS);
        pendingNoGroundDamage.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        realFallDistanceMap.remove(uuid);
        teleportImmunity.remove(uuid);
        pendingNoGroundDamage.remove(uuid);
    }

    private boolean isNearGround(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 1.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        double feetThreshold = Math.floor(loc.getY() - 0.6);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    if (type.isAir()) continue;

                    String name = type.name();

                    if (name.contains("FENCE") || name.contains("WALL")) {
                        return true;
                    }

                    if (by >= feetThreshold) {
                        if (type.isSolid() || name.contains("SNOW") || name.contains("CARPET") || name.contains("LILY")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isSafeLandingBlock(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY() + 1.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    String name = type.name();

                    if (name.contains("WATER") || name.contains("LAVA") ||
                            name.contains("COBWEB") || name.contains("SLIME") ||
                            name.contains("HONEY") || name.contains("BED") ||
                            name.contains("LADDER") || name.contains("VINE") ||
                            name.contains("SCAFFOLDING") || name.contains("BERRY_BUSH") ||
                            name.contains("POWDER_SNOW") || name.contains("KELP") ||
                            name.contains("TWISTING_VINES") || name.contains("WEEPING_VINES")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}