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
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Sprint. */
public class Sprint implements Listener {

    @Option(value = "min-horizontal-movement", min = 0.0, max = 1.0E9)
    private static volatile double MIN_HORIZONTAL_MOVEMENT = 0.01;

    @Option(value = "max-allowed-angle", min = 0.0, max = 1.0E9)
    private static volatile double MAX_ALLOWED_ANGLE = 85.0;

    @Option(value = "max-yaw-flick-tolerance", min = 0.0, max = 1.0E9)
    private static volatile float MAX_YAW_FLICK_TOLERANCE = 25.0f;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 4;

    private final ConcurrentHashMap<UUID, Integer> omniBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastYawMap = new ConcurrentHashMap<>();

    public Sprint() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Sprint")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Sprint")) return;
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            if (!player.isSprinting() || player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                return;
                            }

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

                            boolean onGround = PacketAccess.onGround(event.getPacket());

                            double[] fromPos = lastPosMap.get(uuid);
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toZ});
                                return;
                            }

                            double moveX = toX - fromPos[0];
                            double moveZ = toZ - fromPos[1];

                            if (Math.abs(moveX) < MIN_HORIZONTAL_MOVEMENT && Math.abs(moveZ) < MIN_HORIZONTAL_MOVEMENT) {
                                return;
                            }

                            lastPosMap.put(uuid, new double[]{toX, toZ});

                            if (!onGround) {
                                decreaseBuffer(uuid);
                                return;
                            }

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                float currentYaw = player.getLocation().getYaw();
                                float lastYaw = lastYawMap.getOrDefault(uuid, currentYaw);
                                lastYawMap.put(uuid, currentYaw);

                                float deltaYaw = Math.abs(currentYaw - lastYaw) % 360;
                                if (deltaYaw > 180) {
                                    deltaYaw = 360 - deltaYaw;
                                }

                                if (deltaYaw > MAX_YAW_FLICK_TOLERANCE) {
                                    decreaseBuffer(uuid);
                                    return; 
                                }

                                Vector lookDirection = player.getEyeLocation().getDirection().setY(0).normalize();
                                Vector moveDirection = new Vector(moveX, 0, moveZ).normalize();

                                double angle = Math.toDegrees(lookDirection.angle(moveDirection));

                                if (angle > MAX_ALLOWED_ANGLE) {
                                    int vl = omniBuffer.getOrDefault(uuid, 0) + 1;
                                    omniBuffer.put(uuid, vl);

                                    if (vl > MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Sprint", Lang.text("checks.Sprint.name-sprint-omnisprint"), Lang.text("checks.Sprint.details-sprinting-sideways-backwards-angle", "angle", String.format(java.util.Locale.ROOT, "%.1f", angle)));
                                        if (CheckPolicy.enforce("Sprint")) player.setSprinting(false);
                                    }
                                } else {
                                    decreaseBuffer(uuid);
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Sprint", ex);
                        }
                    }
                }
        );
    }

    private void decreaseBuffer(UUID uuid) {
        if (omniBuffer.getOrDefault(uuid, 0) > 0) {
            omniBuffer.put(uuid, omniBuffer.get(uuid) - 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        omniBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        lastYawMap.remove(uuid);
    }
}