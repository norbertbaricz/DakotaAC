package skypixel.Render;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.WrappedBlockData;
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

public class XRay implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Setări pentru sistemul matematic (Stone vs Ore)
    // În versiunile 1.18+, venele de fier/cupru sunt masive. Așteptăm să spargă 30 de minereuri pentru a face o medie corectă.
    private static final int MIN_ORES_TO_CHECK = 30;

    // Raportul minim admis. Un raport sub 5.0 înseamnă că jucătorul găsește 1 minereu la fiecare 4 blocuri sparte (imposibil legitim pe termen lung).
    private static final double MIN_STONE_TO_ORE_RATIO = 5.0;

    // Pedepsim jucătorii care minează pe întuneric? (Falsează dacă folosesc moduri Fullbright / Gamma)
    private static final boolean PENALIZE_MINING_IN_DARK = false;

    // Setări pentru sistemul "Momeală" (Decoy Ores)
    private static final int MAX_ACTIVE_DECOYS_PER_PLAYER = 15;
    private static final int DECOYS_PLANTED_PER_CYCLE = 5;
    private static final int SCAN_RADIUS_HORIZONTAL = 6;
    private static final int SCAN_RADIUS_VERTICAL = 5;
    // ==========================================

    private final ConcurrentHashMap<UUID, Integer> stoneMined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> oresMined = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ConcurrentHashMap<Location, Material>> fakeOres = new ConcurrentHashMap<>();

    private static final Material[] DECOY_ORES = {
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

    private static final Material[] FAKEABLE_BLOCKS = {
            Material.STONE, Material.DEEPSLATE, Material.TUFF, Material.NETHERRACK
    };

    public XRay() {
        // Detectăm momentul în care clientul începe să spargă un bloc de momeală
        try {
            ProtocolLibrary.getProtocolManager().addPacketListener(
                    new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                            com.comphenix.protocol.events.ListenerPriority.NORMAL,
                            PacketType.Play.Client.BLOCK_DIG) {

                        @Override
                        public void onPacketReceiving(PacketEvent event) {
                            try {
                                if (!dakotaAC.isCheckActive("XRay")) return;

                                Player player = event.getPlayer();
                                if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                                PacketContainer packet = event.getPacket();
                                EnumWrappers.PlayerDigType digType = packet.getPlayerDigTypes().readSafely(0);
                                if (digType != EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) return;

                                BlockPosition pos = packet.getBlockPositionModifier().readSafely(0);
                                if (pos == null) return;

                                Map<Location, Material> playerFakes = fakeOres.get(player.getUniqueId());
                                if (playerFakes == null || playerFakes.isEmpty()) return;

                                Location loc = new Location(player.getWorld(), pos.getX(), pos.getY(), pos.getZ());
                                Material real = playerFakes.remove(loc);

                                if (real != null) {
                                    // Dacă dă click pe momeală, trimitem direct flag!
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "XRay (Decoy)", "Attempted to mine a fake ore block generated by the AntiCheat.");
                                        }
                                    });
                                    revertFakeOre(player, loc, real);
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
            );
        } catch (Exception ex) {
            ex.printStackTrace();
        }

        // La fiecare 10 secunde, plantăm câteva minereuri false lângă jucători.
        new BukkitRunnable() {
            @Override
            public void run() {
                if (!dakotaAC.isCheckActive("XRay")) return;

                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) continue;
                    plantDecoyOres(player);
                }
            }
        }.runTaskTimer(dakotaAC.getPlugin(dakotaAC.class), 100L, 200L);
    }

    private void plantDecoyOres(Player player) {
        ConcurrentHashMap<Location, Material> playerFakes = fakeOres.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>());
        if (playerFakes.size() >= MAX_ACTIVE_DECOYS_PER_PLAYER) return;

        Random r = new Random();
        Location base = player.getLocation();
        int planted = 0;
        int attempts = 0;

        while (planted < DECOYS_PLANTED_PER_CYCLE && playerFakes.size() < MAX_ACTIVE_DECOYS_PER_PLAYER && attempts < 20) {
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
        try {
            ProtocolManager pm = ProtocolLibrary.getProtocolManager();
            PacketContainer packet = pm.createPacket(PacketType.Play.Server.BLOCK_CHANGE);
            packet.getBlockPositionModifier().writeSafely(0, new BlockPosition(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ()));
            packet.getBlockData().writeSafely(0, WrappedBlockData.createData(fakeMaterial));
            pm.sendServerPacket(player, packet);
        } catch (Exception ignored) {}
    }

    private void revertFakeOre(Player player, Location loc, Material realMaterial) {
        sendFakeBlock(player, loc, realMaterial);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("XRay")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        Block block = event.getBlock();
        Material type = block.getType();
        UUID uuid = player.getUniqueId();

        // Expunerea momelelor
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

        // Statistici Minereuri vs Piatră
        if (isCommonBlock(type)) {
            stoneMined.put(uuid, stoneMined.getOrDefault(uuid, 0) + 1);
        } else if (isValuableOre(type)) {
            int ores = oresMined.getOrDefault(uuid, 0) + 1;
            oresMined.put(uuid, ores);

            int stone = stoneMined.getOrDefault(uuid, 0);

            if (ores >= MIN_ORES_TO_CHECK) {
                double ratio = (double) stone / ores;

                if (ratio < MIN_STONE_TO_ORE_RATIO) {
                    flagPlayer.addFlag(player, "XRay (Ratio)", "Unnatural mining precision (Ratio: " + String.format("%.1f", ratio) + " stone/ore).");

                    // Resetăm doar o parte din memorie, pentru a nu-l face imun pe termen scurt
                    oresMined.put(uuid, 0);
                    stoneMined.put(uuid, 0);
                }
            }

            // Opțional: Verificarea luminii
            if (PENALIZE_MINING_IN_DARK) {
                if ((type == Material.DIAMOND_ORE || type == Material.DEEPSLATE_DIAMOND_ORE || type == Material.ANCIENT_DEBRIS)) {
                    if (block.getLightLevel() == 0 && !player.hasPotionEffect(org.bukkit.potion.PotionEffectType.NIGHT_VISION)) {
                        flagPlayer.addFlag(player, "XRay (Light)", "Mined valuable ore in complete darkness.");
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
        // Excludem Iron și Copper pentru a proteja venele uriașe din 1.18+ care strică raportul!
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
}