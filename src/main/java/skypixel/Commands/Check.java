package skypixel.Commands;

import org.bukkit.command.CommandSender;
import skypixel.config.Lang;
import skypixel.dakotaAC;
import java.util.List;
import java.util.Locale;

public class Check extends SubCommand {
    @Override public String getName() { return "check"; }
    @Override public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) { sender.sendMessage(Lang.text("commands.check-usage")); return; }
        String module = dakotaAC.getChecks().keySet().stream().filter(key -> key.equalsIgnoreCase(args[1])).findFirst().orElse(null);
        if (module == null) { sender.sendMessage(Lang.text("commands.check-unknown", "module", args[1])); return; }
        dakotaAC.toggleCheck(module);
        sender.sendMessage(Lang.text("commands.check-toggle", "module", module,
                "state", Lang.text(dakotaAC.isCheckActive(module) ? "common.enabled" : "common.disabled")));
    }
    @Override public List<String> getTabCompletions(CommandSender sender, String[] args) {
        if (args.length != 2) return List.of();
        return dakotaAC.getChecks().keySet().stream().filter(key -> key.toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))).toList();
    }
}
