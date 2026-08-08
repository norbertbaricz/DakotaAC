package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.dakotaAC;

import java.util.HashMap;
import java.util.UUID;

public class violation implements Listener {

    // Stocăm câte flag-uri a acumulat fiecare jucător
    private static final HashMap<UUID, Integer> totalFlags = new HashMap<>();

    /**
     * Adaugă un flag jucătorului și verifică dacă trebuie pedepsit
     */
    public static void addViolation(Player player, String hackType, String details) {
        UUID uuid = player.getUniqueId();

        // Creștem numărul de flag-uri cu 1
        int flags = totalFlags.getOrDefault(uuid, 0) + 1;
        totalFlags.put(uuid, flags);

        // 1. Verificăm BAN-ul mai întâi (Limita superioară)
        if (dakotaAC.isAutoBan() && flags >= dakotaAC.getMaxBanFlags()) {
            executePunishment(player, hackType, details, "BAN");
            return;
        }

        // 2. Verificăm KICK-ul
        // Folosim "%" (modulo) ca să dea kick exact la multiplu (ex: dacă limita e 15, dă kick la 15, 30, 45... în caz că Ban-ul e dezactivat)
        if (dakotaAC.isAutoKick() && flags % dakotaAC.getMaxKickFlags() == 0) {
            executePunishment(player, hackType, details, "KICK");
        }
    }

    /**
     * Execută pedeapsa și curăță memoria DOAR la BAN
     */
    private static void executePunishment(Player player, String hackType, String details, String punishmentType) {

        String reason = ChatColor.translateAlternateColorCodes('&',
                "&c&lDakotaAC Security\n\n" +
                        "&7You have been automatically removed from the server.\n" +
                        "&7Reason: &c" + hackType + "\n" +
                        "&7Details: &8" + details);

        if (punishmentType.equals("BAN")) {
            // ȘTERGEM FLAG-URILE DOAR LA BAN!
            totalFlags.remove(player.getUniqueId());

            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player.getName() + " [DakotaAC] " + hackType + " - " + details);
            player.kickPlayer(reason);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &c" + player.getName() + " &7was banned for &c" + hackType + "&7."));
        } else {
            // DOAR KICK. Memoria (totalFlags) rămâne neatinsă ca să se adune în continuare!
            player.kickPlayer(reason);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &c" + player.getName() + " &7was kicked for &c" + hackType + "&7."));
        }
    }

    /**
     * AM ELIMINAT STERGEREA DE AICI!
     * Dacă ștergem flagurile când dă quit, le va pierde și când ia Kick, ceea ce strică planul.
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Opțional: Aici poți stoca în Baza de Date flag-urile când iese, dacă vrei să persiste la restart!
    }
}