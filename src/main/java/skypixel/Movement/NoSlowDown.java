package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoSlowDown implements Listener {

    // Stocăm asincron locațiile pentru a garanta siguranța la citire/scriere
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public NoSlowDown() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoSlowDown")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele noi brute din pachet
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // Înregistrăm coordonatele la prima mișcare și ieșim
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            // Calculăm viteza orizontală nativă (fără influența Bukkit)
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Păstrăm valorile de bază într-o constantă pentru a le folosi în RunTask
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm memoria pentru următorul pachet
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // Optimizare Netty: Dacă stă pe loc sau cade vertical, pachetul e curat
                            if (deltaXZ == 0.0) {
                                return;
                            }

                            // Trecem pe Main Thread pentru a verifica mediul fizic (Harta și Itemele)
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Excepții legitime (Zbor, Vehicule)
                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight() || player.isRiptiding()) {
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // ========================================================
                                // LOGICA 1: BLOCURI CARE ÎNCETINESC (Cobweb / Berry Bush)
                                // ========================================================
                                Material blockAtFeet = toLoc.getBlock().getType();
                                Material blockAtHead = toLoc.clone().add(0, 1.0, 0).getBlock().getType();

                                boolean inWeb = blockAtFeet == Material.COBWEB || blockAtHead == Material.COBWEB;
                                boolean inBushOrSnow = blockAtFeet.name().contains("BERRY_BUSH") || blockAtHead.name().contains("BERRY_BUSH") ||
                                        blockAtFeet.name().contains("POWDER_SNOW") || blockAtHead.name().contains("POWDER_SNOW");

                                if (inWeb || inBushOrSnow) {
                                    // Limita Vanilla este ~0.05. Lăsăm 0.15 pentru knockback / momentum
                                    if (deltaXZ > 0.15) {
                                        flagPlayer.addFlag(player, "NoSlowDown (Web)", "Moving too fast through a slowing block (Speed: " + String.format("%.2f", deltaXZ) + ")");

                                        // Rubber-Band: Îl tragem înapoi!
                                        player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        return;
                                    }
                                }

                                // ========================================================
                                // LOGICA 2: UTILIZAREA ITEMELOR (Scut, Arc, Mâncare)
                                // ========================================================
                                boolean isUsingSlowingItem = false;
                                try {
                                    isUsingSlowingItem = player.isHandRaised() || player.isBlocking();
                                } catch (NoSuchMethodError e) {
                                    isUsingSlowingItem = player.isBlocking();
                                }

                                if (!isUsingSlowingItem) {
                                    return; // Joacă curat
                                }

                                // Permitem Jump-Eating (Săritură + Mâncat simultan)
                                // Restricționăm viteza doar dacă merge strict pe suprafață plană.
                                if (deltaY != 0.0) {
                                    return;
                                }

                                // Protecție pentru gheață/slime (momentum păstrat)
                                Material blockUnder = fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
                                if (blockUnder.name().contains("ICE") || blockUnder == Material.SLIME_BLOCK) {
                                    return;
                                }

                                // Limită generoasă (0.16 e suficient să prinzi un sprint de 0.28+)
                                double speedLimit = 0.16;

                                if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                                    int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                                    speedLimit += 0.05 * (amplifier + 1);
                                }

                                if (deltaXZ > speedLimit) {
                                    flagPlayer.addFlag(player, "NoSlowDown (Item)", "Moving too fast while using item (Speed: " + String.format("%.2f", deltaXZ) + ")");

                                    player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    /**
     * Sincronizare vitală pentru prevenirea Rubber-Band-ului fals.
     * Când serverul teleportează un jucător legitim, locația lui "from" trebuie
     * actualizată pe placa de rețea.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks
        lastPosMap.remove(event.getPlayer().getUniqueId());
    }
}