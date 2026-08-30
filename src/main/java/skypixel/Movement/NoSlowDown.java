package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

public class NoSlowDown implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Mărit la 8 pentru a absorbi perfect inerția de la încărcarea Tridentului/Arcului
    private static final int ITEM_SPAM_TOLERANCE_TICKS = 8;
    private static final double MAX_ITEM_USE_SPEED = 0.16;
    private static final double MAX_WEB_SPEED = 0.15;
    // ==========================================

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> itemVlMap = new ConcurrentHashMap<>();

    public NoSlowDown() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoSlowDown")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
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

                            double deltaXZ = Math.hypot(deltaX, deltaZ);
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            if (deltaXZ == 0.0) {
                                decreaseVL(uuid);
                                return;
                            }

                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight() || player.isRiptiding()) {
                                    return;
                                }

                                // === FIX CRITIC: Protecție la Knockback (Pentru Tufișuri și PvP) ===
                                if (player.getNoDamageTicks() > 10 || Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ()) > 0.05) {
                                    decreaseVL(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // ========================================================
                                // LOGICA 1: BLOCURI CARE ÎNCETINESC
                                // ========================================================
                                Material blockAtFeet = toLoc.getBlock().getType();
                                Material blockAtHead = toLoc.clone().add(0, 1.0, 0).getBlock().getType();

                                boolean inWeb = blockAtFeet == Material.COBWEB || blockAtHead == Material.COBWEB;
                                boolean inBushOrSnow = blockAtFeet.name().contains("BERRY_BUSH") || blockAtHead.name().contains("BERRY_BUSH") ||
                                        blockAtFeet.name().contains("POWDER_SNOW") || blockAtHead.name().contains("POWDER_SNOW");

                                if (inWeb || inBushOrSnow) {
                                    if (deltaXZ > MAX_WEB_SPEED) {
                                        flagPlayer.addFlag(player, "NoSlowDown (Web)", "Moving too fast through a slowing block (Speed: " + String.format("%.2f", deltaXZ) + ")");
                                        player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        return;
                                    }
                                }

                                // ========================================================
                                // LOGICA 2: UTILIZAREA ITEMELOR CU TOLERANȚĂ SPAM/INERȚIE
                                // ========================================================
                                boolean isUsingSlowingItem = false;
                                try {
                                    isUsingSlowingItem = player.isHandRaised() || player.isBlocking();
                                } catch (NoSuchMethodError e) {
                                    isUsingSlowingItem = player.isBlocking();
                                }

                                if (!isUsingSlowingItem) {
                                    decreaseVL(uuid);
                                    return;
                                }

                                // Permitem Jump-Eating doar pe urcare (deltaY > 0), nu la coborârea pantelor
                                if (deltaY > 0.0) {
                                    return;
                                }

                                Material blockUnder = fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
                                if (blockUnder.name().contains("ICE") || blockUnder == Material.SLIME_BLOCK) {
                                    return;
                                }

                                double speedLimit = MAX_ITEM_USE_SPEED;
                                if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                                    int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                                    speedLimit += 0.05 * (amplifier + 1);
                                }

                                if (deltaXZ > speedLimit) {
                                    int vl = itemVlMap.getOrDefault(uuid, 0) + 1;
                                    itemVlMap.put(uuid, vl);

                                    // Lăsăm inerția Tridentului/Mâncării să se descarce natural
                                    if (vl > ITEM_SPAM_TOLERANCE_TICKS) {
                                        flagPlayer.addFlag(player, "NoSlowDown (Item)", "Sustained speed while using item (Speed: " + String.format("%.2f", deltaXZ) + ")");
                                        player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        itemVlMap.put(uuid, 0);
                                    }
                                } else {
                                    decreaseVL(uuid);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    private void decreaseVL(UUID uuid) {
        int vl = itemVlMap.getOrDefault(uuid, 0);
        if (vl > 0) {
            itemVlMap.put(uuid, vl - 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            itemVlMap.put(uuid, 0);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPosMap.remove(uuid);
        itemVlMap.remove(uuid);
    }
}