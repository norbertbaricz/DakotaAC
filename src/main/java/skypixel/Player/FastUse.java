package skypixel.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FastUse implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Timpul legal în Vanilla pentru a consuma alimente/poțiuni este de 1600ms (32 ticks).
    // Permitem 1200ms pentru a absorbi latența (Ping) și packet-loss-ul.
    private static final long MIN_CONSUME_TIME_MS = 1200L;

    // Algele uscate (Dried Kelp) se consumă dublu de repede (800ms / 16 ticks).
    // Oferim o limită adaptată de 600ms.
    private static final long MIN_KELP_TIME_MS = 600L;

    // Câte abateri iertăm înainte să dăm flag? (Absoarbe desincronizările izolate)
    private static final int MAX_VIOLATIONS = 2;
    // ==========================================

    // Folosim ConcurrentHashMap pentru scriere/citire asincronă sigură
    private final ConcurrentHashMap<UUID, Long> consumeStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastUseBuffer = new ConcurrentHashMap<>();

    public FastUse() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM,
                        PacketType.Play.Client.USE_ITEM_ON,
                        PacketType.Play.Client.BLOCK_DIG) { // Adăugat pentru a citi oprirea animației

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastUse")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            PacketType type = event.getPacketType();

                            // FIX CRITIC: Dacă jucătorul ia degetul de pe click-dreapta, ștergem memoria!
                            // Previne folosirea unui timp "vechi" pentru a abuza un InstaEat mai târziu.
                            if (type == PacketType.Play.Client.BLOCK_DIG) {
                                EnumWrappers.PlayerDigType digType = event.getPacket().getPlayerDigTypes().readSafely(0);
                                if (digType == EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
                                    consumeStartTimes.remove(uuid);
                                }
                                return;
                            }

                            // Pentru USE_ITEM și USE_ITEM_ON:
                            consumeStartTimes.put(uuid, System.currentTimeMillis());

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // DELEGAREA CĂTRE MAIN THREAD: Când serverul finalizează consumul
    // Folosim LOWEST ca să anulăm mâncatul înainte ca alte sisteme să-i dea viață.
    // ========================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!dakotaAC.isCheckActive("FastUse")) return;

        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        Material consumedItem = event.getItem().getType();

        long now = System.currentTimeMillis();
        int vl = fastUseBuffer.getOrDefault(uuid, 0);

        // Verificăm dacă avem înregistrat momentul în care a început interacțiunea
        if (consumeStartTimes.containsKey(uuid)) {

            // Calculăm timpul real (rețea -> finalizare server)
            long timeTaken = now - consumeStartTimes.get(uuid);
            long minLegalTime = MIN_CONSUME_TIME_MS;

            // Excepția jocului: Algele uscate se mănâncă mai repede
            if (consumedItem == Material.DRIED_KELP) {
                minLegalTime = MIN_KELP_TIME_MS;
            }

            // LOGICA SUPREMĂ:
            if (timeTaken < minLegalTime) {
                vl++;
                fastUseBuffer.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {
                    flagPlayer.addFlag(player, "FastUse", "Consumed " + consumedItem.name() + " abnormally fast (" + timeTaken + "ms).");
                    event.setCancelled(true);
                    consumeStartTimes.remove(uuid); // Îl forțăm să dea click din nou

                    fastUseBuffer.put(uuid, MAX_VIOLATIONS - 1); // Resetăm pentru a nu spama
                }
            } else {
                // Dacă a fost curat, îi curățăm suspiciunile
                if (vl > 0) fastUseBuffer.put(uuid, vl - 1);

                // BUG-FIX: "CHAIN EATING"
                // Jucătorul ține apăsat click. Serverul consumă item-ul, dar clientul nu va trimite
                // un nou pachet USE_ITEM pentru următorul măr. Așadar, resetăm timpul ACUM!
                consumeStartTimes.put(uuid, now);
            }

        } else {
            // InstaEat / AutoConsume Hack extrem: Forțează serverul fără să inițieze animația
            vl++;
            fastUseBuffer.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastUse (InstaEat)", "Consumed item without starting the use-animation.");
                event.setCancelled(true);
                fastUseBuffer.put(uuid, MAX_VIOLATIONS - 1);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        consumeStartTimes.remove(uuid);
        fastUseBuffer.remove(uuid);
    }
}