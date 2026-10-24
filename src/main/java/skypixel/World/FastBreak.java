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
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.FastBreak. */
public class FastBreak implements Listener {

    @Option(value = "min-obsidian-time", min = 1.0, max = 1.0E9)
    private static volatile long MIN_OBSIDIAN_TIME = 1500L;

    @Option(value = "min-heavy-time", min = 1.0, max = 1.0E9)
    private static volatile long MIN_HEAVY_TIME = 350L;

    @Option(value = "min-deepslate-time", min = 1.0, max = 1.0E9)
    private static volatile long MIN_DEEPSLATE_TIME = 150L;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 2;

    private final ConcurrentHashMap<UUID, Long> breakStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactBreakDuration = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Integer> breakVL = new ConcurrentHashMap<>();

    public FastBreak() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastBreak")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "FastBreak")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;
                            UUID uuid = player.getUniqueId();

                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().read(0);
                            long now = System.currentTimeMillis();

                            if (action == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {

                                breakStartTimes.put(uuid, now);
                            }
                            else if (action == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {

                                long start = breakStartTimes.getOrDefault(uuid, 0L);

                                if (start > 0) {
                                    exactBreakDuration.put(uuid, now - start);
                                } else {

                                    exactBreakDuration.put(uuid, 0L);
                                }
                            }
                            else if (action == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {

                                breakStartTimes.remove(uuid);
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("FastBreak", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("FastBreak")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "FastBreak")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Material blockType = event.getBlock().getType();
        String blockName = blockType.name();

        long timeTaken = exactBreakDuration.getOrDefault(uuid, -1L);

        if (timeTaken == -1L) return;

        boolean isFlagged = false;
        String flagReason = "";
        int vl = breakVL.getOrDefault(uuid, 0);

        if (blockName.contains("OBSIDIAN") || blockName.equals("RESPAWN_ANCHOR")) {
            if (timeTaken < MIN_OBSIDIAN_TIME) {
                isFlagged = true;
                flagReason = Lang.text("checks.FastBreak.details-broke-obsidian-extremely-fast-ms", "time-taken", timeTaken);
            }
        }

        else if (blockName.equals("ANCIENT_DEBRIS") || blockName.equals("ENDER_CHEST") || blockName.contains("ANVIL")) {
            if (timeTaken < MIN_HEAVY_TIME) {
                isFlagged = true;
                flagReason = Lang.text("checks.FastBreak.details-broke-heavy-block-extremely-fast-ms", "time-taken", timeTaken);
            }
        }

        else if (blockName.contains("DEEPSLATE")) {
            if (timeTaken < MIN_DEEPSLATE_TIME) {
                isFlagged = true;
                flagReason = Lang.text("checks.FastBreak.details-insta-mined-deepslate-block-ms", "time-taken", timeTaken);
            }
        }

        if (isFlagged) {
            vl++;
            breakVL.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastBreak", Lang.text("checks.FastBreak.name-fastbreak"), flagReason);

                if (CheckPolicy.cancel("FastBreak")) event.setCancelled(true);

                player.sendBlockChange(event.getBlock().getLocation(), event.getBlock().getBlockData());

                breakVL.put(uuid, MAX_VIOLATIONS - 1); 
            }
        } else {

            if (vl > 0) breakVL.put(uuid, vl - 1);
        }

        exactBreakDuration.remove(uuid);
        breakStartTimes.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid = event.getPlayer().getUniqueId();
        breakStartTimes.remove(uuid);
        exactBreakDuration.remove(uuid);
        breakVL.remove(uuid);
    }
}