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
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Original detection formulas; annotated defaults below are loaded from Modules.AutoClicker. */
public class AutoClicker implements Listener {

    @Option(value = "buffer-decay-delay-ms", min = 1.0, max = 1.0E9)
    private static volatile long BUFFER_DECAY_DELAY_MS = 3000L;

    @Option(value = "max-allowed-cps", min = 1.0, max = 1.0E9)
    private static volatile int MAX_ALLOWED_CPS = 25;

    @Option(value = "time-window-ms", min = 1.0, max = 1.0E9)
    private static volatile long TIME_WINDOW_MS = 1000L;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Long>> clickHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastViolationTime = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<UUID, Boolean> isMining = new ConcurrentHashMap<>();

    public AutoClicker() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.ARM_ANIMATION,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AutoClicker")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "AutoClicker")) return;
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            if (type == PacketType.Play.Client.BLOCK_DIG) {
                                EnumWrappers.PlayerDigType digType = event.getPacket().getPlayerDigTypes().read(0);

                                if (digType == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                    isMining.put(uuid, true);
                                } else if (digType == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK ||
                                        digType == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {
                                    isMining.put(uuid, false);
                                }
                                return;
                            }

                            if (PacketAccess.isSwing(type)) {

                                if (isMining.getOrDefault(uuid, false)) {
                                    return;
                                }

                                long now = System.currentTimeMillis();

                                clickHistory.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());
                                ConcurrentLinkedQueue<Long> history = clickHistory.get(uuid);

                                history.add(now);

                                while (!history.isEmpty() && history.peek() < now - TIME_WINDOW_MS) {
                                    history.poll();
                                }

                                int currentCPS = history.size();

                                if (currentCPS > MAX_ALLOWED_CPS) {

                                    int vl = violationBuffer.getOrDefault(uuid, 0) + 1;
                                    violationBuffer.put(uuid, vl);
                                    lastViolationTime.put(uuid, now);

                                    history.clear();

                                    if (vl >= MAX_VIOLATIONS) {
                                        Diagnostics.sync(() -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "AutoClicker", Lang.text("checks.AutoClicker.name-autoclicker"), Lang.text("checks.AutoClicker.details-inhuman-cps-detected-cps", "current-c-p-s", currentCPS));
                                            }
                                        });

                                        violationBuffer.put(uuid, MAX_VIOLATIONS - 1); 
                                    }
                                } else {

                                    long lastSpike = lastViolationTime.getOrDefault(uuid, 0L);
                                    if (now - lastSpike > BUFFER_DECAY_DELAY_MS) {
                                        int vl = violationBuffer.getOrDefault(uuid, 0);
                                        if (vl > 0) violationBuffer.put(uuid, vl - 1);
                                        lastViolationTime.put(uuid, now); 
                                    }
                                }
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("AutoClicker", ex);
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        clickHistory.remove(uuid);
        isMining.remove(uuid);
        violationBuffer.remove(uuid);
        lastViolationTime.remove(uuid);
    }
}