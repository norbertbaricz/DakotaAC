package skypixel.Commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import skypixel.Notification.flagPlayer;
import skypixel.compat.Diagnostics;
import skypixel.config.Lang;
import skypixel.dakotaAC;

public class Status extends SubCommand {
    @Override public String getName() { return "status"; }
    @Override public void execute(CommandSender sender, String[] args) {
        dakotaAC plugin = dakotaAC.getInstance();
        Plugin protocolLib = Bukkit.getPluginManager().getPlugin("ProtocolLib");
        sender.sendMessage(Lang.text("status.header"));
        sender.sendMessage(Lang.text("status.version", "version", plugin.getDescription().getVersion()));
        sender.sendMessage(Lang.text("status.authors", "authors", String.join(", ", plugin.getDescription().getAuthors())));
        sender.sendMessage(Lang.text("status.environment", "server", Bukkit.getBukkitVersion(), "protocollib", protocolLib == null ? "-" : protocolLib.getDescription().getVersion()));
        sender.sendMessage(Lang.text("status.notifications", "state", state(flagPlayer.alertsEnabled)));
        sender.sendMessage(Lang.text("status.auto-kick", "state", state(dakotaAC.isAutoKick())));
        sender.sendMessage(Lang.text("status.auto-ban", "state", state(dakotaAC.isAutoBan())));
        sender.sendMessage(Lang.text("status.kick-limit", "limit", dakotaAC.getMaxKickFlags()));
        sender.sendMessage(Lang.text("status.ban-limit", "limit", dakotaAC.getMaxBanFlags()));
        sender.sendMessage(Lang.text("status.active", "active", dakotaAC.getChecks().values().stream().filter(Boolean::booleanValue).count(), "total", dakotaAC.getChecks().size()));
        if (!plugin.getUnavailableChecks().isEmpty()) sender.sendMessage(Lang.text("status.unavailable", "checks", String.join(", ", plugin.getUnavailableChecks().keySet())));
        sender.sendMessage(Lang.text("status.errors", "errors", Diagnostics.failureCount()));
        sender.sendMessage(Lang.text(plugin.getUnavailableChecks().isEmpty() && Diagnostics.failureCount() == 0 ? "status.healthy" : "status.degraded"));
    }
    private String state(boolean enabled) { return Lang.text(enabled ? "common.on" : "common.off"); }
}
