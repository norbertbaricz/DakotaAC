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

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Unghiul maxim permis între direcția de mers și direcția în care se uită jucătorul.
    // W = 0°, W+A/D = 45°. Hack-urile (OmniSprint) forțează 90° sau 180°.
    private static final double MAX_ALLOWED_ANGLE = 85.0;

    // Câte grade pe tick are voie să întoarcă camera înainte să considerăm că a făcut un "Flick"
    // Dacă întoarce camera prea repede, ignorăm unghiul ca să nu dăm kick din inerție.
    private static final float MAX_YAW_FLICK_TOLERANCE = 25.0f;

    // De câte ori are voie să greșească unghiul până să primească flag (Toleranță rețea)
    private static final int MAX_VIOLATIONS = 4;
    // ==========================================

    private final ConcurrentHashMap<UUID, Integer> omniBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> lastYawMap = new ConcurrentHashMap<>();

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
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            if (!player.isSprinting() || player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                return;
                            }

                            // Extragem coordonatele noi (X la index 0, Z la index 2)
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            // Extragem starea OnGround direct din rețea
                            boolean onGround = event.getPacket().getBooleans().readSafely(0);

                            double[] fromPos = lastPosMap.get(uuid);
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toZ});
                                return;
                            }

                            double moveX = toX - fromPos[0];
                            double moveZ = toZ - fromPos[1];

                            // Verificăm dacă mișcarea este prea mică (evităm calcule pe loc)
                            if (Math.abs(moveX) < 0.01 && Math.abs(moveZ) < 0.01) {
                                return;
                            }

                            lastPosMap.put(uuid, new double[]{toX, toZ});

                            // Dacă jucătorul este în aer, inerția e masivă. Nu verificăm unghiul.
                            if (!onGround) {
                                decreaseBuffer(uuid);
                                return;
                            }

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // --- LOGICA DE FLICK CAMERA ---
                                float currentYaw = player.getLocation().getYaw();
                                float lastYaw = lastYawMap.getOrDefault(uuid, currentYaw);
                                lastYawMap.put(uuid, currentYaw);

                                // Calculăm diferența reală de rotație (gestionând trecerea de la 360 la 0)
                                float deltaYaw = Math.abs(currentYaw - lastYaw) % 360;
                                if (deltaYaw > 180) {
                                    deltaYaw = 360 - deltaYaw;
                                }

                                // Jucătorul a rotit camera brusc. Mișcarea pe care o citim e doar inerție.
                                if (deltaYaw > MAX_YAW_FLICK_TOLERANCE) {
                                    decreaseBuffer(uuid);
                                    return; // Iertăm tick-ul acesta
                                }

                                // --- VERIFICAREA UNGHIULUI (OmniSprint) ---
                                Vector lookDirection = player.getEyeLocation().getDirection().setY(0).normalize();
                                Vector moveDirection = new Vector(moveX, 0, moveZ).normalize();

                                double angle = Math.toDegrees(lookDirection.angle(moveDirection));

                                if (angle > MAX_ALLOWED_ANGLE) {
                                    int vl = omniBuffer.getOrDefault(uuid, 0) + 1;
                                    omniBuffer.put(uuid, vl);

                                    if (vl > MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Sprint (OmniSprint)", "Sprinting sideways/backwards (Angle: " + String.format("%.1f", angle) + "°).");
                                        player.setSprinting(false);
                                    }
                                } else {
                                    decreaseBuffer(uuid);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
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