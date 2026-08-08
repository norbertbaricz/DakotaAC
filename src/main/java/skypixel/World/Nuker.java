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

import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Nuker implements Listener {

    // Folosim o hartă concurentă pentru acces rapid și stabil asincron
    private final ConcurrentHashMap<UUID, Tracker> breakHistory = new ConcurrentHashMap<>();

    public Nuker() {
        // Interceptăm pachetele brute de minare trimise de client
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Nuker")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            // Extragem acțiunea din pachet
                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);

                            // Hack-urile Nuker trimit un val de START_DESTROY_BLOCK pentru fiecare bloc din raza de acțiune.
                            // Contorizând direct intenția de start, oprim hack-ul din prima milisecundă.
                            if (action != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                return;
                            }

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();

                            breakHistory.putIfAbsent(uuid, new Tracker());
                            Tracker tracker = breakHistory.get(uuid);

                            // Sincronizăm accesul pentru a proteja LinkedList-ul de avalanșa de pachete (previne Crash-urile)
                            synchronized (tracker) {
                                tracker.breaks.add(now);

                                // Curățăm din memorie blocurile lovite cu mai mult de 1 secundă în urmă
                                tracker.breaks.removeIf(time -> time < now - 1000);

                                boolean flagged = false;
                                String reason = "";

                                // --------------------------------------------------------
                                // LOGICA 1: BURST NUKER (Spargere instantanee pe zonă)
                                // --------------------------------------------------------
                                if (tracker.breaks.size() >= 4) {
                                    long timeForLast4Blocks = now - tracker.breaks.get(tracker.breaks.size() - 4);

                                    // A atins 4 blocuri noi în sub 50ms (Imposibil fără mouse macro sau hack)
                                    if (timeForLast4Blocks < 50) {
                                        flagged = true;
                                        reason = "Broke 4 blocks simultaneously (" + timeForLast4Blocks + "ms).";
                                    }
                                }

                                // --------------------------------------------------------
                                // LOGICA 2: SUSTAINED NUKER (Viteză inumană)
                                // --------------------------------------------------------
                                if (!flagged && tracker.breaks.size() > 25) {
                                    flagged = true;
                                    reason = "Inhuman breaking speed (" + tracker.breaks.size() + " blocks/sec).";
                                }

                                // DECIZIA
                                if (flagged) {
                                    // 1. Oprim blocul din a fi procesat de server (Anulăm pachetul pe loc)
                                    event.setCancelled(true);

                                    // 2. Prevenim spam-ul pe chat-ul adminilor
                                    if (!tracker.isFlagged) {
                                        tracker.isFlagged = true;
                                        final String finalReason = reason;

                                        // 3. Trimitem Flag-ul sincron (Bukkit)
                                        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                            if (player.isOnline()) {
                                                // Iertăm creativul aici, pe thread-ul principal, fiind metoda 100% sigură
                                                if (player.getGameMode() == GameMode.CREATIVE) return;

                                                flagPlayer.addFlag(player, "Nuker", finalReason);
                                            }
                                        });
                                    }

                                    // Resetăm memoria pentru a opri fluxul, dar continuăm să anulăm dacă insistă
                                    tracker.breaks.clear();
                                } else {
                                    tracker.isFlagged = false;
                                }
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
     * Clasă auxiliară dedicată stocării sigure.
     */
    private static class Tracker {
        final LinkedList<Long> breaks = new LinkedList<>();
        boolean isFlagged = false;
    }
}