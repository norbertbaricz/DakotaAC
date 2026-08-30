package skypixel.Render;

import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import skypixel.dakotaAC;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

public class ESP implements Listener {

    // =========================================================
    // SETĂRI UȘOR DE REGLAT
    // =========================================================
    // Distanța maximă la care procesăm entitățile. Orice e peste, dispare.
    private static final double MAX_ENTITY_RANGE = 40.0D;
    private static final long TASK_INTERVAL_TICKS = 5L;
    // =========================================================

    private static final HashMap<UUID, HashSet<UUID>> hiddenPlayers = new HashMap<>();
    private static final HashMap<UUID, HashSet<UUID>> hiddenEntities = new HashMap<>();

    public ESP() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!dakotaAC.isCheckActive("ESP")) return;

                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    if (viewer.getGameMode() == GameMode.SPECTATOR) continue;

                    UUID viewerId = viewer.getUniqueId();
                    hiddenPlayers.putIfAbsent(viewerId, new HashSet<>());
                    hiddenEntities.putIfAbsent(viewerId, new HashSet<>());

                    HashSet<UUID> hiddenP = hiddenPlayers.get(viewerId);
                    HashSet<UUID> hiddenE = hiddenEntities.get(viewerId);

                    // 1. JUCĂTORI (Anti-Player ESP)
                    for (Player target : Bukkit.getOnlinePlayers()) {
                        if (viewer.equals(target)) continue;

                        if (!hasTrueLineOfSight(viewer, target)) {
                            hidePlayer(viewer, target, hiddenP);
                        } else {
                            showPlayer(viewer, target, hiddenP);
                        }
                    }

                    // 2. ENTITĂȚI (Anti-Mob / Item ESP)
                    for (Entity target : viewer.getNearbyEntities(MAX_ENTITY_RANGE, MAX_ENTITY_RANGE, MAX_ENTITY_RANGE)) {
                        if (target instanceof Player) continue;

                        boolean outOfRange = viewer.getLocation().distanceSquared(target.getLocation()) > (MAX_ENTITY_RANGE * MAX_ENTITY_RANGE);
                        boolean noLineOfSight = !hasTrueLineOfSight(viewer, target);

                        // Trimiterea pachetului nativ de ENTITY_DESTROY șterge ESP-ul complet
                        if (outOfRange || noLineOfSight) {
                            hideEntity(viewer, target, hiddenE);
                        } else {
                            showEntity(viewer, target, hiddenE);
                        }
                    }
                }
            }
        }.runTaskTimer(dakotaAC.getInstance(), 0L, TASK_INTERVAL_TICKS);
    }

    /**
     * RayTrace inteligent în 2 puncte care ignoră formele transparente/decorative.
     */
    private boolean hasTrueLineOfSight(Player viewer, Entity target) {
        Location eye = viewer.getEyeLocation();
        double height = target.getHeight();

        Location[] points = {
                target.getLocation().add(0, height * 0.8, 0),
                target.getLocation().add(0, height * 0.1, 0)
        };

        for (Location point : points) {
            double distance = eye.distance(point);
            if (distance < 2.5) return true;

            Vector direction = point.toVector().subtract(eye.toVector()).normalize();

            RayTraceResult ray = viewer.getWorld().rayTraceBlocks(
                    eye, direction, distance, FluidCollisionMode.NEVER, true
            );

            if (ray == null || ray.getHitBlock() == null) {
                return true;
            }

            Material hitType = ray.getHitBlock().getType();
            if (!hitType.isOccluding() || hitType.name().contains("FENCE") || hitType.name().contains("WALL") ||
                    hitType.name().contains("LEAVES") || hitType.name().contains("GLASS") ||
                    hitType.name().contains("SLAB") || hitType.name().contains("STAIRS") || hitType.name().contains("IRON_BARS")) {
                return true;
            }
        }
        return false;
    }

    // ========================================================================
    // METODE NATIVE BUKKIT (Gestionează automat pachetele Destroy/Spawn)
    // ========================================================================

    private void hidePlayer(Player viewer, Player target, HashSet<UUID> hiddenSet) {
        if (!hiddenSet.contains(target.getUniqueId())) {
            viewer.hidePlayer(dakotaAC.getInstance(), target);
            hiddenSet.add(target.getUniqueId());
        }
    }

    private void showPlayer(Player viewer, Player target, HashSet<UUID> hiddenSet) {
        if (hiddenSet.contains(target.getUniqueId())) {
            viewer.showPlayer(dakotaAC.getInstance(), target);
            hiddenSet.remove(target.getUniqueId());
        }
    }

    private void hideEntity(Player viewer, Entity target, HashSet<UUID> hiddenSet) {
        if (!hiddenSet.contains(target.getUniqueId())) {
            viewer.hideEntity(dakotaAC.getInstance(), target);
            hiddenSet.add(target.getUniqueId());
        }
    }

    private void showEntity(Player viewer, Entity target, HashSet<UUID> hiddenSet) {
        if (hiddenSet.contains(target.getUniqueId())) {
            viewer.showEntity(dakotaAC.getInstance(), target);
            hiddenSet.remove(target.getUniqueId());
        }
    }

    // ========================================================================
    // CLEANUP
    // ========================================================================

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID quitId = event.getPlayer().getUniqueId();
        hiddenPlayers.remove(quitId);
        hiddenEntities.remove(quitId);

        for (HashSet<UUID> hiddenSet : hiddenPlayers.values()) {
            hiddenSet.remove(quitId);
        }
    }

    public static void cleanupAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(target)) {
                    viewer.showPlayer(dakotaAC.getInstance(), target);
                }
            }
            for (World world : Bukkit.getWorlds()) {
                for (Entity entity : world.getEntities()) {
                    if (!(entity instanceof Player) && entity.isValid()) {
                        viewer.showEntity(dakotaAC.getInstance(), entity);
                    }
                }
            }
        }
        hiddenPlayers.clear();
        hiddenEntities.clear();
    }
}