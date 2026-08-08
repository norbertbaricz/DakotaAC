package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KillAura implements Listener {

    private static final double MIN_DOT_PRODUCT = 0.6;

    // Salvăm datele despre unghi preluate direct din pachet (Asincron -> Sincron)
    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    // Istoricul hit-urilor pentru MultiAura / Fast Switch
    private final HashMap<UUID, HitData> hitTracker = new HashMap<>();

    public KillAura() {
        // ====================================================================
        // PASUL 1: PACHETE (ProtocolLib) - Capturăm unghiul în milisecunda click-ului
        // ====================================================================
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("KillAura")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            // Verificăm acțiunea (doar ATTACK)
                            EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().readSafely(0).getAction();
                            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                            // Salvăm vectorii de vizualizare siguranți asincron (fără getEntity() din lume)
                            Location eyeLoc = player.getEyeLocation();
                            Vector lookVector = eyeLoc.getDirection();

                            packetAimData.put(player.getUniqueId(), new ClientAimData(eyeLoc, lookVector));

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ====================================================================
    // PASUL 2: LOGICA (Bukkit API) - Verificăm unghiul, pereții și viteza de switch
    // ====================================================================
    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("KillAura")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player player = (Player) event.getDamager();

            if (player.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = player.getUniqueId();
            Entity target = event.getEntity();

            // Preluăm datele de unghi precise capturate din pachet
            ClientAimData aimData = packetAimData.get(uuid);
            if (aimData == null) return;

            // === 1. VERIFICARE ANGLE / FOV (Dot Product folosind pachetul) ===
            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            Vector directionToTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector()).normalize();
            double dotProduct = aimData.lookVector.dot(directionToTarget);

            if (dotProduct < MIN_DOT_PRODUCT) {
                flagPlayer.addFlag(player, "KillAura (Angle)", "Unnatural hit angle (Dot: " + String.format("%.2f", dotProduct) + ").");
                event.setCancelled(true);
                packetAimData.remove(uuid);
                return;
            }

            // === 2. VERIFICARE WALL-AURA (Line of Sight) ===
            if (!player.hasLineOfSight(target)) {
                flagPlayer.addFlag(player, "KillAura (Wall)", "Attempted to attack through blocks.");
                event.setCancelled(true);
                packetAimData.remove(uuid);
                return;
            }

            // === 3. VERIFICARE MULTI-AURA / FAST SWITCH ===
            long currentTime = System.currentTimeMillis();
            int currentTargetId = target.getEntityId();

            if (hitTracker.containsKey(uuid)) {
                HitData previousHit = hitTracker.get(uuid);

                if (previousHit.targetId != currentTargetId) {
                    long timeDifference = currentTime - previousHit.timestamp;

                    if (timeDifference < 50) {
                        flagPlayer.addFlag(player, "KillAura (Multi)", "Inhuman target switch (" + timeDifference + "ms).");
                        event.setCancelled(true);
                        packetAimData.remove(uuid);
                        return;
                    }
                }
            }

            // Actualizăm memoria cu ultimul hit VALID
            hitTracker.put(uuid, new HitData(currentTargetId, currentTime));

            // Curățăm memoria temporară a pachetului
            packetAimData.remove(uuid);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitTracker.remove(uuid);
        packetAimData.remove(uuid);
    }

    private static final class ClientAimData {
        final Location eyeLoc;
        final Vector lookVector;

        public ClientAimData(Location eyeLoc, Vector lookVector) {
            this.eyeLoc = eyeLoc;
            this.lookVector = lookVector;
        }
    }

    private static final class HitData {
        final int targetId;
        final long timestamp;

        public HitData(int targetId, long timestamp) {
            this.targetId = targetId;
            this.timestamp = timestamp;
        }
    }
}