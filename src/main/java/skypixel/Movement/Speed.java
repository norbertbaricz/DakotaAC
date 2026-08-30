package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Speed implements Listener {

    // ==========================================
    // SETĂRI BUFFER (FĂRĂ FIZICĂ INSTABILĂ)
    // ==========================================
    private static final double BASE_SPEED_LIMIT = 0.36; // Acoperă un sprint-jump perfect
    private static final double MAX_BUFFER = 1.5; // Limita până la care iertăm impulsul
    private static final double BUFFER_DECAY = 0.05; // Cât de repede se șterge suspiciunea

    private static final double ICE_MULTIPLIER = 1.8;
    private static final double SLAB_MULTIPLIER = 1.3;
    private static final double HEADHITTER_MULTIPLIER = 1.5;
    // ==========================================

    private final ConcurrentHashMap<UUID, Double> speedBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportGrace = new ConcurrentHashMap<>();

    public Speed() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Speed")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            if (Double.isNaN(toX) || Double.isNaN(toZ) || Math.abs(toX) > 3.0E7 || Math.abs(toZ) > 3.0E7) {
                                event.setCancelled(true);
                                return;
                            }

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaZ = toZ - fromPos[2];
                            final double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Blink-ul gestionează teleportările, noi doar ignorăm
                            if (deltaXZ > 10.0 || deltaXZ == 0.0) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Imunitate teleport
                                if (teleportGrace.containsKey(uuid) && teleportGrace.get(uuid) > System.currentTimeMillis()) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                // Imunitate zbor/vehicul
                                if (player.isFlying() || player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                                        player.isInsideVehicle() || player.isSwimming() || player.isGliding() || player.isSleeping()) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                // Imunitate PvP (Knockback)
                                if (player.getNoDamageTicks() > 10 || Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ()) > 0.05) {
                                    speedBuffer.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2]);
                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                double speedLimit = BASE_SPEED_LIMIT;

                                // Poțiuni
                                for (PotionEffect effect : player.getActivePotionEffects()) {
                                    if (effect.getType().equals(PotionEffectType.SPEED)) {
                                        speedLimit += (0.06 * (effect.getAmplifier() + 1));
                                    }
                                }

                                // Mediu
                                boolean onIce = isBlockIce(fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType()) ||
                                        isBlockIce(fromLoc.clone().subtract(0, 1.0, 0).getBlock().getType());
                                boolean underBlock = !fromLoc.clone().add(0, 2.0, 0).getBlock().getType().isAir();
                                boolean onStairs = isStairOrSlab(fromLoc) || isStairOrSlab(toLoc);

                                if (onIce) {
                                    speedLimit *= ICE_MULTIPLIER;
                                } else if (onStairs) {
                                    speedLimit *= SLAB_MULTIPLIER;
                                } else if (underBlock) {
                                    speedLimit *= HEADHITTER_MULTIPLIER;
                                }

                                double buffer = speedBuffer.getOrDefault(uuid, 0.0);

                                // LOGICA DE BUFFER: Nu dăm flag instant, adunăm excesul.
                                if (deltaXZ > speedLimit) {
                                    buffer += (deltaXZ - speedLimit);

                                    // Doar dacă e ceva complet inuman dăm flag instant
                                    if (deltaXZ > speedLimit * 2.5) {
                                        flagPlayer.addFlag(player, "Speed (Instant)", "Absurd momentum (" + String.format("%.2f", deltaXZ) + ")");
                                        player.teleport(lastSafeLocation.getOrDefault(uuid, fromLoc), PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        speedBuffer.put(uuid, 0.0);
                                        return;
                                    }

                                    // Dacă s-a acumulat prea mult exces (BHOP continuu)
                                    if (buffer > MAX_BUFFER) {
                                        flagPlayer.addFlag(player, "Speed (Strafe/Bhop)", "Sustained unnatural speed (Buffer: " + String.format("%.2f", buffer) + ")");
                                        player.teleport(lastSafeLocation.getOrDefault(uuid, fromLoc), PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        buffer = 0.5; // Resetăm parțial pentru a nu spama
                                    }
                                } else {
                                    // Dacă viteza e legală, scurtăm buffer-ul (iertăm)
                                    buffer = Math.max(0.0, buffer - BUFFER_DECAY);
                                    lastSafeLocation.put(uuid, toLoc); // Salvăm locația de siguranță DOAR când e curat
                                }

                                speedBuffer.put(uuid, buffer);
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        if (event.getTo() != null) {
            teleportGrace.put(uuid, System.currentTimeMillis() + 1000L);
            lastSafeLocation.put(uuid, event.getTo());
            lastPosMap.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        teleportGrace.put(uuid, System.currentTimeMillis() + 1000L);
        lastSafeLocation.put(uuid, event.getRespawnLocation());
        lastPosMap.remove(uuid);
        speedBuffer.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        speedBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
    }

    private boolean isStairOrSlab(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    String name = type.name();

                    if (name.contains("STAIRS") || name.contains("SLAB") || name.contains("STEP")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isBlockIce(Material material) {
        return material.name().contains("ICE");
    }
}