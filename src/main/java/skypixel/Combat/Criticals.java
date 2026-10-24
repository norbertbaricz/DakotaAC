package skypixel.Combat;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Criticals. */
public class Criticals implements Listener {

    @Option(value = "zero-vertical-tolerance", min = 0.0, max = 1.0E9)
    private static volatile double ZERO_VERTICAL_TOLERANCE = 0.0001;
    @Option(value = "min-vanilla-jump", min = 0.0, max = 1.0E9)
    private static volatile double MIN_VANILLA_JUMP = 0.41;
    @Option(value = "micro-jump-max-ticks", min = 1.0, max = 1.0E9)
    private static volatile int MICRO_JUMP_MAX_TICKS = 2;
    @Option(value = "min-jump-cycle-ms", min = 1.0, max = 1.0E9)
    private static volatile long MIN_JUMP_CYCLE_MS = 50L;

    private final ConcurrentHashMap<UUID, Double> lastYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> deltaYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> lastDeltaYMap = new ConcurrentHashMap<>(); 
    private final ConcurrentHashMap<UUID, Boolean> onGroundMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> airTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastGroundTimeMap = new ConcurrentHashMap<>(); 

    public Criticals() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getInstance(),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.LOOK,
                        PacketType.Play.Client.FLYING) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Criticals")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Criticals")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            if (PacketAccess.isUseEntity(type)) {
                                if (!PacketAccess.isAttack(event)) return;
                                if (player.isInWater() || player.getVehicle() != null || player.isGliding()
                                        || player.getAllowFlight() || player.isClimbing()
                                        || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)
                                        || player.hasPotionEffect(PotionEffectType.LEVITATION)
                                        || player.getLocation().getBlock().getType().name().contains("COBWEB")) return;

                                Double dY = deltaYMap.get(uuid);
                                Double lastDY = lastDeltaYMap.get(uuid);
                                Boolean og = onGroundMap.get(uuid);
                                Integer ticks = airTicksMap.get(uuid);
                                Long lastGroundTime = lastGroundTimeMap.get(uuid);

                                if (dY == null || og == null || ticks == null || lastGroundTime == null) return;

                                if (og) return;

                                boolean isFlagged = false;
                                String flagReason = "";

                                long timeInAir = System.currentTimeMillis() - lastGroundTime;

                                if (Math.abs(dY) < ZERO_VERTICAL_TOLERANCE && ticks > 0) {
                                    isFlagged = true;
                                    flagReason = Lang.text("checks.Criticals.details-zero-vertical-velocity-mid-air-noground");
                                }

                                else if (dY > 0.0 && dY < MIN_VANILLA_JUMP && ticks <= MICRO_JUMP_MAX_TICKS) {
                                    isFlagged = true;
                                    flagReason = Lang.text("checks.Criticals.details-impossible-micro-jump-detected-y", "d-y", String.format(java.util.Locale.ROOT, "%.4f", dY));
                                }

                                else if (dY < 0.0 && lastDY != null && lastDY > 0.0 && timeInAir < MIN_JUMP_CYCLE_MS) {
                                    isFlagged = true;
                                    flagReason = Lang.text("checks.Criticals.details-packet-burst-blink-timer-jump-cycle-completed-in", "time-in-air", timeInAir);
                                }

                                if (isFlagged) {

                                    if (CheckPolicy.cancel("Criticals")) event.setCancelled(true);
                                    final String finalReason = flagReason;

                                    Diagnostics.sync(() -> {
                                        if (player.isOnline() && !player.isDead()) {

                                            if (player.isInWater() || player.getVehicle() != null || player.isGliding() ||
                                                    player.getAllowFlight() || player.isClimbing() ||
                                                    player.hasPotionEffect(PotionEffectType.JUMP_BOOST) ||
                                                    player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                                return;
                                            }

                                            if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
                                                return;
                                            }

                                            flagPlayer.addFlag(player, "Criticals", Lang.text("checks.Criticals.name-criticals"), finalReason);
                                        }
                                    });
                                }
                            }

                            else {
                                boolean onGround = PacketAccess.onGround(event.getPacket());
                                onGroundMap.put(uuid, onGround);

                                if (onGround) {
                                    airTicksMap.put(uuid, 0);
                                    lastGroundTimeMap.put(uuid, System.currentTimeMillis());
                                } else {
                                    airTicksMap.put(uuid, airTicksMap.getOrDefault(uuid, 0) + 1);
                                }

                                if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.POSITION_LOOK) {
                                    double y = PacketAccess.coordinate(event.getPacket(), 1);
                                    Double prevY = lastYMap.get(uuid);

                                    if (prevY != null) {
                                        double currentDelta = y - prevY;
                                        Double prevDelta = deltaYMap.getOrDefault(uuid, 0.0);

                                        lastDeltaYMap.put(uuid, prevDelta);
                                        deltaYMap.put(uuid, currentDelta);
                                    }
                                    lastYMap.put(uuid, y);
                                }
                            }

                        } catch (Exception | LinkageError ex) { Diagnostics.report("Criticals", ex); }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastYMap.remove(uuid);
        deltaYMap.remove(uuid);
        lastDeltaYMap.remove(uuid);
        onGroundMap.remove(uuid);
        airTicksMap.remove(uuid);
        lastGroundTimeMap.remove(uuid);
    }
}