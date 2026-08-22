package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Dakota implements CommandExecutor, TabCompleter {

    private final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

    // Aici stocăm toate sub-comenzile
    private final Map<String, SubCommand> subCommands = new HashMap<>();

    public Dakota() {
        // Înregistrăm fiecare fișier separat aici, folosind denumirile simple
        register(new Reload());
        register(new Notify());
        register(new Status());
        register(new Kick());
        register(new Ban());
        register(new Flag());
        register(new Check());
    }

    private void register(SubCommand cmd) {
        subCommands.put(cmd.getName().toLowerCase(), cmd);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("dakotaac.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(PREFIX + "Available commands: " + ChatColor.RED + "/dakotaac <" + String.join("|", subCommands.keySet()) + ">");
            return true;
        }

        SubCommand target = subCommands.get(args[0].toLowerCase());

        if (target == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Unknown command.");
            return true;
        }

        // Executăm logica din fișierul corespunzător
        target.execute(sender, args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            for (String subCmd : subCommands.keySet()) {
                if (subCmd.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(subCmd);
                }
            }
        } else if (args.length > 1) {
            SubCommand target = subCommands.get(args[0].toLowerCase());
            if (target != null) {
                completions = target.getTabCompletions(sender, args);
            }
        }

        return completions;
    }
}