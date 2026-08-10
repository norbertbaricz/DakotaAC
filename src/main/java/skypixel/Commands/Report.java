package skypixel.Commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import skypixel.Misc.AntiBot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class Report implements CommandExecutor, TabCompleter {

    private final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

    // Stocăm report-urile folosind UUID
    private final Map<UUID, Integer> reportCounts = new HashMap<>();

    // Numărul de report-uri necesare pentru a declanșa verificarea AntiBot
    private final int REPORTS_NEEDED_FOR_ANTIBOT = 3;

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /report <player>");
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online!");
            return true;
        }

        if (sender instanceof Player && target.equals(sender)) {
            sender.sendMessage(ChatColor.RED + "You cannot report yourself!");
            return true;
        }

        UUID targetUUID = target.getUniqueId();
        int currentReports = reportCounts.getOrDefault(targetUUID, 0) + 1;
        reportCounts.put(targetUUID, currentReports);

        // Notificăm Staff-ul online
        String reporterName = sender.getName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("dakotaac.admin")) {
                p.sendMessage(PREFIX + ChatColor.YELLOW + reporterName + ChatColor.GRAY + " has reported "
                        + ChatColor.RED + target.getName() + ChatColor.GRAY + "!");
                p.sendMessage(PREFIX + "Total reports: " + ChatColor.RED + currentReports);
            }
        }

        sender.sendMessage(ChatColor.GREEN + "Your report has been sent to the online staff!");

        // Verificăm dacă s-a atins limita pentru AntiBot
        if (currentReports >= REPORTS_NEEDED_FOR_ANTIBOT) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("dakotaac.admin")) {
                    p.sendMessage(PREFIX + ChatColor.DARK_RED + "⚠ " + ChatColor.RED + target.getName() + " has accumulated " + currentReports + " reports! Triggering AntiBot module...");
                }
            }

            // Declanșăm AntiBot-ul direct din clasa AntiBot
            AntiBot.executeReportCheck(target);

            // Resetăm contorul de report-uri pentru a nu face spam
            reportCounts.put(targetUUID, 0);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        // Returnăm null pentru ca Bukkit să sugereze automat jucătorii online
        if (args.length == 1) {
            return null;
        }
        return new ArrayList<>();
    }
}