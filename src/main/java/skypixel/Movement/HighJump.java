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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.HighJump. */
public class HighJump implements Listener {

    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "bounce-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long BOUNCE_IMMUNITY_MS = 2000L;
    @Option(value = "death-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long DEATH_IMMUNITY_MS = 3000L;
    @Option(value = "respawn-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long RESPAWN_IMMUNITY_MS = 1500L;

    @Option(value = "base-jump-velocity", min = 0.0, max = 1.0E9)
    private static volatile double BASE_JUMP_VELOCITY = 0.425;

    @Option(value = "jump-boost-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double JUMP_BOOST_MULTIPLIER = 0.15;

    @Option(value = "complex-block-buffer", min = 0.0, max = 1.0E9)
    private static volatile double COMPLEX_BLOCK_BUFFER = 0.6;

    @Option(value = "grace-period-ms", min = 1.0, max = 1.0E9)
    private static volatile long GRACE_PERIOD_MS = 1000L;

    private final ConcurrentHashMap<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    public HighJump() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("HighJump")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "HighJump")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            if (deltaY <= 0.0 || deltaY == 0.5) {
                                return;
                            }

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());
                                Location fromLoc = new Location(player.getWorld(), fromPos[0], fromPos[1], fromPos[2]);

                                if (player.getAllowFlight() || player.isGliding() || player.isRiptiding() || player.isInsideVehicle()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS) {
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

                                if (isInBubbleColumn(toLoc)) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                double maxJumpVelocity = BASE_JUMP_VELOCITY;
                                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                                    maxJumpVelocity += (level * JUMP_BOOST_MULTIPLIER);
                                }

                                if (isNearComplexBlock(fromLoc) || isNearComplexBlock(toLoc)) {
                                    maxJumpVelocity += COMPLEX_BLOCK_BUFFER;
                                }

                                if (player.getVelocity().getY() > maxJumpVelocity) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                if (deltaY > maxJumpVelocity) {
                                    flagPlayer.addFlag(player, "HighJump", Lang.text("checks.HighJump.name-highjump"), Lang.text("checks.HighJump.details-impossible-jump-velocity-y-speed-max", "delta-y", String.format(java.util.Locale.ROOT, "%.3f", deltaY), "max-jump-velocity", String.format(java.util.Locale.ROOT, "%.3f", maxJumpVelocity)));

                                    Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());

                                    teleportImmunity.put(uuid, System.currentTimeMillis() + GRACE_PERIOD_MS);
                                    if (CheckPolicy.setbacks("HighJump")) player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                } else {

                                    lastSafeLocation.put(uuid, toLoc);
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("HighJump", ex);
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
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            teleportImmunity.put(uuid, System.currentTimeMillis() + GRACE_PERIOD_MS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        teleportImmunity.put(uuid, System.currentTimeMillis() + DEATH_IMMUNITY_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        teleportImmunity.put(uuid, System.currentTimeMillis() + RESPAWN_IMMUNITY_MS);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        bounceCooldowns.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportImmunity.remove(uuid);
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

    private boolean isInBubbleColumn(Location loc) {
        Material blockAt = loc.getBlock().getType();
        return blockAt.name().contains("BUBBLE_COLUMN") || blockAt == Material.WATER;
    }

    private boolean isNearComplexBlock(Location loc) {
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
                    if (type.isAir()) continue;

                    String name = type.name();
                    if (name.contains("FENCE") ||
                            name.contains("CARPET") ||
                            name.contains("SNOW") ||
                            name.contains("SLAB") ||
                            name.contains("STEP") ||
                            name.contains("STAIRS") ||
                            name.contains("WALL") ||
                            name.contains("TRAPDOOR") ||
                            name.contains("BED") ||
                            name.contains("LILY") ||
                            name.contains("SCAFFOLDING")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}