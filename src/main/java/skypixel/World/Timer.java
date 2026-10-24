package skypixel.World;

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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Timer. */
public class Timer implements Listener {

    @Option(value = "reset-after-idle-ms", min = 1.0, max = 1.0E9)
    private static volatile long RESET_AFTER_IDLE_MS = 2000L;

    @Option(value = "packet-cost-ms", min = 1.0, max = 1.0E9)
    private static volatile long PACKET_COST_MS = 50L; 
    @Option(value = "max-lag-buffer", min = 1.0, max = 1.0E9)
    private static volatile long MAX_LAG_BUFFER = 150L; 
    @Option(value = "flag-threshold", min = -1.0E9, max = -1.0)
    private static volatile long FLAG_THRESHOLD = -250L; 

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3; 

    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> packetBalance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violations = new ConcurrentHashMap<>();

    public Timer() {

        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.FLYING,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Timer")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Timer")) return;
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            long now = System.currentTimeMillis();
                            long lastTime = lastPacketTime.getOrDefault(uuid, now);
                            long diff = now - lastTime;

                            lastPacketTime.put(uuid, now);

                            if (diff > RESET_AFTER_IDLE_MS) {
                                packetBalance.put(uuid, 0L);
                                return;
                            }

                            long balance = packetBalance.getOrDefault(uuid, 0L);
                            balance += diff; 
                            balance -= PACKET_COST_MS; 

                            if (balance > MAX_LAG_BUFFER) {
                                balance = MAX_LAG_BUFFER;
                            }

                            if (balance < FLAG_THRESHOLD) {

                                if (CheckPolicy.cancel("Timer")) event.setCancelled(true);

                                balance = FLAG_THRESHOLD;

                                int vl = violations.getOrDefault(uuid, 0) + 1;
                                violations.put(uuid, vl);

                                if (vl > MAX_VIOLATIONS) {
                                    Diagnostics.sync(() -> {
                                        if (player.isOnline() && !player.isDead()) {
                                            flagPlayer.addFlag(player, "Timer", Lang.text("checks.Timer.name-timer"), Lang.text("checks.Timer.details-sent-packets-too-fast-game-speed-artificially-increased"));
                                        }
                                    });

                                    violations.put(uuid, 0);
                                }
                            } else {

                                int vl = violations.getOrDefault(uuid, 0);
                                if (vl > 0 && balance > (FLAG_THRESHOLD / 2)) {
                                    violations.put(uuid, vl - 1);
                                }
                            }

                            packetBalance.put(uuid, balance);

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Timer", ex);
                        }
                    }
                }
        );
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPacketTime.remove(uuid);
        packetBalance.remove(uuid);
        violations.remove(uuid);
    }

    private void resetPlayer(UUID uuid) {
        lastPacketTime.put(uuid, System.currentTimeMillis());
        packetBalance.put(uuid, 0L);
        violations.remove(uuid);
    }
}