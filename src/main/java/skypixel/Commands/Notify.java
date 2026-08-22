package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;
import skypixel.Notification.flagPlayer;

public class Notify extends SubCommand {

    @Override
    public String getName() {
        return "notify";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleNotifications();
        if (flagPlayer.alertsEnabled) {
            sender.sendMessage(PREFIX + ChatColor.GREEN + "AntiCheat notifications are now ENABLED.");
        } else {
            sender.sendMessage(PREFIX + ChatColor.RED + "AntiCheat notifications are now DISABLED.");
        }
    }
}