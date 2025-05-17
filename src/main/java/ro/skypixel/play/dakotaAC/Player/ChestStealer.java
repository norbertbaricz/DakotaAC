package ro.skypixel.play.dakotaAC.Player; // Assuming this is the correct package

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;

public class ChestStealer implements Listener {

    // Max interval between consecutive clicks in a "fast burst" (milliseconds)
    private static final long MAX_CLICK_INTERVAL_MS = 150L;
    // Number of recent clicks to analyze for speed
    private static final int CLICK_WINDOW_SIZE = 4;
    // If a fast click burst is detected, minimum number of items taken during that burst to trigger an alert
    private static final int MIN_ITEMS_TAKEN_DURING_FAST_BURST = 3; // Adjust based on testing

    // Stores recent click actions for each player
    private final Map<UUID, Deque<ClickActionLog>> playerClickLogs = new HashMap<>();

    // Inner class to store timestamp and items taken for each relevant click
    private static class ClickActionLog {
        final long timestamp;
        final int itemsTakenThisClick;

        ClickActionLog(long timestamp, int itemsTakenThisClick) {
            this.timestamp = timestamp;
            this.itemsTakenThisClick = itemsTakenThisClick;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }

        Player player = (Player) event.getWhoClicked();
        InventoryType inventoryType = event.getInventory().getType();

        // Check if the clicked inventory is one we monitor for stealing
        if (inventoryType == InventoryType.CHEST ||
                inventoryType == InventoryType.ENDER_CHEST ||
                inventoryType == InventoryType.SHULKER_BOX ||
                inventoryType == InventoryType.BARREL) {

            // Check if the click was in the top inventory (the container itself)
            if (event.getRawSlot() < event.getInventory().getSize()) {
                long currentTimeMs = System.currentTimeMillis();
                UUID playerUUID = player.getUniqueId();

                // Calculate items taken in this specific click
                int itemsTakenThisClick = calculateItemsTaken(event);

                // Get or create the log queue for the player
                Deque<ClickActionLog> logs = playerClickLogs.computeIfAbsent(playerUUID, k -> new LinkedList<>());
                logs.addLast(new ClickActionLog(currentTimeMs, itemsTakenThisClick));

                // Maintain the window size
                while (logs.size() > CLICK_WINDOW_SIZE) {
                    logs.removeFirst();
                }

                // If we have enough clicks in the window to analyze
                if (logs.size() == CLICK_WINDOW_SIZE) {
                    if (areClicksSuspiciouslyFast(logs)) {
                        int totalItemsTakenInWindow = 0;
                        for (ClickActionLog log : logs) {
                            totalItemsTakenInWindow += log.itemsTakenThisClick;
                        }

                        if (totalItemsTakenInWindow >= MIN_ITEMS_TAKEN_DURING_FAST_BURST) {
                            Alert.getInstance().alert("ChestStealer", player);
                            event.setCancelled(true);
                            // Optionally, clear logs to prevent immediate re-flagging for the same burst
                            logs.clear();
                        }
                    }
                }
            }
        }
    }

    /**
     * Calculates the number of items effectively taken from the container in this click event.
     * @param event The InventoryClickEvent.
     * @return The number of items taken, or 0 if no items were taken from the container.
     */
    private int calculateItemsTaken(InventoryClickEvent event) {
        ItemStack currentItemInSlot = event.getCurrentItem(); // Item in the slot *before* the click
        InventoryAction action = event.getAction();

        if (currentItemInSlot == null || currentItemInSlot.getType() == Material.AIR) {
            return 0; // No item in the slot to take
        }

        // Check if the click is within the top inventory (the container)
        if (event.getRawSlot() >= event.getView().getTopInventory().getSize()) {
            return 0; // Click was not in the container
        }

        switch (action) {
            case PICKUP_ALL:
            case MOVE_TO_OTHER_INVENTORY: // Shift-click from container
            case HOTBAR_SWAP:             // Number key swap from container to hotbar
            case HOTBAR_MOVE_AND_READD:   // Similar to HOTBAR_SWAP
                return currentItemInSlot.getAmount();
            case PICKUP_HALF:
                return (currentItemInSlot.getAmount() + 1) / 2; // Standard half-stack calculation
            case PICKUP_ONE:
                return 1;
            case PICKUP_SOME:
                // This action is typically when right-clicking to pick up half, same as PICKUP_HALF
                return (currentItemInSlot.getAmount() + 1) / 2;
            default:
                return 0; // Other actions (e.g., placing items, unknown actions) don't count as "taking"
        }
    }

    /**
     * Checks if the sequence of clicks in the provided logs is suspiciously fast.
     * This means all click intervals within the window are below MAX_CLICK_INTERVAL_MS.
     * @param logs The deque of ClickActionLog to check. Must contain CLICK_WINDOW_SIZE elements.
     * @return True if the click sequence is considered suspiciously fast, false otherwise.
     */
    private boolean areClicksSuspiciouslyFast(Deque<ClickActionLog> logs) {
        if (logs.size() < 2) { // Need at least two clicks to have an interval
            return false;
        }

        ClickActionLog[] logsArray = logs.toArray(new ClickActionLog[0]);
        // Check intervals between all clicks in the current window
        for (int i = 1; i < logsArray.length; i++) {
            long interval = logsArray[i].timestamp - logsArray[i - 1].timestamp;
            if (interval > MAX_CLICK_INTERVAL_MS) {
                return false; // Found an interval that is not "fast"
            }
        }
        return true; // All intervals in the window were fast
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player) {
            playerClickLogs.remove(event.getPlayer().getUniqueId());
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerClickLogs.remove(event.getPlayer().getUniqueId());
    }
}
