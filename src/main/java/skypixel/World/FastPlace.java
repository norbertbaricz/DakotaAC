package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
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

public class FastPlace implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Timpul minim legal (în milisecunde) între două click-uri dreapta.
    // 75ms = aprox. 13-14 CPS (Click-uri pe secundă).
    // Oamenii care fac "Drag Click" sau "Butterfly Click" pentru poduri pot atinge 15 CPS.
    // Pentru servere foarte competitive poți scădea la 50ms (20 CPS). Pentru Survival lasă-l la 75-80ms.
    private static final long MIN_PLACE_DELAY_MS = 75L;

    // Pragul de filtrare a lag-ului (TCP Bursts).
    // Pachetele care ajung la server cu o diferență mai mică de 15ms sunt 99% lag de rețea (Packet Stacking).
    // Nu le luăm în considerare pentru a nu pedepsi jucătorii cu internet slab.
    private static final long TCP_BURST_THRESHOLD_MS = 15L;

    // Câte blocuri puse prea rapid iertăm înainte de a acționa?
    private static final int MAX_VIOLATIONS = 4;
    // ==========================================

    // Stocăm datele asincron pentru performanță extremă și siguranță
    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactDelayMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastPlaceBuffer = new ConcurrentHashMap<>();

    public FastPlace() {
        // Interceptăm pachetul prin care clientul dă click dreapta pe un bloc
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM_ON) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastPlace")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();

                            long lastTime = lastPacketTime.getOrDefault(uuid, 0L);

                            if (lastTime > 0) {
                                // Calculăm întârzierea exactă pe rețea, neafectată de lag-ul Bukkit
                                long delay = now - lastTime;

                                // FIX CRITIC: Filtrăm Packet Stacking-ul (Lag-ul de internet)
                                // Dacă pachetele ajung aproape instantaneu unul după altul, e lag de rețea, nu un click uman real.
                                if (delay > TCP_BURST_THRESHOLD_MS) {
                                    exactDelayMap.put(uuid, delay);
                                }
                            }

                            // Actualizăm timpul pentru viitorul pachet
                            lastPacketTime.put(uuid, now);

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // DELEGAREA CĂTRE MAIN THREAD: Când serverul plasează blocul
    // Folosim LOWEST pentru a opri plasarea înaintea plugin-urilor de protecție
    // ========================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!dakotaAC.isCheckActive("FastPlace")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();

        // Extragem delay-ul pur calculat de placa de rețea.
        // Default la 500ms pentru siguranță în cazul primului bloc pus pe sesiune.
        long delay = exactDelayMap.getOrDefault(uuid, 500L);

        int vl = fastPlaceBuffer.getOrDefault(uuid, 0);

        // Dacă blocul este pus prea rapid față de capacitatea umană sau mecanica Vanilla
        if (delay < MIN_PLACE_DELAY_MS) {
            vl++;
            fastPlaceBuffer.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastPlace / Scaffold", "Machine-like block placement (" + delay + "ms delay).");

                // Anulăm evenimentul nativ. Bukkit va forța clientul să șteargă blocul fantomă.
                event.setCancelled(true);

                // Reducem buffer-ul parțial pentru a limita spam-ul de flag-uri, dar îl ținem pe radar
                fastPlaceBuffer.put(uuid, MAX_VIOLATIONS - 1);
            }
        } else {
            // Dacă ritmul este uman, iertăm jucătorul curățând treptat suspiciunile
            if (vl > 0) {
                fastPlaceBuffer.put(uuid, vl - 1);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenirea Memory Leaks (Scurgerilor de memorie)
        UUID uuid = event.getPlayer().getUniqueId();
        lastPacketTime.remove(uuid);
        exactDelayMap.remove(uuid);
        fastPlaceBuffer.remove(uuid);
    }
}