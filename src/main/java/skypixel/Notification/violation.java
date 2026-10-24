package skypixel.Notification;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.config.Lang;
import skypixel.dakotaAC;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Violation implements Listener {
    // Preserve session-long totals across disconnects and configuration reloads.
    private static final ConcurrentHashMap<UUID, Long> totalFlags = new ConcurrentHashMap<>();

    public static void addViolation(Player player, String check, String hackType, String details) {
        dakotaAC plugin = dakotaAC.getInstance();
        int weight = Math.max(1, Math.min(1000000, plugin.getConfig().getInt("Modules." + check + ".flag-weight", 1)));
        long before = totalFlags.getOrDefault(player.getUniqueId(), 0L);
        long flags = before > Long.MAX_VALUE - weight ? Long.MAX_VALUE : before + weight;
        totalFlags.put(player.getUniqueId(), flags);
        if (!plugin.getConfig().getBoolean("Modules." + check + ".punish", true)) return;
        // With weight 1 this is exactly the original multiple-based behavior. Larger weights cannot skip a boundary.
        if (dakotaAC.isAutoBan() && crossed(before, flags, dakotaAC.getMaxBanFlags())) {
            executePunishment(player, hackType, details, true); return;
        }
        if (dakotaAC.isAutoKick() && crossed(before, flags, dakotaAC.getMaxKickFlags())) executePunishment(player, hackType, details, false);
    }

    public static boolean crossed(long before, long after, int interval) {
        return interval > 0 && after / interval > before / interval;
    }

    public static long getFlags(UUID uuid) { return totalFlags.getOrDefault(uuid, 0L); }

    private static void executePunishment(Player player, String hackType, String details, boolean ban) {
        dakotaAC plugin = dakotaAC.getInstance();
        Object[] placeholders = {"player", player.getName(), "hack", hackType, "details", details};
        if (ban) {
            String command = Lang.format(plugin.getConfig().getString("Punishments.ban-command", ""),
                    java.util.Map.of("player", player.getName(), "hack", hackType, "details", details));
            if (!command.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command);
        }
        player.kickPlayer(Lang.text("punishments.reason", placeholders));
        if (plugin.getConfig().getBoolean(ban ? "Punishments.broadcast-bans" : "Punishments.broadcast-kicks", true)) {
            Bukkit.broadcastMessage(Lang.text(ban ? "punishments.ban-broadcast" : "punishments.kick-broadcast", placeholders));
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) { flagPlayer.clearCache(event.getPlayer().getUniqueId()); }
}
