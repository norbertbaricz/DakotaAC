package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
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

public class Nuker implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Fereastra de timp pentru spargerea susținută
    private static final long TIME_WINDOW_MS = 1000L;

    // Viteza inumană maximă pe secundă. Oamenii dau cam 10-15 click-uri pe secundă (CPS).
    private static final int MAX_BLOCKS_PER_SECOND = 25;

    // Fereastra de timp pentru Burst (spargere simultană a mai multor blocuri)
    private static final long BURST_WINDOW_MS = 50L;

    // Câte blocuri are voie să înceapă să spargă într-un Burst de 50ms?
    private static final int MAX_BURST_BLOCKS = 4;
    // ==========================================

    // Folosim o hartă concurentă pentru acces rapid și stabil asincron
    private final ConcurrentHashMap<UUID, Tracker> breakHistory = new ConcurrentHashMap<>();

    public Nuker() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Nuker")) return;

                            Player player = event.getPlayer();

                            // FIX CRITIC: Verificăm creativul asincron, înainte să anulăm vreun pachet!
                            // În versiunile moderne de Paper, player.getGameMode() este thread-safe.
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);

                            // Contorizăm doar intenția de a începe spargerea
                            if (action != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                return;
                            }

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();

                            breakHistory.putIfAbsent(uuid, new Tracker());
                            Tracker tracker = breakHistory.get(uuid);

                            tracker.breaks.add(now);

                            // Curățăm asincron și sigur pachetele mai vechi de 1 secundă
                            while (!tracker.breaks.isEmpty() && tracker.breaks.peek() < now - TIME_WINDOW_MS) {
                                tracker.breaks.poll();
                            }

                            boolean flagged = false;
                            String reason = "";

                            // --------------------------------------------------------
                            // LOGICA 1: BURST NUKER (Spargere instantanee pe zonă)
                            // --------------------------------------------------------
                            int burstCount = 0;
                            for (Long time : tracker.breaks) {
                                if (now - time <= BURST_WINDOW_MS) {
                                    burstCount++;
                                }
                            }

                            if (burstCount >= MAX_BURST_BLOCKS) {
                                flagged = true;
                                reason = "Attempted to break " + burstCount + " blocks simultaneously (under " + BURST_WINDOW_MS + "ms).";
                            }

                            // --------------------------------------------------------
                            // LOGICA 2: SUSTAINED NUKER (Viteză inumană per secundă)
                            // --------------------------------------------------------
                            int totalBreaks = tracker.breaks.size();
                            if (!flagged && totalBreaks > MAX_BLOCKS_PER_SECOND) {
                                flagged = true;
                                reason = "Inhuman breaking speed (" + totalBreaks + " blocks/sec).";
                            }

                            // DECIZIA FINALĂ
                            if (flagged) {
                                // 1. Oprim blocul din a fi procesat de server (Anulăm pachetul)
                                event.setCancelled(true);

                                // 2. Prevenim spam-ul către admini
                                if (!tracker.isFlagged) {
                                    tracker.isFlagged = true;
                                    final String finalReason = reason;

                                    // 3. Trimitem Flag-ul sincron (Bukkit)
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "Nuker", finalReason);
                                        }
                                    });
                                }

                                // Resetăm logica de analiză ca să lăsăm jucătorul să spargă din nou legitim
                                tracker.breaks.clear();
                            } else {
                                tracker.isFlagged = false;
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Ștergem profilul jucătorului pentru a elibera RAM-ul
        breakHistory.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clasă auxiliară dedicată stocării sigure folosind cozi concurente.
     */
    private static class Tracker {
        final ConcurrentLinkedQueue<Long> breaks = new ConcurrentLinkedQueue<>();
        boolean isFlagged = false;
    }
}