package ro.skypixel.play.dakotaAC.Combat;

import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;

public class AutoClicker implements Listener {

    // Enum to distinguish between different types of clicks we are tracking
    private enum ClickType {
        LEFT_CLICK_SWING,  // For left-click swings (combat related)
        RIGHT_CLICK_ITEM_USE // For right-click item usage (snowballs, eggs, etc.)
    }

    // Stores click timestamps: ClickType -> Player UUID -> Queue of click timestamps
    private final Map<ClickType, Map<UUID, Queue<Long>>> clickTimesByType = new HashMap<>();
    // Stores click intervals: ClickType -> Player UUID -> Queue of intervals
    private final Map<ClickType, Map<UUID, Queue<Long>>> clickIntervalsByType = new HashMap<>();

    // Configuration constants
    private static final int MAX_CPS_LEFT_CLICK = 25; // Max CPS for left-click swings (Updated as per user request)
    private static final int MAX_CPS_RIGHT_CLICK = 25; // Max CPS for item usage (Updated as per user request)
    private static final long CLICK_WINDOW_MS = 1000; // Window size in milliseconds (1 second) to count CPS
    private static final long CONSISTENCY_INTERVAL_THRESHOLD_MS = 10; // Max deviation for consistent clicks (ms)
    private static final int MIN_INTERVALS_FOR_CONSISTENCY_CHECK = 10; // Min number of intervals to check for consistency

    // Set of items considered "spammable" for right-click detection
    private static final Set<Material> SPAMMABLE_RIGHT_CLICK_ITEMS = new HashSet<>(Arrays.asList(
            Material.SNOWBALL,
            Material.EGG,
            Material.ENDER_PEARL
            // Add other items like potions if necessary, though potion drinking has its own cooldowns
    ));

    /**
     * Handles player interaction events to detect rapid clicking.
     *
     * @param event The PlayerInteractEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_AIR) {
            // Player is swinging their arm in the air (common in combat, not breaking blocks)
            checkAutoClicker(player, ClickType.LEFT_CLICK_SWING, "LeftClickAirSwing", MAX_CPS_LEFT_CLICK);
        } else if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            ItemStack itemInHand = event.getItem(); // Could be null if hand is empty
            if (itemInHand != null && SPAMMABLE_RIGHT_CLICK_ITEMS.contains(itemInHand.getType())) {
                // Player is using a known spammable item (e.g., snowballs, eggs)
                // This covers using items "fara sa ma uit la blocuri" (RIGHT_CLICK_AIR)
                // or using them towards a block (RIGHT_CLICK_BLOCK with item)
                checkAutoClicker(player, ClickType.RIGHT_CLICK_ITEM_USE, itemInHand.getType().name(), MAX_CPS_RIGHT_CLICK);
            }
        }
        // Note: LEFT_CLICK_BLOCK is intentionally not checked here for general autoclicker
        // to avoid false positives from players breaking blocks quickly.
    }

    /**
     * Checks a player for autoclicker behavior based on the type of click.
     *
     * @param player      The player to check.
     * @param type        The type of click (LEFT_CLICK_SWING or RIGHT_CLICK_ITEM_USE).
     * @param alertDetail A string detail for the alert message (e.g., "LeftClickAirSwing" or item name).
     * @param maxCpsForType The maximum CPS allowed for this specific click type.
     */
    private void checkAutoClicker(Player player, ClickType type, String alertDetail, int maxCpsForType) {
        UUID playerId = player.getUniqueId();
        long currentTime = System.currentTimeMillis();

        // Get or create the data structures for this player and click type
        Map<UUID, Queue<Long>> typeClickTimesMap = clickTimesByType.computeIfAbsent(type, k -> new HashMap<>());
        Map<UUID, Queue<Long>> typeClickIntervalsMap = clickIntervalsByType.computeIfAbsent(type, k -> new HashMap<>());

        Queue<Long> playerClickTimestamps = typeClickTimesMap.computeIfAbsent(playerId, k -> new LinkedList<>());
        Queue<Long> playerClickIntervals = typeClickIntervalsMap.computeIfAbsent(playerId, k -> new LinkedList<>());

        // Add current click timestamp
        playerClickTimestamps.add(currentTime);

        // Calculate and add interval if there's more than one click
        if (playerClickTimestamps.size() > 1) {
            Long previousClickTime = ((LinkedList<Long>) playerClickTimestamps).get(playerClickTimestamps.size() - 2);
            long interval = currentTime - previousClickTime;
            playerClickIntervals.add(interval);
        }

        // Remove old click timestamps and their corresponding intervals that are outside the CLICK_WINDOW_MS
        while (!playerClickTimestamps.isEmpty() && currentTime - playerClickTimestamps.peek() > CLICK_WINDOW_MS) {
            playerClickTimestamps.poll();
            if (!playerClickIntervals.isEmpty()) {
                playerClickIntervals.poll();
            }
        }

        // CPS Check
        int cps = playerClickTimestamps.size(); // Clicks within the window
        if (cps > maxCpsForType) {
            Alert.getInstance().alert("AutoClicker", player);
        }

        // Consistency Check
        if (playerClickIntervals.size() >= MIN_INTERVALS_FOR_CONSISTENCY_CHECK) {
            long sumOfIntervals = 0;
            for (long interval : playerClickIntervals) {
                sumOfIntervals += interval;
            }
            long averageInterval = playerClickIntervals.isEmpty() ? 0 : sumOfIntervals / playerClickIntervals.size();

            boolean isConsistent = true;
            if (playerClickIntervals.isEmpty() && MIN_INTERVALS_FOR_CONSISTENCY_CHECK > 0) {
                isConsistent = false;
            } else {
                for (long interval : playerClickIntervals) {
                    if (Math.abs(interval - averageInterval) > CONSISTENCY_INTERVAL_THRESHOLD_MS) {
                        isConsistent = false;
                        break;
                    }
                }
            }

            if (isConsistent && !playerClickIntervals.isEmpty()) {
                Alert.getInstance().alert("AutoClicker", player);
            }
        }

        // Clean up player data if they haven't clicked for a while
        if (playerClickTimestamps.isEmpty()) {
            typeClickTimesMap.remove(playerId);
            typeClickIntervalsMap.remove(playerId);
            if (typeClickTimesMap.isEmpty()) {
                clickTimesByType.remove(type);
            }
            if (typeClickIntervalsMap.isEmpty()) {
                clickIntervalsByType.remove(type);
            }
        }
    }
}
