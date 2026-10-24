package skypixel.Combat;

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
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.AimBot. */
public class AimBot implements Listener {

    @Option(value = "max-valid-pitch", min = 0.0, max = 1.0E9)
    private static volatile float MAX_VALID_PITCH = 90.0f;

    @Option(value = "flick-threshold", min = 0.0, max = 1.0E9)
    private static volatile float FLICK_THRESHOLD = 20.0f;    
    @Option(value = "snap-tolerance", min = 0.0, max = 1.0E9)
    private static volatile float SNAP_TOLERANCE = 0.5f;      
    @Option(value = "snap-max-violations", min = 1.0, max = 1.0E9)
    private static volatile int SNAP_MAX_VIOLATIONS = 2;      
    @Option(value = "noise-max-violations", min = 1.0, max = 1.0E9)
    private static volatile int NOISE_MAX_VIOLATIONS = 4;     

    private final ConcurrentHashMap<UUID, Float> previousYawMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> oldYawMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Float> previousPitchMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Float> oldPitchMap = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> aimBuffer = new ConcurrentHashMap<>();

    public AimBot() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.LOOK,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AimBot")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "AimBot")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            float currentYaw = PacketAccess.rotation(event.getPacket(), 0);
                            float currentPitch = PacketAccess.rotation(event.getPacket(), 1);

                            if (currentPitch > MAX_VALID_PITCH || currentPitch < -MAX_VALID_PITCH) {
                                sendFlagSync(player, Lang.text("checks.AimBot.name-aimbot-derp"), Lang.text("checks.AimBot.details-sent-invalid-pitch-angle", "current-pitch", currentPitch));
                                if (CheckPolicy.cancel("AimBot")) event.setCancelled(true);
                                return;
                            }

                            float previousYaw = previousYawMap.getOrDefault(uuid, currentYaw);
                            float oldYaw = oldYawMap.getOrDefault(uuid, previousYaw);

                            float previousPitch = previousPitchMap.getOrDefault(uuid, currentPitch);
                            float oldPitch = oldPitchMap.getOrDefault(uuid, previousPitch);

                            float deltaYaw = Math.abs(currentYaw - previousYaw);
                            float deltaPitch = Math.abs(currentPitch - previousPitch);

                            if (deltaYaw == 0.0f && deltaPitch == 0.0f) {
                                return;
                            }

                            if (deltaYaw > FLICK_THRESHOLD || deltaPitch > FLICK_THRESHOLD) {

                                float diffToOldYaw = Math.abs(currentYaw - oldYaw);
                                float diffToOldPitch = Math.abs(currentPitch - oldPitch);

                                if (diffToOldYaw == 0.0f && diffToOldPitch == 0.0f) {
                                    int vl = aimBuffer.getOrDefault(uuid, 0) + 1;
                                    aimBuffer.put(uuid, vl);

                                    if (vl > SNAP_MAX_VIOLATIONS) {
                                        sendFlagSync(player, Lang.text("checks.AimBot.name-aimbot-snap"), Lang.text("checks.AimBot.details-robotic-3d-snap-back-detected-flick", "delta-yaw", String.format(java.util.Locale.ROOT, "%.2f", deltaYaw)));

                                        if (CheckPolicy.cancel("AimBot")) event.setCancelled(true);
                                    }
                                }

                                else if (diffToOldYaw < SNAP_TOLERANCE && diffToOldPitch < SNAP_TOLERANCE) {
                                    int vl = aimBuffer.getOrDefault(uuid, 0) + 1;
                                    aimBuffer.put(uuid, vl);

                                    if (vl > NOISE_MAX_VIOLATIONS) {
                                        sendFlagSync(player, Lang.text("checks.AimBot.name-aimbot-aura"), Lang.text("checks.AimBot.details-unnatural-target-switching-detected-yaw-diff", "diff-to-old-yaw", String.format(java.util.Locale.ROOT, "%.3f", diffToOldYaw)));
                                    }
                                }
                            } else {

                                int vl = aimBuffer.getOrDefault(uuid, 0);
                                if (vl > 0) {

                                    aimBuffer.put(uuid, vl - 1);
                                }
                            }

                            oldYawMap.put(uuid, previousYaw);
                            previousYawMap.put(uuid, currentYaw);

                            oldPitchMap.put(uuid, previousPitch);
                            previousPitchMap.put(uuid, currentPitch);

                        } catch (Exception | LinkageError ex) { Diagnostics.report("AimBot", ex); }
                    }
                }
        );
    }

    private void sendFlagSync(Player player, String cheatName, String details) {
        Diagnostics.sync(() -> {
            if (player.isOnline()) {
                flagPlayer.addFlag(player, "AimBot", cheatName, details);
            }
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid = event.getPlayer().getUniqueId();

        previousYawMap.remove(uuid);
        oldYawMap.remove(uuid);

        previousPitchMap.remove(uuid);
        oldPitchMap.remove(uuid);

        aimBuffer.remove(uuid);
    }
}