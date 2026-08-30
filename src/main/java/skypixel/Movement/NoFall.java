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

    private static final double MIN_FALL_DIST_TO_CHECK = 2.5;
    private static final double UPWARD_VELOCITY_RESET_NORMAL = 0.4;
    private static final double UPWARD_VELOCITY_RESET_JUMP_BOOST = 0.2;

    private static final long TELEPORT_IMMUNITY_MS = 1500L;
    private static final long RESPAWN_IMMUNITY_MS = 2000L;
    private static final long DEATH_IMMUNITY_MS = 3000L;

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> realFallDistanceMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    // Păstrăm buffer-ul exclusiv pentru desync-ul natural la impactul cu solul
    private final ConcurrentHashMap<UUID, Double> pendingNoGroundDamage = new ConcurrentHashMap<>();

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

                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                realFallDistanceMap.put(uuid, 0.0);
                                pendingNoGroundDamage.remove(uuid);
                                return;
                            }

                            double deltaY = toY - fromPos[1];
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                                        player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                if (player.hasPotionEffect(PotionEffectType.SLOW_FALLING) ||
                                        player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                boolean isPhysicallyOnGround = isNearGround(toLoc);

                                if (isSafeLandingBlock(toLoc)) {
                                    realFallDistanceMap.put(uuid, 0.0);
                                    pendingNoGroundDamage.remove(uuid);
                                    return;
                                }

                                double realFallDist = realFallDistanceMap.getOrDefault(uuid, 0.0);
                                boolean hasJumpBoost = player.hasPotionEffect(PotionEffectType.JUMP_BOOST);

                                if (deltaY < 0.0) {
                                    realFallDist += Math.abs(deltaY);
                                } else if (deltaY > 0.0 && !isPhysicallyOnGround) {
                                    double resetThreshold = hasJumpBoost ? UPWARD_VELOCITY_RESET_JUMP_BOOST : UPWARD_VELOCITY_RESET_NORMAL;
                                    if (deltaY > resetThreshold) {
                                        realFallDist = 0.0;
                                    }
                                }

                                // === 5. LOGICA SUPREMĂ DE DETECȚIE ===
                                if (!isPhysicallyOnGround) {
                                    // SUNTEM ÎN AER
                                    if (realFallDist > MIN_FALL_DIST_TO_CHECK) {
                                        if (clientOnGround) {
                                            // FLAG INSTANT: Clientul Vanilla nu trimite niciodată onGround=true la >1.5 blocuri altitudine!
                                            flagPlayer.addFlag(player, "NoFall (Spoof)", "Spoofed onGround mid-air (RealFall: " + String.format("%.2f", realFallDist) + ")");
                                            player.setFallDistance((float) realFallDist);
                                        }
                                    }
                                    pendingNoGroundDamage.remove(uuid);
                                } else {
                                    // AM ATERIZAT PE PĂMÂNT (server-side)
                                    if (realFallDist >= 3.0) {
                                        if (!clientOnGround) {
                                            // BUFFER 1-TICK: Așteptăm un tick pentru a compensa lag-ul natural la impact
                                            Double pending = pendingNoGroundDamage.remove(uuid);
                                            if (pending != null) {
                                                flagPlayer.addFlag(player, "NoFall (NoGround)", "Landed but denied onGround (RealFall: " + String.format("%.2f", pending) + ")");
                                                forceFallDamage(player, pending);
                                            } else {
                                                // Înghețăm resetarea distanței! Salvăm și așteptăm tick-ul următor.
                                                pendingNoGroundDamage.put(uuid, realFallDist);
                                                realFallDistanceMap.put(uuid, realFallDist);
                                                return;
                                            }
                                        }
                                    }
                                    // Dacă e curat sau a fost deja pedepsit, resetăm
                                    realFallDist = 0.0;
                                    pendingNoGroundDamage.remove(uuid);
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
            pendingNoGroundDamage.remove(uuid);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + DEATH_IMMUNITY_MS);
        pendingNoGroundDamage.remove(uuid);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        realFallDistanceMap.put(uuid, 0.0);
        teleportImmunity.put(uuid, System.currentTimeMillis() + RESPAWN_IMMUNITY_MS);
        pendingNoGroundDamage.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        realFallDistanceMap.remove(uuid);
        teleportImmunity.remove(uuid);
        pendingNoGroundDamage.remove(uuid);
    }

    private boolean isNearGround(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 1.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        double feetThreshold = Math.floor(loc.getY() - 0.6);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Material type = loc.getWorld().getBlockAt(bx, by, bz).getType();
                    if (type.isAir()) continue;

                    String name = type.name();

                    // Gardurile și Zidurile au hitbox nativ înalt, le validăm pe toată adâncimea (Y-1.5)
                    if (name.contains("FENCE") || name.contains("WALL")) {
                        return true;
                    }

                    // Blocurile normale sunt valide doar dacă sunt chiar sub picioare (Y-0.6)
                    if (by >= feetThreshold) {
                        if (type.isSolid() || name.contains("SNOW") || name.contains("CARPET") || name.contains("LILY")) {
                            return true;
                        }
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