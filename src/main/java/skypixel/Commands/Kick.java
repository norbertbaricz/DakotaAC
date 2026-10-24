package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;

public class Kick extends SubCommand {
    @Override public String getName() { return "kick"; }
    @Override public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleAutoKick();
        sender.sendMessage(Lang.text("commands.auto-kick-toggle", "state", Lang.text(dakotaAC.isAutoKick() ? "common.enabled" : "common.disabled")));
    }
}
