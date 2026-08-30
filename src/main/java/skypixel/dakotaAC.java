package skypixel;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import com.comphenix.protocol.ProtocolLibrary;
import skypixel.Combat.*;
import skypixel.Exploit.*;
import skypixel.Fun.*;
import skypixel.Misc.*;
import skypixel.Movement.*;
import skypixel.Notification.*;
import skypixel.Commands.*;
import skypixel.Player.*;
import skypixel.Render.*;
import skypixel.World.*;

import java.util.HashMap;
import java.util.Map;

public final class dakotaAC extends JavaPlugin {

    private static dakotaAC instance;
    private static final Map<String, Boolean> activeChecks = new HashMap<>();

    private static boolean autoKick;
    private static boolean autoBan;
    private static int maxKickFlags;
    private static int maxBanFlags;

    @Override
    public void onEnable() {
        instance = this;

        org.bukkit.command.ConsoleCommandSender console = getServer().getConsoleSender();
        String version = getDescription().getVersion();

        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");
        console.sendMessage(org.bukkit.ChatColor.RED + "" + org.bukkit.ChatColor.BOLD + " DakotaAC " + org.bukkit.ChatColor.GRAY + "v" + version);

        try {
            // DEPENDENCY CHECK
            if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
                console.sendMessage(org.bukkit.ChatColor.DARK_RED + " [CRITICAL ERROR] ProtocolLib is missing!");
                console.sendMessage(org.bukkit.ChatColor.RED + " DakotaAC requires ProtocolLib to intercept network packets.");
                console.sendMessage(org.bukkit.ChatColor.RED + " Please install ProtocolLib. The Anti-Cheat will now disable itself.");
                console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

                notifyStaff(org.bukkit.ChatColor.DARK_RED + "CRITICAL ERROR: ProtocolLib is missing! Plugin disabled. Contact Developer!", true);

                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            console.sendMessage(org.bukkit.ChatColor.YELLOW + " Initializing high-performance security modules...");

            saveDefaultConfig();
            loadConfigSettings();
            registerListeners();

            getCommand("report").setExecutor(new Report());
            getCommand("report").setTabCompleter(new Report());

            getCommand("dakotaac").setExecutor(new Dakota());
            getCommand("dakotaac").setTabCompleter(new Dakota());

            console.sendMessage(org.bukkit.ChatColor.GREEN + " [System] All modules loaded successfully. Zero errors.");
            console.sendMessage(org.bukkit.ChatColor.GREEN + " [System] Server is now fully protected!");
            console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

            // Mesaj direct către Staff în joc, cu sunet de SUCCES
            notifyStaff(org.bukkit.ChatColor.GREEN + "System started successfully! Zero errors detected. Server is protected.", false);

        } catch (Exception e) {
            console.sendMessage(org.bukkit.ChatColor.DARK_RED + " [CRITICAL ERROR] A fatal error occurred during startup!");
            e.printStackTrace();
            console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

            // Mesaj direct către Staff în joc, cu sunet de EROARE
            notifyStaff(org.bukkit.ChatColor.DARK_RED + "CRITICAL ERROR during startup! Modules might be offline. Check console and contact Developer immediately!", true);
        }
    }

    @Override
    public void onDisable() {
        org.bukkit.command.ConsoleCommandSender console = getServer().getConsoleSender();

        // Curățare memorie preventivă
        skypixel.Render.Tracers.cleanupAllDecoys();
        skypixel.Render.ESP.cleanupAll();
        skypixel.Misc.AntiBot.cleanupAllBots();
        skypixel.Combat.AutoArmor.cleanupAllDecoys();

        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            ProtocolLibrary.getProtocolManager().removePacketListeners(this);
        }

        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");
        console.sendMessage(org.bukkit.ChatColor.RED + "" + org.bukkit.ChatColor.BOLD + " DakotaAC " + org.bukkit.ChatColor.GRAY + "is shutting down.");
        console.sendMessage(org.bukkit.ChatColor.YELLOW + " [System] Security modules are now OFFLINE.");
        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

