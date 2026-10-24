package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;

public class Reload extends SubCommand {
    @Override public String getName() { return "reload"; }
    @Override public void execute(CommandSender sender, String[] args) {
        try {
            dakotaAC.getInstance().loadConfigSettings();
            sender.sendMessage(Lang.text("commands.reload-success"));
        } catch (IllegalStateException ex) { sender.sendMessage(Lang.text("commands.reload-failed")); }
    }
}
