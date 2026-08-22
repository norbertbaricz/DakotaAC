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
    // Limita maximă de click-uri pe secundă (CPS) permisă.
    // Un om normal face 6-10 CPS. Cei care folosesc Butterfly/Drag click pot atinge 15-20.
    // Peste 20 este garantat macro sau AutoClicker inuman.
    private static final int MAX_ALLOWED_CPS = 20;

    // Fereastra de timp în care contorizăm click-urile (1000ms = 1 secundă)
    private static final long TIME_WINDOW_MS = 1000L;
    // ==========================================

    // Folosim colecții Thread-Safe pentru istoricul click-urilor
    private final ConcurrentHashMap<UUID, ConcurrentLinkedQueue<Long>> clickHistory = new ConcurrentHashMap<>();

    // Urmărim exact starea de minat a clientului din pachetele de rețea
    private final ConcurrentHashMap<UUID, Boolean> isMining = new ConcurrentHashMap<>();

    public AutoClicker() {
        // Interceptăm ambele pachete: Animația brațului ȘI Interacțiunea cu blocurile
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
                                    // Clientul ne anunță că ține apăsat click pentru a sparge un bloc
                                    isMining.put(uuid, true);
                                } else if (digType == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK ||
                                        digType == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {
                                    // Clientul a terminat de spart blocul sau a renunțat
                                    isMining.put(uuid, false);
                                }
                                return; // Ieșim, acest pachet nu este un click ofensiv
                            }

                            // ----------------------------------------------------
                            // 2. LOGICA DE AUTOCLICKER (ARM_ANIMATION)
                            // ----------------------------------------------------
                            if (type == PacketType.Play.Client.ARM_ANIMATION) {

                                // FIX MAJOR: Dacă știm că jucătorul minează chiar acum, ignorăm animațiile!
                                // Acest filtru absoarbe toate pachetele trimise natural de joc în timpul minatului.
                                if (isMining.getOrDefault(uuid, false)) {
                                    return;
                                }

                                long now = System.currentTimeMillis();

                                clickHistory.putIfAbsent(uuid, new ConcurrentLinkedQueue<>());
                                ConcurrentLinkedQueue<Long> history = clickHistory.get(uuid);

                                history.add(now);

                                // Curățăm istoricul: ștergem click-urile care sunt mai vechi de o secundă (TIME_WINDOW_MS)
                                while (!history.isEmpty() && history.peek() < now - TIME_WINDOW_MS) {
                                    history.poll();
                                }

                                int currentCPS = history.size();

                                // Verificăm dacă a depășit limita setată de noi
                                if (currentCPS > MAX_ALLOWED_CPS) {
                                    history.clear(); // Îi curățăm buffer-ul ca să nu primească spam de mesaje

                                    // Trimitem flag-ul pe thread-ul principal (Bukkit) ca să fie sigur și curat
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline()) {
                                            flagPlayer.addFlag(player, "AutoClicker", "Inhuman CPS detected (" + currentCPS + " CPS).");
                                        }
                                    });
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
        // Prevenim scurgerile de memorie (Memory Leaks)
        UUID uuid = event.getPlayer().getUniqueId();
        clickHistory.remove(uuid);
        isMining.remove(uuid);
    }
}