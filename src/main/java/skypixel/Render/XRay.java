package skypixel.Render;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitRunnable;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.XRay. */
public class XRay implements Listener {

    @Option(value = "initial-delay-ticks", min = 0.0, max = 1.0E9)
    private static volatile long INITIAL_DELAY_TICKS = 100L;
    @Option(value = "task-interval-ticks", min = 1.0, max = 1.0E9)
    private static volatile long TASK_INTERVAL_TICKS = 200L;
    @Option(value = "max-plant-attempts", min = 1.0, max = 256.0)
    private static volatile int MAX_PLANT_ATTEMPTS = 20;

    @Option(value = "min-ores-to-check", min = 1.0, max = 1.0E9)
    private static volatile int MIN_ORES_TO_CHECK = 30;

    @Option(value = "min-stone-to-ore-ratio", min = 0.0, max = 1.0E9)
    private static volatile double MIN_STONE_TO_ORE_RATIO = 5.0;

    @Option("penalize-mining-in-dark")
    private static volatile boolean PENALIZE_MINING_IN_DARK = false;

    @Option(value = "max-active-decoys-per-player", min = 1.0, max = 256.0)
    private static volatile int MAX_ACTIVE_DECOYS_PER_PLAYER = 15;
    @Option(value = "decoys-planted-per-cycle", min = 1.0, max = 256.0)
    private static volatile int DECOYS_PLANTED_PER_CYCLE = 5;
    @Option(value = "scan-radius-horizontal", min = 1.0, max = 64.0)
    private static volatile int SCAN_RADIUS_HORIZONTAL = 6;
    @Option(value = "scan-radius-vertical", min = 1.0, max = 64.0)
    private static volatile int SCAN_RADIUS_VERTICAL = 5;

    private final ConcurrentHashMap<UUID, Integer> stoneMined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> oresMined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Location, Material>> fakeOres = new ConcurrentHashMap<>();

    @Option(value = "decoy-ores", minSize = 1)
    private static volatile Material[] DECOY_ORES = {
            Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
            Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
            Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
            Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
            Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
            Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
            Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
            Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.ANCIENT_DEBRIS
    };

    @Option("fakeable-blocks")
    private static volatile Material[] FAKEABLE_BLOCKS = {
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.NETHERRACK
    };

