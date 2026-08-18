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
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    private static final double MAX_SPEED_BASE = 0.28; // Viteza Vanilla la sprint
    private static final double SPEED_TOLERANCE_MULTIPLIER = 1.15; // Marja de eroare generală

    // Praguri specifice pentru mecanici care cresc viteza în Vanilla
    private static final double THRESHOLD_STAIRS_SLABS = 0.62; // Viteza admisă pe scări / slab-uri
    private static final double THRESHOLD_ICE = 0.65; // Viteza admisă când aluneci pe gheață
    private static final double THRESHOLD_ICE_CEILING = 1.30; // Viteza "Headhitter" (Gheață + Tavan)
    // ==========================================

    private final ConcurrentHashMap<UUID, Double> violations = new ConcurrentHashMap<>();
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

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Ghost Packet / Extreme Desync limit (Ignorăm TP-urile false masive)
                            if (deltaXZ > 10.0) return;

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // Optimizare Netty
                            if (deltaXZ == 0.0) return;

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Perioadă de grație teleport / respawn
                                long lastTeleport = teleportGrace.getOrDefault(uuid, 0L);
                                if (System.currentTimeMillis() - lastTeleport < 1000) {
                                    violations.put(uuid, 0.0);
                                    lastSafeLocation.put(uuid, new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch()));
                                    return;
                                }

                                // Excepții Vanilla
                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isSwimming() || player.isGliding()) {
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());
                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                // Multiplicatori efecte
                                double maxSpeed = MAX_SPEED_BASE;
                                for (PotionEffect effect : player.getActivePotionEffects()) {
                                    if (effect.getType().equals(PotionEffectType.SPEED)) {
                                        maxSpeed *= (1.0 + (0.2 * (effect.getAmplifier() + 1)));
                                    }
                                }

                                double threshold = maxSpeed * SPEED_TOLERANCE_MULTIPLIER;

                                // --- FIX 100% LOGIC PENTRU MEDIUL FIZIC ---
                                boolean isOnIce = isBlockIce(fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType()) ||
                                        isBlockIce(fromLoc.clone().subtract(0, 1.0, 0).getBlock().getType());
                                boolean hasLowCeiling = !fromLoc.clone().add(0, 2.0, 0).getBlock().getType().isAir();

                                // Verificăm cu un Bounding Box dacă s-a mișcat PE sau CĂTRE o scară/slab
                                boolean isOnStairsOrSlabs = isStairOrSlab(fromLoc) || isStairOrSlab(toLoc);

                                if (isOnIce && hasLowCeiling) {
                                    threshold = THRESHOLD_ICE_CEILING;
                                } else if (isOnIce) {
                                    threshold = THRESHOLD_ICE;
                                } else if (isOnStairsOrSlabs) {
                                    // Dacă este pe scări, aplicăm pragul extins chiar dacă n-are gheață
                                    threshold = THRESHOLD_STAIRS_SLABS;

                                    // Adăugăm multiplicatorul de viteză din poțiuni PESTE viteza de scări
                                    if (maxSpeed > MAX_SPEED_BASE) {
                                        threshold *= (maxSpeed / MAX_SPEED_BASE);
                                    }
                                }

                                // --- LOGICA DE BAZĂ ---
                                if (deltaXZ > threshold) {
                                    double vl = violations.getOrDefault(uuid, 0.0) + (deltaXZ - threshold);
                                    violations.put(uuid, vl);

                                    if (vl > 2.0) {
                                        flagPlayer.addFlag(player, "Speed", "Sustained high speed (Speed: " + String.format("%.2f", deltaXZ) + " | Max: " + String.format("%.2f", threshold) + ")");

                                        Location safe = lastSafeLocation.getOrDefault(uuid, fromLoc);
                                        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        violations.put(uuid, 1.0);
                                    }
                                } else {
                                    double vl = violations.getOrDefault(uuid, 0.0);
                                    if (vl > 0) {
                                        violations.put(uuid, Math.max(0, vl - 0.1));
                                    }
                                    lastSafeLocation.put(uuid, new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch()));
                                }
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
        Location to = event.getTo();

        if (to != null) {
            teleportGrace.put(uuid, System.currentTimeMillis());
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        teleportGrace.put(uuid, System.currentTimeMillis());
        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        violations.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        violations.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
    }

    /**
     * Bounding Box: Scanăm zona sub și în jurul picioarelor pentru blocuri în trepte.
     */
    private boolean isStairOrSlab(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        // Verificăm de la -0.5 (blocul de sub picioare) până la +0.5 (blocul în care stă efectiv)
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    String name = type.name();

                    // Acoperim toate denumirile posibile din versiuni vechi și noi de Bukkit/Spigot
                    if (name.contains("STAIRS") || name.contains("SLAB") || name.contains("STEP")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isBlockIce(Material material) {
        String name = material.name();
        return name.contains("ICE"); // Acoperă automat ICE, PACKED_ICE, BLUE_ICE, FROSTED_ICE
    }
}