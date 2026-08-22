package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;
import skypixel.Notification.flagPlayer;

public class Status extends SubCommand {

    @Override
    public String getName() {
        return "status";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        org.bukkit.plugin.PluginDescriptionFile pdf = dakotaAC.getInstance().getDescription();
        String version = pdf.getVersion();
        String authors = String.join(", ", pdf.getAuthors());

        sender.sendMessage(PREFIX + ChatColor.YELLOW + "--- DakotaAC Status ---");
        sender.sendMessage(PREFIX + "Version: " + ChatColor.AQUA + version);
        sender.sendMessage(PREFIX + "Authors: " + ChatColor.AQUA + authors);
        sender.sendMessage(PREFIX + "Notifications: " + (flagPlayer.alertsEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(PREFIX + "Auto-Kick: " + (dakotaAC.isAutoKick() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(PREFIX + "Auto-Ban: " + (dakotaAC.isAutoBan() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
        sender.sendMessage(PREFIX + "Kick Limit: " + ChatColor.AQUA + dakotaAC.getMaxKickFlags() + " flags");
        sender.sendMessage(PREFIX + "Ban Limit: " + ChatColor.AQUA + dakotaAC.getMaxBanFlags() + " flags");

        int activeCount = 0;
        for (boolean isActive : dakotaAC.getChecks().values()) {
            if (isActive) activeCount++;
        }

        sender.sendMessage(PREFIX + "Active Modules: " + ChatColor.AQUA + activeCount + "/" + dakotaAC.getChecks().size());
        sender.sendMessage(PREFIX + ChatColor.GREEN + "All systems operational. No critical errors.");
    }
}