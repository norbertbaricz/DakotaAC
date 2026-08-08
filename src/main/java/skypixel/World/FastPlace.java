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
                            if (player == null) return;

                            // Jucătorii pe creativ au mecanici de plasare diferite, îi ignorăm total
                            if (player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();

                            long lastTime = lastPacketTime.getOrDefault(uuid, 0L);

                            if (lastTime > 0) {
                                // Calculăm întârzierea exactă pe rețea, neafectată de lag-ul Bukkit
                                long delay = now - lastTime;
                                exactDelayMap.put(uuid, delay);
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
    // ========================================================

    // Folosim prioritatea LOWEST pentru a opri plasarea înaintea altor pluginuri (ex: protecții)
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!dakotaAC.isCheckActive("FastPlace")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();

        // Extragem delay-ul pur calculat de placa de rețea.
        // Default la 500ms pentru siguranță în cazul primului bloc pus pe sesiune.
        long delay = exactDelayMap.getOrDefault(uuid, 500L);

        // Dacă blocul este pus la mai puțin de 75 de milisecunde
        if (delay < 75) {
            int vl = fastPlaceBuffer.getOrDefault(uuid, 0) + 1;
            fastPlaceBuffer.put(uuid, vl);

            // La sub 75ms (aprox 13-14 CPS constanți fix în coordonatele corecte), este AutoClicker/FastPlace clar
            if (vl > 4) {
                flagPlayer.addFlag(player, "FastPlace", "Machine-like block placement (" + delay + "ms delay).");

                // Anulăm evenimentul nativ, forțând Bukkit să șteargă blocul clientului (Anti Ghost-Block)
                event.setCancelled(true);

                // Reducem bufferul pentru a limita spam-ul de flag-uri în consolă
                fastPlaceBuffer.put(uuid, 2);
            }
        } else {
            // Dacă ritmul este uman, iertăm jucătorul curatând treptat suspiciunile
            int currentVl = fastPlaceBuffer.getOrDefault(uuid, 0);
            if (currentVl > 0) {
                fastPlaceBuffer.put(uuid, currentVl - 1);
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