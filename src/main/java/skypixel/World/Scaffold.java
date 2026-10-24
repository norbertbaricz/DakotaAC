package skypixel.World;

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
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Scaffold. */
public class Scaffold implements Listener {

    @Option(value = "initial-place-delay-ms", min = 0.0, max = 1.0E9)
    private static volatile long INITIAL_PLACE_DELAY_MS = 500L;
    @Option(value = "sprint-bridge-distance", min = 0.0, max = 1.0E9)
    private static volatile double SPRINT_BRIDGE_DISTANCE = 1.0;

    @Option(value = "max-dot-product", min = -1.0, max = 1.0)
    private static volatile double MAX_DOT_PRODUCT = 0.35;

    @Option(value = "min-scaffold-pitch", min = 0.0, max = 1.0E9)
    private static volatile float MIN_SCAFFOLD_PITCH = 75.0f;

    @Option(value = "min-tower-delay-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_TOWER_DELAY_MS = 75L;

    @Option(value = "tcp-burst-threshold-ms", min = 1.0, max = 1.0E9)
    private static volatile long TCP_BURST_THRESHOLD_MS = 15L;

    @Option(value = "max-tower-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_TOWER_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactDelayMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastPlaceBuffer = new ConcurrentHashMap<>();

    public Scaffold() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM_ON) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Scaffold")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Scaffold")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();
                            long lastTime = lastPacketTime.getOrDefault(uuid, 0L);

                            if (lastTime > 0) {
                                long delay = now - lastTime;

                                if (delay > TCP_BURST_THRESHOLD_MS) {
                                    exactDelayMap.put(uuid, delay);
                                }
                            }

                            lastPacketTime.put(uuid, now);

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Scaffold", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!dakotaAC.isCheckActive("Scaffold")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "Scaffold")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location playerLoc = player.getLocation();
        UUID uuid = player.getUniqueId();

        boolean isFlagged = false;
        String flagReason = "";
        String flagType = "";

        Vector faceNormal = placed.getLocation().toVector().subtract(against.getLocation().toVector());
        Vector lookVector = playerLoc.getDirection();

        double dotProduct = lookVector.dot(faceNormal);

        if (dotProduct > MAX_DOT_PRODUCT) {
            isFlagged = true;
            flagType = Lang.text("checks.Scaffold.name-scaffold-angle-face");
            flagReason = Lang.text("checks.Scaffold.details-attempted-to-click-a-hidden-block-face-dot", "dot-product", String.format(java.util.Locale.ROOT, "%.2f", dotProduct));
        }

        if (!isFlagged && player.isSprinting() && playerLoc.getPitch() > MIN_SCAFFOLD_PITCH) {
            double distanceXZ = Math.hypot(playerLoc.getX() - (placed.getX() + 0.5), playerLoc.getZ() - (placed.getZ() + 0.5));

            if (placed.getY() <= playerLoc.getBlockY() && distanceXZ < SPRINT_BRIDGE_DISTANCE) {
                isFlagged = true;
                flagType = Lang.text("checks.Scaffold.name-scaffold-sprint");
                flagReason = Lang.text("checks.Scaffold.details-impossible-sprint-bridging-architecture");
            }
        }

        if (!isFlagged) {
            long delay = exactDelayMap.getOrDefault(uuid, INITIAL_PLACE_DELAY_MS);

            if (delay < MIN_TOWER_DELAY_MS) {
                int vl = fastPlaceBuffer.getOrDefault(uuid, 0) + 1;
                fastPlaceBuffer.put(uuid, vl);

                if (vl >= MAX_TOWER_VIOLATIONS) {
                    isFlagged = true;
                    flagType = Lang.text("checks.Scaffold.name-scaffold-tower");
                    flagReason = Lang.text("checks.Scaffold.details-machine-like-block-placement-ms", "delay", delay);
                    fastPlaceBuffer.put(uuid, 0); 
                }
            } else {
                int currentVl = fastPlaceBuffer.getOrDefault(uuid, 0);
                if (currentVl > 0) fastPlaceBuffer.put(uuid, currentVl - 1);
            }
        }

        if (isFlagged) {
            flagPlayer.addFlag(player, "Scaffold", flagType, flagReason);

            if (CheckPolicy.cancel("Scaffold")) event.setCancelled(true);

            if (CheckPolicy.enforce("Scaffold")) player.setSprinting(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid = event.getPlayer().getUniqueId();
        lastPacketTime.remove(uuid);
        exactDelayMap.remove(uuid);
        fastPlaceBuffer.remove(uuid);
    }
}