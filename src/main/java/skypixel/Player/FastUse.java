package skypixel.Player;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.FastUse. */
public class FastUse implements Listener {

    @Option(value = "min-consume-time-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_CONSUME_TIME_MS = 1200L;

    @Option(value = "min-kelp-time-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_KELP_TIME_MS = 600L;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 2;

    private final ConcurrentHashMap<UUID, Long> consumeStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> fastUseBuffer = new ConcurrentHashMap<>();

    public FastUse() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ITEM,
                        PacketType.Play.Client.USE_ITEM_ON,
                        PacketType.Play.Client.BLOCK_DIG) { 

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastUse")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "FastUse")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            PacketType type = event.getPacketType();

                            if (type == PacketType.Play.Client.BLOCK_DIG) {
                                EnumWrappers.PlayerDigType digType = event.getPacket().getPlayerDigTypes().read(0);
                                if (digType == EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
                                    consumeStartTimes.remove(uuid);
                                }
                                return;
                            }

                            consumeStartTimes.put(uuid, System.currentTimeMillis());

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("FastUse", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (!dakotaAC.isCheckActive("FastUse")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "FastUse")) return;
        UUID uuid = player.getUniqueId();
        Material consumedItem = event.getItem().getType();

        long now = System.currentTimeMillis();
        int vl = fastUseBuffer.getOrDefault(uuid, 0);

        if (consumeStartTimes.containsKey(uuid)) {

            long timeTaken = now - consumeStartTimes.get(uuid);
            long minLegalTime = MIN_CONSUME_TIME_MS;

            if (consumedItem == Material.DRIED_KELP) {
                minLegalTime = MIN_KELP_TIME_MS;
            }

            if (timeTaken < minLegalTime) {
                vl++;
                fastUseBuffer.put(uuid, vl);

                if (vl >= MAX_VIOLATIONS) {
                    flagPlayer.addFlag(player, "FastUse", Lang.text("checks.FastUse.name-fastuse"), Lang.text("checks.FastUse.details-consumed-abnormally-fast-ms", "name", consumedItem.name(), "time-taken", timeTaken));
                    if (CheckPolicy.cancel("FastUse")) event.setCancelled(true);
                    consumeStartTimes.remove(uuid); 

                    fastUseBuffer.put(uuid, MAX_VIOLATIONS - 1); 
                }
            } else {

                if (vl > 0) fastUseBuffer.put(uuid, vl - 1);

                consumeStartTimes.put(uuid, now);
            }

        } else {

            vl++;
            fastUseBuffer.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastUse", Lang.text("checks.FastUse.name-fastuse-instaeat"), Lang.text("checks.FastUse.details-consumed-item-without-starting-the-use-animation"));
                if (CheckPolicy.cancel("FastUse")) event.setCancelled(true);
                fastUseBuffer.put(uuid, MAX_VIOLATIONS - 1);
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        consumeStartTimes.remove(uuid);
        fastUseBuffer.remove(uuid);
    }
}