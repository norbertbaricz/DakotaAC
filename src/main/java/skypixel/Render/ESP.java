package skypixel.Render;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import skypixel.dakotaAC;

import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

/**
 * Sistem de prevenire a ESP-ului.
 * Ascunde proactiv jucătorii și entitățile care nu sunt în linia vizuală sau sunt prea departe.
 * Aceasta este o măsură de prevenire și NU generează alerte (flags).
 */
public class ESP implements Listener {

    // =========================================================
    // --- Easy-to-tune thresholds ---
    // =========================================================
    private static final double MAX_ENTITY_RANGE = 48.0D; // Distanța maximă în blocuri la care calculăm entitățile
    private static final long TASK_INTERVAL_TICKS = 5L;   // Cât de des rulează (5 tick-uri = de 4 ori pe secundă)
    // =========================================================

    // Stocăm jucătorii și entitățile care sunt ascunse pentru fiecare "privitor".
    private static final HashMap<UUID, HashSet<UUID>> hiddenPlayers = new HashMap<>();
    private static final HashMap<UUID, HashSet<UUID>> hiddenEntities = new HashMap<>();

    public ESP() {
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!dakotaAC.isCheckActive("ESP")) return;

                for (Player viewer : Bukkit.getOnlinePlayers()) {
                    // Spectatorii pot vedea prin pereți, deci îi ignorăm
                    if (viewer.getGameMode() == GameMode.SPECTATOR) continue;

                    UUID viewerId = viewer.getUniqueId();
                    hiddenPlayers.putIfAbsent(viewerId, new HashSet<>());
                    hiddenEntities.putIfAbsent(viewerId, new HashSet<>());

                    HashSet<UUID> hiddenP = hiddenPlayers.get(viewerId);
                    HashSet<UUID> hiddenE = hiddenEntities.get(viewerId);

                    // 1. Logica pentru JUCĂTORI (metoda vanish de jucători)
                    for (Player target : Bukkit.getOnlinePlayers()) {
                        if (viewer.equals(target)) continue; // Nu te poți ascunde de tine însuți

                        if (!viewer.hasLineOfSight(target)) {
                            hidePlayer(viewer, target, hiddenP);
                        } else {
                            showPlayer(viewer, target, hiddenP);
                        }
                    }

                    // 2. Logica pentru ENTITĂȚI NON-PLAYER (mob-uri, iteme pe jos, etc.)
                    // Folosim getNearbyEntities pentru performanță (nu iterăm toate entitățile din lume)
                    for (Entity target : viewer.getNearbyEntities(MAX_ENTITY_RANGE, MAX_ENTITY_RANGE, MAX_ENTITY_RANGE)) {
                        // Excludem jucătorii, i-am verificat deja mai sus
                        if (target instanceof Player) continue;

                        boolean outOfRange = viewer.getLocation().distanceSquared(target.getLocation()) > (MAX_ENTITY_RANGE * MAX_ENTITY_RANGE);
                        boolean noLineOfSight = !viewer.hasLineOfSight(target);

                        // Dacă entitatea e prea departe SAU nu e în linia vizuală, o ascundem
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

    // ========================================================================
    // Metode pentru JUCĂTORI (hidePlayer / showPlayer)
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

    // ========================================================================
    // Metode pentru ENTITĂȚI (hideEntity / showEntity - specifice Paper 1.17+)
    // ========================================================================

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

        // Ștergem datele jucătorului care a ieșit
        hiddenPlayers.remove(quitId);
        hiddenEntities.remove(quitId);

        // Eliminăm jucătorul din listele celorlalți
        for (HashSet<UUID> hiddenSet : hiddenPlayers.values()) {
            hiddenSet.remove(quitId);
        }
    }

    /**
     * Previne bug-urile de invizibilitate la /reload sau la oprirea pluginului.
     * Face toți jucătorii și entitățile din nou vizibile.
     */
    public static void cleanupAll() {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            // Re-arătăm jucătorii
            for (Player target : Bukkit.getOnlinePlayers()) {
                if (!viewer.equals(target)) {
                    viewer.showPlayer(dakotaAC.getInstance(), target);
                }
            }
            // Re-arătăm toate entitățile din lume către privitor
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