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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Original detection formulas; annotated defaults below are loaded from Modules.ChestStealer. */
public class ChestStealer implements Listener {

    @Option(value = "min-reaction-time-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_REACTION_TIME_MS = 120L;

    @Option(value = "max-clicks-per-second", min = 1.0, max = 1.0E9)
    private static volatile int MAX_CLICKS_PER_SECOND = 15;

    @Option(value = "time-window-ms", min = 1.0, max = 1.0E9)
    private static volatile long TIME_WINDOW_MS = 1000L;

    private final ConcurrentHashMap<UUID, Tracker> containerData = new ConcurrentHashMap<>();

    public ChestStealer() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST, 
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("ChestStealer")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "ChestStealer")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            Tracker tracker = containerData.get(uuid);
                            if (tracker == null) return;

                            long now = System.currentTimeMillis();

                            int ping = player.getPing();
                            long reactionTime = (now - tracker.openTime) - (ping / 2); 

                            if (reactionTime < MIN_REACTION_TIME_MS && !tracker.reactionFlagged) {
                                tracker.reactionFlagged = true;

                                if (CheckPolicy.cancel("ChestStealer")) event.setCancelled(true);

                                Diagnostics.sync(() -> {
                                    if (player.isOnline()) {
                                        flagPlayer.addFlag(player, "ChestStealer", Lang.text("checks.ChestStealer.name-cheststealer-reaction"), Lang.text("checks.ChestStealer.details-looted-instantly-after-opening-react-ms-ping-ms", "reaction-time", reactionTime, "ping", ping));
                                    }
                                });
                                return;
                            }

                            tracker.clicks.add(now);

                            while (!tracker.clicks.isEmpty() && tracker.clicks.peek() < now - TIME_WINDOW_MS) {
                                tracker.clicks.poll();
                            }

                            int currentSpeed = tracker.clicks.size();

                            if (currentSpeed > MAX_CLICKS_PER_SECOND) {
                                if (CheckPolicy.cancel("ChestStealer")) event.setCancelled(true);

                                if (!tracker.speedFlagged) {
                                    tracker.speedFlagged = true;

                                    Diagnostics.sync(() -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "ChestStealer", Lang.text("checks.ChestStealer.name-cheststealer-speed"), Lang.text("checks.ChestStealer.details-inhuman-looting-speed-items-sec", "current-speed", currentSpeed));
                                        }
                                    });
                                }

                                tracker.clicks.clear();
                            } else {

                                tracker.speedFlagged = false;
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("ChestStealer", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!dakotaAC.isCheckActive("ChestStealer")) return;
        if (event.isCancelled()) return;
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "ChestStealer")) return;
        if (player.getGameMode().name().equals("CREATIVE")) return;

        InventoryType type = event.getInventory().getType();

        if (isContainer(type)) {
            containerData.put(player.getUniqueId(), new Tracker(System.currentTimeMillis()));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        containerData.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        containerData.remove(event.getPlayer().getUniqueId());
    }

    private boolean isContainer(InventoryType type) {
        return type == InventoryType.CHEST || type == InventoryType.ENDER_CHEST ||
                type == InventoryType.BARREL || type == InventoryType.SHULKER_BOX ||
                type == InventoryType.HOPPER || type == InventoryType.DISPENSER ||
                type == InventoryType.DROPPER;
    }

    private static class Tracker {
        final long openTime;
        final ConcurrentLinkedQueue<Long> clicks; 
        boolean reactionFlagged;
        boolean speedFlagged;

        public Tracker(long openTime) {
            this.openTime = openTime;
            this.clicks = new ConcurrentLinkedQueue<>();
            this.reactionFlagged = false;
            this.speedFlagged = false;
        }
    }
}