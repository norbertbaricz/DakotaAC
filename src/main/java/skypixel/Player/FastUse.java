package skypixel.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FastUse implements Listener {

    // Folosim ConcurrentHashMap pentru scriere/citire asincronă sigură
    private final ConcurrentHashMap<UUID, Long> consumeStartTimes = new ConcurrentHashMap<>();

    public FastUse() {
        // Interceptăm direct de pe rețea momentul în care jucătorul apasă click dreapta (în aer sau pe un bloc)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM,
                        PacketType.Play.Client.USE_ITEM_ON) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastUse")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            // Indiferent ce item ține în mână, înregistrăm o ștampilă de timp extrem de ușoară.
                            // Acest proces nu consumă absolut nicio resursă, fiind doar o atribuire de Long.
                            consumeStartTimes.put(player.getUniqueId(), System.currentTimeMillis());

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // DELEGAREA CĂTRE MAIN THREAD: Când serverul finalizează consumul
    // ========================================================
    @EventHandler
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!dakotaAC.isCheckActive("FastUse")) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Material consumedItem = event.getItem().getType();

        long now = System.currentTimeMillis();

        // Verificăm dacă avem înregistrat momentul în care a început interacțiunea
        if (consumeStartTimes.containsKey(uuid)) {

            // Calculăm timpul real (rețea -> finalizare server)
            long timeTaken = now - consumeStartTimes.get(uuid);

            // Limita minimă legală (1.6 secunde în Vanilla = 1600ms).
            // Lăsăm o marjă de 400ms pentru lag/desincronizare de internet.
            long minLegalTime = 1200;

            // Excepția jocului: Algele uscate (Dried Kelp) se mănâncă de două ori mai repede
            if (consumedItem.name().equals("DRIED_KELP")) {
                minLegalTime = 600; // Marjă sigură de 0.6 secunde
            }

            // LOGICA SUPREMĂ:
            if (timeTaken < minLegalTime) {

                flagPlayer.addFlag(player, "FastUse", "Consumed " + consumedItem.name() + " abnormally fast (" + timeTaken + "ms).");

                // Pedeapsa: Anulăm consumarea. Item-ul îi rămâne în mână!
                event.setCancelled(true);

                // Îi ștergem timpul ca să-l forțăm să dea click din nou
                consumeStartTimes.remove(uuid);
            } else {
                // BUG-FIX: "CHAIN EATING"
                // Dacă a mâncat legitim și ține apăsat click în continuare pentru a mânca următorul item,
                // clientul nu va mai trimite un nou pachet USE_ITEM. De aceea, îi resetăm timpul ACUM,
                // ca să aibă o bază corectă de măsurare pentru următoarea bucată!
                consumeStartTimes.put(uuid, now);
            }

        } else {
            // InstaEat / AutoConsume Hack (Trimite pachetul de consum finalizat fără să apese click de început)
            flagPlayer.addFlag(player, "FastUse (InstaEat)", "Consumed item instantly without starting animation.");
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim scurgerile de memorie
        consumeStartTimes.remove(event.getPlayer().getUniqueId());
    }
}