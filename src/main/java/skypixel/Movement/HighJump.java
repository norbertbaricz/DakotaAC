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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class HighJump implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Viteza maximă pe axa Y (în sus) pe care o poate prinde un jucător Vanilla fără efecte.
    private static final double BASE_JUMP_VELOCITY = 0.425;

    // Cât adaugă fiecare nivel de Jump Boost la săritură.
    private static final double JUMP_BOOST_MULTIPLIER = 0.15;

    // Marja de eroare oferită când jucătorul sare + pășește pe un bloc complex (ex: Slab/Gard)
    private static final double COMPLEX_BLOCK_BUFFER = 0.6;

    // Timpul de imunitate (milisecunde) la Rubber-Band după TP/Spawn
    private static final long GRACE_PERIOD_MS = 1000L;
    // ==========================================

    private final ConcurrentHashMap<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    public HighJump() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("HighJump")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            // 1. Verificare Imunitate (Grace Period asincron)
                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // 2. OPTIMIZARE EXTREMĂ PE REȚEA
                            // Ignorăm complet căderile (<= 0) sau urcatul perfect pe un slab/scară (0.5)
                            // Salvăm zeci de verificări pe secundă în Bukkit.
                            if (deltaY <= 0.0 || deltaY == 0.5) {
                                return;
                            }

                            // 3. Delegăm verificările fizice complexe către Main Thread
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());
                                Location fromLoc = new Location(player.getWorld(), fromPos[0], fromPos[1], fromPos[2]);

                                // Verificăm excepțiile Vanilla
                                if (player.getAllowFlight() || player.isGliding() || player.isRiptiding() || player.isInsideVehicle()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Knockback din damage (jucătorul tocmai a fost lovit și aruncat în sus)
                                if (player.getNoDamageTicks() > 10) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Verificăm blocurile care propulsează (Slime, Pat)
                                if (isNearBouncingBlock(toLoc) || isNearBouncingBlock(fromLoc)) {
                                    bounceCooldowns.put(uuid, System.currentTimeMillis() + 2000L);
                                }

                                if (bounceCooldowns.containsKey(uuid) && bounceCooldowns.get(uuid) > System.currentTimeMillis()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Apă curentă sau Bubble Columns
                                if (isInBubbleColumn(toLoc)) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Calculăm viteza maximă permisă
                                double maxJumpVelocity = BASE_JUMP_VELOCITY;
                                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                                    maxJumpVelocity += (level * JUMP_BOOST_MULTIPLIER);
                                }

                                // Verificăm dacă e lângă un bloc complex pe care poate face "Step-Up" simultan cu săritura
                                if (isNearComplexBlock(fromLoc) || isNearComplexBlock(toLoc)) {
                                    maxJumpVelocity += COMPLEX_BLOCK_BUFFER;
                                }

                                // Plasă de siguranță server-side (bypass dacă serverul îl împinge prin Velocity)
                                if (player.getVelocity().getY() > maxJumpVelocity) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // === LOGICA DE BAZĂ ===
                                if (deltaY > maxJumpVelocity) {
                                    flagPlayer.addFlag(player, "HighJump", "Impossible jump velocity (Y-Speed: " + String.format("%.3f", deltaY) + " | Max: " + String.format("%.3f", maxJumpVelocity) + ")");

                                    Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());

                                    // Îi dăm imunitate temporară ca Rubber-Band-ul să nu genereze alte alarme
                                    teleportImmunity.put(uuid, System.currentTimeMillis() + GRACE_PERIOD_MS);
                                    player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                } else {
                                    // Locația a fost curată, o putem folosi ca punct sigur pe viitor
                                    lastSafeLocation.put(uuid, toLoc);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // PROTECȚII LA ALARME FALSE
    // ========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            teleportImmunity.put(uuid, System.currentTimeMillis() + GRACE_PERIOD_MS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        teleportImmunity.put(uuid, System.currentTimeMillis() + 3000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        teleportImmunity.put(uuid, System.currentTimeMillis() + 1500L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        bounceCooldowns.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportImmunity.remove(uuid);
    }

    // ========================================================
    // VERIFICĂRI DE FIZICĂ (Slime, Paturi, Garduri, Covoare)
    // ========================================================

    private boolean isNearBouncingBlock(Location loc) {
        int x = loc.getBlockX();
        int minY = loc.getBlockY() - 2;
        int maxY = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    if (type == Material.SLIME_BLOCK || type.name().contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInBubbleColumn(Location loc) {
        Material blockAt = loc.getBlock().getType();
        return blockAt.name().contains("BUBBLE_COLUMN") || blockAt == Material.WATER;
    }

    /**
     * Scanează blocurile complexe (cu Hitbox neregulat) pe care jucătorul
     * s-ar putea urca (Step) în același timp în care sare.
     */
    private boolean isNearComplexBlock(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5); // Căutăm puțin și sub picioare
        int maxY = (int) Math.floor(loc.getY() + 1.5); // Acoperă înălțimea gardurilor (1.5)
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    if (type.isAir()) continue;

                    String name = type.name();
                    if (name.contains("FENCE") ||
                            name.contains("CARPET") ||
                            name.contains("SNOW") ||
                            name.contains("SLAB") ||
                            name.contains("STEP") ||
                            name.contains("STAIRS") ||
                            name.contains("WALL") ||
                            name.contains("TRAPDOOR") ||
                            name.contains("BED") ||
                            name.contains("LILY") ||
                            name.contains("SCAFFOLDING")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}