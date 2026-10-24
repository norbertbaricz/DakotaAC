package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;

public class Flag extends SubCommand {
    @Override public String getName() { return "flag"; }
    @Override public void execute(CommandSender sender, String[] args) {
        if (args.length < 3) { sender.sendMessage(Lang.text("commands.flag-usage")); return; }
        try {
            int kick = Integer.parseInt(args[1]), ban = Integer.parseInt(args[2]);
            if (kick < 1 || ban <= kick) { sender.sendMessage(Lang.text("commands.flag-invalid")); return; }
            dakotaAC.setFlagLimits(kick, ban);
            sender.sendMessage(Lang.text("commands.flag-updated", "kick", kick, "ban", ban));
        } catch (NumberFormatException ex) { sender.sendMessage(Lang.text("commands.flag-number")); }
    }
}
