package ro.skypixel.play.dakotaAC;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class Commands implements CommandExecutor, TabCompleter {
    private final DakotaAC plugin;

    // Constants for messages and permissions
    private static final String PREFIX = "§4§lDakotaAC §8» §7";
    private static final String ADMIN_PERMISSION = "dakotaac.admin";
    private static final String NO_PERMISSION_MESSAGE = PREFIX + "§cYou don't have permission to use this command!";
    private static final String UNKNOWN_COMMAND_MESSAGE = PREFIX + "§cUnknown command. Use §e/dakotaac help §cfor a list of commands.";

    // Constants for sub-commands to improve maintainability and reduce typos
    private static final String CMD_HELP = "help";
    private static final String CMD_VERSION = "version";
    private static final String CMD_STATUS = "status";
    private static final String CMD_RELOAD = "reload";

    // Base list of commands for tab completion
    private static final List<String> BASE_SUBCOMMANDS = Collections.unmodifiableList(Arrays.asList(
            CMD_HELP, CMD_VERSION, CMD_STATUS
    ));

    public Commands(DakotaAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // Ensure the command being handled is "dakotaac"
        if (!command.getName().equalsIgnoreCase("dakotaac")) {
            return false; // Should not happen if command is registered correctly
        }

        // Default to help if no arguments are provided
        if (args.length == 0 || args[0].equalsIgnoreCase(CMD_HELP)) {
            sendHelpMessage(sender);
            return true;
        }

        // Handle sub-commands
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case CMD_VERSION:
                sendVersionMessage(sender);
                return true;
            case CMD_STATUS:
                sendStatusMessage(sender);
                return true;
            case CMD_RELOAD:
                if (sender.hasPermission(ADMIN_PERMISSION)) {
                    reloadPluginConfiguration(sender);
                } else {
                    sender.sendMessage(NO_PERMISSION_MESSAGE);
                }
                return true;
            default:
                sender.sendMessage(UNKNOWN_COMMAND_MESSAGE);
                return true;
        }
    }

    private void sendHelpMessage(CommandSender sender) {
        sender.sendMessage("§8§m------------------------");
        sender.sendMessage("§4§lDakotaAC §7- Anti-Cheat Commands");
        sender.sendMessage("§8§m------------------------");
        sender.sendMessage("§e/dakotaac " + CMD_HELP + " §7- Shows this help menu");
        sender.sendMessage("§e/dakotaac " + CMD_VERSION + " §7- Displays plugin version and credits");
        sender.sendMessage("§e/dakotaac " + CMD_STATUS + " §7- Shows the anti-cheat status");
        if (sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage("§e/dakotaac " + CMD_RELOAD + " §7- Reloads the plugin configuration");
        }
        sender.sendMessage("§8§m------------------------");
    }

    private void sendVersionMessage(CommandSender sender) {
        String version = plugin.getDescription().getVersion();
        // Consider fetching author from plugin.yml if it's defined there for consistency
        String creator = plugin.getDescription().getAuthors().isEmpty() ? "MaxUltimat3" : String.join(", ", plugin.getDescription().getAuthors());

        sender.sendMessage("§8§m------------------------");
        sender.sendMessage("§4§lDakotaAC §7- Version Info");
        sender.sendMessage("§8§m------------------------");
        sender.sendMessage(PREFIX + "Version: §e" + version);
        sender.sendMessage(PREFIX + "Created by: §e" + creator);
        sender.sendMessage(PREFIX + "Protecting your server with advanced anti-cheat technology!");
        sender.sendMessage("§8§m------------------------");
    }

    private void sendStatusMessage(CommandSender sender) {
        boolean isEnabled = plugin.isEnabled(); // Check if the plugin itself is enabled
        // Additional status information could be fetched from the plugin instance if available
        // For example: int activeChecks = plugin.getActiveCheckCount();

        sender.sendMessage("§8§m------------------------");
        sender.sendMessage("§4§lDakotaAC §7- Status");
        sender.sendMessage("§8§m------------------------");
        sender.sendMessage(PREFIX + "Anti-Cheat: " + (isEnabled ? "§aEnabled" : "§cDisabled"));
        sender.sendMessage(PREFIX + "Server Protection: §e" + (isEnabled ? "Active" : "Inactive"));
        // This is a subjective statement, consider if it's always true or if it can be measured
        if (sender instanceof Player) {
            sender.sendMessage(PREFIX + "Lag Impact: §eLow (Optimized for performance)");
        }
        // Potentially add more specific status details here
        sender.sendMessage("§8§m------------------------");
    }

    private void reloadPluginConfiguration(CommandSender sender) {
        try {
            // Bukkit's reloadConfig() only reloads the main config.yml.
            // If your plugin has other configs or needs to re-initialize components,
            // you should implement a more comprehensive reload logic within the DakotaAC class
            // and call that method here. e.g., plugin.performFullReload();
            plugin.reloadConfig();
            // Example: if you have a custom reload method in DakotaAC:
            // plugin.customReloadLogic();
            sender.sendMessage(PREFIX + "§aConfiguration reloaded successfully!");
        } catch (Exception e) {
            sender.sendMessage(PREFIX + "§cFailed to reload configuration: " + e.getMessage());
            // It's good practice to log the full error to the console for debugging
            plugin.getLogger().severe("Error reloading configuration: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        // Provide suggestions only for the "dakotaac" command and only for the first argument
        if (!command.getName().equalsIgnoreCase("dakotaac")) {
            return Collections.emptyList();
        }

        if (args.length == 1) {
            List<String> suggestions = new ArrayList<>(BASE_SUBCOMMANDS);
            if (sender.hasPermission(ADMIN_PERMISSION)) {
                suggestions.add(CMD_RELOAD);
            }

            String currentArg = args[0].toLowerCase();
            // Filter suggestions based on what the user has already typed
            return suggestions.stream()
                    .filter(s -> s.toLowerCase().startsWith(currentArg))
                    .collect(Collectors.toList());
        }

        // No suggestions for arguments beyond the first one for these commands
        return Collections.emptyList();
    }
}