    public XRay() {

        try {
            ProtocolLibrary.getProtocolManager().addPacketListener(
                    new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                            com.comphenix.protocol.events.ListenerPriority.NORMAL,
                            PacketType.Play.Client.BLOCK_DIG) {

                        @Override
                        public void onPacketReceiving(PacketEvent event) {
                            try {
                                if (!dakotaAC.isCheckActive("XRay")) return;

                                Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "XRay")) return;
                                if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                                PacketContainer packet = event.getPacket();
                                EnumWrappers.PlayerDigType digType = packet.getPlayerDigTypes().read(0);
                                if (digType != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) return;

                                BlockPosition pos = packet.getBlockPositionModifier().read(0);
                                if (pos == null) return;

                                Map<Location, Material> playerFakes = fakeOres.get(player.getUniqueId());
                                if (playerFakes == null || playerFakes.isEmpty()) return;

                                Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                                Material real = playerFakes.remove(loc);

                                if (real != null) {

                                    Diagnostics.sync(() -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "XRay", Lang.text("checks.XRay.name-xray-decoy"), Lang.text("checks.XRay.details-attempted-to-mine-a-fake-ore-block-generated"));
                                        }
                                    });
                                    revertFakeOre(player, loc, real);
                                }
                            } catch (Exception | LinkageError ex) {
                                Diagnostics.report("XRay", ex);
                            }
                        }
                    }
            );
        } catch (Exception | LinkageError ex) {
            throw new IllegalStateException(ex);
        }

        new BukkitRunnable() {
            @Override
            public void run() {
                if (!dakotaAC.isCheckActive("XRay")) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (CheckPolicy.exempt(player, "XRay")) { cleanupPlayer(player); continue; }
                    if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
                    plantDecoyOres(player);
                }
            }
        }.runTaskTimer(dakotaAC.getPlugin(dakotaAC.class), INITIAL_DELAY_TICKS, TASK_INTERVAL_TICKS);
    }

    private void plantDecoyOres(Player player) {
        ConcurrentHashMap<Location, Material> playerFakes = fakeOres.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        if (playerFakes.size() >= MAX_ACTIVE_DECOYS_PER_PLAYER) return;

        Random r = new Random();
        Location base = player.getLocation();
        int planted = 0;
        int attempts = 0;

        while (planted < DECOYS_PLANTED_PER_CYCLE && playerFakes.size() < MAX_ACTIVE_DECOYS_PER_PLAYER && attempts < MAX_PLANT_ATTEMPTS) {
            attempts++;

            int dx = r.nextInt(SCAN_RADIUS_HORIZONTAL * 2 + 1) - SCAN_RADIUS_HORIZONTAL;
            int dy = r.nextInt(SCAN_RADIUS_VERTICAL * 2 + 1) - SCAN_RADIUS_VERTICAL;
            int dz = r.nextInt(SCAN_RADIUS_HORIZONTAL * 2 + 1) - SCAN_RADIUS_HORIZONTAL;

            Block block = base.clone().add(dx, dy, dz).getBlock();

            if (!isFakeable(block.getType())) continue;
            if (!isFullyEnclosed(block)) continue;

            Location key = block.getLocation();
            if (playerFakes.containsKey(key)) continue;

            Material decoy = DECOY_ORES[r.nextInt(DECOY_ORES.length)];
            sendFakeBlock(player, key, decoy);
            playerFakes.put(key, block.getType());
            planted++;
        }
    }

    private boolean isFakeable(Material mat) {
        for (Material m : FAKEABLE_BLOCKS) {
            if (m == mat) return true;
        }
        return false;
    }

    private boolean isFullyEnclosed(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.UP, BlockFace.DOWN, BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            if (!block.getRelative(face).getType().isSolid()) return false;
        }
        return true;
    }

    private void sendFakeBlock(Player player, Location loc, Material fakeMaterial) {
        player.sendBlockChange(loc, fakeMaterial.createBlockData());
    }

    private void revertFakeOre(Player player, Location loc, Material realMaterial) {
        if (loc.getWorld().equals(player.getWorld())) player.sendBlockChange(loc, loc.getBlock().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("XRay")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "XRay")) return;
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        Material type = block.getType();
        UUID uuid = player.getUniqueId();

        Map<Location, Material> playerFakes = fakeOres.get(uuid);
        if (playerFakes != null && !playerFakes.isEmpty()) {
            for (BlockFace face : BlockFace.values()) {
                if (face.isCartesian()) {
                    Location neighborLoc = block.getLocation().add(face.getModX(), face.getModY(), face.getModZ());
                    Material real = playerFakes.remove(neighborLoc);
                    if (real != null) {
                        revertFakeOre(player, neighborLoc, real);
                    }
                }
            }
        }

        if (isCommonBlock(type)) {
            stoneMined.put(uuid, stoneMined.getOrDefault(uuid, 0) + 1);
        } else if (isValuableOre(type)) {
            int ores = oresMined.getOrDefault(uuid, 0) + 1;
            oresMined.put(uuid, ores);

            int stone = stoneMined.getOrDefault(uuid, 0);

            if (ores >= MIN_ORES_TO_CHECK) {
                double ratio = (double) stone / ores;

                if (ratio < MIN_STONE_TO_ORE_RATIO) {
                    flagPlayer.addFlag(player, "XRay", Lang.text("checks.XRay.name-xray-ratio"), Lang.text("checks.XRay.details-unnatural-mining-precision-ratio-stone-ore", "ratio", String.format(java.util.Locale.ROOT, "%.1f", ratio)));

                    oresMined.put(uuid, 0);
                    stoneMined.put(uuid, 0);
                }
            }

            if (PENALIZE_MINING_IN_DARK) {
                if ((type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE || type == Material.ANCIENT_DEBRIS)) {
                    if (block.getLightLevel() == 0 && !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION)) {
                        flagPlayer.addFlag(player, "XRay", Lang.text("checks.XRay.name-xray-light"), Lang.text("checks.XRay.details-mined-valuable-ore-in-complete-darkness"));
                    }
                }
            }
        }
    }

    private boolean isCommonBlock(Material mat) {
        String name = mat.name();
        return name.contains("STONE") || name.contains("DEEPSLATE") || name.contains("DIRT") ||
                name.contains("GRAVEL") || name.contains("TUFF") || name.contains("ANDESITE") ||
                name.contains("DIORITE") || name.contains("GRANITE") || name.equals("NETHERRACK");
    }

    private boolean isValuableOre(Material mat) {
        String name = mat.name();

        if (name.contains("IRON") || name.contains("COPPER") || name.contains("COAL")) return false;

        return name.contains("_ORE") || name.equals("ANCIENT_DEBRIS");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        stoneMined.remove(uuid);
        oresMined.remove(uuid);
        fakeOres.remove(uuid);
    }
    /** Restore current world data; stored material snapshots can become stale after another player mines. */
    public void cleanup() {
        for (Map.Entry<UUID, ConcurrentHashMap<Location, Material>> entry : fakeOres.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) continue;
            for (Location location : entry.getValue().keySet()) {
                if (location.getWorld().equals(player.getWorld()) && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                    player.sendBlockChange(location, location.getBlock().getBlockData());
                }
            }
        }
        fakeOres.clear();
    }
    private void cleanupPlayer(Player player) {
        Map<Location, Material> blocks = fakeOres.remove(player.getUniqueId());
        if (blocks == null) return;
        for (Location location : blocks.keySet()) {
            if (location.getWorld().equals(player.getWorld()) && location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4)) {
                player.sendBlockChange(location, location.getBlock().getBlockData());
            }
        }
    }

    @EventHandler
    public void onWorldChange(org.bukkit.event.player.PlayerChangedWorldEvent event) {
        fakeOres.remove(event.getPlayer().getUniqueId());
    }
}