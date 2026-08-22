package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;

public class Kick extends SubCommand {

    @Override
    public String getName() {
        return "kick";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleAutoKick();
        if (dakotaAC.isAutoKick()) {
            sender.sendMessage(PREFIX + "Auto-Kick is now " + ChatColor.GREEN + "ENABLED");
        } else {
            sender.sendMessage(PREFIX + "Auto-Kick is now " + ChatColor.RED + "DISABLED");
        }
    }
}