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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Reach implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Distanța Vanilla pură este de 3.0 blocuri în Survival.
    private static final double MAX_VANILLA_REACH = 3.0;

    // Cât adăugăm extra pentru ping/lag (Dezechilibru de hitboxes client/server)
    // 0.5 este o valoare echilibrată. (Total 3.5 blocuri permise).
    private static final double LAG_TOLERANCE = 0.5;

    // Câte lovituri nefirești trebuie să dea ca să ia flag (absoarbe lag-ul masiv)
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Salvăm locația ochiului direct din pachet
    private final ConcurrentHashMap<UUID, Location> packetEyeData = new ConcurrentHashMap<>();

    // Buffer de alerte (Violation Level)
    private final ConcurrentHashMap<UUID, Integer> reachVL = new ConcurrentHashMap<>();

    public Reach() {
        // ====================================================================
        // PASUL 1: PACHETE - Salvăm poziția exactă a atacatorului asincron
        // ====================================================================
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Reach")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().readSafely(0).getAction();
                            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                            // Salvăm locația ochiului fix în momentul click-ului
                            packetEyeData.put(player.getUniqueId(), player.getEyeLocation());

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ====================================================================
    // PASUL 2: BUKKIT API - Calculăm distanța către Hitbox-ul țintei
    // ====================================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("Reach")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player attacker = (Player) event.getDamager();

            if (attacker.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = attacker.getUniqueId();
            Entity target = event.getEntity();

            // Preluăm datele exacte din pachet (dacă sunt null, ignorăm - ex: Sweeping Edge)
            Location eyeLoc = packetEyeData.get(uuid);
            if (eyeLoc == null) return;

            // Extragem Cutia de Coliziune (Bounding Box-ul) țintei. Acoperă și boșii mari perfect!
            BoundingBox targetBox = target.getBoundingBox();

            // Calculăm distanța exactă până la CEA MAI APROPIATĂ LATURĂ a inamicului
            double distanceToHitbox = getDistanceToBoundingBox(eyeLoc, targetBox);

            // Adăugăm toleranța de lag. Dacă inamicul aleargă de tine, desync-ul e mai mare
            double maxAllowedDistance = MAX_VANILLA_REACH + LAG_TOLERANCE;
            int vl = reachVL.getOrDefault(uuid, 0);

            if (distanceToHitbox > maxAllowedDistance) {
                vl++;
                reachVL.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {
                    flagPlayer.addFlag(attacker, "Reach", "Hit from " + String.format("%.2f", distanceToHitbox) + " blocks away (Max: " + maxAllowedDistance + ").");
                    event.setCancelled(true);
                    reachVL.put(uuid, MAX_VIOLATIONS - 1);
                }
            } else {
                if (vl > 0) {
                    reachVL.put(uuid, vl - 1);
                }
            }

            // Curățăm memoria
            packetEyeData.remove(uuid);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        reachVL.remove(uuid);
        packetEyeData.remove(uuid);
    }

    // ====================================================================
    // MATEMATICĂ: Calcularea distanței de la Ochi către Marginea Hitbox-ului
    // ====================================================================
    private double getDistanceToBoundingBox(Location eyeLoc, BoundingBox box) {
        // Găsește cel mai apropiat punct de pe cubul inamicului față de fața jucătorului
        double closestX = clamp(eyeLoc.getX(), box.getMinX(), box.getMaxX());
        double closestY = clamp(eyeLoc.getY(), box.getMinY(), box.getMaxY());
        double closestZ = clamp(eyeLoc.getZ(), box.getMinZ(), box.getMaxZ());

        // Calculează distanța 3D dintre ochi și acel punct
        double dx = eyeLoc.getX() - closestX;
        double dy = eyeLoc.getY() - closestY;
        double dz = eyeLoc.getZ() - closestZ;

        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // Funcție de prindere (limitează valoarea între un minim și un maxim)
    private double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}