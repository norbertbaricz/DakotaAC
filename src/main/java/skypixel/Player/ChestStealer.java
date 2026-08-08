package skypixel.Player;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
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

import java.util.LinkedList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChestStealer implements Listener {

    // Folosim o hartă concurentă pentru a citi datele în siguranță de pe thread-ul de rețea
    private final ConcurrentHashMap<UUID, Tracker> containerData = new ConcurrentHashMap<>();

    public ChestStealer() {
        // Interceptăm DOAR click-urile din interiorul meniurilor (Inventare)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST, // Prioritate maximă pentru a proteja cufărul
                        PacketType.Play.Client.WINDOW_CLICK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("ChestStealer")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Dacă jucătorul nu are un container deschis, ignorăm
                            Tracker tracker = containerData.get(uuid);
                            if (tracker == null) return;

                            long now = System.currentTimeMillis();

                            // Bloc Sincronizat: Previne coruperea datelor când hackerul trimite 27 pachete simultan
                            synchronized (tracker) {
                                // --------------------------------------------------------
                                // 1. VERIFICAREA TIMPULUI DE REACȚIE (Instant Loot)
                                // --------------------------------------------------------
                                long reactionTime = now - tracker.openTime;

                                // Niciun om nu poate procesa vizual și să dea click în sub 100ms.
                                if (reactionTime < 100 && !tracker.reactionFlagged) {
                                    tracker.reactionFlagged = true;

                                    // Anulăm pachetul! Itemul rămâne în cufăr, neatins de hacker.
                                    event.setCancelled(true);

                                    // Trimitem flag-ul sincron pentru siguranță
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "ChestStealer (Reaction)", "Looted instantly after opening (" + reactionTime + "ms).");
                                        }
                                    });
                                    return; // Oprim procesarea acestui click
                                }

                                // --------------------------------------------------------
                                // 2. VERIFICAREA VITEZEI DE FURAT (Speed Loot)
                                // --------------------------------------------------------
                                tracker.clicks.add(now);

                                // Curățăm click-urile mai vechi de jumătate de secundă (500ms)
                                tracker.clicks.removeIf(time -> time < now - 500);

                                // Dacă mută mai mult de 5 iteme în jumătate de secundă (10 iteme/secundă), este robot!
                                if (tracker.clicks.size() > 5) {
                                    event.setCancelled(true);

                                    // Prevenim spam-ul în consolă (dăm flag o singură dată per rafală)
                                    if (!tracker.speedFlagged) {
                                        tracker.speedFlagged = true;
                                        int currentSpeed = tracker.clicks.size();

                                        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "ChestStealer (Speed)", "Inhuman looting speed (" + currentSpeed + " items/0.5s).");
                                            }
                                        });
                                    }

                                    // Resetăm memoria de click-uri ca să lăsăm playerul să dea click normal ulterior
                                    tracker.clicks.clear();
                                } else {
                                    tracker.speedFlagged = false;
                                }
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // EVENIMENTE BUKKIT (Folosite doar pentru a marca "deschiderea" cufărului)
    // ========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!dakotaAC.isCheckActive("ChestStealer")) return;
        if (event.isCancelled()) return;
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        if (player.getGameMode().name().equals("CREATIVE")) return;

        InventoryType type = event.getInventory().getType();

        // Când deschide cufărul pe server, creăm un tracker nou cu milisecunda exactă
        if (isContainer(type)) {
            containerData.put(player.getUniqueId(), new Tracker(System.currentTimeMillis()));
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        // Când meniul se închide, eliberăm memoria
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

    /**
     * Clasă auxiliară pentru a ține evidența perfectă a datelor per jucător.
     */
    private static class Tracker {
        long openTime;
        LinkedList<Long> clicks;
        boolean reactionFlagged;
        boolean speedFlagged;

        public Tracker(long openTime) {
            this.openTime = openTime;
            this.clicks = new LinkedList<>();
            this.reactionFlagged = false;
            this.speedFlagged = false;
        }
    }
}