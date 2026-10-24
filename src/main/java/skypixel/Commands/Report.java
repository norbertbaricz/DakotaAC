package skypixel.Commands;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import skypixel.Misc.AntiBot;
import skypixel.config.Lang;
import skypixel.config.Option;
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Report implements CommandExecutor, TabCompleter {
    // Preserve the report database in the first world's persistent data container.
    @Option(value = "reports-needed-for-antibot", min = 1, max = 1000000)
    private static volatile int REPORTS_NEEDED_FOR_ANTIBOT = 3;
    @Option(value = "report-cooldown-ms", min = 0, max = 86400000)
    private static volatile long REPORT_COOLDOWN_MS = 60000L;
    @Option(value = "players-per-book-page", min = 1, max = 5)
    private static volatile int PLAYERS_PER_BOOK_PAGE = 3;
    @Option(value = "max-book-pages", min = 1, max = 100)
    private static volatile int MAX_BOOK_PAGES = 100;
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(Lang.text(dakotaAC.isAdmin(sender) ? "reports.admin-usage" : "reports.usage"));
            return true;
        }
        return args[0].equalsIgnoreCase("info") ? handleInfo(sender) : handleReport(sender, args[0]);
    }

    private boolean handleReport(CommandSender sender, String targetName) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) { sender.sendMessage(Lang.text("reports.not-online")); return true; }
        long now = System.currentTimeMillis();
        cooldowns.entrySet().removeIf(entry -> now - entry.getValue() >= REPORT_COOLDOWN_MS || Bukkit.getPlayer(entry.getKey()) == null);
        if (sender instanceof Player player) {
            if (target.equals(player)) { player.sendMessage(Lang.text("reports.self")); return true; }
            long remaining = REPORT_COOLDOWN_MS - (now - cooldowns.getOrDefault(player.getUniqueId(), 0L));
            if (remaining > 0) {
                player.sendMessage(Lang.text("reports.cooldown", "seconds", (remaining + 999L) / 1000L));
                return true;
            }
            cooldowns.put(player.getUniqueId(), now);
        }
        World world = Bukkit.getWorlds().get(0);
        PersistentDataContainer database = world.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(dakotaAC.getInstance(), target.getUniqueId().toString());
        int before = database.getOrDefault(key, PersistentDataType.INTEGER, 0);
        int count = before == Integer.MAX_VALUE ? before : before + 1;
        database.set(key, PersistentDataType.INTEGER, count);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!dakotaAC.isAdmin(staff)) continue;
            staff.sendMessage(Lang.text("reports.staff", "reporter", sender.getName(), "player", target.getName()));
            staff.sendMessage(Lang.text("reports.total", "count", count));
        }
        sender.sendMessage(Lang.text("reports.sent"));
        if (count > 0 && count % REPORTS_NEEDED_FOR_ANTIBOT == 0 && dakotaAC.isCheckActive("AntiBot")) {
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (dakotaAC.isAdmin(staff)) staff.sendMessage(Lang.text("reports.antibot", "player", target.getName(), "count", count));
            }
            AntiBot.executeReportCheck(target);
        }
        return true;
    }

    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player player)) { sender.sendMessage(Lang.text("reports.only-player")); return true; }
        if (!dakotaAC.isAdmin(player)) { player.sendMessage(Lang.text("reports.no-permission")); return true; }
        PersistentDataContainer database = Bukkit.getWorlds().get(0).getPersistentDataContainer();
        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        if (meta == null) return true;
        meta.setTitle(Lang.text("reports.book-title"));
        meta.setAuthor(Lang.text("reports.book-author"));
        StringBuilder page = new StringBuilder(Lang.text("reports.book-header"));
        int entries = 0;
        boolean found = false;
        // Sorting gives stable pages while ignoring unrelated plugin data in the same namespace.
        List<NamespacedKey> keys = database.getKeys().stream().sorted(java.util.Comparator.comparing(NamespacedKey::toString)).toList();
        for (NamespacedKey key : keys) {
            if (!key.getNamespace().equals("dakotaac") || !database.has(key, PersistentDataType.INTEGER)) continue;
            UUID uuid;
            try { uuid = UUID.fromString(key.getKey()); } catch (IllegalArgumentException ignored) { continue; }
            Integer count = database.get(key, PersistentDataType.INTEGER);
            if (count == null || count < 1) continue;
            OfflinePlayer target = Bukkit.getOfflinePlayer(uuid);
            String name = target.getName() == null ? Lang.text("reports.unknown-player") : target.getName();
            if (meta.getPageCount() >= MAX_BOOK_PAGES) break;
            page.append(Lang.text("reports.book-entry", "name", name, "count", count));
            found = true;
            if (++entries == PLAYERS_PER_BOOK_PAGE) {
                meta.addPage(page.toString());
                page = new StringBuilder(Lang.text("reports.book-header"));
                entries = 0;
            }
        }
        if (!found) { player.sendMessage(Lang.text("reports.empty")); return true; }
        if (entries > 0 && meta.getPageCount() < MAX_BOOK_PAGES) meta.addPage(page.toString());
        book.setItemMeta(meta);
        player.openBook(book);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length != 1) return List.of();
        List<String> suggestions = new ArrayList<>();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        if (dakotaAC.isAdmin(sender) && "info".startsWith(prefix)) suggestions.add("info");
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) suggestions.add(player.getName());
        }
        return suggestions;
    }
}
