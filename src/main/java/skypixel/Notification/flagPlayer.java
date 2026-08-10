package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.UUID;

public class flagPlayer {

    private static final String NOTIFY_PERMISSION = "dakotaac.notification";

    // Variabilă globală pentru a opri/porni alertele în chat
    public static boolean alertsEnabled = true;

    // Memorie UNIFICATĂ pentru a preveni spam-ul în consolă ȘI în chat (stocăm timpul ultimului mesaj)
    private static final HashMap<UUID, Long> lastAlertTime = new HashMap<>();

    public static void addFlag(Player player, String hackType, String details) {
        UUID uuid = player.getUniqueId();

        // --------------------------------------------------------
        // 1. SISTEMUL DE PEDEPSE (Se execută MEREU, de fiecare dată când e detectat un hack)
        // --------------------------------------------------------
        Violation.addViolation(player, hackType, details);

        // --------------------------------------------------------
        // 2. SISTEMUL DE ALERTE ANTI-SPAM (Consolă + Chat)
        // --------------------------------------------------------
        long currentTime = System.currentTimeMillis();

        // Dacă jucătorul nu are o alertă recentă, sau au trecut mai mult de 3 secunde (3000ms)
        // de la ultima alertă, permitem afișarea mesajelor noi.
        if (!lastAlertTime.containsKey(uuid) || (currentTime - lastAlertTime.get(uuid)) > 3000) {

            // --- A. Logica pentru Consolă ---
            String consoleAlert = org.bukkit.ChatColor.translateAlternateColorCodes('&',
                    "&8[&cDakotaAC&8] &e" + player.getName() + " &7was flagged for &c" + hackType + " &8(&7" + details + "&8)");
            Bukkit.getConsoleSender().sendMessage(consoleAlert);

            // --- B. Logica pentru Chat-ul din Joc ---
            // Verificăm dacă adminul a lăsat alertele pornite din comandă
            if (alertsEnabled) {
                String staffMessage = ChatColor.translateAlternateColorCodes('&',
                        "&8[&cDakotaAC&8] &e" + player.getName() + " &7is suspected of &c" + hackType + " &8(&7" + details + "&8)");

                for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                    if (onlinePlayer.hasPermission(NOTIFY_PERMISSION)) {
                        onlinePlayer.sendMessage(staffMessage);
                    }
                }
            }

            // Salvăm noul timp (se aplică pentru ambele sisteme)
            lastAlertTime.put(uuid, currentTime);
        }
    }

    /**
     * Metodă utilă pentru a preveni memory leaks atunci când jucătorul iese de pe server.
     */
    public static void clearCache(UUID uuid) {
        lastAlertTime.remove(uuid);
    }
}