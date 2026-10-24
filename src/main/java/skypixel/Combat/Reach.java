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
import org.bukkit.util.BoundingBox;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Reach. */
public class Reach implements Listener {

    @Option(value = "max-vanilla-reach", min = 0.0, max = 1.0E9)
    private static volatile double MAX_VANILLA_REACH = 3.0;

    @Option(value = "lag-tolerance", min = 0.0, max = 1.0E9)
    private static volatile double LAG_TOLERANCE = 0.5;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Location> packetEyeData = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> reachVL = new ConcurrentHashMap<>();

    public Reach() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Reach")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Reach")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            if (!PacketAccess.isAttack(event)) return;

                            packetEyeData.put(player.getUniqueId(), player.getEyeLocation());

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Reach", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("Reach")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player attacker = (Player) event.getDamager();
            if (CheckPolicy.exempt(attacker, "Reach")) return;

            if (attacker.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = attacker.getUniqueId();
            Entity target = event.getEntity();

            Location eyeLoc = packetEyeData.get(uuid);
            if (eyeLoc == null) return;

            BoundingBox targetBox = target.getBoundingBox();

            double distanceToHitbox = getDistanceToBoundingBox(eyeLoc, targetBox);

            double maxAllowedDistance = MAX_VANILLA_REACH + LAG_TOLERANCE;
            int vl = reachVL.getOrDefault(uuid, 0);

            if (distanceToHitbox > maxAllowedDistance) {
                vl++;
                reachVL.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {
                    flagPlayer.addFlag(attacker, "Reach", Lang.text("checks.Reach.name-reach"), Lang.text("checks.Reach.details-hit-from-blocks-away-max", "distance-to-hitbox", String.format(java.util.Locale.ROOT, "%.2f", distanceToHitbox), "max-allowed-distance", maxAllowedDistance));
                    if (CheckPolicy.cancel("Reach")) event.setCancelled(true);
                    reachVL.put(uuid, MAX_VIOLATIONS - 1);
                }
            } else {
                if (vl > 0) {
                    reachVL.put(uuid, vl - 1);
                }
            }

            packetEyeData.remove(uuid);

        } catch (Exception | LinkageError ex) {
            Diagnostics.report("Reach", ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reachVL.remove(uuid);
        packetEyeData.remove(uuid);
    }

    private double getDistanceToBoundingBox(Location eyeLoc, BoundingBox box) {

        double closestX = clamp(eyeLoc.getX(), box.getMinX(), box.getMaxX());
        double closestY = clamp(eyeLoc.getY(), box.getMinY(), box.getMaxY());
        double closestZ = clamp(eyeLoc.getZ(), box.getMinZ(), box.getMaxZ());

        double dx = eyeLoc.getX() - closestX;
        double dy = eyeLoc.getY() - closestY;
        double dz = eyeLoc.getZ() - closestZ;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}