package ro.skypixel.play.dakotaAC.World; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ro.skypixel.play.dakotaAC.Alert;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class FastBreak implements Listener {

    // Base break times in milliseconds. These should represent the time to break a block
    // with an appropriate, un-enchanted tool, without Haste or other speed-enhancing effects.
    // These values are critical and need to be accurate for good detection.
    // User's provided values are used as a starting point.
    private static final Map<Material, Long> BASE_BREAK_TIMES_MS = new HashMap<>();

    static {
        // Values from user, assuming they represent "un-enchanted tool, no haste"
        BASE_BREAK_TIMES_MS.put(Material.STONE, 900L);
        BASE_BREAK_TIMES_MS.put(Material.COBBLESTONE, 900L); // Similar to stone
        BASE_BREAK_TIMES_MS.put(Material.DIRT, 250L);
        BASE_BREAK_TIMES_MS.put(Material.GRASS_BLOCK, 300L); // Slightly more than dirt
        BASE_BREAK_TIMES_MS.put(Material.SAND, 300L);
        BASE_BREAK_TIMES_MS.put(Material.GRAVEL, 350L);
        BASE_BREAK_TIMES_MS.put(Material.OBSIDIAN, 3000L); // This is very fast for obsidian without Eff V + Haste II.
        // Vanilla Diamond Pick (no enchants/haste): ~9375ms.
        // If 3000ms is the intended baseline, it's very lenient or assumes a specific server context.
        // Using user's value for now.

        // Common Ores (assuming appropriate basic pickaxe, e.g., Stone Pick for Iron/Coal, Iron Pick for Gold/Diamond)
        // These times are rough estimates for un-enchanted tools.
        BASE_BREAK_TIMES_MS.put(Material.COAL_ORE, 2250L);
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_COAL_ORE, 3375L); // Deepslate variants are 1.5x harder
        BASE_BREAK_TIMES_MS.put(Material.IRON_ORE, 2250L);
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_IRON_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.COPPER_ORE, 2250L);
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_COPPER_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.GOLD_ORE, 2250L); // Requires Iron Pick
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_GOLD_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.DIAMOND_ORE, 2250L); // Requires Iron Pick
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_DIAMOND_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.LAPIS_ORE, 2250L); // Requires Stone Pick
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_LAPIS_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.REDSTONE_ORE, 2250L); // Requires Iron Pick
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_REDSTONE_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.EMERALD_ORE, 2250L); // Requires Iron Pick
        BASE_BREAK_TIMES_MS.put(Material.DEEPSLATE_EMERALD_ORE, 3375L);
        BASE_BREAK_TIMES_MS.put(Material.NETHER_QUARTZ_ORE, 2250L); // Any pickaxe
        BASE_BREAK_TIMES_MS.put(Material.NETHER_GOLD_ORE, 2250L); // Any pickaxe

        // Wood Logs (assuming basic axe, e.g., wooden axe)
        long woodLogTime = 1500L; // Approx for wooden axe
        BASE_BREAK_TIMES_MS.put(Material.OAK_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.SPRUCE_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.BIRCH_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.JUNGLE_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.ACACIA_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.DARK_OAK_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.MANGROVE_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.CHERRY_LOG, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.CRIMSON_STEM, woodLogTime);
        BASE_BREAK_TIMES_MS.put(Material.WARPED_STEM, woodLogTime);

        // Other common blocks
        BASE_BREAK_TIMES_MS.put(Material.GLASS, 250L); // Bare hands (instant with Silk Touch tool, but baseline is no enchants)
        BASE_BREAK_TIMES_MS.put(Material.SANDSTONE, 600L); // Wooden Pick
        BASE_BREAK_TIMES_MS.put(Material.NETHERRACK, 300L); // Wooden Pick
        // This map should be expanded for better coverage and accuracy.
    }

    // Fallback break time for blocks not listed in the map.
    private static final long DEFAULT_UNLISTED_BLOCK_BREAK_TIME_MS = 750L;
    // Tolerance: if player breaks block (1 - TOLERANCE_FACTOR) * base_time, it's flagged.
    // e.g., 0.20 means 20% faster than baseline is suspicious.
    private static final double BREAK_TIME_TOLERANCE_FACTOR = 0.20; // Allow breaking 20% faster than baseline

    // Stores data about the player's current block breaking sequence.
    private static class PlayerBreakState {
        long sequenceStartOrLastBreakTimeMs; // Time when the last block OF THE SAME TYPE was broken,
        // or when this sequence of this block type started.
        Material currentBlockTypeInSequence;

        PlayerBreakState(long startTimeMs, Material type) {
            this.sequenceStartOrLastBreakTimeMs = startTimeMs;
            this.currentBlockTypeInSequence = type;
        }
    }

    private final Map<UUID, PlayerBreakState> playerBreakStates = new HashMap<>();

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();
        UUID playerUUID = player.getUniqueId();

        // 1. Exemptions: Creative/Spectator mode.
        // The check is designed to see if they break faster than "no effects/enchants" baseline.
        // So, having effects/enchants doesn't exempt them from the check itself.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            playerBreakStates.remove(playerUUID); // Clear state if they switch to an exempt mode
            return;
        }

        Material brokenBlockType = event.getBlock().getType();
        long currentTimeMs = System.currentTimeMillis();

        PlayerBreakState currentState = playerBreakStates.get(playerUUID);

        // 2. Check if this is the first block broken by the player or a new type of block sequence.
        if (currentState == null || currentState.currentBlockTypeInSequence != brokenBlockType) {
            // Started breaking a new type of block or first block ever for this player session.
            // We record this break's completion time as the start for the *next* potential block of this type.
            // We cannot reliably check the duration for breaking *this specific block* with this model,
            // as we don't know when they *started* mining it.
            playerBreakStates.put(playerUUID, new PlayerBreakState(currentTimeMs, brokenBlockType));
            return;
        }

        // 3. Player is breaking the same type of block consecutively.
        // Calculate the time taken since the last block of this type was broken.
        long actualIntervalMs = currentTimeMs - currentState.sequenceStartOrLastBreakTimeMs;

        // Get the base theoretical minimum break time for this block type (no Haste/Efficiency, appropriate basic tool)
        long minTheoreticalTimeForBlockMs = BASE_BREAK_TIMES_MS.getOrDefault(brokenBlockType, DEFAULT_UNLISTED_BLOCK_BREAK_TIME_MS);

        // Apply tolerance: if they are (1 - tolerance_factor) times faster, it's suspicious.
        long toleratedMinTimeMs = (long) (minTheoreticalTimeForBlockMs * (1.0 - BREAK_TIME_TOLERANCE_FACTOR));

        // Ensure there's a minimum checkable time to avoid issues with very fast theoretical break times
        // or potential floating point inaccuracies leading to near-zero tolerated times.
        // 50ms = 1 server tick. Breaking a block faster than 1 tick is generally impossible without instabreak.
        if (toleratedMinTimeMs < 50L) {
            toleratedMinTimeMs = 50L;
        }


        if (actualIntervalMs < toleratedMinTimeMs) {
            // The check for optimal conditions (onGround, not inWater) was removed as per thought process,
            // because if they break faster than baseline even WITH penalties, it's more suspicious.
            // The BASE_BREAK_TIMES_MS should ideally reflect optimal conditions.

            Alert.getInstance().alert(
                    "FastBreak",
                    player
            );
            event.setCancelled(true);

            // Reset state after flagging to prevent alert spam for the same "burst"
            // and to require a new sequence to be established.
            playerBreakStates.remove(playerUUID);
            return; // Important to return after cancelling and resetting
        }

        // Update the start time for the next block in this sequence of the same type.
        // This effectively sets the completion time of the current block.
        currentState.sequenceStartOrLastBreakTimeMs = currentTimeMs;
        // currentState.currentBlockTypeInSequence remains the same.
    }

    /**
     * Clears player data on quit to prevent memory leaks.
     * @param event The PlayerQuitEvent.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        playerBreakStates.remove(event.getPlayer().getUniqueId());
    }
}