        // Alerta la oprire / reload
        notifyStaff(org.bukkit.ChatColor.RED + "System shutting down... Server is exposed!", true);
    }

    /**
     * SISTEMUL "GLONȚ DE ARGINT" PENTRU NOTIFICĂRI
     * Trimite mesajul direct oricărui jucător care are OP sau permisiunea de admin.
     */
    public void notifyStaff(String message, boolean isError) {
        String prefix = org.bukkit.ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] ");

        for (Player p : getServer().getOnlinePlayers()) {
            if (p.hasPermission("dakotaac.admin") || p.isOp()) {
                p.sendMessage(prefix + message);

                try {
                    if (isError) {
                        // Sunet de eroare (Bass grav)
                        p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                    } else {
                        // Sunet de succes (XP Orb)
                        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    }
                } catch (Exception ignored) {
                    // Ignorăm erorile de sunet în caz că serverul folosește o versiune prea veche de MC
                }
            }
        }
    }

    public void loadConfigSettings() {
        reloadConfig();

        autoKick = getConfig().getBoolean("Settings.Auto-Kick", false);
        autoBan = getConfig().getBoolean("Settings.Auto-Ban", false);
        maxKickFlags = getConfig().getInt("Settings.Max-Kick-Flags", 15);
        maxBanFlags = getConfig().getInt("Settings.Max-Ban-Flags", 30);
        flagPlayer.alertsEnabled = getConfig().getBoolean("Settings.Notifications", true);

        initializeChecks();
    }

    private void initializeChecks() {
        loadModuleState("AimBot");
        loadModuleState("AutoArmor");
        loadModuleState("AutoClicker");
        loadModuleState("Criticals");
        loadModuleState("Hitbox");
        loadModuleState("KillAura");
        loadModuleState("Reach");
        loadModuleState("Velocity");
        loadModuleState("GhostHand");
        loadModuleState("GodMode");
        loadModuleState("Phase");
        loadModuleState("PingSpoof");
        loadModuleState("Plugins");
        loadModuleState("ServerCrasher");
        loadModuleState("Blink");
        loadModuleState("Regen");
        loadModuleState("Derp");
        loadModuleState("AntiBot");
        loadModuleState("Spammer");
        loadModuleState("Fly");
        loadModuleState("HighJump");
        loadModuleState("InventoryMove");
        loadModuleState("Jesus");
        loadModuleState("NoFall");
        loadModuleState("NoSlowDown");
        loadModuleState("Sneak");
        loadModuleState("Speed");
        loadModuleState("Sprint");
        loadModuleState("Step");
        loadModuleState("AntiVoid");
        loadModuleState("ChestStealer");
        loadModuleState("FastUse");
        loadModuleState("InventoryCleaner");
        loadModuleState("Version");
        loadModuleState("AntiVPN");
        loadModuleState("ESP");
        loadModuleState("Tracers");
        loadModuleState("XRay");
        loadModuleState("FastBreak");
        loadModuleState("FastPlace");
        loadModuleState("Fucker");
        loadModuleState("Nuker");
        loadModuleState("Scaffold");
    }

    private void loadModuleState(String moduleName) {
        String path = "Modules." + moduleName + ".enabled";
        boolean isActive = getConfig().getBoolean(path, true);
        activeChecks.put(moduleName, isActive);
    }

    private void registerListeners() {
        org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();

        pm.registerEvents(new AimBot(), this);
        pm.registerEvents(new AutoArmor(), this);
        pm.registerEvents(new AutoClicker(), this);
        pm.registerEvents(new Criticals(), this);
        pm.registerEvents(new Hitbox(), this);
        pm.registerEvents(new KillAura(), this);
        pm.registerEvents(new Reach(), this);
        pm.registerEvents(new Velocity(), this);
        pm.registerEvents(new GhostHand(), this);
        pm.registerEvents(new GodMode(), this);
        pm.registerEvents(new Phase(), this);
        pm.registerEvents(new PingSpoof(), this);
        pm.registerEvents(new Plugins(), this);
        pm.registerEvents(new ServerCrasher(), this);
        pm.registerEvents(new Blink(), this);
        pm.registerEvents(new Regen(), this);
        pm.registerEvents(new Derp(), this);
        pm.registerEvents(new Spammer(), this);
        pm.registerEvents(new AntiBot(), this);
        pm.registerEvents(new Fly(), this);
        pm.registerEvents(new HighJump(), this);
        pm.registerEvents(new InventoryMove(), this);
        pm.registerEvents(new Jesus(), this);
        pm.registerEvents(new NoFall(), this);
        pm.registerEvents(new NoSlowDown(), this);
        pm.registerEvents(new Sneak(), this);
        pm.registerEvents(new Speed(), this);
        pm.registerEvents(new Sprint(), this);
        pm.registerEvents(new Step(), this);
        pm.registerEvents(new AntiVoid(), this);
        pm.registerEvents(new ChestStealer(), this);
        pm.registerEvents(new FastUse(), this);
        pm.registerEvents(new InventoryCleaner(), this);
        pm.registerEvents(new Version(), this);
        pm.registerEvents(new AntiVPN(), this);
        pm.registerEvents(new ESP(), this);
        pm.registerEvents(new Tracers(), this);
        pm.registerEvents(new XRay(), this);
        pm.registerEvents(new FastBreak(), this);
        pm.registerEvents(new FastPlace(), this);
        pm.registerEvents(new Fucker(), this);
        pm.registerEvents(new Nuker(), this);
        pm.registerEvents(new Scaffold(), this);
        pm.registerEvents(new Violation(), this);
    }

    public static dakotaAC getInstance() { return instance; }
    public static Map<String, Boolean> getChecks() { return activeChecks; }
    public static boolean isCheckActive(String checkName) { return activeChecks.getOrDefault(checkName, false); }

    public static void toggleCheck(String moduleName) {
        if (activeChecks.containsKey(moduleName)) {
            boolean newState = !activeChecks.get(moduleName);
            activeChecks.put(moduleName, newState);
            instance.getConfig().set("Modules." + moduleName + ".enabled", newState);
            instance.saveConfig();
        }
    }

    public static boolean isAutoKick() { return autoKick; }
    public static void toggleAutoKick() {
        autoKick = !autoKick;
        instance.getConfig().set("Settings.Auto-Kick", autoKick);
        instance.saveConfig();
    }

    public static boolean isAutoBan() { return autoBan; }
    public static void toggleAutoBan() {
        autoBan = !autoBan;
        instance.getConfig().set("Settings.Auto-Ban", autoBan);
        instance.saveConfig();
    }

    public static int getMaxKickFlags() { return maxKickFlags; }
    public static int getMaxBanFlags() { return maxBanFlags; }

    public static void setFlagLimits(int kick, int ban) {
        maxKickFlags = kick;
        maxBanFlags = ban;
        instance.getConfig().set("Settings.Max-Kick-Flags", maxKickFlags);
        instance.getConfig().set("Settings.Max-Ban-Flags", maxBanFlags);
        instance.getConfig().set("Settings.Max-Flags", null);
        instance.saveConfig();
    }

    public static void toggleNotifications() {
        flagPlayer.alertsEnabled = !flagPlayer.alertsEnabled;
        instance.getConfig().set("Settings.Notifications", flagPlayer.alertsEnabled);
        instance.saveConfig();
    }
}