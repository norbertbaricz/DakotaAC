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
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Step. */
public class Step implements Listener {

    @Option(value = "max-tracked-delta-y", min = 0.0, max = 1.0E9)
    private static volatile double MAX_TRACKED_DELTA_Y = 10.0;
    @Option(value = "teleport-grace-ms", min = 1.0, max = 1.0E9)
    private static volatile long TELEPORT_GRACE_MS = 1000L;
    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "velocity-exemption-y", min = 0.0, max = 1.0E9)
    private static volatile double VELOCITY_EXEMPTION_Y = 0.43;
    @Option(value = "bounce-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long BOUNCE_IMMUNITY_MS = 2000L;

    @Option(value = "base-jump-y", min = 0.0, max = 1.0E9)
    private static volatile double BASE_JUMP_Y = 0.425;

    @Option(value = "max-step-y", min = 0.0, max = 1.0E9)
    private static volatile double MAX_STEP_Y = 0.6;

    @Option(value = "jump-boost-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double JUMP_BOOST_MULTIPLIER = 0.15;

    @Option(value = "max-world-coordinate", min = 0.0, max = 1.0E9)
    private static volatile double MAX_WORLD_COORDINATE = 3.0E7;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 2;

    private final ConcurrentHashMap<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportGrace = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> stepBuffer = new ConcurrentHashMap<>();

    public Step() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Step")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Step")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

                            if (Double.isNaN(toX) || Double.isNaN(toY) || Double.isNaN(toZ) ||
                                    Double.isInfinite(toX) || Double.isInfinite(toY) || Double.isInfinite(toZ) ||
                                    Math.abs(toX) > MAX_WORLD_COORDINATE || Math.abs(toZ) > MAX_WORLD_COORDINATE) {
                                if (CheckPolicy.cancel("Step")) event.setCancelled(true);
                                return;
                            }

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];

                            if (deltaY > MAX_TRACKED_DELTA_Y || deltaY < -MAX_TRACKED_DELTA_Y) {
                                return;
                            }

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            if (deltaY <= 0.0) {
                                return;
                            }

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                long lastTeleport = teleportGrace.getOrDefault(uuid, 0L);
                                if (System.currentTimeMillis() - lastTeleport < TELEPORT_GRACE_MS) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isSwimming() || player.isGliding() || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS || player.getVelocity().getY() > VELOCITY_EXEMPTION_Y) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                if (isNearBouncingBlock(toLoc) || isNearBouncingBlock(fromLoc)) {
                                    bounceCooldowns.put(uuid, System.currentTimeMillis() + BOUNCE_IMMUNITY_MS);
                                }

                                if (bounceCooldowns.containsKey(uuid) && bounceCooldowns.get(uuid) > System.currentTimeMillis()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                Material currentBlock = toLoc.getBlock().getType();
                                if (currentBlock == Material.WATER || currentBlock == Material.LAVA ||
                                        currentBlock == Material.LADDER || currentBlock == Material.VINE ||
                                        currentBlock.name().contains("SCAFFOLDING") || currentBlock.name().contains("BUBBLE_COLUMN") ||
                                        currentBlock == Material.POWDER_SNOW) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                double maxAllowedY = BASE_JUMP_Y;

                                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                                    maxAllowedY += (level * JUMP_BOOST_MULTIPLIER);
                                }

                                boolean canStepNaturally = isNearStepBlock(toLoc) || isNearStepBlock(fromLoc);
                                if (canStepNaturally) {
                                    maxAllowedY = Math.max(maxAllowedY, MAX_STEP_Y);
                                }

                                int vl = stepBuffer.getOrDefault(uuid, 0);

                                if (deltaY > maxAllowedY) {
                                    vl++;
                                    stepBuffer.put(uuid, vl);

                                    if (vl >= MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Step", Lang.text("checks.Step.name-step"), Lang.text("checks.Step.details-exceeded-max-y-step-limit", "delta-y", String.format(java.util.Locale.ROOT, "%.3f", deltaY), "max-allowed-y", maxAllowedY));

                                        teleportGrace.put(uuid, System.currentTimeMillis() + TELEPORT_GRACE_MS);

                                        Location safe = lastSafeLocation.getOrDefault(uuid, fromLoc);
                                        if (CheckPolicy.setbacks("Step")) player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        stepBuffer.put(uuid, 1);
                                    }
                                } else {

                                    if (vl > 0) stepBuffer.put(uuid, vl - 1);
                                    lastSafeLocation.put(uuid, toLoc);
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Step", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            teleportGrace.put(uuid, System.currentTimeMillis()); 
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        teleportGrace.put(uuid, System.currentTimeMillis());

        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        bounceCooldowns.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
        stepBuffer.remove(uuid);
    }

    private boolean isNearBouncingBlock(Location loc) {
        int x = loc.getBlockX();
        int minY = loc.getBlockY() - 2;
        int maxY = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    if (type == Material.SLIME_BLOCK || type.name().contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isNearStepBlock(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = y - 1; dy <= y; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    String name = type.name();

                    if (name.contains("SLAB") || name.contains("STAIRS") || name.contains("STEP") ||
                            name.contains("SNOW") || name.contains("BED") || name.contains("DAYLIGHT_DETECTOR") ||
                            name.contains("PATH") || name.contains("FARMLAND") || name.contains("CARPET") ||
                            name.contains("CAMPFIRE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}