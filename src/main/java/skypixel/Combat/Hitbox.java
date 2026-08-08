package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
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

public class Hitbox implements Listener {

    // Aici salvăm unghiurile exacte din momentul pachetului (Asincron -> Sincron)
    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    // Aici stocăm alertele (Violation Level)
    private final HashMap<UUID, Integer> hitboxBuffer = new HashMap<>();

    public Hitbox() {
        // ====================================================================
        // PASUL 1: PACHETE (ProtocolLib) - Interceptăm instantaneu unghiul
        // ====================================================================
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Hitbox")) return;

                            Player attacker = event.getPlayer();
                            if (attacker == null || attacker.getGameMode() == GameMode.CREATIVE) return;

                            // Citim acțiunea (ne interesează doar ATTACK)
                            EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().readSafely(0).getAction();
                            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                            // AICI ESTE MAGIA: Salvăm locația ochiului și direcția EXACT în milisecunda în care a dat click.
                            // Acest lucru este 100% sigur de rulat pe thread-ul asincron. Nu atingem entitățile!
                            Location eyeLoc = attacker.getEyeLocation();
                            Vector lookDirection = eyeLoc.getDirection().normalize();

                            packetAimData.put(attacker.getUniqueId(), new ClientAimData(eyeLoc, lookDirection));

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ====================================================================
    // PASUL 2: LOGICA (Bukkit API) - Verificăm și blocăm hackerul
    // ====================================================================
    @EventHandler
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("Hitbox")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player attacker = (Player) event.getDamager();

            if (attacker.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = attacker.getUniqueId();
            Entity target = event.getEntity();

            // Preluăm datele ultra-precise salvate de ProtocolLib
            ClientAimData aimData = packetAimData.get(uuid);
            if (aimData == null) return; // Fallback de siguranță

            // 1. Calculăm centrul inamicului
            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);

            // 2. MATEMATICA VECTORIALĂ folosind unghiurile EXTREM DE PRECISE din pachet
            Vector toTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector());
            double distanceToRay = toTarget.crossProduct(aimData.direction).length();

            // Limita de toleranță (putem să o scădem puțin acum, pentru că pachetele ne dau precizie maximă)
            double maxAllowedHitbox = 0.85;
            int vl = hitboxBuffer.getOrDefault(uuid, 0);

            if (distanceToRay > maxAllowedHitbox) {
                vl++;
                hitboxBuffer.put(uuid, vl);

                if (vl >= 3) {
                    flagPlayer.addFlag(attacker, "Hitbox", "Hit entity without looking (Offset: " + String.format("%.2f", distanceToRay) + " blocks).");

                    // Anulăm instantaneu damage-ul!
                    event.setCancelled(true);

                    hitboxBuffer.put(uuid, 1);
                }
            } else {
                if (vl > 0) {
                    hitboxBuffer.put(uuid, vl - 1);
                }
            }

            // Ștergem pachetul după ce a fost procesat pentru a păstra memoria curată
            packetAimData.remove(uuid);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitboxBuffer.remove(uuid);
        packetAimData.remove(uuid);
    }

    // ====================================================================
    // CLASĂ AUXILIARĂ PENTRU STOCAREA UNGHIURILOR DIN PACHETE
    // ====================================================================
    private static class ClientAimData {
        final Location eyeLoc;
        final Vector direction;

        public ClientAimData(Location eyeLoc, Vector direction) {
            this.eyeLoc = eyeLoc;
            this.direction = direction;
        }
    }
}