package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerItemBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class AutoArmor implements Listener {

    // =========================================================
    // --- Easy-to-tune thresholds ---
    // =========================================================
    private static final long DECOY_INTERVAL_TICKS = 60L; // La cat timp facem testul cu momeala (3 secunde)
    private static final long DECOY_DURATION_TICKS = 3L;  // Cat timp sta momeala pe ecranul lui (150ms)
    private static final int SEQUENTIAL_EQUIP_MS = 60;    // Timp minim intre 2 piese echipate manual din GUI
    private static final int PICKUP_EQUIP_MS = 150;       // Timp minim de reactie dupa ce a ridicat piesa
    private static final int BREAK_EQUIP_MS = 150;        // Timp minim de reactie dupa ce i s-a spart piesa
    // =========================================================

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final ConcurrentHashMap<UUID, Long> lastPickup = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastBreak = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastEquipTime = new ConcurrentHashMap<>();

    // Stocam datele momelii
    private static final ConcurrentHashMap<UUID, DecoyData> activeDecoys = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    public AutoArmor() {
        // ========================================================
        // ENGINE PENTRU DECOY (CLIENT-SIDE PACKETS)
        // ========================================================
        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), () -> {
            if (!dakotaAC.isCheckActive("AutoArmor")) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                    continue;
                }

                // Daca are un cufar deschis, il ignoram
                if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
                    continue;
                }

                UUID uuid = player.getUniqueId();
                if (activeDecoys.containsKey(uuid)) continue;

                // Cautam sloturi goale in inventarul principal (9-35). Ignoram hotbar-ul (0-8)
                List<Integer> emptySlots = new ArrayList<>();
                for (int i = 9; i <= 35; i++) {
                    ItemStack item = player.getInventory().getItem(i);
                    if (item == null || item.getType() == Material.AIR) {
                        emptySlots.add(i);
                    }
                }

                if (emptySlots.isEmpty()) continue;

                // Alegem un slot random
                int randomSlot = emptySlots.get(RANDOM.nextInt(emptySlots.size()));

                // Generam momeala
                ItemStack decoyItem = new ItemStack(Material.NETHERITE_CHESTPLATE);
                ItemMeta meta = decoyItem.getItemMeta();
                if (meta != null) {
                    meta.setDisplayName("§cSystem Decoy");
                    decoyItem.setItemMeta(meta);
                }

                // Trimitem armura falsă DOAR prin pachete (pe server slotul ramane gol!)
                sendFakeItem(player, randomSlot, decoyItem);
                activeDecoys.put(uuid, new DecoyData(randomSlot, System.currentTimeMillis()));

                // Dupa 3 tick-uri (150ms), stergem vizual momeala de pe client
                Bukkit.getScheduler().runTaskLater(dakotaAC.getInstance(), () -> {
                    if (activeDecoys.containsKey(uuid) && player.isOnline()) {
                        sendFakeItem(player, randomSlot, new ItemStack(Material.AIR));
                        activeDecoys.remove(uuid);

                        // FIX-UL MAGIC PENTRU GHOST ITEMS:
                        // Fortam clientul sa isi refaca inventarul vizual ca sa stergem orice fantoma ramasa
                        player.updateInventory();
                    }
                }, DECOY_DURATION_TICKS);
            }
        }, 100L, DECOY_INTERVAL_TICKS);
    }

    // ========================================================
    // LOGICA DE TRIMITERE A ITEMELOR FALSE (PROTOCOL LIB)
    // ========================================================
    private void sendFakeItem(Player player, int slot, ItemStack item) {
        try {
            PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.SET_SLOT);

            if (packet.getIntegers().size() > 0) {
                packet.getIntegers().writeSafely(0, 0); // Window ID 0 = Inventarul jucatorului
            } else if (packet.getBytes().size() > 0) {
                packet.getBytes().writeSafely(0, (byte) 0);
            }

            if (packet.getIntegers().size() > 1) {
                packet.getIntegers().writeSafely(1, 0); // State ID
            }

            if (packet.getIntegers().size() > 2) {
                packet.getIntegers().writeSafely(2, slot);
            } else if (packet.getShorts().size() > 0) {
                packet.getShorts().writeSafely(0, (short) slot);
            }

            packet.getItemModifier().writeSafely(0, item);
            PROTOCOL.sendServerPacket(player, packet);
        } catch (Exception ignored) {
        }
    }

    // ========================================================
    // ASCULTĂM SLOTURILE DIN GUI ȘI TESTUL DE DECOY
    // ========================================================
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!dakotaAC.isCheckActive("AutoArmor")) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        // 1. VERIFICARE DECOY (MOMEALA)
        if (activeDecoys.containsKey(uuid)) {
            DecoyData decoy = activeDecoys.get(uuid);

            // Clientul codat a incercat sa dea click fix pe momeala
            if (event.getSlot() == decoy.slot) {
                event.setCancelled(true);
                long reactionTime = System.currentTimeMillis() - decoy.spawnTime;

                sendFakeItem(player, decoy.slot, new ItemStack(Material.AIR));
                activeDecoys.remove(uuid);

                flagPlayer.addFlag(player, "AutoArmor", "Tried to equip a ghost decoy piece in " + reactionTime + "ms.");

                // FIX-UL MAGIC NR 2: Daca apuca sa dea click, hack-ul va incerca sa o mute
                // Asta cauzeaza Ghost Item in slotul de armura. updateInventory() curata asta complet.
                Bukkit.getScheduler().runTask(dakotaAC.getInstance(), player::updateInventory);
                return;
            }
        }

        // 2. VERIFICARE VITEZA ECHIPARE NORMALA DIN GUI
        boolean isArmorEquip = false;

        if (event.getSlotType() == InventoryType.SlotType.ARMOR) {
            isArmorEquip = true;
        }
        else if (event.isShiftClick() && isArmor(event.getCurrentItem())) {
            isArmorEquip = true;
        }

        if (isArmorEquip) {
            long currentTime = System.currentTimeMillis();
            boolean isHacking = validateEquipTiming(player, uuid, currentTime, "GUI Click");

            if (isHacking) {
                event.setCancelled(true);
            }
        }
    }

    // ========================================================
    // MOTORUL CENTRAL DE VALIDARE A VITEZEI
    // ========================================================
    private boolean validateEquipTiming(Player player, UUID uuid, long currentTime, String type) {
        boolean flagged = false;
        String reason = "";

        long lastEquip = lastEquipTime.getOrDefault(uuid, 0L);
        long timeSinceLastEquip = currentTime - lastEquip;

        if (timeSinceLastEquip > 0 && timeSinceLastEquip < SEQUENTIAL_EQUIP_MS) {
            flagged = true;
            reason = "Equipped multiple pieces instantly via " + type + " (" + timeSinceLastEquip + "ms)";
        }
        lastEquipTime.put(uuid, currentTime);

        if (!flagged && lastPickup.containsKey(uuid)) {
            long timeSincePickup = currentTime - lastPickup.get(uuid);
            if (timeSincePickup < PICKUP_EQUIP_MS) {
                flagged = true;
                reason = "Equipped instantly after pickup (" + timeSincePickup + "ms)";
            }
        }

        if (!flagged && lastBreak.containsKey(uuid)) {
            long timeSinceBreak = currentTime - lastBreak.get(uuid);
            if (timeSinceBreak < BREAK_EQUIP_MS) {
                flagged = true;
                reason = "Equipped instantly after armor broke (" + timeSinceBreak + "ms)";
            }
        }

        if (flagged) {
            final String finalReason = reason;
            Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> {
                if (player.isOnline()) {
                    flagPlayer.addFlag(player, "AutoArmor", finalReason);
                }
            });
            return true;
        }

        return false;
    }

    // ========================================================
    // EVENIMENTE DE REFERINȚĂ
    // ========================================================
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        if (isArmor(event.getItem().getItemStack())) {
            lastPickup.put(player.getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onItemBreak(PlayerItemBreakEvent event) {
        if (isArmor(event.getBrokenItem())) {
            lastBreak.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPickup.remove(uuid);
        lastBreak.remove(uuid);
        lastEquipTime.remove(uuid);
        activeDecoys.remove(uuid);
    }

    private boolean isArmor(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) return false;
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") ||
                name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS") ||
                name.equals("ELYTRA");
    }

    // ========================================================
    // CURĂȚARE LA OPRIREA SERVERULUI/PLUGINULUI
    // ========================================================
    public static void cleanupAllDecoys() {
        for (Map.Entry<UUID, DecoyData> entry : activeDecoys.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                new AutoArmor().sendFakeItem(player, entry.getValue().slot, new ItemStack(Material.AIR));
                player.updateInventory(); // stergem si la curatare
            }
        }
        activeDecoys.clear();
    }

    private static class DecoyData {
        int slot;
        long spawnTime;

        DecoyData(int slot, long spawnTime) {
            this.slot = slot;
            this.spawnTime = spawnTime;
        }
    }
}