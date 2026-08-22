package skypixel.Commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import java.util.ArrayList;
import java.util.List;

public abstract class SubCommand {

    // Prefixul este moștenit automat de toate sub-comenzile, nu mai trebuie să îl scrii de 10 ori!
    protected final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

    public abstract String getName();

    public abstract void execute(CommandSender sender, String[] args);

    // Generăm o listă goală by default pentru TabComplete, ca să nu ne repetăm în comenzile simple
    public List<String> getTabCompletions(CommandSender sender, String[] args) {
        return new ArrayList<>();
    }
}