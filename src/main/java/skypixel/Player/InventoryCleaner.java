package skypixel.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class InventoryCleaner implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Fereastra de timp pentru calculul acțiunilor (1 secundă absoarbe perfect lag-ul de rețea)
    private static final long TIME_WINDOW_MS = 1000L;

    // Numărul maxim de acțiuni în inventar (click-uri/mutări) permis pe secundă.
    // Oamenii rapizi (Shift+Click) ating ~12-15 acțiuni/sec.
    // Un Auto-Cleaner mută 36 de iteme instant (milisecunde).
    private static final int MAX_INVENTORY_ACTIONS = 20;

    // Numărul maxim de iteme aruncate pe jos (AutoDrop / Q-Spam) pe secundă.
    // Când apeși 'Q' normal, clientul adaugă un delay intern. Macro-urile trec peste acest delay.
    private static final int MAX_ITEM_DROPS = 15;
    // ==========================================

    // Folosim ConcurrentHashMap și un obiect centralizat pentru thread-safety total
    private final ConcurrentHashMap<UUID, Tracker> trackerMap = new ConcurrentHashMap<>();

    public InventoryCleaner() {
        // 1. Interceptăm click-urile asincron pentru a proteja Main Thread-ul de Stealer/Cleaner
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("InventoryCleaner")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            trackerMap.putIfAbsent(uuid, new Tracker());
                            Tracker tracker = trackerMap.get(uuid);

                            long now = System.currentTimeMillis();

                            tracker.clicks.add(now);

                            // Curățare extrem de rapidă și asincronă, fără blocaje (Thread-Safe)
                            while (!tracker.clicks.isEmpty() && tracker.clicks.peek() < now - TIME_WINDOW_MS) {
                                tracker.clicks.poll();
                            }

                            int currentActions = tracker.clicks.size();

                            if (currentActions > MAX_INVENTORY_ACTIONS) {
                                // Dropăm pachetul! Jucătorul este blocat și nu mai poate muta itemul
                                event.setCancelled(true);

                                // Prevenim spam-ul excesiv în consolă
                                if (!tracker.clickFlagged) {
                                    tracker.clickFlagged = true;

                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "InventoryCleaner", "Inhuman inventory actions (" + currentActions + " actions/sec).");
                                        }
                                    });
                                }

                                // Resetăm parțial logica pentru a prinde din nou tura viitoare
                                tracker.clicks.clear();
                            } else {
                                tracker.clickFlagged = false;
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // 2. GESTIUNEA DROP-URILOR (AutoDrop) PE MAIN THREAD
    // Păstrăm Bukkit pentru a lăsa serverul să repare Ghost Items automat!
    // ========================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!dakotaAC.isCheckActive("InventoryCleaner")) return;

        Player player = event.getPlayer();

        // În joc, iertăm Modul Creativ de limite la aruncarea pe jos
        if (player.getGameMode().name().equals("CREATIVE")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        trackerMap.putIfAbsent(uuid, new Tracker());
        Tracker tracker = trackerMap.get(uuid);

        tracker.drops.add(now);

        while (!tracker.drops.isEmpty() && tracker.drops.peek() < now - TIME_WINDOW_MS) {
            tracker.drops.poll();
        }

        int currentDrops = tracker.drops.size();

        if (currentDrops > MAX_ITEM_DROPS) {

            // Anulăm drop-ul (Bukkit va forța instantaneu itemul înapoi în inventar)
            event.setCancelled(true);

            if (!tracker.dropFlagged) {
                tracker.dropFlagged = true;
                flagPlayer.addFlag(player, "InventoryCleaner (AutoDrop)", "Inhuman item drop rate (" + currentDrops + " drops/sec).");
            }

            tracker.drops.clear();
        } else {
            tracker.dropFlagged = false;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks (scurgeri de memorie)
        trackerMap.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clasă ajutătoare care stochează evenimentele folosind Cozi Concurente,
     * garantând 0 Crash-uri și performanță maximă fără `synchronized`.
     */
    private static class Tracker {
        final ConcurrentLinkedQueue<Long> clicks = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> drops = new ConcurrentLinkedQueue<>();
        boolean clickFlagged = false;
        boolean dropFlagged = false;
    }
}