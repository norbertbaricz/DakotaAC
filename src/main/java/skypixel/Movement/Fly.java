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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Fly. */
public class Fly implements Listener {

    @Option(value = "hover-y-tolerance", min = 0.0, max = 1.0E9)
    private static volatile double HOVER_Y_TOLERANCE = 0.001;
    @Option(value = "teleport-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long TELEPORT_IMMUNITY_MS = 1000L;

    @Option(value = "max-hover-ticks", min = 1.0, max = 1.0E9)
    private static volatile int MAX_HOVER_TICKS = 3;

    @Option(value = "max-ascension-ticks", min = 1.0, max = 1.0E9)
    private static volatile int MAX_ASCENSION_TICKS = 12;

    @Option(value = "bounce-immunity-ms", min = 1.0, max = 1.0E9)
    private static volatile long BOUNCE_IMMUNITY_MS = 2500L;

    private final ConcurrentHashMap<UUID, Integer> hoverTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> ascendTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> bounceImmunity = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Fly() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fly")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Fly")) return;
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                                return;
                            }

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());

                                if (player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming() ||
                                        player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.JUMP_BOOST) ||
                                        player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                if (isNearGround(toLoc)) {
                                    if (isBouncyBlock(toLoc)) {
                                        bounceImmunity.put(uuid, System.currentTimeMillis() + BOUNCE_IMMUNITY_MS);
                                    }
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                if (deltaY < 0.0) {
                                    hoverTicksMap.put(uuid, 0);
                                    ascendTicksMap.put(uuid, 0);
                                    return;
                                }

                                if (Math.abs(deltaY) < HOVER_Y_TOLERANCE) {
                                    int hoverTicks = hoverTicksMap.getOrDefault(uuid, 0) + 1;
                                    hoverTicksMap.put(uuid, hoverTicks);

                                    if (hoverTicks > MAX_HOVER_TICKS) {
                                        flagAndRubberband(player, uuid, Lang.text("checks.Fly.name-fly-hover"), Lang.text("checks.Fly.details-suspended-in-air-for-ticks", "hover-ticks", hoverTicks), toLoc);
                                    }
                                }

                                else if (deltaY > 0.0) {
                                    int ascendTicks = ascendTicksMap.getOrDefault(uuid, 0) + 1;
                                    ascendTicksMap.put(uuid, ascendTicks);

                                    boolean hasBounceImmunity = bounceImmunity.containsKey(uuid) && bounceImmunity.get(uuid) > System.currentTimeMillis();

                                    if (!hasBounceImmunity && ascendTicks > MAX_ASCENSION_TICKS) {
                                        flagAndRubberband(player, uuid, Lang.text("checks.Fly.name-fly-ascension"), Lang.text("checks.Fly.details-ascended-for-ticks-dy", "ascend-ticks", ascendTicks, "delta-y", String.format(java.util.Locale.ROOT, "%.4f", deltaY)), toLoc);
                                    }
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Fly", ex);
                        }
                    }
                }
        );
    }

    private void flagAndRubberband(Player player, UUID uuid, String hackType, String details, Location badLoc) {
        flagPlayer.addFlag(player, "Fly", hackType, details);

        Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());
        if (CheckPolicy.setbacks("Fly")) player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

        hoverTicksMap.put(uuid, 0);
        ascendTicksMap.put(uuid, 0);
    }

    private void resetTicksAndSafeLocation(UUID uuid, Location loc) {
        hoverTicksMap.put(uuid, 0);
        ascendTicksMap.put(uuid, 0);
        lastSafeLocation.put(uuid, loc);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            resetTicksAndSafeLocation(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            bounceImmunity.put(uuid, System.currentTimeMillis() + TELEPORT_IMMUNITY_MS);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hoverTicksMap.remove(uuid);
        ascendTicksMap.remove(uuid);
        bounceImmunity.remove(uuid);
        lastSafeLocation.remove(uuid);
        lastPosMap.remove(uuid);
    }

    private boolean isNearGround(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);

        int minY = (int) Math.floor(loc.getY() - 1.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = loc.getWorld().getBlockAt(bx, by, bz);
                    Material type = block.getType();

                    if (type.isAir()) continue;

                    String name = type.name();

                    if (name.contains("FENCE") || name.contains("WALL")) {
                        return true;
                    }

                    if (by >= Math.floor(loc.getY() - 0.6)) {
                        if (type.isSolid() || name.contains("SNOW") || name.contains("CARPET") || name.contains("SLAB") ||
                                name.contains("STEP") || name.contains("STAIRS") || name.contains("LILY") || name.contains("WATER") ||
                                name.contains("LAVA") || name.contains("LADDER") || name.contains("VINE") || name.contains("LEAVES") ||
                                name.contains("ICE")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isBouncyBlock(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY());
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    String name = loc.getWorld().getBlockAt(bx, by, bz).getType().name();
                    if (name.contains("SLIME") || name.contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}