package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AimBot implements Listener {

    // =========================================================
    // --- Easy-to-tune thresholds ---
    // =========================================================
    private static final float FLICK_THRESHOLD = 20.0f;    // Câte grade înseamnă o mișcare "bruscă"
    private static final float SNAP_TOLERANCE = 0.5f;      // Toleranța de grade pentru a considera un snap "perfect"
    private static final int SNAP_MAX_VIOLATIONS = 2;      // După câte snap-uri perfecte primește flag
    private static final int NOISE_MAX_VIOLATIONS = 4;     // După câte snap-uri imperfecte (cu zgomot) primește flag
    // =========================================================

    // Stocăm stările de rotație (pe ambele axe) pentru a analiza secvențele de mișcare
    private final ConcurrentHashMap<UUID, Float> previousYawMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> oldYawMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Float> previousPitchMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> oldPitchMap = new ConcurrentHashMap<>();

    // Buffer de Violations pentru a evita alertele false accidentale
    private final ConcurrentHashMap<UUID, Integer> aimBuffer = new ConcurrentHashMap<>();

    public AimBot() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.LOOK,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AimBot")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // În pachetele de Look, Yaw este la indexul 0, iar Pitch este la indexul 1
                            float currentYaw = event.getPacket().getFloat().readSafely(0);
                            float currentPitch = event.getPacket().getFloat().readSafely(1);

                            // 1. VERIFICARE "DERP" / "ANTI-AIM" (Imposibilitate geometrică)
                            // Un client vanilla nu poate privi mai sus de 90 de grade sau mai jos de -90.
                            if (currentPitch > 90.0f || currentPitch < -90.0f) {
                                sendFlagSync(player, "AimBot (Derp)", "Sent invalid pitch angle: " + currentPitch + "°");
                                event.setCancelled(true);
                                return;
                            }

                            // Extragem istoricul
                            float previousYaw = previousYawMap.getOrDefault(uuid, currentYaw);
                            float oldYaw = oldYawMap.getOrDefault(uuid, previousYaw);

                            float previousPitch = previousPitchMap.getOrDefault(uuid, currentPitch);
                            float oldPitch = oldPitchMap.getOrDefault(uuid, previousPitch);

                            // Calculăm diferența de la ultimul pachet (cât de bruscă a fost mișcarea)
                            float deltaYaw = Math.abs(currentYaw - previousYaw);
                            float deltaPitch = Math.abs(currentPitch - previousPitch);

                            // Dacă diferențele sunt 0, jucătorul doar s-a mișcat (Walk), nu a rotit capul
                            if (deltaYaw == 0.0f && deltaPitch == 0.0f) {
                                return;
                            }

                            // 2. VERIFICARE "SILENT AURA" / "SNAP"
                            // Daca miscarea a fost extrem de brusca pe oricare din axe
                            if (deltaYaw > FLICK_THRESHOLD || deltaPitch > FLICK_THRESHOLD) {

                                // Verificăm dacă camera s-a întors EXACT în punctul în care era acum 2 pachete
                                // Asta prinde hackurile care se uita la inamic 1 milisecunda sa dea hit, apoi pun camera la loc
                                float diffToOldYaw = Math.abs(currentYaw - oldYaw);
                                float diffToOldPitch = Math.abs(currentPitch - oldPitch);

                                // Daca revine perfect (100% Snap Robotic)
                                if (diffToOldYaw == 0.0f && diffToOldPitch == 0.0f) {
                                    int vl = aimBuffer.getOrDefault(uuid, 0) + 1;
                                    aimBuffer.put(uuid, vl);

                                    if (vl > SNAP_MAX_VIOLATIONS) {
                                        sendFlagSync(player, "AimBot (Snap)", "Robotic 3D snap-back detected (Flick: " + String.format("%.2f", deltaYaw) + "°)");

                                        // Anulam pachetul - creeaza rubber-band pe camera, stricand hit-urile codatului
                                        event.setCancelled(true);
                                    }
                                }
                                // Daca hack-ul are zgomot adaugat (Randomized Noise pentru a pacali AntiCheat-urile)
                                else if (diffToOldYaw < SNAP_TOLERANCE && diffToOldPitch < SNAP_TOLERANCE) {
                                    int vl = aimBuffer.getOrDefault(uuid, 0) + 1;
                                    aimBuffer.put(uuid, vl);

                                    if (vl > NOISE_MAX_VIOLATIONS) {
                                        sendFlagSync(player, "AimBot (Aura)", "Unnatural target switching detected (Yaw Diff: " + String.format("%.3f", diffToOldYaw) + "°)");
                                    }
                                }
                            } else {
                                // Jucătorul mișcă camera normal și fluid, reducem buffer-ul de pedepse încetul cu încetul
                                int vl = aimBuffer.getOrDefault(uuid, 0);
                                if (vl > 0) {
                                    // Scădem câte puțin pentru a ierta "smuciturile" umane accidentale
                                    aimBuffer.put(uuid, vl - 1);
                                }
                            }

                            // Actualizăm memoria pentru următorul pachet (Shift la variabile)
                            oldYawMap.put(uuid, previousYaw);
                            previousYawMap.put(uuid, currentYaw);

                            oldPitchMap.put(uuid, previousPitch);
                            previousPitchMap.put(uuid, currentPitch);

                        } catch (Exception ex) {
                            // Ignorăm erorile interne de ProtocolLib pentru a nu polua consola
                        }
                    }
                }
        );
    }

    /**
     * Trimite alerta catre sistemul Bukkit pe Thread-ul principal.
     * Foarte important pentru a evita crash-uri de tip "Asynchronous Bukkit API Call".
     */
    private void sendFlagSync(Player player, String cheatName, String details) {
        Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> {
            if (player.isOnline()) {
                flagPlayer.addFlag(player, cheatName, details);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Curățăm complet datele pentru a preveni memory leaks (scurgeri de memorie)
        UUID uuid = event.getPlayer().getUniqueId();

        previousYawMap.remove(uuid);
        oldYawMap.remove(uuid);

        previousPitchMap.remove(uuid);
        oldPitchMap.remove(uuid);

        aimBuffer.remove(uuid);
    }
}