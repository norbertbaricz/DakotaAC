package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Fly implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Cât permitem unui jucător să plutească pe loc în aer (deltaY = 0)?
    private final int MAX_HOVER_TICKS = 3;

    // Cât permitem să urce continuu (deltaY > 0) dintr-o săritură normală?
    private final int MAX_ASCENSION_TICKS = 12;

    // Câte milisecunde permitem ascensiune liberă după ce atinge un Slime / Pat?
    private final long BOUNCE_IMMUNITY_MS = 2500L;
    // ==========================================

    private final ConcurrentHashMap<UUID, Integer> hoverTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> ascendTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> bounceImmunity = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Fly() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fly")) return;

                            Player player = event.getPlayer();
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            // 0. Dacă doar a mișcat capul, ignorăm (evităm falsificarea tick-urilor)
                            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                                return;
                            }

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // --- VERIFICARE PE MAIN THREAD ---
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());

                                // 1. Excepții legitime
                                if (player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming() ||
                                        player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.JUMP_BOOST) ||
                                        player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                // 2. Verificare Pământ + Bouncy Blocks (Slime / Bed)
                                if (isNearGround(toLoc)) {
                                    if (isBouncyBlock(toLoc)) {
                                        bounceImmunity.put(uuid, System.currentTimeMillis() + BOUNCE_IMMUNITY_MS);
                                    }
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                // 3. Gravitația Naturală (Cădere)
                                if (deltaY < 0.0) {
                                    hoverTicksMap.put(uuid, 0);
                                    ascendTicksMap.put(uuid, 0);
                                    return;
                                }

                                // === 4. LOGICA SUPREMĂ FLY CHECK ===

                                // HOVER FLY (Plutire: deltaY este 0.0 sau microscopic de mic)
                                if (Math.abs(deltaY) < 0.001) {
                                    int hoverTicks = hoverTicksMap.getOrDefault(uuid, 0) + 1;
                                    hoverTicksMap.put(uuid, hoverTicks);

                                    if (hoverTicks > MAX_HOVER_TICKS) {
                                        flagAndRubberband(player, uuid, "Fly (Hover)", "Suspended in air for " + hoverTicks + " ticks", toLoc);
                                    }
                                }
                                // ASCENSION FLY (Urcare: deltaY > 0)
                                else if (deltaY > 0.0) {
                                    int ascendTicks = ascendTicksMap.getOrDefault(uuid, 0) + 1;
                                    ascendTicksMap.put(uuid, ascendTicks);

                                    boolean hasBounceImmunity = bounceImmunity.containsKey(uuid) && bounceImmunity.get(uuid) > System.currentTimeMillis();

                                    if (!hasBounceImmunity && ascendTicks > MAX_ASCENSION_TICKS) {
                                        flagAndRubberband(player, uuid, "Fly (Ascension)", "Ascended for " + ascendTicks + " ticks (dY: " + String.format("%.4f", deltaY) + ")", toLoc);
                                    }
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    private void flagAndRubberband(Player player, UUID uuid, String hackType, String details, Location badLoc) {
        flagPlayer.addFlag(player, hackType, details);

        Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());
        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

        hoverTicksMap.put(uuid, 0);
        ascendTicksMap.put(uuid, 0);
    }

    private void resetTicksAndSafeLocation(UUID uuid, Location loc) {
        hoverTicksMap.put(uuid, 0);
        ascendTicksMap.put(uuid, 0);
        lastSafeLocation.put(uuid, loc);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            resetTicksAndSafeLocation(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            bounceImmunity.put(uuid, System.currentTimeMillis() + 1000L);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hoverTicksMap.remove(uuid);
        ascendTicksMap.remove(uuid);
        bounceImmunity.remove(uuid);
        lastSafeLocation.remove(uuid);
        lastPosMap.remove(uuid);
    }

    // === FIX GEOMETRIC PENTRU HITBOX-URI DE 1.5 ===
    private boolean isNearGround(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        // Căutăm până la 1.5 blocuri în jos pentru a găsi Ziduri și Garduri!
        int minY = (int) Math.floor(loc.getY() - 1.5);
        int maxY = (int) Math.floor(loc.getY() + 0.5);
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    Block block = loc.getWorld().getBlockAt(bx, by, bz);
                    Material type = block.getType();

                    if (type.isAir()) continue;

                    String name = type.name();

                    // Dacă blocul este gard sau perete, îi acceptăm înălțimea vizuală extinsă
                    if (name.contains("FENCE") || name.contains("WALL")) {
                        return true;
                    }

                    // Pentru restul blocurilor normale, validăm doar dacă sunt la distanța firească de 0.6
                    if (by >= Math.floor(loc.getY() - 0.6)) {
                        if (type.isSolid() || name.contains("SNOW") || name.contains("CARPET") || name.contains("SLAB") ||
                                name.contains("STEP") || name.contains("STAIRS") || name.contains("LILY") || name.contains("WATER") ||
                                name.contains("LAVA") || name.contains("LADDER") || name.contains("VINE") || name.contains("LEAVES") ||
                                name.contains("ICE")) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean isBouncyBlock(Location loc) {
        int minX = (int) Math.floor(loc.getX() - 0.3);
        int maxX = (int) Math.floor(loc.getX() + 0.3);
        int minY = (int) Math.floor(loc.getY() - 0.5);
        int maxY = (int) Math.floor(loc.getY());
        int minZ = (int) Math.floor(loc.getZ() - 0.3);
        int maxZ = (int) Math.floor(loc.getZ() + 0.3);

        for (int bx = minX; bx <= maxX; bx++) {
            for (int by = minY; by <= maxY; by++) {
                for (int bz = minZ; bz <= maxZ; bz++) {
                    String name = loc.getWorld().getBlockAt(bx, by, bz).getType().name();
                    if (name.contains("SLIME") || name.contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}