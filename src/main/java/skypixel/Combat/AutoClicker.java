package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
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

public class AutoClicker implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Limita maximă de click-uri pe secundă (CPS).
    // Drag Click / Butterfly Click extrem poate atinge 20-24 CPS.
    // 25 este pragul sigur care desparte tryhard-ul de Macro/AutoClicker.
    private static final int MAX_ALLOWED_CPS = 25;

    // Fereastra de timp în care contorizăm click-urile (1000ms = 1 secundă)
    private static final long TIME_WINDOW_MS = 1000L;

    // Câte secunde de CPS inuman iertăm? (Absoarbe complet lag-ul de rețea / TCP Bursts)
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Folosim colecții Thread-Safe pentru istoric
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Long>> clickHistory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violationBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastViolationTime = new ConcurrentHashMap<>();

    // Urmărim exact starea de minat a clientului din pachetele de rețea
    private final ConcurrentHashMap<UUID, Boolean> isMining = new ConcurrentHashMap<>();

    public AutoClicker() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.ARM_ANIMATION,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("AutoClicker")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            // ----------------------------------------------------
                            // 1. ACTUALIZĂM STATUSUL DE MINAT (BLOCK_DIG)
                            // ----------------------------------------------------
                            if (type == PacketType.Play.Client.BLOCK_DIG) {
                                EnumWrappers.PlayerDigType digType = event.getPacket().getPlayerDigTypes().readSafely(0);

                                if (digType == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                    isMining.put(uuid, true);
                                } else if (digType == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK ||
                                        digType == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {
                                    isMining.put(uuid, false);
                                }
                                return;
                            }

                            // ----------------------------------------------------
                            // 2. LOGICA DE AUTOCLICKER (ARM_ANIMATION)
                            // ----------------------------------------------------
                            if (type == PacketType.Play.Client.ARM_ANIMATION) {

                                // Dacă știm că jucătorul minează chiar acum, ignorăm animațiile naturale
                                if (isMining.getOrDefault(uuid, false)) {
                                    return;
                                }

                                long now = System.currentTimeMillis();

                                clickHistory.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());
                                ConcurrentLinkedQueue<Long> history = clickHistory.get(uuid);

                                history.add(now);

                                // Curățăm istoricul: ștergem click-urile mai vechi de 1 secundă
                                while (!history.isEmpty() && history.peek() < now - TIME_WINDOW_MS) {
                                    history.poll();
                                }

                                int currentCPS = history.size();

                                // Dacă a depășit limita admisă (Macro sau Lag extrem)
                                if (currentCPS > MAX_ALLOWED_CPS) {

                                    int vl = violationBuffer.getOrDefault(uuid, 0) + 1;
                                    violationBuffer.put(uuid, vl);
                                    lastViolationTime.put(uuid, now);

                                    // Curățăm coada INSTANT! Asta transformă un Lag Spike uriaș de 60 de click-uri
                                    // într-o singură penalizare (+1 VL), protejând jucătorul legitim.
                                    history.clear();

                                    // A menținut un ritm inuman prea mult timp. Este 100% hack.
                                    if (vl >= MAX_VIOLATIONS) {
                                        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                            if (player.isOnline()) {
                                                flagPlayer.addFlag(player, "AutoClicker", "Inhuman CPS detected (" + currentCPS + " CPS).");
                                            }
                                        });

                                        violationBuffer.put(uuid, MAX_VIOLATIONS - 1); // Resetăm parțial pentru a evita spam-ul
                                    }
                                } else {
                                    // Sistem de "Decay": Dacă joacă normal timp de 3 secunde, îl iertăm de vechile Spike-uri.
                                    long lastSpike = lastViolationTime.getOrDefault(uuid, 0L);
                                    if (now - lastSpike > 3000L) {
                                        int vl = violationBuffer.getOrDefault(uuid, 0);
                                        if (vl > 0) violationBuffer.put(uuid, vl - 1);
                                        lastViolationTime.put(uuid, now); // Resetăm timer-ul de decay
                                    }
                                }
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
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