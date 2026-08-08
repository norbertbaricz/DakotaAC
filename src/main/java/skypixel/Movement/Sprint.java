package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Sprint implements Listener {

    private final ConcurrentHashMap<UUID, Integer> omniBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Sprint() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Sprint")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            if (!player.isSprinting() || player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                return;
                            }

                            // Extragem coordonatele noi (X la index 0, Z la index 2)
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            // Extragem starea OnGround direct din rețea!
                            boolean onGround = event.getPacket().getBooleans().readSafely(0);

                            double[] fromPos = lastPosMap.get(uuid);
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toZ});
                                return;
                            }

                            double moveX = toX - fromPos[0];
                            double moveZ = toZ - fromPos[1];

                            if (Math.abs(moveX) < 0.01 && Math.abs(moveZ) < 0.01) {
                                return;
                            }

                            lastPosMap.put(uuid, new double[]{toX, toZ});

                            // FIX 1: Dacă jucătorul este în aer, își păstrează inerția de la săritură.
                            // Îl lăsăm să se întoarcă la 180 grade în aer fără să-i dăm flag.
                            if (!onGround) {
                                // Reducem buffer-ul ca să-l "iertăm" treptat
                                if (omniBuffer.getOrDefault(uuid, 0) > 0) {
                                    omniBuffer.put(uuid, omniBuffer.get(uuid) - 1);
                                }
                                return;
                            }

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Vector lookDirection = player.getEyeLocation().getDirection().setY(0).normalize();
                                Vector moveDirection = new Vector(moveX, 0, moveZ).normalize();

                                double angle = Math.toDegrees(lookDirection.angle(moveDirection));

                                // FIX 2: Relaxăm unghiul la 85.0°.
                                // W+A (diagonala) = 45°. Hack-urile OmniSprint au 90° sau 180°.
                                if (angle > 85.0) {
                                    int vl = omniBuffer.getOrDefault(uuid, 0) + 1;
                                    omniBuffer.put(uuid, vl);

                                    if (vl > 4) {
                                        flagPlayer.addFlag(player, "Sprint", "[OmniSprint] Sprinting sideways or backwards (Angle: " + String.format("%.1f", angle) + "°).");
                                        player.setSprinting(false);
                                    }
                                } else {
                                    if (omniBuffer.getOrDefault(uuid, 0) > 0) {
                                        omniBuffer.put(uuid, omniBuffer.get(uuid) - 1);
                                    }
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        omniBuffer.remove(uuid);
        lastPosMap.remove(uuid);
    }
}