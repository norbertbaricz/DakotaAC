package skypixel.Movement;

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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class InventoryMove implements Listener {

    // Folosim colecții concurente pentru a preveni crash-urile pe thread-urile asincrone Netty
    private final ConcurrentHashMap<UUID, Boolean> openGUIs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationBuffer = new ConcurrentHashMap<>();

    // Memorăm coordonatele și starea onGround din rețea
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> lastGroundMap = new ConcurrentHashMap<>();

    public InventoryMove() {
        // Interceptăm atât Mișcarea cât și Click-ul în inventar în același PacketAdapter
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("InventoryMove")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            // ==========================================
                            // LOGICA 1: SPRINT PARADOX (WINDOW_CLICK)
                            // ==========================================
                            if (type == PacketType.Play.Client.WINDOW_CLICK) {
                                // În Vanilla, nu poți sprinta și da click în inventar simultan.
                                // Verificăm starea de sprint (sigură de citit) și starea onGround din rețea.
                                boolean onGround = lastGroundMap.getOrDefault(uuid, true);

                                if (player.isSprinting() && onGround) {
                                    // Anulăm pachetul din zbor! Nu va putea muta itemul.
                                    event.setCancelled(true);

                                    // Trimitem alerta sincron pe Main Thread
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "InventoryMove (Sprint)", "Sprinting while interacting with inventory.");
                                        }
                                    });
                                }
                                return;
                            }

                            // ==========================================
                            // LOGICA 2: MIȘCAREA CU GUI EXTERN DESCHIS
                            // ==========================================
                            // Dacă nu are un GUI extern deschis, salvăm datele și ieșim.
                            if (!openGUIs.containsKey(uuid)) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // Dacă nu avem date vechi, le înregistrăm și așteptăm pachetul următor
                            if (fromPos == null) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Ignorăm complet mișcările de cap (cameră)
                            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                                return;
                            }

                            // Ignorăm căderea (gravitația normală) sau situațiile legitime
                            if (deltaY < 0 || player.isInsideVehicle() || player.isSwimming() || player.isGliding()) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            // Când un jucător merge intenționat dXZ depășește 0.15. Saltul face dY pozitiv.
                            if (deltaY > 0.1 || deltaXZ > 0.15) {
                                int vl = violationBuffer.getOrDefault(uuid, 0) + 1;
                                violationBuffer.put(uuid, vl);

                                // Folosim VL 3 pentru a ierta situațiile în care pachetele de mișcare
                                // s-au "intersectat" pe rețea cu pachetul de deschidere a cufărului (Ping Lag).
                                if (vl > 3) {
                                    // Anulăm pachetul -> Jucătorul e înghețat virtual
                                    event.setCancelled(true);

                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "InventoryMove", "Walking/Jumping with GUI open (dXZ: " + String.format("%.2f", deltaXZ) + ")");
                                        }
                                    });
                                }
                            } else {
                                violationBuffer.remove(uuid);
                            }

                            // Dacă pachetul n-a fost anulat, îi salvăm poziția curentă
                            if (!event.isCancelled()) {
                                updatePositionData(event, uuid);
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    /**
     * Metodă utilitară pentru a salva coordonatele și starea onGround din pachetele curente.
     */
    private void updatePositionData(PacketEvent event, UUID uuid) {
        double x = event.getPacket().getDoubles().readSafely(0);
        double y = event.getPacket().getDoubles().readSafely(1);
        double z = event.getPacket().getDoubles().readSafely(2);
        boolean onGround = event.getPacket().getBooleans().readSafely(0);

        lastPosMap.put(uuid, new double[]{x, y, z});
        lastGroundMap.put(uuid, onGround);
    }

    // ==========================================
    // EVENIMENTE BUKKIT PENTRU STAREA MENIULUI
    // ==========================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!dakotaAC.isCheckActive("InventoryMove")) return;
        if (event.isCancelled()) return;

        // Dacă deschide orice fel de inventar (Cufăr, Furnal, Dispenser) exceptând cel din propriul buzunar
        if (event.getInventory().getType() != InventoryType.CRAFTING) {
            openGUIs.put(event.getPlayer().getUniqueId(), true);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        openGUIs.remove(uuid);
        violationBuffer.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks (scurgeri de memorie)
        UUID uuid = event.getPlayer().getUniqueId();
        openGUIs.remove(uuid);
        violationBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        lastGroundMap.remove(uuid);
    }
}