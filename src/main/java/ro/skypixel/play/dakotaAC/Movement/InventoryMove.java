package ro.skypixel.play.dakotaAC.Movement;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.InventoryView;
import org.bukkit.potion.PotionEffectType;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class InventoryMove implements Listener {

    // Inner class to store player-specific data related to inventory interaction
    private static class PlayerInventoryData {
        long inventoryOpenTimeMs;         // Timestamp when the current GUI was effectively considered open for checks
        Location locationAtLastMoveCheck; // Player's location at the last PlayerMoveEvent check
        long lastClickInInventoryTimeMs;  // Timestamp of the last click while any GUI was open

        PlayerInventoryData(Location initialLocation, long openTime) {
            this.inventoryOpenTimeMs = openTime;
            this.locationAtLastMoveCheck = initialLocation.clone();
            this.lastClickInInventoryTimeMs = 0L;
        }
    }

    private final Map<UUID, PlayerInventoryData> playerData = new HashMap<>();

    // Configuration Constants
    private static final long GRACE_PERIOD_AFTER_OPEN_MS = 500L; // 0.5 seconds
    private static final double MOVEMENT_THRESHOLD_SQUARED = 0.01; // (0.1 blocks)^2
    private static final long CLICK_WINDOW_MS_FOR_FLAG = 500L;   // 0.5 seconds
    private static final float FALL_DISTANCE_THRESHOLD = 0.3f;

    /**
     * Called when a player opens any inventory GUI screen.
     * Starts monitoring if it's not the default game view (i.e., a GUI screen is actually opened).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        Player player = (Player) event.getPlayer();
        InventoryView view = event.getView();

        // Start monitoring if any GUI screen is opened (player's own inv, chest, crafting table, etc.)
        // InventoryType.CRAFTING for a view often means the default game screen (no separate GUI).
        if (view.getType() != InventoryType.CRAFTING) {
            playerData.put(player.getUniqueId(), new PlayerInventoryData(player.getLocation(), System.currentTimeMillis()));
        }
    }

    /**
     * Called when a player closes an inventory. Clears monitoring data.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;
        playerData.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Called when a player clicks in an inventory. Records click time if monitored.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        PlayerInventoryData data = playerData.get(player.getUniqueId());

        if (data != null) {
            // Check if the click is within any part of the open inventory view
            // This ensures clicks in player's own inventory part while a chest is open are also counted.
            if (event.getView().getTopInventory() != null || event.getView().getBottomInventory() != null) {
                data.lastClickInInventoryTimeMs = System.currentTimeMillis();
            }
        }
    }

    /**
     * Called when a player moves. Core logic for InventoryMove detection.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        InventoryView openInventoryView = player.getOpenInventory();

        // 1. If no GUI screen is open (player is in default game view), stop monitoring.
        if (openInventoryView.getType() == InventoryType.CRAFTING) {
            playerData.remove(playerUUID);
            return;
        }

        // A GUI screen is open (player inv, chest, workbench, etc.)
        PlayerInventoryData data = playerData.get(playerUUID);
        if (data == null) {
            // If data is null but a GUI is open, it might be a custom GUI or missed open event.
            // Initialize data now; grace period starts from this movement.
            data = new PlayerInventoryData(event.getFrom(), System.currentTimeMillis());
            playerData.put(playerUUID, data);
        }

        // 2. Exemptions
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR ||
                player.isInsideVehicle() || player.isGliding() || player.isFlying() || player.getAllowFlight() ||
                player.hasPotionEffect(PotionEffectType.LEVITATION)) {
            playerData.remove(playerUUID); // Not applicable, clear data.
            return;
        }

        // 3. Falling check
        if (player.getFallDistance() > FALL_DISTANCE_THRESHOLD) {
            data.locationAtLastMoveCheck = event.getTo().clone();
            data.inventoryOpenTimeMs = System.currentTimeMillis(); // Reset grace period
            data.lastClickInInventoryTimeMs = 0L;
            return;
        }

        long currentTimeMs = System.currentTimeMillis();

        // 4. Grace period check
        if (currentTimeMs - data.inventoryOpenTimeMs < GRACE_PERIOD_AFTER_OPEN_MS) {
            data.locationAtLastMoveCheck = event.getTo().clone();
            return;
        }

        Location fromLocation = data.locationAtLastMoveCheck;
        Location toLocation = event.getTo();

        if (toLocation == null || fromLocation.getWorld() == null || toLocation.getWorld() == null || !fromLocation.getWorld().equals(toLocation.getWorld())) {
            playerData.remove(playerUUID);
            return;
        }
        // Ignore if only rotation changed
        if (fromLocation.getX() == toLocation.getX() && fromLocation.getY() == toLocation.getY() && fromLocation.getZ() == toLocation.getZ()) {
            return;
        }

        double deltaX = toLocation.getX() - fromLocation.getX();
        double deltaZ = toLocation.getZ() - fromLocation.getZ();
        double distanceMovedSquared = (deltaX * deltaX) + (deltaZ * deltaZ);

        // 5. Significant horizontal movement check
        if (distanceMovedSquared > MOVEMENT_THRESHOLD_SQUARED) {
            // 6. Recent click check
            if (data.lastClickInInventoryTimeMs > 0 && (currentTimeMs - data.lastClickInInventoryTimeMs < CLICK_WINDOW_MS_FOR_FLAG)) {
                Alert.getInstance().alert("InventoryMove", player);
                event.setCancelled(true);
                player.teleport(fromLocation); // Teleport back

                data.lastClickInInventoryTimeMs = 0L; // Reset click
                data.inventoryOpenTimeMs = currentTimeMs; // Re-apply grace
                data.locationAtLastMoveCheck = fromLocation.clone();
                return;
            }
        }
        data.locationAtLastMoveCheck = toLocation.clone();
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerData.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();
        PlayerInventoryData data = playerData.get(playerUUID);

        if (data != null) { // If player was being monitored
            data.locationAtLastMoveCheck = event.getTo().clone();
            data.inventoryOpenTimeMs = System.currentTimeMillis(); // Re-apply grace period
            data.lastClickInInventoryTimeMs = 0L; // Reset last click time
        }
    }
}
