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

/** Original detection formulas; annotated defaults below are loaded from Modules.Hitbox. */
public class Hitbox implements Listener {

    @Option(value = "extra-hitbox-tolerance", min = 0.0, max = 1.0E9)
    private static volatile double EXTRA_HITBOX_TOLERANCE = 0.35;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> hitboxBuffer = new ConcurrentHashMap<>();

    public Hitbox() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Hitbox")) return;

                            Player attacker = event.getPlayer();
                            if (attacker != null && CheckPolicy.exempt(attacker, "Hitbox")) return;
                            if (attacker == null || attacker.getGameMode() == GameMode.CREATIVE) return;

                            if (!PacketAccess.isAttack(event)) return;

                            Location eyeLoc = attacker.getEyeLocation();
                            Vector lookDirection = eyeLoc.getDirection().normalize();

                            packetAimData.put(attacker.getUniqueId(), new ClientAimData(eyeLoc, lookDirection));

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Hitbox", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("Hitbox")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player attacker = (Player) event.getDamager();
            if (CheckPolicy.exempt(attacker, "Hitbox")) return;

            if (attacker.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = attacker.getUniqueId();
            Entity target = event.getEntity();

            ClientAimData aimData = packetAimData.get(uuid);
            if (aimData == null) return; 

            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);

            Vector toTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector());
            double distanceToRay = toTarget.crossProduct(aimData.direction).length();

            double entityRadius = target.getWidth() / 2.0;
            double maxAllowedHitbox = entityRadius + EXTRA_HITBOX_TOLERANCE;

            int vl = hitboxBuffer.getOrDefault(uuid, 0);

            if (distanceToRay > maxAllowedHitbox) {
                vl++;
                hitboxBuffer.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {

                    flagPlayer.addFlag(attacker, "Hitbox", Lang.text("checks.Hitbox.name-hitbox"), Lang.text("checks.Hitbox.details-hit-entity-without-looking-offset-max", "distance-to-ray", String.format(java.util.Locale.ROOT, "%.2f", distanceToRay), "max-allowed-hitbox", String.format(java.util.Locale.ROOT, "%.2f", maxAllowedHitbox)));

                    if (CheckPolicy.cancel("Hitbox")) event.setCancelled(true);

                    hitboxBuffer.put(uuid, MAX_VIOLATIONS - 1);
                }
            } else {

                if (vl > 0) {
                    hitboxBuffer.put(uuid, vl - 1);
                }
            }

            packetAimData.remove(uuid);

        } catch (Exception | LinkageError ex) {
            Diagnostics.report("Hitbox", ex);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitboxBuffer.remove(uuid);
        packetAimData.remove(uuid);
    }

    private static class ClientAimData {
        final Location eyeLoc;
        final Vector direction;

        public ClientAimData(Location eyeLoc, Vector direction) {
            this.eyeLoc = eyeLoc;
            this.direction = direction;
        }
    }
}