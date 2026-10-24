package skypixel.Movement;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
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

/** Original detection formulas; annotated defaults below are loaded from Modules.InventoryMove. */
public class InventoryMove implements Listener {

    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "velocity-exemption-squared", min = 0.0, max = 1.0E9)
    private static volatile double VELOCITY_EXEMPTION_SQUARED = 0.05;

    @Option(value = "max-gui-speed-xz", min = 0.0, max = 1.0E9)
    private static volatile double MAX_GUI_SPEED_XZ = 0.15;
    @Option(value = "max-gui-speed-y", min = 0.0, max = 1.0E9)
    private static volatile double MAX_GUI_SPEED_Y = 0.10;
    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Boolean> openGUIs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationBuffer = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> lastGroundMap = new ConcurrentHashMap<>();

    public InventoryMove() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("InventoryMove")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "InventoryMove")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            if (type == PacketType.Play.Client.WINDOW_CLICK) {
                                boolean onGround = lastGroundMap.getOrDefault(uuid, true);

                                if (player.isSprinting() && onGround) {
                                    if (CheckPolicy.cancel("InventoryMove")) event.setCancelled(true);
                                    Diagnostics.sync(() -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "InventoryMove", Lang.text("checks.InventoryMove.name-inventorymove-sprint"), Lang.text("checks.InventoryMove.details-sprinting-while-interacting-with-inventory"));
                                        }
                                    });
                                }
                                return;
                            }

                            if (player.getAllowFlight() || player.isFlying() || player.isGliding() || player.isSwimming() || player.isInsideVehicle()) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            if (!openGUIs.containsKey(uuid)) {
                                updatePositionData(event, uuid);
                                return;
                            }

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

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

                                    if (CheckPolicy.cancel("InventoryMove")) event.setCancelled(true);

                                    Diagnostics.sync(() -> {
                                        if (!player.isOnline() || player.isDead()) return;

                                        if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS || player.getVelocity().lengthSquared() > VELOCITY_EXEMPTION_SQUARED) {
                                            violationBuffer.put(uuid, 0);
                                            return;
                                        }

                                        flagPlayer.addFlag(player, "InventoryMove", Lang.text("checks.InventoryMove.name-inventorymove"), Lang.text("checks.InventoryMove.details-walking-jumping-with-gui-open-dxz", "delta-x-z", String.format(java.util.Locale.ROOT, "%.2f", deltaXZ)));
                                    });
                                }
                            } else {
                                int vl = violationBuffer.getOrDefault(uuid, 0);
                                if (vl > 0) violationBuffer.put(uuid, vl - 1);
                            }

                            if (!event.isCancelled()) {
                                updatePositionData(event, uuid);
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("InventoryMove", ex);
                        }
                    }
                }
        );
    }

    private void updatePositionData(PacketEvent event, UUID uuid) {
        double x = PacketAccess.coordinate(event.getPacket(), 0);
        double y = PacketAccess.coordinate(event.getPacket(), 1);
        double z = PacketAccess.coordinate(event.getPacket(), 2);
        boolean onGround = PacketAccess.onGround(event.getPacket());

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