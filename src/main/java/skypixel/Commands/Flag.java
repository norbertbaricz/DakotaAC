package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import skypixel.dakotaAC;

public class Flag extends SubCommand {

    @Override
    public String getName() {
        return "flag";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Usage: /dakotaac flag <KickLimit> <BanLimit>");
            return;
        }
        try {
            int kickLimit = Integer.parseInt(args[1]);
            int banLimit = Integer.parseInt(args[2]);

            if (kickLimit < 1 || banLimit <= kickLimit) {
                sender.sendMessage(PREFIX + ChatColor.RED + "Invalid! Kick limit must be at least 1, and Ban limit must be strictly higher than Kick limit.");
                return;
            }

            dakotaAC.setFlagLimits(kickLimit, banLimit);
            sender.sendMessage(PREFIX + ChatColor.GREEN + "Punishment thresholds successfully updated!");
            sender.sendMessage(PREFIX + "Kick at: " + ChatColor.YELLOW + kickLimit + " flags");
            sender.sendMessage(PREFIX + "Ban at: " + ChatColor.YELLOW + banLimit + " flags");
        } catch (NumberFormatException e) {
            sender.sendMessage(PREFIX + ChatColor.RED + "Please enter valid numbers.");
        }
    }
}