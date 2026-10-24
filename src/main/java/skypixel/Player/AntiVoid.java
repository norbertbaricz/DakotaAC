package skypixel.Player;

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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.AntiVoid. */
public class AntiVoid implements Listener {

    @Option(value = "void-threshold-depth", min = 0.0, max = 1.0E9)
    private static volatile double VOID_THRESHOLD_DEPTH = 5.0;

    @Option(value = "max-void-upward-distance", min = 0.0, max = 1.0E9)
    private static volatile double MAX_VOID_UPWARD_DISTANCE = 5.0;

    @Option(value = "grace-period-ms", min = 1.0, max = 1.0E9)
    private static volatile long GRACE_PERIOD_MS = 2000L;

    @Option(value = "max-world-coordinate", min = 0.0, max = 1.0E9)
    private static volatile double MAX_WORLD_COORDINATE = 3.0E7;

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> minHeightMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();

    public AntiVoid() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AntiVoid")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "AntiVoid")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);
                            boolean onGround = PacketAccess.onGround(event.getPacket());

                            if (Double.isNaN(toX) || Double.isNaN(toY) || Double.isNaN(toZ) ||
                                    Double.isInfinite(toX) || Double.isInfinite(toY) || Double.isInfinite(toZ) ||
                                    Math.abs(toX) > MAX_WORLD_COORDINATE || Math.abs(toZ) > MAX_WORLD_COORDINATE) {
                                if (CheckPolicy.cancel("AntiVoid")) event.setCancelled(true);
                                return;
                            }

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double fromY = fromPos[1];
                            double deltaY = toY - fromY;

                            double minHeight = minHeightMap.getOrDefault(uuid, (double) player.getWorld().getMinHeight());

                            boolean isVoidSpoof = false;
                            boolean isVoidBlink = false;

                            if (toY < (minHeight - VOID_THRESHOLD_DEPTH) && onGround) {
                                isVoidSpoof = true;
                            }

                            if (fromY < minHeight && deltaY > MAX_VOID_UPWARD_DISTANCE) {
                                long timeSinceTeleport = System.currentTimeMillis() - lastTeleportTime.getOrDefault(uuid, 0L);

                                if (timeSinceTeleport > GRACE_PERIOD_MS) {
                                    isVoidBlink = true;
                                }
                            }

                            if (isVoidSpoof || isVoidBlink) {
                                if (CheckPolicy.cancel("AntiVoid")) event.setCancelled(true);
                                final boolean spoof = isVoidSpoof;

                                Diagnostics.sync(() -> {
                                    if (!player.isOnline()) return;

                                    if (spoof) {
                                        flagPlayer.addFlag(player, "AntiVoid", Lang.text("checks.AntiVoid.name-antivoid-ground"), Lang.text("checks.AntiVoid.details-claiming-to-be-on-the-ground-while-in"));
                                    } else {
                                        flagPlayer.addFlag(player, "AntiVoid", Lang.text("checks.AntiVoid.name-antivoid-blink"), Lang.text("checks.AntiVoid.details-teleported-back-up-from-the-void-illegally-distance", "delta-y", String.format(java.util.Locale.ROOT, "%.1f", deltaY)));
                                    }
                                });

                                return; 
                            }

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("AntiVoid", ex);
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "AntiVoid")) return;
        minHeightMap.put(player.getUniqueId(), (double) player.getWorld().getMinHeight());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "AntiVoid")) return;
        minHeightMap.put(player.getUniqueId(), (double) player.getWorld().getMinHeight());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            lastTeleportTime.put(uuid, System.currentTimeMillis());
            minHeightMap.put(uuid, (double) to.getWorld().getMinHeight());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();

        lastTeleportTime.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});

        lastTeleportTime.put(uuid, System.currentTimeMillis());

        minHeightMap.put(uuid, (double) to.getWorld().getMinHeight());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        minHeightMap.remove(uuid);
        lastTeleportTime.remove(uuid);
    }
}