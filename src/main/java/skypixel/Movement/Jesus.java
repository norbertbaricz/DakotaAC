package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Jesus implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    private static final int MAX_VIOLATIONS = 5;
    private static final double MAX_BOUNCE_Y = 0.1;
    // ==========================================

    private final ConcurrentHashMap<UUID, Integer> jesusBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Jesus() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Jesus")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) return;
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
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            if (deltaXZ == 0.0 && deltaY == 0.0) {
                                return;
                            }

                            if (deltaY < -0.1 || deltaY > 0.42) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                jesusBuffer.remove(uuid);
                                return;
                            }

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isGliding() || player.isRiptiding()) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                // === FIX PIKEUN LAVA JEUNG KNOCKBACK SEUNEU ===
                                // Lamun pamaén keur kabeuleum atawa narima ruksakna (damage), ulah dipariksa
                                if (player.getFireTicks() > 0 || player.getNoDamageTicks() > 10) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                if (!isLiquidAt(toLoc) && !isLiquidAt(toLoc.clone().subtract(0, 0.1, 0))) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                if (isNearSolidBlock(toLoc)) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                if (player.isSwimming()) {
                                    int vl = jesusBuffer.getOrDefault(uuid, 0);
                                    if (vl > 0) jesusBuffer.put(uuid, vl - 1);
                                    return;
                                }

                                int vl = jesusBuffer.getOrDefault(uuid, 0);

                                if (deltaY == 0.0) {
                                    vl += 2;
                                } else if (deltaY > 0.0 && deltaY <= MAX_BOUNCE_Y) {
                                    vl += 1;
                                } else {
                                    if (vl > 0) vl--;
                                }

                                jesusBuffer.put(uuid, vl);

                                if (vl > MAX_VIOLATIONS) {
                                    flagPlayer.addFlag(player, "Jesus", "Unnatural vertical stability in liquid (Y-Speed: " + String.format("%.3f", deltaY) + ")");

                                    Location pullDownLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1] - 0.5, safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());
                                    player.teleport(pullDownLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                    jesusBuffer.put(uuid, 2);
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
            jesusBuffer.remove(uuid);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        jesusBuffer.remove(uuid);
        lastPosMap.remove(uuid);
    }

    private boolean isLiquidAt(Location loc) {
        Material type = loc.getBlock().getType();
        return type == Material.WATER || type == Material.LAVA ||
                type.name().contains("KELP") || type.name().contains("SEAGRASS") ||
                type.name().contains("BUBBLE_COLUMN");
    }

    private boolean isNearSolidBlock(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = loc.getWorld().getBlockAt(x + dx, y + dy, z + dz);
                    Material type = b.getType();

                    if (type.isSolid() || type == Material.LILY_PAD || type.name().contains("ICE") || type.name().contains("CARPET") || type.name().contains("BOAT")) {
                        return true;
                    }

                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged) {
                        if (type.isSolid()) return true;
                    }
                }
            }
        }
        return false;
    }
}