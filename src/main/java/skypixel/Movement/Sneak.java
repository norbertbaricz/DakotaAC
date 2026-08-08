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

public class Sneak implements Listener {

    // Utilizăm ConcurrentHashMap pentru a garanta stabilitatea memoriei asincrone Netty
    private final ConcurrentHashMap<UUID, Integer> sneakBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Sneak() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Sneak")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele direct din zbor
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // La prima conectare/mișcare, doar salvăm datele
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            // Calculăm viteza orizontală pură
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Stocăm valorile anterioare ca variabile imuabile pentru thread-ul principal
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm matricea pentru pachetul imediat următor
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // 1. Optimizare Extremă Netty
                            // Dacă jucătorul stă pe loc, mișcă doar capul, sau sare/cade (Jump-Sneaking),
                            // ignorăm complet pachetul și salvăm ciclul procesorului.
                            if (deltaXZ < 0.01 || deltaY != 0.0) {
                                return;
                            }

                            // 2. Delegăm verificările logice către Bukkit (Main Thread)
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Verificăm starea de Sneak
                                if (!player.isSneaking()) {
                                    return;
                                }

                                // Excepții legitime Vanilla
                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // Protecție pentru gheață și slime
                                Material blockUnder = fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
                                if (blockUnder.name().contains("ICE") || blockUnder == Material.SLIME_BLOCK) {
                                    return;
                                }

                                // LOGICA DE BAZĂ (Matematica Vitezei)
                                double maxSneakSpeed = 0.14;

                                if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                                    int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                                    maxSneakSpeed += 0.05 * (amplifier + 1);
                                }

                                int vl = sneakBuffer.getOrDefault(uuid, 0);

                                if (deltaXZ > maxSneakSpeed) {
                                    vl++;
                                    sneakBuffer.put(uuid, vl);

                                    if (vl > 3) {
                                        flagPlayer.addFlag(player, "Sneak", "Moving too fast while sneaking (Speed: " + String.format("%.2f", deltaXZ) + ")");

                                        // Rubber-Band: Tragem jucătorul înapoi fizic
                                        player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        // Pedeapsă: Oprim forțat starea de sneak pentru a sparge loop-ul hack-ului
                                        player.setSneaking(false);
                                    }
                                } else {
                                    if (vl > 0) {
                                        sneakBuffer.put(uuid, vl - 1);
                                    }
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
     * actualizată în memoria rețelei.
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
        UUID uuid = event.getPlayer().getUniqueId();
        sneakBuffer.remove(uuid);
        lastPosMap.remove(uuid);
    }
}