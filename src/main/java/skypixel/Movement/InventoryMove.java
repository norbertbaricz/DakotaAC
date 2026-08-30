package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
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

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    private static final double MAX_GUI_SPEED_XZ = 0.15;
    private static final double MAX_GUI_SPEED_Y = 0.10;
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    private final ConcurrentHashMap<UUID, Boolean> openGUIs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationBuffer = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> lastGroundMap = new ConcurrentHashMap<>();

    public InventoryMove() {
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
                            // LOGICA 1: SPRINT PARADOX
                            // ==========================================
                            if (type == PacketType.Play.Client.WINDOW_CLICK) {
                                boolean onGround = lastGroundMap.getOrDefault(uuid, true);

                                if (player.isSprinting() && onGround) {
                                    event.setCancelled(true);
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

                            // === FIX NOU: EXCEPȚII ASINCRONE ===
                            // Dacă jucătorul are voie să zboare, folosește Elytra, înoată sau e într-o barcă,
                            // îi ignorăm mișcarea direct de pe placa de rețea pentru a preveni "înghețarea" ecranului!
                            if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            if (!openGUIs.containsKey(uuid)) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                                return;
                            }

                            if (deltaY < 0) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            if (deltaY > MAX_GUI_SPEED_Y || deltaXZ > MAX_GUI_SPEED_XZ) {
                                int vl = violationBuffer.getOrDefault(uuid, 0) + 1;
                                violationBuffer.put(uuid, vl);

                                if (vl > MAX_VIOLATIONS) {
                                    // Anulăm pachetul asincron doar pentru jucătorii fără grad/zbor legitim
                                    event.setCancelled(true);

                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (!player.isOnline() || player.isDead()) return;

                                        // Double-check pentru Knockback (Săgeți/Hit-uri luate în timp ce se uită în meniu)
                                        if (player.getNoDamageTicks() > 10 || player.getVelocity().lengthSquared() > 0.05) {
                                            violationBuffer.put(uuid, 0);
                                            return;
                                        }

                                        flagPlayer.addFlag(player, "InventoryMove", "Walking/Jumping with GUI open (dXZ: " + String.format("%.2f", deltaXZ) + ")");
                                    });
                                }
                            } else {
                                int vl = violationBuffer.getOrDefault(uuid, 0);
                                if (vl > 0) violationBuffer.put(uuid, vl - 1);
                            }

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

    private void updatePositionData(PacketEvent event, UUID uuid) {
        double x = event.getPacket().getDoubles().readSafely(0);
        double y = event.getPacket().getDoubles().readSafely(1);
        double z = event.getPacket().getDoubles().readSafely(2);
        boolean onGround = event.getPacket().getBooleans().readSafely(0);

        lastPosMap.put(uuid, new double[]{x, y, z});
        lastGroundMap.put(uuid, onGround);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!dakotaAC.isCheckActive("InventoryMove")) return;
        if (event.isCancelled()) return;

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
        UUID uuid = event.getPlayer().getUniqueId();
        openGUIs.remove(uuid);
        violationBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        lastGroundMap.remove(uuid);
    }
}