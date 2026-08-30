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
    // Timpul minim (în milisecunde) permis între două mesaje/comenzi. (700 = 0.7 secunde)
    private static final long MIN_DELAY_BETWEEN_MESSAGES = 700L;

    // Numărul maxim de avertismente (spam-uri anulate) înainte să alertăm adminii
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Folosim ConcurrentHashMap deoarece ProtocolLib citește pachetele de chat asincron
    private final ConcurrentHashMap<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> spamViolations = new ConcurrentHashMap<>();

    public Spammer() {
        // Interceptăm pachetul CHAT și CHAT_COMMAND (esențial pentru 1.19+)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST, // Prioritate maximă pentru a tăia mesajul primul
                        PacketType.Play.Client.CHAT,
                        PacketType.Play.Client.CHAT_COMMAND) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Spammer")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            // Citim string-ul (textul) direct din pachetul trimis de client
                            String message = event.getPacket().getStrings().readSafely(0);

                            // Prevenim erorile în cazul pachetelor de chat goale sau modificate
                            if (message == null || message.trim().isEmpty()) return;

                            // Tratăm diferențiat, având în vedere că acum prindem și CHAT_COMMAND
                            String type = (event.getPacketType() == PacketType.Play.Client.CHAT_COMMAND || message.startsWith("/")) ? "Command" : "Chat";

                            // Iertăm adminii la comenzi (Verificarea permisiunilor este thread-safe în Bukkit)
                            if (type.equals("Command") && player.hasPermission("dakotaac.admin")) {
                                return;
                            }

                            // Trimitem textul către filtrul logic
                            if (handleSpamCheck(player, message, type)) {

                                // Oprim pachetul direct pe placa de rețea!
                                // Plugin-urile de chat și consola nu vor vedea niciodată acest mesaj.
                                event.setCancelled(true);

                                player.sendMessage("§c§l[!] §cPlease slow down! Do not spam " + type.toLowerCase() + "s.");
                            }

                        } catch (Exception ex) {
                            // Protecție în caz de schimbări de versiune
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    /**
     * Metoda care procesează logica (Viteza și Repetiția).
     * Returnează TRUE dacă este spam (trebuie blocat), FALSE dacă este curat.
     */
    private boolean handleSpamCheck(Player player, String currentText, String type) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        long lastTime = lastMessageTime.getOrDefault(uuid, 0L);
        String lastText = lastMessageText.getOrDefault(uuid, "");

        int vl = spamViolations.getOrDefault(uuid, 0);

        boolean isSpam = false;
        String flagReason = "";

        // LOGICA 1: Viteza (Sub MIN_DELAY_BETWEEN_MESSAGES între mesaje)
        if (now - lastTime < MIN_DELAY_BETWEEN_MESSAGES) {
            isSpam = true;
            flagReason = "Sending " + type.toLowerCase() + "s too fast.";
        }
        // LOGICA 2: Repetiția (Același mesaj)
        else if (currentText.equalsIgnoreCase(lastText)) {
            isSpam = true;
            flagReason = "Repeating the exact same " + type.toLowerCase() + ".";
        }

        // Actualizăm memoria cu noul mesaj și timpul curent
        lastMessageTime.put(uuid, now);
        lastMessageText.put(uuid, currentText);

        if (isSpam) {
            vl++;
            spamViolations.put(uuid, vl);

            // Dacă forțează de repetate ori (bot de spam activat), trimitem alerta!
            if (vl >= MAX_VIOLATIONS) {
                // Trimiterea alertei (flag) trebuie delegată către Main Thread pentru stabilitate
                final String finalReason = flagReason;
                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                    if (player.isOnline()) {
                        flagPlayer.addFlag(player, "Spammer", finalReason);
                    }
                });

                // Resetăm VL-ul puțin ca să nu facă spam și la noi în consolă
                spamViolations.put(uuid, MAX_VIOLATIONS - 1);
            }
            return true;
        } else {
            // Dacă a fost cuminte și a scris normal, îi iertăm treptat suspiciunile
            if (vl > 0) {
                spamViolations.put(uuid, vl - 1);
            }
        }

        return false;
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Ștergem datele ca să păstrăm memoria RAM liberă
        UUID uuid = event.getPlayer().getUniqueId();
        lastMessageTime.remove(uuid);
        lastMessageText.remove(uuid);
        spamViolations.remove(uuid);
    }
}