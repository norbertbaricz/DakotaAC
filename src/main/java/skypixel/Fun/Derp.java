package skypixel.Fun;

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

public class Derp implements Listener {

    // Folosim ConcurrentHashMap pentru stabilitate pe thread-ul de rețea (ProtocolLib)
    private final ConcurrentHashMap<UUID, Integer> derpBuffer = new ConcurrentHashMap<>();

    // Stocăm istoricul rotației sub forma unui array de float: [yaw, pitch]
    private final ConcurrentHashMap<UUID, float[]> lastRotation = new ConcurrentHashMap<>();

    public Derp() {
        // Interceptăm DOAR pachetele care conțin direcția privirii (capului)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.LOOK,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Derp")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem valorile din pachet.
                            // Pentru LOOK și POSITION_LOOK, primul float (0) e Yaw, al doilea (1) e Pitch.
                            float currentYaw = event.getPacket().getFloat().readSafely(0);
                            float currentPitch = event.getPacket().getFloat().readSafely(1);

                            // --------------------------------------------------------
                            // LOGICA 1: Gâtul Rupt (Impossible Pitch)
                            // --------------------------------------------------------
                            if (Math.abs(currentPitch) > 90.0f) {
                                // Anulăm pachetul direct pe rețea!
                                // Serverul nu va ști niciodată că jucătorul și-a rotit capul.
                                event.setCancelled(true);

                                // Trimiterea alertei o facem pe Main Thread
                                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                    if (player.isOnline() && !player.isDead()) {
                                        flagPlayer.addFlag(player, "Derp (Pitch)", "Impossible head pitch rotation (" + String.format("%.1f", currentPitch) + "°).");
                                    }
                                });
                                return; // Oprim procesarea aici
                            }

                            // --------------------------------------------------------
                            // LOGICA 2: SpinBot / Derp Random (Yaw & Pitch)
                            // --------------------------------------------------------
                            float[] previousRotation = lastRotation.get(uuid);

                            if (previousRotation != null) {
                                float previousYaw = previousRotation[0];
                                float previousPitch = previousRotation[1];

                                float deltaYaw = Math.abs(currentYaw - previousYaw);
                                float deltaPitch = Math.abs(currentPitch - previousPitch);

                                // Normalizăm Yaw-ul pentru a nu da flag la o trecere de la 359° la 1°
                                deltaYaw = deltaYaw % 360.0f;
                                if (deltaYaw > 180.0f) {
                                    deltaYaw = 360.0f - deltaYaw;
                                }

                                int vl = derpBuffer.getOrDefault(uuid, 0);

                                // Detectăm mișcările extrem de inumane
                                if ((deltaYaw > 100.0f && deltaPitch > 40.0f) || deltaYaw > 150.0f) {
                                    vl++;
                                    derpBuffer.put(uuid, vl);

                                    if (vl > 3) {
                                        // Anulăm mișcarea ilegală (Nu mai e nevoie de event.setTo!)
                                        event.setCancelled(true);

                                        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "Derp/SpinBot", "Erratic and unnatural head rotations.");
                                            }
                                        });

                                        derpBuffer.put(uuid, 1);
                                        return; // Dacă l-am prins, nu salvăm rotația curentă ca fiind una "validă"
                                    }
                                } else {
                                    // Dacă joacă normal, îi reducem din suspiciune
                                    if (vl > 0) {
                                        derpBuffer.put(uuid, vl - 1);
                                    }
                                }
                            }

                            // Dacă pachetul a fost curat, salvăm noua rotație pentru pachetul viitor
                            lastRotation.put(uuid, new float[]{currentYaw, currentPitch});

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim Memory Leaks
        UUID uuid = event.getPlayer().getUniqueId();
        derpBuffer.remove(uuid);
        lastRotation.remove(uuid);
    }
}