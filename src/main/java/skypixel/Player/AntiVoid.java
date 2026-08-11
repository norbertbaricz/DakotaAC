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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AntiVoid implements Listener {

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
                            if (fromY < minHeight && deltaY > 5.0) {
                                long timeSinceTeleport = System.currentTimeMillis() - lastTeleportTime.getOrDefault(uuid, 0L);
                                // Dacă au trecut mai mult de 1000ms de la ultimul TP/Respawn
                                if (timeSinceTeleport > 1000) {
                                    isVoidBlink = true;
                                }
                            }

                            if (isVoidSpoof || isVoidBlink) {
                                event.setCancelled(true);
                                final boolean spoof = isVoidSpoof;

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
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            lastTeleportTime.put(uuid, System.currentTimeMillis());
            minHeightMap.put(uuid, (double) to.getWorld().getMinHeight());
        }
    }

    // ---> EVENIMENTE NOI ADAUGATE AICI <---

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        // Resetăm timer-ul ca să îi oferim imunitate cât timp este pe ecranul de moarte
        lastTeleportTime.put(uuid, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        // Când dă click pe Respawn, actualizăm direct poziția de start pentru viitorul pachet
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        // Resetăm timer-ul pentru a reînnoi acea secundă de imunitate (1000ms)
        lastTeleportTime.put(uuid, System.currentTimeMillis());
        // Actualizăm limita lumii în caz că patul de respawn este în altă dimensiune
        minHeightMap.put(uuid, (double) to.getWorld().getMinHeight());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        minHeightMap.remove(uuid);
        lastTeleportTime.remove(uuid);
    }
}