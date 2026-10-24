package skypixel.Misc;

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
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Original detection formulas; annotated defaults below are loaded from Modules.Spammer. */
public class Spammer implements Listener {

    @Option(value = "min-delay-between-messages", min = 1.0, max = 1.0E9)
    private static volatile long MIN_DELAY_BETWEEN_MESSAGES = 700L;

    @Option(value = "repeat-message-cooldown", min = 1.0, max = 1.0E9)
    private static volatile long REPEAT_MESSAGE_COOLDOWN = 5000L;

    @Option(value = "max-violations", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIOLATIONS = 3;

    private final ConcurrentHashMap<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, String> lastMessageText = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> spamViolations = new ConcurrentHashMap<>();

    public Spammer() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new CompatibleAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.HIGHEST,
                        PacketType.Play.Client.CHAT,
                        PacketType.Play.Client.CHAT_COMMAND) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Spammer")) return;

                            Player player = event.getPlayer();
                            if (player != null && CheckPolicy.exempt(player, "Spammer")) return;
                            if (player == null) return;

                            String message = event.getPacket().getStrings().read(0);

                            if (message == null || message.trim().isEmpty()) return;

                            String type = (event.getPacketType() != PacketType.Play.Client.CHAT || message.startsWith("/")) ? "Command" : "Chat";

                            if (type.equals("Command") && dakotaAC.isAdmin(player)) {
                                return;
                            }

                            if (handleSpamCheck(player, message, type)) {
                                if (CheckPolicy.cancel("Spammer")) event.setCancelled(true);
                                player.sendMessage(Lang.text("checks.Spammer.message-please-slow-down-do-not-spam-s", "to-lower-case", Lang.text("checks.Spammer.type-" + type.toLowerCase(java.util.Locale.ROOT))));
                            }

                        } catch (Exception | LinkageError ex) {
                            Diagnostics.report("Spammer", ex);
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

        if (timeSinceLastMessage < MIN_DELAY_BETWEEN_MESSAGES) {
            isSpam = true;
            flagReason = Lang.text("checks.Spammer.details-sending-s-too-fast", "to-lower-case", Lang.text("checks.Spammer.type-" + type.toLowerCase(java.util.Locale.ROOT)));
        }

        else if (currentText.equalsIgnoreCase(lastText) && timeSinceLastMessage < REPEAT_MESSAGE_COOLDOWN) {
            isSpam = true;
            flagReason = Lang.text("checks.Spammer.details-repeating-the-exact-same-too-quickly", "to-lower-case", Lang.text("checks.Spammer.type-" + type.toLowerCase(java.util.Locale.ROOT)));
        }

        if (!isSpam) {
            lastMessageTime.put(uuid, now);
            lastMessageText.put(uuid, currentText);
        }

        if (isSpam) {
            vl++;
            spamViolations.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                final String finalReason = flagReason;
                Diagnostics.sync(() -> {
                    if (player.isOnline()) {
                        flagPlayer.addFlag(player, "Spammer", Lang.text("checks.Spammer.name-spammer"), finalReason);
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