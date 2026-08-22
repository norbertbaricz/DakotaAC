package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;

public class Reload extends SubCommand {

    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        dakotaAC.getInstance().loadConfigSettings();
        sender.sendMessage(PREFIX + ChatColor.GREEN + "Plugin configuration and modules successfully reloaded!");
    }
}