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
    private static final long DECOY_INTERVAL_TICKS = 80L;  // Testăm la fiecare 4 secunde
    private static final long DECOY_DURATION_TICKS = 10L;  // Momeala stă 500ms (Așteptăm să muște hack-ul cu delay)
    private static final long INHUMAN_REACTION_MS = 400L;  // Niciun om nu poate observa și da click în sub 400ms

    private static final int SEQUENTIAL_EQUIP_MS = 60;
    private static final int PICKUP_EQUIP_MS = 150;
    private static final int BREAK_EQUIP_MS = 150;
    // =========================================================

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final ConcurrentHashMap<UUID, Long> lastPickup = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastBreak = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<UUID, Long> lastEquipTime = new ConcurrentHashMap<>();

    private static final ConcurrentHashMap<UUID, DecoyData> activeDecoys = new ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    public AutoArmor() {
        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), () -> {
            if (!dakotaAC.isCheckActive("AutoArmor")) return;

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR || player.isDead()) {
                    continue;
                }

                if (player.getOpenInventory().getType() != InventoryType.CRAFTING) {
                    continue;
                }

                UUID uuid = player.getUniqueId();
                if (activeDecoys.containsKey(uuid)) continue;

                List<Integer> emptySlots = new ArrayList<>();
                for (int i = 9; i <= 35; i++) {
                    ItemStack item = player.getInventory().getItem(i);
                    if (item == null || item.getType() == Material.AIR) {
                        emptySlots.add(i);
                    }
                }

                if (emptySlots.isEmpty()) continue;

                int randomSlot = emptySlots.get(RANDOM.nextInt(emptySlots.size()));

                ItemStack decoyItem = generateSmartDecoy(player);

                sendFakeItem(player, randomSlot, decoyItem);
                activeDecoys.put(uuid, new DecoyData(randomSlot, System.currentTimeMillis()));

                Bukkit.getScheduler().runTaskLater(dakotaAC.getInstance(), () -> {
                    if (activeDecoys.containsKey(uuid) && player.isOnline()) {
                        sendFakeItem(player, randomSlot, new ItemStack(Material.AIR));
                        activeDecoys.remove(uuid);

                        player.updateInventory();
                    }
                }, DECOY_DURATION_TICKS);
            }
        }, 100L, DECOY_INTERVAL_TICKS);
    }

    private ItemStack generateSmartDecoy(Player player) {
        String[] types = {"_HELMET", "_CHESTPLATE", "_LEGGINGS", "_BOOTS"};
        int randIndex = RANDOM.nextInt(types.length);
        String suffix = types[randIndex];

        ItemStack currentEquipped = null;
        switch (randIndex) {
            case 0: currentEquipped = player.getInventory().getHelmet(); break;
            case 1: currentEquipped = player.getInventory().getChestplate(); break;
            case 2: currentEquipped = player.getInventory().getLeggings(); break;
            case 3: currentEquipped = player.getInventory().getBoots(); break;
        }

        String materialPrefix = "NETHERITE";

        if (currentEquipped == null || currentEquipped.getType() == Material.AIR) {
            materialPrefix = "DIAMOND";
        } else {
            String name = currentEquipped.getType().name();
            if (name.startsWith("LEATHER")) materialPrefix = "CHAINMAIL";
            else if (name.startsWith("CHAINMAIL") || name.startsWith("GOLDEN")) materialPrefix = "IRON";
            else if (name.startsWith("IRON")) materialPrefix = "DIAMOND";
            else if (name.startsWith("DIAMOND")) materialPrefix = "NETHERITE";
            else if (name.startsWith("NETHERITE")) materialPrefix = "NETHERITE";
        }

        ItemStack decoy = new ItemStack(Material.valueOf(materialPrefix + suffix));
        ItemMeta meta = decoy.getItemMeta();
        if (meta != null) {
            meta.setDisplayName("§cSystem Decoy");
            meta.setUnbreakable(true);
            decoy.setItemMeta(meta);
        }
        return decoy;
    }

    private static void sendFakeItem(Player player, int slot, ItemStack item) {
        try {
            PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.SET_SLOT);

            if (packet.getIntegers().size() > 0) {
                packet.getIntegers().writeSafely(0, 0);
            } else if (packet.getBytes().size() > 0) {
                packet.getBytes().writeSafely(0, (byte) 0);
            }

            if (packet.getIntegers().size() > 1) {
                packet.getIntegers().writeSafely(1, 0);
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

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!dakotaAC.isCheckActive("AutoArmor")) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        UUID uuid = player.getUniqueId();

        if (activeDecoys.containsKey(uuid)) {
            DecoyData decoy = activeDecoys.get(uuid);

            // FIX: Folosim getRawSlot() pentru a prinde click-ul exact trimis de pachetul hack-ului
            if (event.getRawSlot() == decoy.slot) {
                event.setCancelled(true);
                long reactionTime = System.currentTimeMillis() - decoy.spawnTime;

                sendFakeItem(player, decoy.slot, new ItemStack(Material.AIR));
                activeDecoys.remove(uuid);

                // FIX: Oprim alarmele false dacă un jucător legitim apucă să dea click din greșeală
                if (reactionTime < INHUMAN_REACTION_MS) {
                    flagPlayer.addFlag(player, "AutoArmor", "Equipped ghost decoy in inhuman time (" + reactionTime + "ms).");
                }

                Bukkit.getScheduler().runTask(dakotaAC.getInstance(), player::updateInventory);
                return;
            }
        }

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

    public static void cleanupAllDecoys() {
        for (Map.Entry<UUID, DecoyData> entry : activeDecoys.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.isOnline()) {
                sendFakeItem(player, entry.getValue().slot, new ItemStack(Material.AIR));
                player.updateInventory();
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