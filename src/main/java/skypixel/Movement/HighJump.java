package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
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

public class HighJump implements Listener {

    // Folosim ConcurrentHashMap pentru siguranță asincronă totală
    private final ConcurrentHashMap<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();

    public HighJump() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("HighJump")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // Înregistrăm prima locație și ieșim
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];

                            // Actualizăm poziția asincronă pentru viitorul pachet
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // 1. Optimizare extremă pe thread-ul de rețea:
                            // Ignorăm complet jucătorii care stau pe loc, cad, merg în linie dreaptă sau urcă jumătăți de bloc (slab/scări)
                            if (deltaY <= 0.0 || deltaY == 0.5) {
                                return;
                            }

                            // 2. Delegăm verificările fizice (Harta, Blocuri, Efecte) către Main Thread
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());
                                Location fromLoc = new Location(player.getWorld(), fromPos[0], fromPos[1], fromPos[2]);

                                // Verificăm excepțiile Vanilla
                                if (player.getAllowFlight() || player.isGliding() || player.isRiptiding() || player.isInsideVehicle()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Knockback din damage
                                if (player.getNoDamageTicks() > 10) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Verificăm blocurile care propulsează
                                if (isNearBouncingBlock(toLoc) || isNearBouncingBlock(fromLoc)) {
                                    bounceCooldowns.put(uuid, System.currentTimeMillis() + 2000L);
                                }

                                // E în perioada de imunitate?
                                if (bounceCooldowns.containsKey(uuid) && bounceCooldowns.get(uuid) > System.currentTimeMillis()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                if (isInBubbleColumn(toLoc)) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Calculăm viteza maximă permisă
                                double maxJumpVelocity = 0.425;
                                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                                    maxJumpVelocity += (level * 0.15);
                                }

                                // Plasă de siguranță: Viteza propulsată de server (ex: explozii sau plugin-uri externe care te aruncă în sus)
                                if (player.getVelocity().getY() > maxJumpVelocity) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // === LOGICA DE BAZĂ ===
                                if (deltaY > maxJumpVelocity) {
                                    flagPlayer.addFlag(player, "HighJump", "Impossible jump velocity (Y-Speed: " + String.format("%.3f", deltaY) + ")");

                                    // Îl forțăm să se întoarcă la ultima locație stabilă de pe pământ (Rubber-Band)
                                    Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());
                                    player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                } else {
                                    // Dacă saltul e curat, salvăm locația ca fiind sigură
                                    lastSafeLocation.put(uuid, toLoc);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    /**
     * Sincronizăm teleportările serverului pentru a preveni alertele false și tragerile înapoi.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        bounceCooldowns.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
    }

    private boolean isNearBouncingBlock(Location loc) {
        int x = loc.getBlockX();
        int minY = loc.getBlockY() - 2;
        int maxY = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    if (type == Material.SLIME_BLOCK || type.name().contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isInBubbleColumn(Location loc) {
        Material blockAt = loc.getBlock().getType();
        return blockAt.name().contains("BUBBLE_COLUMN");
    }
}