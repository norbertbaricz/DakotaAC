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

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class ChestStealer implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Timpul minim de reacție umană (în milisecunde).
    // Un om normal reacționează vizual în ~200ms. Lăsăm 120ms pentru a acoperi "Ping/Lag Prediction"
    // (când jucătorul dă click orb unde știe că va fi itemul).
    private static final long MIN_REACTION_TIME_MS = 120L;

    // Numărul maxim de click-uri (iteme furate) permise pe secundă.
    // Oamenii cu Shift+Drag-Click pot atinge 10-12.
    // ChestStealer-ul (Hack-ul) trage 27 de iteme (tot cufărul) în sub 15-20ms.
    private static final int MAX_CLICKS_PER_SECOND = 15;

    // Fereastra de timp pentru calculul vitezei (1 secundă = 1000ms)
    private static final long TIME_WINDOW_MS = 1000L;
    // ==========================================

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

                            // Dacă jucătorul nu are un container extern deschis, ignorăm
                            Tracker tracker = containerData.get(uuid);
                            if (tracker == null) return;

                            long now = System.currentTimeMillis();

                            // --------------------------------------------------------
                            // 1. VERIFICAREA TIMPULUI DE REACȚIE (Instant Loot)
                            // --------------------------------------------------------
                            // Calculăm timpul de reacție compensând ping-ul jucătorului!
                            // Dacă are ping mare, pachetul ajunge mai greu, deci pare că a luat itemul instant.
                            int ping = player.getPing();
                            long reactionTime = (now - tracker.openTime) - (ping / 2); // Scădem un sens de rețea

                            if (reactionTime < MIN_REACTION_TIME_MS && !tracker.reactionFlagged) {
                                tracker.reactionFlagged = true;

                                // Anulăm pachetul pe rețea! Itemul rămâne în cufăr pentru server.
                                event.setCancelled(true);

                                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                    if (player.isOnline()) {
                                        flagPlayer.addFlag(player, "ChestStealer (Reaction)", "Looted instantly after opening (React: " + reactionTime + "ms | Ping: " + ping + "ms).");
                                    }
                                });
                                return;
                            }

                            // --------------------------------------------------------
                            // 2. VERIFICAREA VITEZEI DE FURAT (Speed Loot)
                            // --------------------------------------------------------
                            tracker.clicks.add(now);

                            // Curățăm eficient click-urile mai vechi de 1 secundă
                            while (!tracker.clicks.isEmpty() && tracker.clicks.peek() < now - TIME_WINDOW_MS) {
                                tracker.clicks.poll();
                            }

                            int currentSpeed = tracker.clicks.size();

                            if (currentSpeed > MAX_CLICKS_PER_SECOND) {
                                event.setCancelled(true);

                                if (!tracker.speedFlagged) {
                                    tracker.speedFlagged = true;

                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "ChestStealer (Speed)", "Inhuman looting speed (" + currentSpeed + " items/sec).");
                                        }
                                    });
                                }

                                // Îi curățăm buffer-ul parțial ca să îl lăsăm să continue normal dacă a fost un lag spike ciudat
                                tracker.clicks.clear();
                            } else {
                                // Resetăm flag-ul dacă a redus viteza
                                tracker.speedFlagged = false;
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // EVENIMENTE BUKKIT (Marchează "deschiderea" cufărului)
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
        final long openTime;
        final ConcurrentLinkedQueue<Long> clicks; // 100% Thread-Safe și rapid
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