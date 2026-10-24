package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;
import skypixel.Notification.flagPlayer;

public class Notify extends SubCommand {
    @Override public String getName() { return "notify"; }
    @Override public void execute(CommandSender sender, String[] args) {
        dakotaAC.toggleNotifications();
        sender.sendMessage(Lang.text("commands.notifications-toggle", "state", Lang.text(flagPlayer.alertsEnabled ? "common.enabled" : "common.disabled")));
    }
}
