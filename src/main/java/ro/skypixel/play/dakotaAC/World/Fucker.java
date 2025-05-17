package ro.skypixel.play.dakotaAC.World; // Assuming this is the correct package

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.FluidCollisionMode; // Import for FluidCollisionMode
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import ro.skypixel.play.dakotaAC.Alert;

// No longer need EnumSet or a predefined list of blocks for this generalized check
// import java.util.EnumSet;
// import java.util.Set;

public class Fucker implements Listener {

    // Maximum distance the player can legitimately target a block for breaking in survival.
    // Vanilla survival reach is around 4.5-5 blocks.
    private static final int MAX_BREAK_DISTANCE = 5; // Integer for getTargetBlockExact

    /**
     * Handles block break events to detect if a player breaks a block
     * without a clear line of sight or by targeting a different block.
     *
     * @param event The BlockBreakEvent.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }

        Player player = event.getPlayer();

        // 1. Exemptions: Ignore players in Creative or Spectator mode.
        if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }

        Block brokenBlock = event.getBlock(); // The block that was actually broken

        // 2. Determine what block the server thinks the player is looking at.
        // FluidCollisionMode.NEVER allows the ray to pass through liquids.
        Block targetSeenByServer = player.getTargetBlockExact(MAX_BREAK_DISTANCE, FluidCollisionMode.NEVER);

        // 3. Perform the check:
        // If the server sees the player targeting nothing (null) within range,
        // OR if the block seen by the server is different from the block actually broken,
        // then it's a suspicious break.
        if (targetSeenByServer == null || !targetSeenByServer.equals(brokenBlock)) {
            // Alert message can be customized. Using "Fucker" as per original user code.
            // A more descriptive internal name might be "NoLOSBreak" or "GhostBreak".
            Alert.getInstance().alert("Fucker", player);
            event.setCancelled(true); // Prevent the block from being broken
        }
    }
}
