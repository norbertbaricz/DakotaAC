package skypixel.Commands;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import skypixel.config.Lang;
import skypixel.dakotaAC;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class Dakota implements CommandExecutor, TabCompleter {
    private final Map<String, SubCommand> subCommands = new LinkedHashMap<>();

    public Dakota() {
        register(new Reload()); register(new Notify()); register(new Status());
        register(new Kick()); register(new Ban()); register(new Flag()); register(new Check());
    }
    private void register(SubCommand command) { subCommands.put(command.getName(), command); }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!dakotaAC.isAdmin(sender)) { sender.sendMessage(Lang.text("commands.no-permission")); return true; }
        if (args.length == 0) {
            sender.sendMessage(Lang.text("commands.help", "commands", String.join("|", subCommands.keySet())));
            return true;
        }
        SubCommand target = subCommands.get(args[0].toLowerCase(Locale.ROOT));
        if (target == null) sender.sendMessage(Lang.text("commands.unknown"));
        else target.execute(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!dakotaAC.isAdmin(sender)) return List.of();
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            for (String name : subCommands.keySet()) if (name.startsWith(args[0].toLowerCase(Locale.ROOT))) result.add(name);
        } else if (args.length > 1) {
            SubCommand target = subCommands.get(args[0].toLowerCase(Locale.ROOT));
            if (target != null) result.addAll(target.getTabCompletions(sender, args));
        }
        return result;
    }
}
