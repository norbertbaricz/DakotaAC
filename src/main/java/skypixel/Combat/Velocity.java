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
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Velocity. */
public class Velocity implements Listener {

    @Option(value = "jump-reset-min-y", min = 0.0, max = 1.0E9)
    private static volatile double JUMP_RESET_MIN_Y = 0.4;
    @Option(value = "min-expected-horizontal", min = 0.0, max = 1.0E9)
    private static volatile double MIN_EXPECTED_HORIZONTAL = 0.1;
    @Option(value = "min-knockback-y", min = 0.0, max = 1.0E9)
    private static volatile double MIN_KNOCKBACK_Y = 0.1;
    @Option(value = "ceiling-check-height", min = 0.0, max = 1.0E9)
    private static volatile double CEILING_CHECK_HEIGHT = 2.0;

    @Option(value = "min-vertical-ratio", min = 0.0, max = 1.0E9)
    private static volatile double MIN_VERTICAL_RATIO = 0.35;

    @Option(value = "min-horizontal-ratio", min = 0.0, max = 1.0E9)
    private static volatile double MIN_HORIZONTAL_RATIO = 0.15;

    @Option(value = "max-ping-delay-ms", min = 1.0, max = 1.0E9)
    private static volatile long MAX_PING_DELAY_MS = 600;

    private static class ExpectedKnockback {
        Vector expectedVelocity;
        long timestamp;

        ExpectedKnockback(Vector v, long t) {
            this.expectedVelocity = v;
            this.timestamp = t;
        }
    }

    private final ConcurrentHashMap<UUID, ExpectedKnockback> expectedVelocityMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Velocity() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Velocity")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Velocity")) return;
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            ExpectedKnockback kbData = expectedVelocityMap.get(uuid);
                            if (kbData == null) return;

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

                            long timeSinceHit = System.currentTimeMillis() - kbData.timestamp;

                            double expectedY = kbData.expectedVelocity.getY();
                            double expectedHorizontal = Math.hypot(kbData.expectedVelocity.getX(), kbData.expectedVelocity.getZ());
                            double actualHorizontal = Math.hypot(deltaX, deltaZ);

                            if (deltaY > 0.0) {

                                boolean isJumpReset = deltaY >= JUMP_RESET_MIN_Y;

                                if (!isJumpReset && deltaY < (expectedY * MIN_VERTICAL_RATIO)) {
                                    flagSync(player, Lang.text("checks.Velocity.name-velocity-vertical-reduce"), Lang.text("checks.Velocity.details-took-only-vertical-kb", "expected-y", String.format(java.util.Locale.ROOT, "%.1f", (deltaY/expectedY)*100)));
                                }

                                else if (expectedHorizontal > MIN_EXPECTED_HORIZONTAL && actualHorizontal < (expectedHorizontal * MIN_HORIZONTAL_RATIO)) {

                                    if (!isJumpReset) {
                                        flagSync(player, Lang.text("checks.Velocity.name-velocity-horizontal-reduce"), Lang.text("checks.Velocity.details-ignored-horizontal-kb"));
                                    }
                                }

                                expectedVelocityMap.remove(uuid);

                            } else {

                                if (timeSinceHit > MAX_PING_DELAY_MS) {
                                    flagSync(player, Lang.text("checks.Velocity.name-velocity-cancel-lag"), Lang.text("checks.Velocity.details-no-reaction-to-kb-after-ms", "value", MAX_PING_DELAY_MS));
                                    expectedVelocityMap.remove(uuid);
                                }
                            }

                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Velocity", ex);
                        }
                    }
                }
        );
    }

    private void flagSync(Player player, String type, String details) {
        Diagnostics.sync(() -> {
            if (player.isOnline() && !player.isDead()) {
                flagPlayer.addFlag(player, "Velocity", type, details);
            }
        });
    }

    @EventHandler
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!dakotaAC.isCheckActive("Velocity")) return;

        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "Velocity")) return;
        if (player.getGameMode() == GameMode.CREATIVE || player.isDead()) return;

        Vector velocity = event.getVelocity();

        if (velocity.getY() > MIN_KNOCKBACK_Y) {
            if (!canTakeKnockback(player)) return;

            expectedVelocityMap.put(player.getUniqueId(), new ExpectedKnockback(velocity.clone(), System.currentTimeMillis()));

            Location loc = player.getLocation();
            lastPosMap.put(player.getUniqueId(), new double[]{loc.getX(), loc.getY(), loc.getZ()});
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        expectedVelocityMap.remove(uuid);
        lastPosMap.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        expectedVelocityMap.remove(uuid);
        lastPosMap.remove(uuid);
    }

    private boolean canTakeKnockback(Player player) {
        Location loc = player.getLocation();
        Material currentBlock = loc.getBlock().getType();

        if (currentBlock.name().contains("WATER") || currentBlock.name().contains("LAVA") ||
                currentBlock.name().contains("COBWEB") || currentBlock.name().contains("VINE") ||
                currentBlock.name().contains("HONEY")) {
            return false;
        }

        Block blockAbove = loc.clone().add(0, CEILING_CHECK_HEIGHT, 0).getBlock();
        if (blockAbove.getType().isSolid()) {
            return false;
        }

        return true;
    }
}