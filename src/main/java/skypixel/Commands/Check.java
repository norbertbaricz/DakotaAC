package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class Check extends SubCommand {

    @Override
    public String getName() {
        return "check";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /dakotaac check <ModuleName>");
            return;
        }

        String module = getExactModuleName(args[1]);

        if (module == null) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Module '" + args[1] + "' does not exist.");
            return;
        }

        Map<String, Boolean> checks = dakotaAC.getChecks();
        boolean currentState = checks.get(module);
        checks.put(module, !currentState);

        if (!currentState) {
            sender.sendMessage(PREFIX + "Module " + ChatColor.YELLOW + module + ChatColor.GRAY + " is now " + ChatColor.GREEN + "ENABLED");
        } else {
            sender.sendMessage(PREFIX + "Module " + ChatColor.YELLOW + module + ChatColor.GRAY + " is now " + ChatColor.RED + "DISABLED");
        }
    }

    @Override
    public List<String> getTabCompletions(CommandSender sender, String[] args) {
        List<String> completions = new ArrayList<>();
        if (args.length == 2) {
            for (String module : dakotaAC.getChecks().keySet()) {
                if (module.toLowerCase().startsWith(args[1].toLowerCase())) {
                    completions.add(module);
                }
            }
        }
        return completions;
    }

    private String getExactModuleName(String input) {
        for (String key : dakotaAC.getChecks().keySet()) {
            if (key.equalsIgnoreCase(input)) {
                return key;
            }
        }
        return null;
    }
}