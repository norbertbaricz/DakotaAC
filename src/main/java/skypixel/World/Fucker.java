package skypixel.World;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
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

/** Original detection formulas; annotated defaults below are loaded from Modules.Fucker. */
public class Fucker implements Listener {

    @Option(value = "reach-center-tolerance", min = 0.0, max = 1.0E9)
    private static volatile double REACH_CENTER_TOLERANCE = 0.5;
    @Option(value = "nearby-exemption-distance", min = 0.0, max = 1.0E9)
    private static volatile double NEARBY_EXEMPTION_DISTANCE = 1.2;
    @Option(value = "ray-end-margin", min = 0.0, max = 1.0E9)
    private static volatile double RAY_END_MARGIN = 0.1;
    @Option(value = "min-wall-ray-distance", min = 0.0, max = 1.0E9)
    private static volatile double MIN_WALL_RAY_DISTANCE = 0.5;

    @Option(value = "max-block-reach", min = 0.0, max = 1.0E9)
    private static volatile double MAX_BLOCK_REACH = 6.0;
    @Option(value = "hitbox-forgiveness", min = 0.0, max = 1.0E9)
    private static volatile double HITBOX_FORGIVENESS = 0.15;
    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fuckerVL = new ConcurrentHashMap<>();

    public Fucker() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fucker")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Fucker")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().read(0);

                            if (action == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                                Location eyeLoc = player.getEyeLocation();
                                Vector direction = eyeLoc.getDirection().normalize();
                                packetAimData.put(player.getUniqueId(), new ClientAimData(eyeLoc, direction));
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Fucker", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("Fucker")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "Fucker")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Block targetBlock = event.getBlock();

        ClientAimData aimData = packetAimData.remove(uuid);
        Location eyeLoc = aimData != null ? aimData.eyeLoc : player.getEyeLocation();
        Vector direction = aimData != null ? aimData.direction : eyeLoc.getDirection().normalize();

        int vl = fuckerVL.getOrDefault(uuid, 0);
        boolean isFlagged = false;
        String flagReason = "";

        Location blockCenter = targetBlock.getLocation().add(0.5, 0.5, 0.5);
        double distanceToCenter = eyeLoc.distance(blockCenter);

        if (distanceToCenter > MAX_BLOCK_REACH + REACH_CENTER_TOLERANCE) {
            isFlagged = true;
            flagReason = Lang.text("checks.Fucker.details-broke-block-out-of-range-reach", "distance-to-center", String.format(java.util.Locale.ROOT, "%.2f", distanceToCenter));
        }
        else if (distanceToCenter > NEARBY_EXEMPTION_DISTANCE) {

            BoundingBox blockBox;
            if (targetBlock.getType().isSolid()) {
                blockBox = targetBlock.getBoundingBox().expand(HITBOX_FORGIVENESS);
            } else {
                blockBox = new BoundingBox(
                        targetBlock.getX(), targetBlock.getY(), targetBlock.getZ(),
                        targetBlock.getX() + 1.0, targetBlock.getY() + 1.0, targetBlock.getZ() + 1.0
                ).expand(HITBOX_FORGIVENESS);
            }

            RayTraceResult boxHit = blockBox.rayTrace(eyeLoc.toVector(), direction, MAX_BLOCK_REACH);

            if (boxHit == null) {
                isFlagged = true;
                flagReason = Lang.text("checks.Fucker.details-broke-block-without-looking-at-it-ghostbreak");
            } else {

                double distanceToHit = boxHit.getHitPosition().distance(eyeLoc.toVector());
                double safeRayDistance = distanceToHit - RAY_END_MARGIN;

                if (safeRayDistance > MIN_WALL_RAY_DISTANCE) {
                    RayTraceResult wallHit = player.getWorld().rayTraceBlocks(
                            eyeLoc,
                            direction,
                            safeRayDistance,
                            FluidCollisionMode.NEVER,
                            true
                    );

                    if (wallHit != null && wallHit.getHitBlock() != null) {
                        Block hitBlock = wallHit.getHitBlock();
                        if (hitBlock.getType().isSolid()) {
                            isFlagged = true;
                            flagReason = Lang.text("checks.Fucker.details-broke-through-a-solid-block", "name", targetBlock.getType().name(), "name-2", hitBlock.getType().name());
                        }
                    }
                }
            }
        }

        if (isFlagged) {
            vl++;
            fuckerVL.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "Fucker", Lang.text("checks.Fucker.name-fucker"), flagReason);
                if (CheckPolicy.cancel("Fucker")) event.setCancelled(true);
                fuckerVL.put(uuid, MAX_VIOLATIONS - 1);
            }
        } else {
            if (vl > 0) fuckerVL.put(uuid, vl - 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        packetAimData.remove(uuid);
        fuckerVL.remove(uuid);
    }

    private static class ClientAimData {
        final Location eyeLoc;
        final Vector direction;

        public ClientAimData(Location eyeLoc, Vector direction) {
            this.eyeLoc = eyeLoc;
            this.direction = direction;
        }
    }
}