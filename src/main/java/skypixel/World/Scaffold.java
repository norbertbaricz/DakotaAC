package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Scaffold implements Listener {

    // Memorie thread-safe pentru calculul asincron al delay-ului (Prevenirea lag-ului)
    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactDelayMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastPlaceBuffer = new ConcurrentHashMap<>();

    public Scaffold() {
        // Interceptăm intenția de a pune blocul direct din placa de rețea
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM_ON) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Scaffold")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            // Iertăm creativul încă de la nivel de rețea
                            if (player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();
                            long lastTime = lastPacketTime.getOrDefault(uuid, 0L);

                            // Calculăm puritatea latenței de click
                            if (lastTime > 0) {
                                exactDelayMap.put(uuid, now - lastTime);
                            }

                            lastPacketTime.put(uuid, now);

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // DELEGAREA CĂTRE BUKKIT: Analiza Geometrică și de Viteză
    // ========================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!dakotaAC.isCheckActive("Scaffold")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        Location playerLoc = player.getLocation();
        UUID uuid = player.getUniqueId();

        boolean isFlagged = false;
        String flagReason = "";
        String flagType = "";

        // --------------------------------------------------------
        // 1. HIDDEN FACE CHECK (RayTrace / Matematică Vectorială)
        // --------------------------------------------------------
        Vector faceNormal = placed.getLocation().toVector().subtract(against.getLocation().toVector());
        Vector lookVector = playerLoc.getDirection();

        // Produsul scalar (Dot Product) - Determină unghiul de intersecție
        double dotProduct = lookVector.dot(faceNormal);

        // Dacă produsul scalar este prea mare pozitiv, jucătorul atinge o față
        // a blocului pe care nu o poate vedea fizic (prin spatele blocului).
        if (dotProduct > 0.25) {
            isFlagged = true;
            flagType = "Scaffold (RayTrace)";
            flagReason = "Attempted to click a hidden block face (Dot: " + String.format("%.2f", dotProduct) + ").";
        }

        // --------------------------------------------------------
        // 2. SPRINT PARADOX (Arhitectura podurilor)
        // --------------------------------------------------------
        if (!isFlagged && player.isSprinting() && playerLoc.getPitch() > 75.0f) {
            double distanceXZ = Math.hypot(playerLoc.getX() - (placed.getX() + 0.5), playerLoc.getZ() - (placed.getZ() + 0.5));

            if (placed.getY() <= playerLoc.getBlockY() && distanceXZ < 1.2) {
                isFlagged = true;
                flagType = "Scaffold (Sprint)";
                flagReason = "Impossible sprint bridging architecture.";
            }
        }

        // --------------------------------------------------------
        // 3. FAST PLACE / TOWER CHECK (Protecția de lag pe pachete)
        // --------------------------------------------------------
        if (!isFlagged) {
            // Extragem cronometrul perfect calculat asincron pe rețea
            long delay = exactDelayMap.getOrDefault(uuid, 500L);

            if (delay < 75) {
                int vl = fastPlaceBuffer.getOrDefault(uuid, 0) + 1;
                fastPlaceBuffer.put(uuid, vl);

                if (vl > 3) {
                    isFlagged = true;
                    flagType = "Scaffold (Tower)";
                    flagReason = "Machine-like block placement (" + delay + "ms).";
                    fastPlaceBuffer.put(uuid, 0); // Resetăm pentru a preveni spam-ul
                }
            } else {
                fastPlaceBuffer.put(uuid, 0);
            }
        }

        // ========================================================
        // EXECUTAREA PEDEPSEI (Anti Ghost-Block)
        // ========================================================
        if (isFlagged) {
            flagPlayer.addFlag(player, flagType, flagReason);

            // Anulăm direct evenimentul pe server. Bukkit va face automat un update
            // forțat către client, spunându-i să șteargă blocul vizual!
            event.setCancelled(true);

            // Tăiem sprint-ul (dacă există) pentru a-l face să cadă de pe pod
            player.setSprinting(false);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenirea scurgerilor de memorie RAM
        UUID uuid = event.getPlayer().getUniqueId();
        lastPacketTime.remove(uuid);
        exactDelayMap.remove(uuid);
        fastPlaceBuffer.remove(uuid);
    }
}