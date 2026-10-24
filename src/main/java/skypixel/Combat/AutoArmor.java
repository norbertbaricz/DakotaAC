package skypixel.Combat;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
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

/** Original detection formulas; annotated defaults below are loaded from Modules.AutoArmor. */
public class AutoArmor implements Listener {

    @Option(value = "initial-delay-ticks", min = 0.0, max = 1.0E9)
    private static volatile long INITIAL_DELAY_TICKS = 100L;

    @Option(value = "decoy-interval-ticks", min = 1.0, max = 1.0E9)
    private static volatile long DECOY_INTERVAL_TICKS = 80L;  
    @Option(value = "decoy-duration-ticks", min = 1.0, max = 1.0E9)
    private static volatile long DECOY_DURATION_TICKS = 10L;  
    @Option(value = "inhuman-reaction-ms", min = 1.0, max = 1.0E9)
    private static volatile long INHUMAN_REACTION_MS = 400L;  

    @Option(value = "sequential-equip-ms", min = 1.0, max = 1.0E9)
    private static volatile int SEQUENTIAL_EQUIP_MS = 60;
    @Option(value = "pickup-equip-ms", min = 1.0, max = 1.0E9)
    private static volatile int PICKUP_EQUIP_MS = 150;
    @Option(value = "break-equip-ms", min = 1.0, max = 1.0E9)
    private static volatile int BREAK_EQUIP_MS = 150;

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
                if (CheckPolicy.exempt(player, "AutoArmor")) continue;
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

                if (!sendFakeItem(player, randomSlot, decoyItem)) continue;
                activeDecoys.put(uuid, new DecoyData(randomSlot, System.currentTimeMillis()));

                Bukkit.getScheduler().runTaskLater(dakotaAC.getInstance(), () -> {
                    if (activeDecoys.containsKey(uuid) && player.isOnline()) {
                        sendFakeItem(player, randomSlot, new ItemStack(Material.AIR));
                        activeDecoys.remove(uuid);

                        player.updateInventory();
                    }
                }, DECOY_DURATION_TICKS);
            }
        }, INITIAL_DELAY_TICKS, DECOY_INTERVAL_TICKS);
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
            meta.setDisplayName(Lang.text("checks.AutoArmor.message-system-decoy"));
            meta.setUnbreakable(true);
            decoy.setItemMeta(meta);
        }
        return decoy;
    }

    private static boolean sendFakeItem(Player player, int slot, ItemStack item) {
        try { PacketAccess.fakeInventorySlot(player, slot, item); return true; }
        catch (RuntimeException | LinkageError ex) { Diagnostics.report("AutoArmor", ex); return false; }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!dakotaAC.isCheckActive("AutoArmor")) return;
        if (!(event.getWhoClicked() instanceof Player)) return;

        Player player = (Player) event.getWhoClicked();
        if (player != null && CheckPolicy.exempt(player, "AutoArmor")) return;
        UUID uuid = player.getUniqueId();

        if (activeDecoys.containsKey(uuid)) {
            DecoyData decoy = activeDecoys.get(uuid);

            if (event.getRawSlot() == decoy.slot) {
                if (CheckPolicy.cancel("AutoArmor")) event.setCancelled(true);
                long reactionTime = System.currentTimeMillis() - decoy.spawnTime;

                sendFakeItem(player, decoy.slot, new ItemStack(Material.AIR));
                activeDecoys.remove(uuid);

                if (reactionTime < INHUMAN_REACTION_MS) {
                    flagPlayer.addFlag(player, "AutoArmor", Lang.text("checks.AutoArmor.name-autoarmor"), Lang.text("checks.AutoArmor.details-equipped-ghost-decoy-in-inhuman-time-ms", "reaction-time", reactionTime));
                }

                Diagnostics.sync(player::updateInventory);
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
            boolean isHacking = validateEquipTiming(player, uuid, currentTime, Lang.text("checks.AutoArmor.input-gui-click"));

            if (isHacking) {
                if (CheckPolicy.cancel("AutoArmor")) event.setCancelled(true);
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
            reason = Lang.text("checks.AutoArmor.details-equipped-multiple-pieces-instantly-via-ms", "type", type, "time-since-last-equip", timeSinceLastEquip);
        }
        lastEquipTime.put(uuid, currentTime);

        if (!flagged && lastPickup.containsKey(uuid)) {
            long timeSincePickup = currentTime - lastPickup.get(uuid);
            if (timeSincePickup < PICKUP_EQUIP_MS) {
                flagged = true;
                reason = Lang.text("checks.AutoArmor.details-equipped-instantly-after-pickup-ms", "time-since-pickup", timeSincePickup);
            }
        }

        if (!flagged && lastBreak.containsKey(uuid)) {
            long timeSinceBreak = currentTime - lastBreak.get(uuid);
            if (timeSinceBreak < BREAK_EQUIP_MS) {
                flagged = true;
                reason = Lang.text("checks.AutoArmor.details-equipped-instantly-after-armor-broke-ms", "time-since-break", timeSinceBreak);
            }
        }

        if (flagged) {
            final String finalReason = reason;
            Diagnostics.sync(() -> {
                if (player.isOnline()) {
                    flagPlayer.addFlag(player, "AutoArmor", Lang.text("checks.AutoArmor.name-autoarmor"), finalReason);
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
        if (player != null && CheckPolicy.exempt(player, "AutoArmor")) return;

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