package skypixel.Player;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Original detection formulas; annotated defaults below are loaded from Modules.InventoryCleaner. */
public class InventoryCleaner implements Listener {

    @Option(value = "time-window-ms", min = 1.0, max = 1.0E9)
    private static volatile long TIME_WINDOW_MS = 1000L;

    @Option(value = "max-inventory-actions", min = 1.0, max = 1.0E9)
    private static volatile int MAX_INVENTORY_ACTIONS = 20;

    @Option(value = "max-item-drops", min = 1.0, max = 1.0E9)
    private static volatile int MAX_ITEM_DROPS = 15;

    private final ConcurrentHashMap<UUID, Tracker> trackerMap = new ConcurrentHashMap<>();

    public InventoryCleaner() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("InventoryCleaner")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "InventoryCleaner")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            trackerMap.putIfAbsent(uuid, new Tracker());
                            Tracker tracker = trackerMap.get(uuid);

                            long now = System.currentTimeMillis();

                            tracker.clicks.add(now);

                            while (!tracker.clicks.isEmpty() && tracker.clicks.peek() < now - TIME_WINDOW_MS) {
                                tracker.clicks.poll();
                            }

                            int currentActions = tracker.clicks.size();

                            if (currentActions > MAX_INVENTORY_ACTIONS) {

                                if (CheckPolicy.cancel("InventoryCleaner")) event.setCancelled(true);

                                if (!tracker.clickFlagged) {
                                    tracker.clickFlagged = true;

                                    Diagnostics.sync(() -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "InventoryCleaner", Lang.text("checks.InventoryCleaner.name-inventorycleaner"), Lang.text("checks.InventoryCleaner.details-inhuman-inventory-actions-actions-sec", "current-actions", currentActions));
                                        }
                                    });
                                }

                                tracker.clicks.clear();
                            } else {
                                tracker.clickFlagged = false;
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("InventoryCleaner", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDropItem(PlayerDropItemEvent event) {
        if (!dakotaAC.isCheckActive("InventoryCleaner")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "InventoryCleaner")) return;

        if (player.getGameMode().name().equals("CREATIVE")) return;

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        trackerMap.putIfAbsent(uuid, new Tracker());
        Tracker tracker = trackerMap.get(uuid);

        tracker.drops.add(now);

        while (!tracker.drops.isEmpty() && tracker.drops.peek() < now - TIME_WINDOW_MS) {
            tracker.drops.poll();
        }

        int currentDrops = tracker.drops.size();

        if (currentDrops > MAX_ITEM_DROPS) {

            if (CheckPolicy.cancel("InventoryCleaner")) event.setCancelled(true);

            if (!tracker.dropFlagged) {
                tracker.dropFlagged = true;
                flagPlayer.addFlag(player, "InventoryCleaner", Lang.text("checks.InventoryCleaner.name-inventorycleaner-autodrop"), Lang.text("checks.InventoryCleaner.details-inhuman-item-drop-rate-drops-sec", "current-drops", currentDrops));
            }

            tracker.drops.clear();
        } else {
            tracker.dropFlagged = false;
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        trackerMap.remove(event.getPlayer().getUniqueId());
    }

    private static class Tracker {
        final ConcurrentLinkedQueue<Long> clicks = new ConcurrentLinkedQueue<>();
        final ConcurrentLinkedQueue<Long> drops = new ConcurrentLinkedQueue<>();
        boolean clickFlagged = false;
        boolean dropFlagged = false;
    }
}