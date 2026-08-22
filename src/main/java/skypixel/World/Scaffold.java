package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
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

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Produsul scalar maxim permis.
    // > 0 înseamnă că se uită în aceeași direcție cu fața blocului (imposibil).
    // Lăsăm 0.35 pentru a ierta desincronizările când jucătorul este pe muchia absolută a blocului.
    private static final double MAX_DOT_PRODUCT = 0.35;

    // Pitch-ul minim pentru a considera că se uită în jos (în grade).
    private static final float MIN_SCAFFOLD_PITCH = 75.0f;

    // Timpul minim legal (în milisecunde) între plasări pentru un "Tower".
    private static final long MIN_TOWER_DELAY_MS = 75L;

    // Filtrul de lag (Packet Stacking / TCP Burst)
    private static final long TCP_BURST_THRESHOLD_MS = 15L;

    // Câte plasări rapide (Tower) iertăm?
    private static final int MAX_TOWER_VIOLATIONS = 3;
    // ==========================================

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
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            long now = System.currentTimeMillis();
                            long lastTime = lastPacketTime.getOrDefault(uuid, 0L);

                            // Calculăm puritatea latenței de click
                            if (lastTime > 0) {
                                long delay = now - lastTime;

                                // FIX CRITIC DE REȚEA: Ignorăm TCP Bursts (pachete trimise instantaneu din cauza lag-ului)
                                if (delay > TCP_BURST_THRESHOLD_MS) {
                                    exactDelayMap.put(uuid, delay);
                                }
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
        // 1. HIDDEN FACE CHECK (Vector Dot Product)
        // --------------------------------------------------------
        // Vectorul Normal (direcția feței blocului pe care a dat click)
        Vector faceNormal = placed.getLocation().toVector().subtract(against.getLocation().toVector());
        Vector lookVector = playerLoc.getDirection();

        // Produsul scalar: Dacă e > 0, unghiul e sub 90 de grade (imposibil legitim)
        double dotProduct = lookVector.dot(faceNormal);

        if (dotProduct > MAX_DOT_PRODUCT) {
            isFlagged = true;
            flagType = "Scaffold (Angle/Face)";
            flagReason = "Attempted to click a hidden block face (Dot: " + String.format("%.2f", dotProduct) + ").";
        }

        // --------------------------------------------------------
        // 2. SPRINT PARADOX (Arhitectura podurilor)
        // --------------------------------------------------------
        // Ninja Bridge (Sneak/Unsneak) și God Bridge necesită timing perfect, dar
        // dacă jucătorul este strict în SPRINT continuu, se uită mult în jos și pune blocul direct sub el...
        if (!isFlagged && player.isSprinting() && playerLoc.getPitch() > MIN_SCAFFOLD_PITCH) {
            double distanceXZ = Math.hypot(playerLoc.getX() - (placed.getX() + 0.5), playerLoc.getZ() - (placed.getZ() + 0.5));

            // Dacă blocul este pus sub nivelul picioarelor și e extrem de aproape de centrul lui
            if (placed.getY() <= playerLoc.getBlockY() && distanceXZ < 1.0) {
                isFlagged = true;
                flagType = "Scaffold (Sprint)";
                flagReason = "Impossible sprint bridging architecture.";
            }
        }

        // --------------------------------------------------------
        // 3. TOWER CHECK / AUTO-CLICKER (Viteza asincronă perfectă)
        // --------------------------------------------------------
        if (!isFlagged) {
            long delay = exactDelayMap.getOrDefault(uuid, 500L);

            if (delay < MIN_TOWER_DELAY_MS) {
                int vl = fastPlaceBuffer.getOrDefault(uuid, 0) + 1;
                fastPlaceBuffer.put(uuid, vl);

                if (vl >= MAX_TOWER_VIOLATIONS) {
                    isFlagged = true;
                    flagType = "Scaffold (Tower)";
                    flagReason = "Machine-like block placement (" + delay + "ms).";
                    fastPlaceBuffer.put(uuid, 0); // Resetăm pentru a preveni spam-ul
                }
            } else {
                int currentVl = fastPlaceBuffer.getOrDefault(uuid, 0);
                if (currentVl > 0) fastPlaceBuffer.put(uuid, currentVl - 1);
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