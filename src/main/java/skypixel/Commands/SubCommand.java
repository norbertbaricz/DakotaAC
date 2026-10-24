package skypixel.Commands;

import org.bukkit.command.CommandSender;
import java.util.List;

public abstract class SubCommand {
    public abstract String getName();
    public abstract void execute(CommandSender sender, String[] args);
    public List<String> getTabCompletions(CommandSender sender, String[] args) { return List.of(); }
}
