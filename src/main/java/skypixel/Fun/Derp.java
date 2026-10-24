package skypixel.Fun;

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
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Derp. */
public class Derp implements Listener {

    @Option(value = "max-pitch", min = 0.0, max = 1.0E9)
    private static volatile float MAX_PITCH = 90.1f;

    @Option(value = "max-yaw-swipe", min = 0.0, max = 1.0E9)
    private static volatile float MAX_YAW_SWIPE = 150.0f;
    @Option(value = "combined-yaw-swipe", min = 0.0, max = 1.0E9)
    private static volatile float COMBINED_YAW_SWIPE = 100.0f;
    @Option(value = "combined-pitch-swipe", min = 0.0, max = 1.0E9)
    private static volatile float COMBINED_PITCH_SWIPE = 40.0f;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Integer> derpBuffer = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, float[]> lastRotation = new ConcurrentHashMap<>();

    public Derp() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.LOOK,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Derp")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Derp")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            float currentYaw = PacketAccess.rotation(event.getPacket(), 0);
                            float currentPitch = PacketAccess.rotation(event.getPacket(), 1);

                            if (Math.abs(currentPitch) > MAX_PITCH) {

                                if (CheckPolicy.cancel("Derp")) event.setCancelled(true);

                                Diagnostics.sync(() -> {
                                    if (player.isOnline() && !player.isDead()) {
                                        flagPlayer.addFlag(player, "Derp", Lang.text("checks.Derp.name-derp-pitch"), Lang.text("checks.Derp.details-impossible-head-pitch-rotation", "current-pitch", String.format(java.util.Locale.ROOT, "%.1f", currentPitch)));
                                    }
                                });
                                return; 
                            }

                            float[] previousRotation = lastRotation.get(uuid);

                            if (previousRotation != null) {
                                float previousYaw = previousRotation[0];
                                float previousPitch = previousRotation[1];

                                float deltaYaw = Math.abs(currentYaw - previousYaw);
                                float deltaPitch = Math.abs(currentPitch - previousPitch);

                                deltaYaw = deltaYaw % 360.0f;
                                if (deltaYaw > 180.0f) {
                                    deltaYaw = 360.0f - deltaYaw;
                                }

                                int vl = derpBuffer.getOrDefault(uuid, 0);

                                if ((deltaYaw > COMBINED_YAW_SWIPE && deltaPitch > COMBINED_PITCH_SWIPE) || deltaYaw > MAX_YAW_SWIPE) {
                                    vl++;
                                    derpBuffer.put(uuid, vl);

                                    if (vl > MAX_VIOLATIONS) {

                                        if (CheckPolicy.cancel("Derp")) event.setCancelled(true);

                                        Diagnostics.sync(() -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "Derp", Lang.text("checks.Derp.name-derp"), Lang.text("checks.Derp.details-erratic-and-unnatural-head-rotations"));
                                            }
                                        });

                                        derpBuffer.put(uuid, MAX_VIOLATIONS - 1);
                                        return; 
                                    }
                                } else {

                                    if (vl > 0) {
                                        derpBuffer.put(uuid, vl - 1);
                                    }
                                }
                            }

                            lastRotation.put(uuid, new float[]{currentYaw, currentPitch});

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Derp", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {

        if (event.isCancelled()) return;
        lastRotation.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        lastRotation.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        derpBuffer.remove(uuid);
        lastRotation.remove(uuid);
    }
}