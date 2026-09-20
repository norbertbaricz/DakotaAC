package skypixel.Misc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Spammer implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Timpul minim (în milisecunde) permis între două mesaje/comenzi ORICARE AR FI ELE.
    private static final long MIN_DELAY_BETWEEN_MESSAGES = 700L;

    // FIX: Timpul maxim (în milisecunde) în care NU ai voie să repeți EXACT același mesaj.
    // (5000L = 5 secunde). După 5 secunde, poți scrie din nou același mesaj.
    private static final long REPEAT_MESSAGE_COOLDOWN = 5000L;

    // Numărul maxim de avertismente (spam-uri anulate) înainte să alertăm adminii
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    private final ConcurrentHashMap<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> spamViolations = new ConcurrentHashMap<>();

    public Spammer() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.CHAT,
                        PacketType.Play.Client.CHAT_COMMAND) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Spammer")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            String message = event.getPacket().getStrings().readSafely(0);

                            if (message == null || message.trim().isEmpty()) return;

                            String type = (event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND || message.startsWith("/")) ? "Command" : "Chat";

                            if (type.equals("Command") && player.hasPermission("dakotaac.admin")) {
                                return;
                            }

                            if (handleSpamCheck(player, message, type)) {
                                event.setCancelled(true);
                                player.sendMessage("§c§l[!] §cPlease slow down! Do not spam " + type.toLowerCase() + "s.");
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    private boolean handleSpamCheck(Player player, String currentText, String type) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        long lastTime = lastMessageTime.getOrDefault(uuid, 0L);
        String lastText = lastMessageText.getOrDefault(uuid, "");

        int vl = spamViolations.getOrDefault(uuid, 0);

        boolean isSpam = false;
        String flagReason = "";

        long timeSinceLastMessage = now - lastTime;

        // LOGICA 1: Viteza (Sub MIN_DELAY_BETWEEN_MESSAGES între oricare mesaje)
        if (timeSinceLastMessage < MIN_DELAY_BETWEEN_MESSAGES) {
            isSpam = true;
            flagReason = "Sending " + type.toLowerCase() + "s too fast.";
        }
        // LOGICA 2 (FIX): Repetiția (Același mesaj în mai puțin de REPEAT_MESSAGE_COOLDOWN)
        else if (currentText.equalsIgnoreCase(lastText) && timeSinceLastMessage < REPEAT_MESSAGE_COOLDOWN) {
            isSpam = true;
            flagReason = "Repeating the exact same " + type.toLowerCase() + " too quickly.";
        }

        // Actualizăm memoria cu noul mesaj și timpul curent doar dacă NU a fost spam,
        // sau dacă a fost spam de tip Viteză. Nu vrem ca mesajul blocat să reseteze complet timpul pentru un om onest.
        if (!isSpam) {
            lastMessageTime.put(uuid, now);
            lastMessageText.put(uuid, currentText);
        }

        if (isSpam) {
            vl++;
            spamViolations.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                final String finalReason = flagReason;
                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                    if (player.isOnline()) {
                        flagPlayer.addFlag(player, "Spammer", finalReason);
                    }
                });

                spamViolations.put(uuid, MAX_VIOLATIONS - 1);
            }
            return true;
        } else {
            if (vl > 0) {
                spamViolations.put(uuid, vl - 1);
            }
        }

        return false;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastMessageTime.remove(uuid);
        lastMessageText.remove(uuid);
        spamViolations.remove(uuid);
    }
}