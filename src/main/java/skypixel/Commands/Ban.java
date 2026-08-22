package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;

public class Ban extends SubCommand {

    @Override
    public String getName() {
        return "ban";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleAutoBan();
        if (dakotaAC.isAutoBan()) {
            sender.sendMessage(PREFIX + "Auto-Ban is now " + ChatColor.GREEN + "ENABLED");
        } else {
            sender.sendMessage(PREFIX + "Auto-Ban is now " + ChatColor.RED + "DISABLED");
        }
    }
}