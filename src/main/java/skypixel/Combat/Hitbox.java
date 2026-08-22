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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Hitbox implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Cât de mult poate să rateze ținta (în blocuri) față de marginea reală a entității.
    // Un jucător are lățimea de 0.6. Raza e 0.3. Noi adăugăm această toleranță pentru Ping/Lag.
    // 0.35 este o valoare foarte strictă. Dacă ai jucători cu lag mare, poți urca la 0.45.
    private static final double EXTRA_HITBOX_TOLERANCE = 0.35;

    // Câte lovituri pe lângă trebuie să dea ca să ia flag.
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Aici salvăm unghiurile exacte din momentul pachetului (Asincron -> Sincron)
    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    // Aici stocăm alertele (Violation Level)
    private final ConcurrentHashMap<UUID, Integer> hitboxBuffer = new ConcurrentHashMap<>();

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

                            // MAGIA: Salvăm locația ochiului și direcția EXACT în milisecunda în care a dat click.
                            // Acest lucru este 100% sigur de rulat pe thread-ul asincron.
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
    // Folosim Priority.LOWEST pentru a fi primii care intervin asupra damage-ului
    // ====================================================================
    @EventHandler(priority = EventPriority.LOWEST)
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
            if (aimData == null) return; // Dacă nu avem date din pachet, îl ignorăm tura asta

            // 1. Calculăm centrul inamicului
            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);

            // 2. MATEMATICA VECTORIALĂ: Calculăm distanța de la privirea jucătorului la centrul țintei
            Vector toTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector());
            double distanceToRay = toTarget.crossProduct(aimData.direction).length();

            // 3. HITBOX DINAMIC: Luăm grosimea reală a entității + marja de lag
            // Împărțim la 2 pentru a obține raza de la centru la margine
            double entityRadius = target.getWidth() / 2.0;
            double maxAllowedHitbox = entityRadius + EXTRA_HITBOX_TOLERANCE;

            int vl = hitboxBuffer.getOrDefault(uuid, 0);

            if (distanceToRay > maxAllowedHitbox) {
                vl++;
                hitboxBuffer.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {
                    // Jucătorul lovește clar în afara razei maxime posibile (Aura / Hitbox Expander)
                    flagPlayer.addFlag(attacker, "Hitbox", "Hit entity without looking (Offset: " + String.format("%.2f", distanceToRay) + " | Max: " + String.format("%.2f", maxAllowedHitbox) + ").");

                    // Anulăm instantaneu damage-ul! Lovitura nu va fi înregistrată de server.
                    event.setCancelled(true);

                    // Resetăm parțial pentru a nu face spam, dar îl lăsăm aproape de un nou flag dacă continuă
                    hitboxBuffer.put(uuid, MAX_VIOLATIONS - 1);
                }
            } else {
                // Dacă joacă curat, îi ștergem treptat din suspiciuni
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