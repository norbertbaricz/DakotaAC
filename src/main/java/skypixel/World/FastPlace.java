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
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.FastPlace. */
public class FastPlace implements Listener {

    @Option(value = "initial-place-delay-ms", min = 0.0, max = 1.0E9)
    private static volatile long INITIAL_PLACE_DELAY_MS = 500L;

    @Option(value = "min-place-delay-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_PLACE_DELAY_MS = 75L;

    @Option(value = "tcp-burst-threshold-ms", min = 1.0, max = 1.0E9)
    private static volatile long TCP_BURST_THRESHOLD_MS = 15L;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 4;

    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactDelayMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastPlaceBuffer = new ConcurrentHashMap<>();

    public FastPlace() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM_ON) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastPlace")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "FastPlace")) return;
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
                            Diagnostics.report("FastPlace", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!dakotaAC.isCheckActive("FastPlace")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "FastPlace")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();

        long delay = exactDelayMap.getOrDefault(uuid, INITIAL_PLACE_DELAY_MS);

        int vl = fastPlaceBuffer.getOrDefault(uuid, 0);

        if (delay < MIN_PLACE_DELAY_MS) {
            vl++;
            fastPlaceBuffer.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastPlace", Lang.text("checks.FastPlace.name-fastplace"), Lang.text("checks.FastPlace.details-machine-like-block-placement-ms-delay", "delay", delay));

                if (CheckPolicy.cancel("FastPlace")) event.setCancelled(true);

                fastPlaceBuffer.put(uuid, MAX_VIOLATIONS - 1);
            }
        } else {

            if (vl > 0) {
                fastPlaceBuffer.put(uuid, vl - 1);
            }
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