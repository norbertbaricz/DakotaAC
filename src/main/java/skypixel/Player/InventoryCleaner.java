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

import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryCleaner implements Listener {

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

                            // Sincronizăm obiectul pentru a procesa curat asaltul de pachete asincrone
                            synchronized (tracker) {
                                tracker.clicks.add(now);

                                // Curățăm click-urile mai vechi de 500ms
                                tracker.clicks.removeIf(time -> time < now - 500);

                                // Limită: 8 acțiuni în inventar pe jumătate de secundă
                                if (tracker.clicks.size() > 8) {

                                    // Dropăm pachetul! Jucătorul este blocat.
                                    event.setCancelled(true);

                                    // Prevenim spam-ul excesiv în consolă
                                    if (!tracker.clickFlagged) {
                                        tracker.clickFlagged = true;
                                        int size = tracker.clicks.size();

                                        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "InventoryCleaner (Stealer)", "Inhuman inventory actions (" + size + " actions/0.5s).");
                                            }
                                        });
                                    }

                                    // Resetăm logica pentru a prinde din nou, dar fără să spamăm
                                    tracker.clicks.clear();
                                } else {
                                    tracker.clickFlagged = false;
                                }
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
    // Păstrăm Bukkit pentru a lăsa serverul să repare Ghost Items
    // ========================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!dakotaAC.isCheckActive("InventoryCleaner")) return;

        Player player = event.getPlayer();

        // În joc, iertăm Modul Creativ de limite la aruncarea pe jos (fiind Vanilla legit)
        if (player.getGameMode().name().equals("CREATIVE")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        trackerMap.putIfAbsent(uuid, new Tracker());
        Tracker tracker = trackerMap.get(uuid);

        synchronized (tracker) {
            tracker.drops.add(now);
            tracker.drops.removeIf(time -> time < now - 500);

            // Limită: 6 iteme dropate individual în jumătate de secundă
            if (tracker.drops.size() > 6) {

                // Anulăm drop-ul (Bukkit va forța itemul înapoi în inventar)
                event.setCancelled(true);

                if (!tracker.dropFlagged) {
                    tracker.dropFlagged = true;
                    flagPlayer.addFlag(player, "InventoryCleaner (AutoDrop)", "Inhuman item drop rate (" + tracker.drops.size() + " drops/0.5s).");
                }

                tracker.drops.clear();
            } else {
                tracker.dropFlagged = false;
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks (scurgeri de memorie)
        trackerMap.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Clasă ajutătoare pentru a lega ambele istorice la un singur UUID
     * și a asigura thread-safety-ul ușor cu `synchronized(tracker)`.
     */
    private static class Tracker {
        final LinkedList<Long> clicks = new LinkedList<>();
        final LinkedList<Long> drops = new LinkedList<>();
        boolean clickFlagged = false;
        boolean dropFlagged = false;
    }
}