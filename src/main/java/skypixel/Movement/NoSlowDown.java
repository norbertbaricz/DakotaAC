package skypixel.Movement;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
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

/** Original detection formulas; annotated defaults below are loaded from Modules.NoSlowDown. */
public class NoSlowDown implements Listener {

    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "velocity-exemption", min = 0.0, max = 1.0E9)
    private static volatile double VELOCITY_EXEMPTION = 0.05;
    @Option(value = "speed-potion-allowance", min = 0.0, max = 1.0E9)
    private static volatile double SPEED_POTION_ALLOWANCE = 0.05;

    @Option(value = "item-spam-tolerance-ticks", min = 1.0, max = 1.0E9)
    private static volatile int ITEM_SPAM_TOLERANCE_TICKS = 8;
    @Option(value = "max-item-use-speed", min = 0.0, max = 1.0E9)
    private static volatile double MAX_ITEM_USE_SPEED = 0.16;
    @Option(value = "max-web-speed", min = 0.0, max = 1.0E9)
    private static volatile double MAX_WEB_SPEED = 0.15;

    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> itemVlMap = new ConcurrentHashMap<>();

    public NoSlowDown() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoSlowDown")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "NoSlowDown")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            double toX = PacketAccess.coordinate(event.getPacket(), 0);
                            double toY = PacketAccess.coordinate(event.getPacket(), 1);
                            double toZ = PacketAccess.coordinate(event.getPacket(), 2);

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

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight() || player.isRiptiding()) {
                                    return;
                                }

                                if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS || Math.hypot(player.getVelocity().getX(), player.getVelocity().getZ()) > VELOCITY_EXEMPTION) {
                                    decreaseVL(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                Material blockAtFeet = toLoc.getBlock().getType();
                                Material blockAtHead = toLoc.clone().add(0, 1.0, 0).getBlock().getType();

                                boolean inWeb = blockAtFeet == Material.COBWEB || blockAtHead == Material.COBWEB;
                                boolean inBushOrSnow = blockAtFeet.name().contains("BERRY_BUSH") || blockAtHead.name().contains("BERRY_BUSH") ||
                                        blockAtFeet.name().contains("POWDER_SNOW") || blockAtHead.name().contains("POWDER_SNOW");

                                if (inWeb || inBushOrSnow) {
                                    if (deltaXZ > MAX_WEB_SPEED) {
                                        flagPlayer.addFlag(player, "NoSlowDown", Lang.text("checks.NoSlowDown.name-noslowdown-web"), Lang.text("checks.NoSlowDown.details-moving-too-fast-through-a-slowing-block-speed", "delta-x-z", String.format(java.util.Locale.ROOT, "%.2f", deltaXZ)));
                                        if (CheckPolicy.setbacks("NoSlowDown")) player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        return;
                                    }
                                }

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
                                    speedLimit += SPEED_POTION_ALLOWANCE * (amplifier + 1);
                                }

                                if (deltaXZ > speedLimit) {
                                    int vl = itemVlMap.getOrDefault(uuid, 0) + 1;
                                    itemVlMap.put(uuid, vl);

                                    if (vl > ITEM_SPAM_TOLERANCE_TICKS) {
                                        flagPlayer.addFlag(player, "NoSlowDown", Lang.text("checks.NoSlowDown.name-noslowdown-item"), Lang.text("checks.NoSlowDown.details-sustained-speed-while-using-item-speed", "delta-x-z", String.format(java.util.Locale.ROOT, "%.2f", deltaXZ)));
                                        if (CheckPolicy.setbacks("NoSlowDown")) player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);
                                        itemVlMap.put(uuid, 0);
                                    }
                                } else {
                                    decreaseVL(uuid);
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("NoSlowDown", ex);
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