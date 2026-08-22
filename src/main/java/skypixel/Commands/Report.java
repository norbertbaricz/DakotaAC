package skypixel.Commands;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Report implements CommandExecutor, TabCompleter {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT
    // ==========================================
    private final String PREFIX = ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");
    private final int REPORTS_NEEDED_FOR_ANTIBOT = 3;
    private final long REPORT_COOLDOWN_MS = 60000L;
    // ==========================================

    private final ConcurrentHashMap<UUID, Long> cooldowns = new ConcurrentHashMap<>();

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            String helpMsg = ChatColor.RED + "Usage: /report <player>";
            if (sender.hasPermission("dakotaac.admin")) {
                helpMsg += " or /report info";
            }
            sender.sendMessage(helpMsg);
            return true;
        }

        // Dacă argumentul este "info", deschidem cartea cu baza de date (Doar Admini)
        if (args[0].equalsIgnoreCase("info")) {
            return handleInfo(sender);
        }

        // Altfel, tratăm argumentul ca pe numele unui jucător și procesăm report-ul
        return handleReport(sender, args[0]);
    }

    // =========================================================
    // LOGICA PENTRU RAPORTAREA UNUI JUCĂTOR: /report <player>
    // =========================================================
    private boolean handleReport(CommandSender sender, String targetName) {
        Player target = Bukkit.getPlayer(targetName);

        if (target == null) {
            sender.sendMessage(ChatColor.RED + "That player is not online!");
            return true;
        }

        if (sender instanceof Player) {
            Player pSender = (Player) sender;

            if (target.equals(pSender)) {
                pSender.sendMessage(ChatColor.RED + "You cannot report yourself!");
                return true;
            }

            long lastReport = cooldowns.getOrDefault(pSender.getUniqueId(), 0L);
            if (System.currentTimeMillis() - lastReport < REPORT_COOLDOWN_MS) {
                long timeLeft = (REPORT_COOLDOWN_MS - (System.currentTimeMillis() - lastReport)) / 1000;
                pSender.sendMessage(ChatColor.RED + "Please wait " + timeLeft + " seconds before reporting again.");
                return true;
            }
            cooldowns.put(pSender.getUniqueId(), System.currentTimeMillis());
        }

        UUID targetUUID = target.getUniqueId();
        World world = Bukkit.getWorlds().get(0);
        PersistentDataContainer pdc = world.getPersistentDataContainer();
        NamespacedKey key = new NamespacedKey(dakotaAC.getPlugin(dakotaAC.class), targetUUID.toString().toLowerCase());

        int currentReports = pdc.getOrDefault(key, PersistentDataType.INTEGER, 0) + 1;
        pdc.set(key, PersistentDataType.INTEGER, currentReports);

        String reporterName = sender.getName();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("dakotaac.admin")) {
                p.sendMessage(PREFIX + ChatColor.YELLOW + reporterName + ChatColor.GRAY + " has reported "
                        + ChatColor.RED + target.getName() + ChatColor.GRAY + "!");
                p.sendMessage(PREFIX + "Total reports for this player: " + ChatColor.RED + currentReports);
            }
        }

        sender.sendMessage(ChatColor.GREEN + "Your report has been sent to the online staff!");

        if (currentReports > 0 && currentReports % REPORTS_NEEDED_FOR_ANTIBOT == 0) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.hasPermission("dakotaac.admin")) {
                    p.sendMessage(PREFIX + ChatColor.DARK_RED + "⚠ " + ChatColor.RED + target.getName()
                            + " has accumulated " + currentReports + " total reports! Triggering AntiBot module...");
                }
            }
            AntiBot.executeReportCheck(target);
        }

        return true;
    }

    // =========================================================
    // LOGICA PENTRU DESCHIDEREA CĂRȚII: /report info
    // =========================================================
    private boolean handleInfo(CommandSender sender) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(color("&cOnly players can open the report book!"));
            return true;
        }

        Player player = (Player) sender;

        if (!player.hasPermission("dakotaac.admin")) {
            player.sendMessage(color("&cYou do not have permission to view reports."));
            return true;
        }

        World world = Bukkit.getWorlds().get(0);
        PersistentDataContainer pdc = world.getPersistentDataContainer();

        ItemStack book = new ItemStack(Material.WRITTEN_BOOK);
        BookMeta meta = (BookMeta) book.getItemMeta();
        meta.setTitle(color("&cReport Database"));
        meta.setAuthor("DakotaAC");

        StringBuilder page = new StringBuilder();
        addHeader(page);

        int playersOnPage = 0;
        boolean hasReports = false;

        for (NamespacedKey key : pdc.getKeys()) {
            if (key.getNamespace().equals("dakotaac")) {
                hasReports = true;

                UUID targetUUID = UUID.fromString(key.getKey());
                OfflinePlayer target = Bukkit.getOfflinePlayer(targetUUID);
                String name = target.getName() != null ? target.getName() : "Unknown";
                int count = pdc.get(key, PersistentDataType.INTEGER);

                page.append(color("&8» &0&l" + name + "\n"));
                page.append(color("   &8➥ &7Reports: &c" + count + "\n\n"));

                playersOnPage++;

                if (playersOnPage == 3) {
                    meta.addPage(page.toString());
                    page = new StringBuilder();
                    addHeader(page);
                    playersOnPage = 0;
                }
            }
        }

        if (!hasReports) {
            player.sendMessage(color("&8[&cDakotaAC&8] &aThere are currently no reports in the database."));
            return true;
        }

        if (playersOnPage > 0) {
            meta.addPage(page.toString());
        }

        book.setItemMeta(meta);
        player.openBook(book);

        return true;
    }

    private void addHeader(StringBuilder page) {
        page.append(color("&0&lDAKOTA AC\n"));
        page.append(color("&8Report Database\n"));
        page.append(color("&8&m===================\n\n"));
    }

    private String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    // =========================================================
    // AUTO-COMPLETARE PENTRU TASTA TAB (TabCompleter)
    // =========================================================
    @Override
    public List<String> onTabComplete(CommandSender sender, Command cmd, String label, String[] args) {
        List<String> completions = new ArrayList<>();

        if (args.length == 1) {
            // Dacă e admin, îi sugerăm și "info"
            if (sender.hasPermission("dakotaac.admin") && "info".startsWith(args[0].toLowerCase())) {
                completions.add("info");
            }
            // Sugerăm numele jucătorilor online
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase().startsWith(args[0].toLowerCase())) {
                    completions.add(p.getName());
                }
            }
        }
        return completions;
    }
}