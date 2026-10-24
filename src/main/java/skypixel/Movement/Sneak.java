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
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Sneak. */
public class Sneak implements Listener {

    @Option(value = "min-horizontal-movement", min = 0.0, max = 1.0E9)
    private static volatile double MIN_HORIZONTAL_MOVEMENT = 0.01;
    @Option(value = "damage-immunity-ticks", min = 1.0, max = 1.0E9)
    private static volatile int DAMAGE_IMMUNITY_TICKS = 10;
    @Option(value = "velocity-exemption-squared", min = 0.0, max = 1.0E9)
    private static volatile double VELOCITY_EXEMPTION_SQUARED = 0.05;

    @Option(value = "max-sneak-speed-base", min = 0.0, max = 1.0E9)
    private static volatile double MAX_SNEAK_SPEED_BASE = 0.145;

    @Option(value = "speed-potion-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double SPEED_POTION_MULTIPLIER = 0.045;

    @Option(value = "swift-sneak-multiplier", min = 0.0, max = 1.0E9)
    private static volatile double SWIFT_SNEAK_MULTIPLIER = 0.035;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Integer> sneakBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    public Sneak() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Sneak")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Sneak")) return;
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

                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            if (deltaXZ < MIN_HORIZONTAL_MOVEMENT || deltaY < 0.0) {
                                return;
                            }

                            Diagnostics.sync(() -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (!player.isSneaking()) {

                                    int vl = sneakBuffer.getOrDefault(uuid, 0);
                                    if (vl > 0) sneakBuffer.put(uuid, vl - 1);
                                    return;
                                }

                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                    return;
                                }

                                if (player.getNoDamageTicks() > DAMAGE_IMMUNITY_TICKS || player.getVelocity().lengthSquared() > VELOCITY_EXEMPTION_SQUARED) {
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                Material blockUnder = fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
                                if (blockUnder.name().contains("ICE") || blockUnder == Material.SLIME_BLOCK) {
                                    return;
                                }

                                double maxSneakSpeed = MAX_SNEAK_SPEED_BASE;

                                if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                                    int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                                    maxSneakSpeed += SPEED_POTION_MULTIPLIER * (amplifier + 1);
                                }

                                ItemStack leggings = player.getInventory().getLeggings();
                                if (leggings != null && leggings.containsEnchantment(Enchantment.SWIFT_SNEAK)) {
                                    int swiftSneakLevel = leggings.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                                    maxSneakSpeed += SWIFT_SNEAK_MULTIPLIER * swiftSneakLevel;
                                }

                                int vl = sneakBuffer.getOrDefault(uuid, 0);

                                if (deltaXZ > maxSneakSpeed) {
                                    vl++;
                                    sneakBuffer.put(uuid, vl);

                                    if (vl >= MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Sneak", Lang.text("checks.Sneak.name-sneak"), Lang.text("checks.Sneak.details-moving-too-fast-while-sneaking-speed-max", "delta-x-z", String.format(java.util.Locale.ROOT, "%.3f", deltaXZ), "max-sneak-speed", String.format(java.util.Locale.ROOT, "%.3f", maxSneakSpeed)));

                                        teleportImmunity.put(uuid, System.currentTimeMillis() + 1000L);

                                        if (CheckPolicy.setbacks("Sneak")) player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        if (CheckPolicy.enforce("Sneak")) player.setSneaking(false);

                                        sneakBuffer.put(uuid, 1); 
                                    }
                                } else {
                                    if (vl > 0) sneakBuffer.put(uuid, vl - 1);
                                }
                            });

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Sneak", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            teleportImmunity.put(uuid, System.currentTimeMillis() + 1000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        teleportImmunity.put(uuid, System.currentTimeMillis() + 3000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        teleportImmunity.put(uuid, System.currentTimeMillis() + 1500L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {

        UUID uuid = event.getPlayer().getUniqueId();
        sneakBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        teleportImmunity.remove(uuid);
    }
}