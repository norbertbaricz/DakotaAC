package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Fucker implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Distanța maximă permisă pentru a sparge un bloc (Vanilla este ~5.0. 6.0 acoperă ping-ul)
    private static final double MAX_BLOCK_REACH = 6.0;

    // Toleranța de Hitbox. Extindem cutia blocului puțin pentru a absorbi lag-ul de rețea
    private static final double HITBOX_FORGIVENESS = 0.15;

    // Câte abateri iertăm înainte să blocăm spargerea? (Absoarbe Flick-urile din mouse)
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Salvăm datele unghiului perfect capturat de placa de rețea
    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fuckerVL = new ConcurrentHashMap<>();

    public Fucker() {
        // PASUL 1: Capturăm geometria privirii exact în milisecunda acțiunii de STOP_DESTROY
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fucker")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);

                            if (action == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                                // Salvăm locația ochiului și direcția asincron
                                Location eyeLoc = player.getEyeLocation();
                                Vector direction = eyeLoc.getDirection().normalize();

                                packetAimData.put(player.getUniqueId(), new ClientAimData(eyeLoc, direction));
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // PASUL 2: LOGICA PE MAIN THREAD (Când blocul chiar se sparge)
    // ========================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("Fucker")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Block targetBlock = event.getBlock();

        // Preluăm unghiurile perfecte sau folosim unghiul actual ca fallback dacă ProtocolLib a omis pachetul
        ClientAimData aimData = packetAimData.remove(uuid);
        Location eyeLoc = aimData != null ? aimData.eyeLoc : player.getEyeLocation();
        Vector direction = aimData != null ? aimData.direction : eyeLoc.getDirection().normalize();

        int vl = fuckerVL.getOrDefault(uuid, 0);
        boolean isFlagged = false;
        String flagReason = "";

        // Centrul și BoundingBox-ul blocului
        Location blockCenter = targetBlock.getLocation().add(0.5, 0.5, 0.5);
        double distanceToCenter = eyeLoc.distance(blockCenter);

        // --------------------------------------------------------
        // VERIFICARE 1: Reach (Distanță)
        // --------------------------------------------------------
        if (distanceToCenter > MAX_BLOCK_REACH + 0.5) {
            isFlagged = true;
            flagReason = "Broke block out of range (Reach: " + String.format("%.2f", distanceToCenter) + ").";
        }
        else if (distanceToCenter > 1.2) { // Excludem blocurile în care jucătorul stă cu capul

            // Extragem Cutia de Coliziune a blocului și o mărim foarte puțin pentru siguranța rețelei
            BoundingBox blockBox = targetBlock.getBoundingBox().expand(HITBOX_FORGIVENESS);

            // --------------------------------------------------------
            // VERIFICARE 2: Unghiul (GhostBreak / Aura)
            // --------------------------------------------------------
            // Verificăm dacă raza din ochii jucătorului se intersectează fizic cu cutia blocului
            RayTraceResult boxHit = blockBox.rayTrace(eyeLoc.toVector(), direction, MAX_BLOCK_REACH);

            if (boxHit == null) {
                isFlagged = true;
                flagReason = "Broke block without looking at it (GhostBreak).";
            } else {
                // --------------------------------------------------------
                // VERIFICARE 3: Ziduri (Fucker / Wall)
                // --------------------------------------------------------
                // Dacă se uită la bloc, verificăm dacă e vreun zid SOLID între ochi și bloc
                double distanceToHit = boxHit.getHitPosition().distance(eyeLoc.toVector());
                double safeRayDistance = distanceToHit - 0.1; // Ne oprim fix cu 10cm înainte de blocul țintă

                if (safeRayDistance > 0.5) {
                    RayTraceResult wallHit = player.getWorld().rayTraceBlocks(
                            eyeLoc,
                            direction,
                            safeRayDistance,
                            FluidCollisionMode.NEVER,
                            true // Ignorăm apa, iarba, florile
                    );

                    if (wallHit != null && wallHit.getHitBlock() != null) {
                        Block hitBlock = wallHit.getHitBlock();
                        if (hitBlock.getType().isSolid()) {
                            isFlagged = true;
                            flagReason = "Broke " + targetBlock.getType().name() + " through a solid block (" + hitBlock.getType().name() + ").";
                        }
                    }
                }
            }
        }

        // ACȚIUNEA FINALĂ
        if (isFlagged) {
            vl++;
            fuckerVL.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "Fucker / Nuker", flagReason);

                // Anulăm distrugerea PE SERVER (Griefing protejat 100%)
                event.setCancelled(true);

                fuckerVL.put(uuid, MAX_VIOLATIONS - 1); // Reset parțial
            }
        } else {
            // Curățăm buffer-ul pentru jucătorii legitimi
            if (vl > 0) fuckerVL.put(uuid, vl - 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        packetAimData.remove(uuid);
        fuckerVL.remove(uuid);
    }

    // Clasă auxiliară pentru păstrarea vectorilor curați
    private static class ClientAimData {
        final Location eyeLoc;
        final Vector direction;

        public ClientAimData(Location eyeLoc, Vector direction) {
            this.eyeLoc = eyeLoc;
            this.direction = direction;
        }
    }
}