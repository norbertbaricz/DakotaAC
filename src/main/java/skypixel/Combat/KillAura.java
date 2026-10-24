package skypixel.Combat;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.KillAura. */
public class KillAura implements Listener {

    @Option(value = "min-dot-product", min = -1.0, max = 1.0)
    private static volatile double MIN_DOT_PRODUCT = 0.6;

    @Option(value = "min-switch-delay-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_SWITCH_DELAY_MS = 100L;

    @Option(value = "max-angle-vl", min = 1.0, max = 1.0E9)
    private static volatile int MAX_ANGLE_VL = 3;
    @Option(value = "max-wall-vl", min = 1.0, max = 1.0E9)
    private static volatile int MAX_WALL_VL = 3;

    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, HitData> hitTracker = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> angleVL = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> wallVL = new ConcurrentHashMap<>();

    public KillAura() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("KillAura")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "KillAura")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            if (!PacketAccess.isAttack(event)) return;

                            Location eyeLoc = player.getEyeLocation();
                            Vector lookVector = eyeLoc.getDirection().normalize();

                            packetAimData.put(player.getUniqueId(), new ClientAimData(eyeLoc, lookVector));

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("KillAura", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("KillAura")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player player = (Player) event.getDamager();

            if (player.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = player.getUniqueId();
            Entity target = event.getEntity();

            ClientAimData aimData = packetAimData.get(uuid);

            if (aimData == null) return;

            boolean isClean = true;

            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            Vector directionToTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector()).normalize();

            double dotProduct = aimData.lookVector.dot(directionToTarget);

            if (dotProduct < MIN_DOT_PRODUCT) {
                isClean = false;
                int vl = angleVL.getOrDefault(uuid, 0) + 1;
                angleVL.put(uuid, vl);

                if (vl >= MAX_ANGLE_VL) {
                    flagPlayer.addFlag(player, "KillAura", Lang.text("checks.KillAura.name-killaura-angle"), Lang.text("checks.KillAura.details-unnatural-hit-angle-dot", "dot-product", String.format(java.util.Locale.ROOT, "%.2f", dotProduct)));
                    if (CheckPolicy.cancel("KillAura")) event.setCancelled(true);
                    angleVL.put(uuid, MAX_ANGLE_VL - 1);
                }
            } else {
                decreaseVL(angleVL, uuid);
            }

            if (!player.hasLineOfSight(target)) {
                isClean = false;
                int vl = wallVL.getOrDefault(uuid, 0) + 1;
                wallVL.put(uuid, vl);

                if (vl >= MAX_WALL_VL) {
                    flagPlayer.addFlag(player, "KillAura", Lang.text("checks.KillAura.name-killaura-wall"), Lang.text("checks.KillAura.details-attempted-to-attack-through-solid-blocks-repeatedly"));
                    if (CheckPolicy.cancel("KillAura")) event.setCancelled(true);
                    wallVL.put(uuid, MAX_WALL_VL - 1);
                }
            } else {
                decreaseVL(wallVL, uuid);
            }

            long currentTime = System.currentTimeMillis();
            int currentTargetId = target.getEntityId();

            if (hitTracker.containsKey(uuid)) {
                HitData previousHit = hitTracker.get(uuid);

                if (previousHit.targetId != currentTargetId) {
                    long timeDifference = currentTime - previousHit.timestamp;

                    if (timeDifference < MIN_SWITCH_DELAY_MS) {
                        isClean = false;
                        flagPlayer.addFlag(player, "KillAura", Lang.text("checks.KillAura.name-killaura-multi"), Lang.text("checks.KillAura.details-inhuman-target-switch-ms", "time-difference", timeDifference));
                        if (CheckPolicy.cancel("KillAura")) event.setCancelled(true);
                    }
                }
            }

            if (isClean) {
                hitTracker.put(uuid, new HitData(currentTargetId, currentTime));
            }

            packetAimData.remove(uuid);

        } catch (Exception | LinkageError ex) {
            Diagnostics.report("KillAura", ex);
        }
    }

    private void decreaseVL(ConcurrentHashMap<UUID, Integer> map, UUID uuid) {
        int vl = map.getOrDefault(uuid, 0);
        if (vl > 0) {
            map.put(uuid, vl - 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitTracker.remove(uuid);
        packetAimData.remove(uuid);
        angleVL.remove(uuid);
        wallVL.remove(uuid);
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