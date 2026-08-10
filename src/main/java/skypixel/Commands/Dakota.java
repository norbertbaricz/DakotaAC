package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import skypixel.dakotaAC;
import skypixel.Notification.flagPlayer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Dakota implements CommandExecutor, TabCompleter {

    private final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!sender.hasPermission("dakotaac.admin")) {
            sender.sendMessage(PREFIX + ChatColor.RED + "You do not have permission to use this command.");
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(PREFIX + "Available commands: &c/dakotaac <reload|check|status|notify|kick|ban|flag>");
            return true;
        }

        String subCommand = args[0].toLowerCase();

        switch (subCommand) {
            case "reload":
                dakotaAC.getInstance().loadConfigSettings();
                sender.sendMessage(PREFIX + ChatColor.GREEN + "Plugin configuration and modules successfully reloaded!");
                break;

            case "notify":
                dakotaAC.toggleNotifications();
                if (flagPlayer.alertsEnabled) {
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "AntiCheat notifications are now ENABLED.");
                } else {
                    sender.sendMessage(PREFIX + ChatColor.RED + "AntiCheat notifications are now DISABLED.");
                }
                break;

            case "status":
                org.bukkit.plugin.PluginDescriptionFile pdf = dakotaAC.getInstance().getDescription();
                String version = pdf.getVersion();
                String authors = String.join(", ", pdf.getAuthors());

                sender.sendMessage(PREFIX + ChatColor.YELLOW + "--- DakotaAC Status ---");
                sender.sendMessage(PREFIX + "Version: " + ChatColor.AQUA + version);
                sender.sendMessage(PREFIX + "Authors: " + ChatColor.AQUA + authors);
                sender.sendMessage(PREFIX + "Notifications: " + (flagPlayer.alertsEnabled ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
                sender.sendMessage(PREFIX + "Auto-Kick: " + (dakotaAC.isAutoKick() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
                sender.sendMessage(PREFIX + "Auto-Ban: " + (dakotaAC.isAutoBan() ? ChatColor.GREEN + "ON" : ChatColor.RED + "OFF"));
                sender.sendMessage(PREFIX + "Kick Limit: " + ChatColor.AQUA + dakotaAC.getMaxKickFlags() + " flags");
                sender.sendMessage(PREFIX + "Ban Limit: " + ChatColor.AQUA + dakotaAC.getMaxBanFlags() + " flags");

                int activeCount = 0;
                for (boolean isActive : dakotaAC.getChecks().values()) {
                    if (isActive) activeCount++;
                }

                sender.sendMessage(PREFIX + "Active Modules: " + ChatColor.AQUA + activeCount + "/" + dakotaAC.getChecks().size());
                sender.sendMessage(PREFIX + ChatColor.GREEN + "All systems operational. No critical errors.");
                break;

            case "kick":
                dakotaAC.toggleAutoKick();
                if (dakotaAC.isAutoKick()) {
                    sender.sendMessage(PREFIX + "Auto-Kick is now " + ChatColor.GREEN + "ENABLED");
                } else {
                    sender.sendMessage(PREFIX + "Auto-Kick is now " + ChatColor.RED + "DISABLED");
                }
                break;

            case "ban":
                dakotaAC.toggleAutoBan();
                if (dakotaAC.isAutoBan()) {
                    sender.sendMessage(PREFIX + "Auto-Ban is now " + ChatColor.GREEN + "ENABLED");
                } else {
                    sender.sendMessage(PREFIX + "Auto-Ban is now " + ChatColor.RED + "DISABLED");
                }
                break;

            case "flag":
                if (args.length < 3) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /dakotaac flag <KickLimit> <BanLimit>");
                    return true;
                }
                try {
                    int kickLimit = Integer.parseInt(args[1]);
                    int banLimit = Integer.parseInt(args[2]);

                    if (kickLimit < 1 || banLimit <= kickLimit) {
                        sender.sendMessage(PREFIX + ChatColor.RED + "Invalid! Kick limit must be at least 1, and Ban limit must be strictly higher than Kick limit.");
                        return true;
                    }

                    dakotaAC.setFlagLimits(kickLimit, banLimit);
                    sender.sendMessage(PREFIX + ChatColor.GREEN + "Punishment thresholds successfully updated!");
                    sender.sendMessage(PREFIX + "Kick at: " + ChatColor.YELLOW + kickLimit + " flags");
                    sender.sendMessage(PREFIX + "Ban at: " + ChatColor.YELLOW + banLimit + " flags");
                } catch (NumberFormatException e) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Please enter valid numbers.");
                }
                break;

            case "check":
                if (args.length < 2) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /dakotaac check <ModuleName>");
                    return true;
                }

                String module = getExactModuleName(args[1]);

                if (module == null) {
                    sender.sendMessage(PREFIX + ChatColor.RED + "Module '" + args[1] + "' does not exist.");
                    return true;
                }

                Map<String, Boolean> checks = dakotaAC.getChecks();
                boolean currentState = checks.get(module);
                checks.put(module, !currentState);

                if (!currentState) {
                    sender.sendMessage(PREFIX + "Module " + ChatColor.YELLOW + module + ChatColor.GRAY + " is now " + ChatColor.GREEN + "ENABLED");
                } else {
                    sender.sendMessage(PREFIX + "Module " + ChatColor.YELLOW + module + ChatColor.GRAY + " is now " + ChatColor.RED + "DISABLED");
                }
                break;

            default:
                sender.sendMessage(PREFIX + ChatColor.RED + "Unknown command.");
                break;
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            List<String> subCommands = Arrays.asList("reload", "check", "status", "notify", "kick", "ban", "flag");
            for (String s : subCommands) {
                if (s.toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(s);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
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