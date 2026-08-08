package skypixel.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiVoid implements Listener {

    // Stocăm datele asincron pentru performanță maximă pe thread-ul de rețea
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> minHeightMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastTeleportTime = new ConcurrentHashMap<>();

    public AntiVoid() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AntiVoid")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele și starea onGround direct din pachet
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);
                            boolean onGround = event.getPacket().getBooleans().readSafely(0);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double fromY = fromPos[1];
                            double deltaY = toY - fromY;

                            // Preluăm limita asincronă a lumii (ex: 0 pentru 1.17, -64 pentru 1.18+)
                            double minHeight = minHeightMap.getOrDefault(uuid, -64.0);

                            boolean isVoidSpoof = false;
                            boolean isVoidBlink = false;

                            // --------------------------------------------------------
                            // LOGICA 1: Ground Spoof în Void
                            // --------------------------------------------------------
                            if (toY < minHeight - 5.0 && onGround) {
                                isVoidSpoof = true;
                            }

                            // --------------------------------------------------------
                            // LOGICA 2: The Rubberband (Blink din Void)
                            // --------------------------------------------------------
                            // Dacă sare din void mai mult de 5 blocuri și nu a primit recent un /tp de la admini
                            if (fromY < minHeight && deltaY > 5.0) {
                                long timeSinceTeleport = System.currentTimeMillis() - lastTeleportTime.getOrDefault(uuid, 0L);
                                if (timeSinceTeleport > 1000) {
                                    isVoidBlink = true;
                                }
                            }

                            // Dacă prindem hackerul, acționăm direct din zbor
                            if (isVoidSpoof || isVoidBlink) {

                                // 1. Anulăm pachetul pe loc!
                                // În loc să îl teleportăm înapoi, serverul îi va refuza mișcarea în sus
                                // sau starea de onGround, lăsându-l să cadă spre moarte.
                                event.setCancelled(true);

                                final boolean spoof = isVoidSpoof;

                                // 2. Trimitem flag-ul către Main Thread
                                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                    if (!player.isOnline()) return;

                                    if (spoof) {
                                        flagPlayer.addFlag(player, "AntiVoid (Ground)", "Claiming to be on the ground while in the void.");
                                    } else {
                                        flagPlayer.addFlag(player, "AntiVoid (Blink)", "Teleported back up from the void illegally (Distance: " + String.format("%.1f", deltaY) + ").");
                                    }
                                });

                                return; // Nu îi salvăm poziția falsă în memorie!
                            }

                            // Dacă pachetul e curat, actualizăm memoria
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // EVENIMENTE BUKKIT PENTRU MENȚINEREA DATELOR SINCRONIZATE
    // ========================================================

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        minHeightMap.put(player.getUniqueId(), (double) player.getWorld().getMinHeight());
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        minHeightMap.put(player.getUniqueId(), (double) player.getWorld().getMinHeight());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            // Sincronizăm locația și oferim 1 secundă de imunitate la AntiVoid Blink
            // pentru a preveni alertele false când un admin salvează un jucător cu /tp
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            lastTeleportTime.put(uuid, System.currentTimeMillis());

            // Actualizăm și în caz că teleportul schimbă lumea
            minHeightMap.put(uuid, (double) to.getWorld().getMinHeight());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        minHeightMap.remove(uuid);
        lastTeleportTime.remove(uuid);
    }
}