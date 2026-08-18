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
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class NoFall implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // Modifică aceste valori direct din cod fără să strici logica principală.
    // ==========================================
    private static final double MIN_FALL_DIST_TO_CHECK = 2.5; // De la ce distanță de cădere începem să flag-uim jucătorul
    private static final double DESYNC_TOLERANCE_NORMAL = 1.5; // Diferența maximă permisă client vs server pentru jucători standard
    private static final double DESYNC_TOLERANCE_JUMP_BOOST = 4.0; // Toleranță mărită pentru Jump Boost (serverul calculează fall distance diferit cu acest efect)
    private static final double UPWARD_VELOCITY_RESET_NORMAL = 0.4; // O săritură normală generează deltaY ~0.42
    private static final double UPWARD_VELOCITY_RESET_JUMP_BOOST = 0.2; // Pentru Jump Boost, suntem mai permisivi pe urcare

    private static final long TELEPORT_IMMUNITY_MS = 1500L;
    private static final long RESPAWN_IMMUNITY_MS = 2000L;
    private static final long DEATH_IMMUNITY_MS = 3000L;
    // ==========================================

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> realFallDistanceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    public NoFall() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoFall")) return;

                            Player player = event.getPlayer();
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);
                            boolean clientOnGround = event.getPacket().getBooleans().readSafely(0);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            // 1. Verificare Imunitate (Teleport / Moarte / Respawn)
                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                realFallDistanceMap.put(uuid, 0.0);
                                return;
                            }

                            double deltaY = toY - fromPos[1];
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // 2. Ignorăm modurile de joc și zborul legitim
                                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                                        player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    return;
                                }

                                // 3. VERIFICĂRI SUPREME PENTRU EFECTE VANILLA / NBT
                                // Jucătorii cu aceste mecanici nu respectă legile gravitației standard.
                                if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                                        player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                boolean isPhysicallyOnGround = isNearGround(toLoc);

                                // Dacă cade în ceva moale, serverul iartă fall damage-ul, deci îl iertăm și noi.
                                if (isSafeLandingBlock(toLoc)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    return;
                                }

                                double realFallDist = realFallDistanceMap.getOrDefault(uuid, 0.0);
                                boolean hasJumpBoost = player.hasPotionEffect(PotionEffectType.JUMP_BOOST);

                                // 4. Matematica căderii
                                if (deltaY < 0.0) {
                                    realFallDist += Math.abs(deltaY);
                                } else if (deltaY > 0.0 && !isPhysicallyOnGround) {
                                    // Setăm un prag de resetare dinamic bazat pe Jump Boost
                                    double resetThreshold = hasJumpBoost ? UPWARD_VELOCITY_RESET_JUMP_BOOST : UPWARD_VELOCITY_RESET_NORMAL;

                                    if (deltaY > resetThreshold) {
                                        realFallDist = 0.0;
                                    }
                                }

                                // === 5. LOGICA SUPREMĂ DE DETECȚIE ===

                                if (!isPhysicallyOnGround) {
                                    // SUNTEM ÎN AER.
                                    if (realFallDist > MIN_FALL_DIST_TO_CHECK) {

                                        // Folosim o toleranță mai mare dacă jucătorul are efect NBT care decalează fizica
                                        double currentTolerance = hasJumpBoost ? DESYNC_TOLERANCE_JUMP_BOOST : DESYNC_TOLERANCE_NORMAL;

                                        // Modul "Packet" / "Hypixel"
                                        if (player.getFallDistance() < (realFallDist - currentTolerance)) {
                                            flagPlayer.addFlag(player, "NoFall (Packet)", "Server distance reset mid-air (RealFall: " + String.format("%.2f", realFallDist) + " | Server: " + String.format("%.2f", player.getFallDistance()) + ")");
                                            player.setFallDistance((float) realFallDist);
                                        }

                                        // Modul "SpoofGround" / "Vulcan"
                                        if (clientOnGround) {
                                            flagPlayer.addFlag(player, "NoFall (Spoof)", "Spoofed onGround mid-air (RealFall: " + String.format("%.2f", realFallDist) + ")");
                                            player.setFallDistance((float) realFallDist);
                                        }
                                    }
                                } else {
                                    // AM ATERIZAT PE PĂMÂNT.
                                    if (realFallDist >= 3.0) {

                                        // Modul "NoGround" / "BlocksMC"
                                        if (!clientOnGround) {
                                            flagPlayer.addFlag(player, "NoFall (NoGround)", "Landed but denied onGround (RealFall: " + String.format("%.2f", realFallDist) + ")");
                                            forceFallDamage(player, realFallDist);
                                        }
                                    }

                                    // A atins pământul, resetăm distanța pentru viitor.
                                    realFallDist = 0.0;
                                }

                                realFallDistanceMap.put(uuid, realFallDist);
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    private void forceFallDamage(Player player, double realFallDist) {
        double damage = realFallDist - 3.0;
        if (damage > 0) {
            teleportImmunity.put(player.getUniqueId(), System.currentTimeMillis() + 500L);
            player.damage(damage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            realFallDistanceMap.put(uuid, 0.0);
            teleportImmunity.put(uuid, System.currentTimeMillis() + TELEPORT_IMMUNITY_MS);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + DEATH_IMMUNITY_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + RESPAWN_IMMUNITY_MS);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        realFallDistanceMap.remove(uuid);
        teleportImmunity.remove(uuid);
    }

    private boolean isNearGround(Location loc) {
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
                    if (type.isAir()) continue;

                    if (type.isSolid() || type.name().contains("SNOW") || type.name().contains("CARPET") || type.name().contains("LILY")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isSafeLandingBlock(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY() + 1.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    String name = type.name();

                    // Am adăugat blocuri noi introduse în versiunile recente (Powder Snow, Kelp, Weeping/Twisting vines)
                    if (name.contains("WATER") || name.contains("LAVA") ||
                            name.contains("COBWEB") || name.contains("SLIME") ||
                            name.contains("HONEY") || name.contains("BED") ||
                            name.contains("LADDER") || name.contains("VINE") ||
                            name.contains("SCAFFOLDING") || name.contains("BERRY_BUSH") ||
                            name.contains("POWDER_SNOW") || name.contains("KELP") ||
                            name.contains("TWISTING_VINES") || name.contains("WEEPING_VINES")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}