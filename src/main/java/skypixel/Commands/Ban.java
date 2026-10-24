package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;

public class Ban extends SubCommand {
    @Override public String getName() { return "ban"; }
    @Override public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleAutoBan();
        sender.sendMessage(Lang.text("commands.auto-ban-toggle", "state", Lang.text(dakotaAC.isAutoBan() ? "common.enabled" : "common.disabled")));
    }
}
