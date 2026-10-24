package skypixel.World;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Original detection formulas; annotated defaults below are loaded from Modules.Nuker. */
public class Nuker implements Listener {

    @Option(value = "time-window-ms", min = 1.0, max = 1.0E9)
    private static volatile long TIME_WINDOW_MS = 1000L;

    @Option(value = "max-blocks-per-second", min = 1.0, max = 1.0E9)
    private static volatile int MAX_BLOCKS_PER_SECOND = 25;

    @Option(value = "burst-window-ms", min = 1.0, max = 1.0E9)
    private static volatile long BURST_WINDOW_MS = 50L;

    @Option(value = "max-burst-blocks", min = 1.0, max = 1.0E9)
    private static volatile int MAX_BURST_BLOCKS = 4;

    private final ConcurrentHashMap<UUID, Tracker> breakHistory = new ConcurrentHashMap<>();

    public Nuker() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Nuker")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Nuker")) return;

                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().read(0);

                            if (action != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                return;
                            }

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();

                            breakHistory.putIfAbsent(uuid, new Tracker());
                            Tracker tracker = breakHistory.get(uuid);

                            tracker.breaks.add(now);

                            while (!tracker.breaks.isEmpty() && tracker.breaks.peek() < now - TIME_WINDOW_MS) {
                                tracker.breaks.poll();
                            }

                            boolean flagged = false;
                            String reason = "";

                            int burstCount = 0;
                            for (Long time : tracker.breaks) {
                                if (now - time <= BURST_WINDOW_MS) {
                                    burstCount++;
                                }
                            }

                            if (burstCount >= MAX_BURST_BLOCKS) {
                                flagged = true;
                                reason = Lang.text("checks.Nuker.details-attempted-to-break-blocks-simultaneously-under-ms", "burst-count", burstCount, "value", BURST_WINDOW_MS);
                            }

                            int totalBreaks = tracker.breaks.size();
                            if (!flagged && totalBreaks > MAX_BLOCKS_PER_SECOND) {
                                flagged = true;
                                reason = Lang.text("checks.Nuker.details-inhuman-breaking-speed-blocks-sec", "total-breaks", totalBreaks);
                            }

                            if (flagged) {

                                if (CheckPolicy.cancel("Nuker")) event.setCancelled(true);

                                if (!tracker.isFlagged) {
                                    tracker.isFlagged = true;
                                    final String finalReason = reason;

                                    Diagnostics.sync(() -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "Nuker", Lang.text("checks.Nuker.name-nuker"), finalReason);
                                        }
                                    });
                                }

                                tracker.breaks.clear();
                            } else {
                                tracker.isFlagged = false;
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Nuker", ex);
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        breakHistory.remove(event.getPlayer().getUniqueId());
    }

    private static class Tracker {
        final ConcurrentLinkedQueue<Long> breaks = new ConcurrentLinkedQueue<>();
        boolean isFlagged = false;
    }
}