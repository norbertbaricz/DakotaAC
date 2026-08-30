package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Violation implements Listener {

    // Stocăm flag-urile permanent în memoria RAM a serverului (până la restart).
    // Folosim ConcurrentHashMap deoarece apelurile pot veni de pe thread-ul asincron (Netty).
    private static final ConcurrentHashMap<UUID, Integer> totalFlags = new ConcurrentHashMap<>();

    /**
     * Adaugă un flag jucătorului și verifică dacă a atins multiplele pentru Kick sau Ban.
     */
    public static void addViolation(Player player, String hackType, String details) {
        UUID uuid = player.getUniqueId();

        // Creștem numărul de flag-uri cu 1
        int flags = totalFlags.getOrDefault(uuid, 0) + 1;
        totalFlags.put(uuid, flags);

        // 1. Verificăm BAN-ul folosind Multiplu (%) pentru execuții infinite (ex: la 2000, 4000, 6000)
        // Oprim execuția cu 'return' pentru ca jucătorul să nu primească și Kick și Ban în același timp.
        if (dakotaAC.isAutoBan() && (flags % dakotaAC.getMaxBanFlags() == 0)) {
            executePunishment(player, hackType, details, "BAN");
            return;
        }

        // 2. Verificăm KICK-ul folosind Multiplu (%) (ex: la 800, 1600, 2400)
        if (dakotaAC.isAutoKick() && (flags % dakotaAC.getMaxKickFlags() == 0)) {
            executePunishment(player, hackType, details, "KICK");
        }
    }

    /**
     * Execută pedeapsa. Memoria din HashMap NU se șterge, deci punctele își continuă numărătoarea!
     */
    private static void executePunishment(Player player, String hackType, String details, String punishmentType) {

        String reason = ChatColor.translateAlternateColorCodes('&',
                "&c&lDakotaAC Security\n\n" +
                        "&7You have been automatically removed from the server.\n" +
                        "&7Reason: &c" + hackType + "\n" +
                        "&7Details: &8" + details);

        if (punishmentType.equals("BAN")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "ban " + player.getName() + " [DakotaAC] " + hackType + " - " + details);
            player.kickPlayer(reason);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &c" + player.getName() + " &7was banned for &c" + hackType + "&7."));
        } else {
            player.kickPlayer(reason);
            Bukkit.broadcastMessage(ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &c" + player.getName() + " &7was kicked for &c" + hackType + "&7."));
        }
    }

    // Am eliminat complet evenimentele de PlayerJoin și PlayerQuit.
    // Odată înregistrat în `totalFlags`, jucătorul rămâne acolo cu punctele sale
    // exact ca într-o memorie permanentă a sesiunii!
}