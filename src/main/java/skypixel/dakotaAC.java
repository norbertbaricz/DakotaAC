package skypixel;

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

    // Instanța pluginului pentru a o putea accesa din alte clase
    private static dakotaAC instance;

    // Stocăm starea check-urilor (Activat/Dezactivat)
    private static final Map<String, Boolean> activeChecks = new HashMap<>();

    // --- SETĂRI PENTRU PUNISHMENTS ---
    private static boolean autoKick;
    private static boolean autoBan;
    private static int maxKickFlags;
    private static int maxBanFlags;

    @Override
    public void onEnable() {
        instance = this;

        org.bukkit.command.ConsoleCommandSender console = getServer().getConsoleSender();
        String version = getDescription().getVersion();
        String prefix = org.bukkit.ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

        // --- STARTUP UI ÎN CONSOLĂ ---
        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");
        console.sendMessage(org.bukkit.ChatColor.RED + "" + org.bukkit.ChatColor.BOLD + " DakotaAC " + org.bukkit.ChatColor.GRAY + "v" + version);

        // ==========================================================
        // DEPENDENCY CHECK: Verificăm dacă ProtocolLib este instalat!
        // ==========================================================
        if (getServer().getPluginManager().getPlugin("ProtocolLib") == null) {
            console.sendMessage(org.bukkit.ChatColor.DARK_RED + " [CRITICAL ERROR] ProtocolLib is missing!");
            console.sendMessage(org.bukkit.ChatColor.RED + " DakotaAC requires ProtocolLib to intercept network packets.");
            console.sendMessage(org.bukkit.ChatColor.RED + " Please install ProtocolLib. The Anti-Cheat will now disable itself.");
            console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

            // Auto-dezactivăm pluginul
            getServer().getPluginManager().disablePlugin(this);

            // Folosim "return" pentru a opri imediat execuția metodei onEnable().
            return;
        }

        console.sendMessage(org.bukkit.ChatColor.YELLOW + " Initializing high-performance security modules...");

        // 1. Încărcăm Configurațiile și Modulele
        saveDefaultConfig();
        loadConfigSettings();

        // 2. Înregistrăm Evenimentele (Listeners)
        registerListeners();

        // 3. Înregistrăm Comenzile
        // Înregistrăm comanda /report
        getCommand("report").setExecutor(new Report());
        getCommand("report").setTabCompleter(new Report());

        // Înregistrăm comanda /dakotaac
        getCommand("dakotaac").setExecutor(new Dakota());
        getCommand("dakotaac").setTabCompleter(new Dakota());

        // Mesajul de succes din consolă
        console.sendMessage(org.bukkit.ChatColor.GREEN + " [System] All modules loaded successfully. Zero errors.");
        console.sendMessage(org.bukkit.ChatColor.GREEN + " [System] Server is now fully protected!");
        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

        // Broadcast pentru staff-ul online
        getServer().broadcast(prefix + org.bukkit.ChatColor.GREEN + "System started successfully! Zero errors detected.", "dakotaac.startup");
    }

    @Override
    public void onDisable() {
        org.bukkit.command.ConsoleCommandSender console = getServer().getConsoleSender();
        String prefix = org.bukkit.ChatColor.translateAlternateColorCodes('&', "&8[&cDakotaAC&8] &7");

        // --- PREVENIRE LAG ȘI BUG-URI LA RELOAD ---
        skypixel.Render.Tracers.cleanupAllDecoys();
        skypixel.Render.ESP.cleanupAll();
        skypixel.Misc.AntiBot.cleanupAllBots();
        skypixel.Combat.AutoArmor.cleanupAllDecoys(); // Adaugat cleanup-ul proaspat pentru AutoArmor!

        // 1. Ștergem listener-ele din ProtocolLib pentru a preveni erorile de la PlugManX
        if (getServer().getPluginManager().getPlugin("ProtocolLib") != null) {
            ProtocolLibrary.getProtocolManager().removePacketListeners(this);
        }

        // --- SHUTDOWN UI ÎN CONSOLĂ ---
        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");
        console.sendMessage(org.bukkit.ChatColor.RED + "" + org.bukkit.ChatColor.BOLD + " DakotaAC " + org.bukkit.ChatColor.GRAY + "is shutting down.");
        console.sendMessage(org.bukkit.ChatColor.YELLOW + " [System] Security modules are now OFFLINE.");
        console.sendMessage(org.bukkit.ChatColor.DARK_GRAY + "--------------------------------------------------");

        getServer().broadcast(prefix + org.bukkit.ChatColor.RED + "System shutting down... Server is exposed!", "dakotaac.startup");
    }

    /**
     * Încarcă setările generale și starea fiecărui modul din config.yml
     */
    public void loadConfigSettings() {
        // Forțează serverul să citească ultima versiune a fișierului de pe disc
        reloadConfig();

        autoKick = getConfig().getBoolean("Settings.Auto-Kick", false);
        autoBan = getConfig().getBoolean("Settings.Auto-Ban", false);
        maxKickFlags = getConfig().getInt("Settings.Max-Kick-Flags", 15);
        maxBanFlags = getConfig().getInt("Settings.Max-Ban-Flags", 30);
        flagPlayer.alertsEnabled = getConfig().getBoolean("Settings.Notifications", true);

        // Încărcăm starea modulelor direct din fișierul config.yml
        initializeChecks();

        // Am eliminat saveConfig() de aici pentru a-ți proteja comentariile (#) făcute manual în fișier!
    }

    /**
     * Sincronizează harta din memorie cu fișierul config.yml
     */
    private void initializeChecks() {
        // Combat
        loadModuleState("AimBot");
        loadModuleState("AutoArmor");
        loadModuleState("AutoClicker");
        loadModuleState("Criticals");
        loadModuleState("Hitbox");
        loadModuleState("KillAura");
        loadModuleState("Reach");
        loadModuleState("Velocity");

        // Exploit
        loadModuleState("GhostHand");
        loadModuleState("GodMode");
        loadModuleState("Phase");
        loadModuleState("PingSpoof");
        loadModuleState("ServerCrasher");
        loadModuleState("Teleport");

        // Fun & Misc
        loadModuleState("Derp");
        loadModuleState("AntiBot");
        loadModuleState("Spammer");

        // Movement
        loadModuleState("Fly");
        loadModuleState("HighJump");
        loadModuleState("InventoryMove");
        loadModuleState("LiquidWalk");
        loadModuleState("NoFall");
        loadModuleState("NoSlowDown");
        loadModuleState("Sneak");
        loadModuleState("Speed");
        loadModuleState("Sprint");
        loadModuleState("Step");

        // Player
        loadModuleState("AntiVoid");
        loadModuleState("ChestStealer");
        loadModuleState("FastUse");
        loadModuleState("InventoryCleaner");

        // Render
        loadModuleState("ESP");
        loadModuleState("Tracers");
        loadModuleState("XRay");

        // World
        loadModuleState("FastBreak");
        loadModuleState("FastPlace");
        loadModuleState("Fucker");
        loadModuleState("Nuker");
        loadModuleState("Scaffold");
    }

    /**
     * Citește starea 'enabled' a unui modul din config. Dacă nu există, fallback pe true.
     */
    private void loadModuleState(String moduleName) {
        // Noua structură: Modules.AimBot.enabled
        String path = "Modules." + moduleName + ".enabled";

        // Citim valoarea din config. Dacă linia nu există, luăm 'true' ca valoare default
        boolean isActive = getConfig().getBoolean(path, true);

        // Actualizăm DOAR în memoria pluginului, nu și pe disc, ca să păstrăm structura și comentariile
        activeChecks.put(moduleName, isActive);
    }

    private void registerListeners() {
        org.bukkit.plugin.PluginManager pm = getServer().getPluginManager();

        // Combat
        pm.registerEvents(new AimBot(), this);
        pm.registerEvents(new AutoArmor(), this);
        pm.registerEvents(new AutoClicker(), this);
        pm.registerEvents(new Criticals(), this);
        pm.registerEvents(new Hitbox(), this);
        pm.registerEvents(new KillAura(), this);
        pm.registerEvents(new Reach(), this);
        pm.registerEvents(new Velocity(), this);

        // Exploit
        pm.registerEvents(new GhostHand(), this);
        pm.registerEvents(new GodMode(), this);
        pm.registerEvents(new Phase(), this);
        pm.registerEvents(new PingSpoof(), this);
        pm.registerEvents(new ServerCrasher(), this);
        pm.registerEvents(new Teleport(), this);

        // Fun & Misc
        pm.registerEvents(new Derp(), this);
        pm.registerEvents(new Spammer(), this);
        pm.registerEvents(new AntiBot(), this);

        // Movement
        pm.registerEvents(new Fly(), this);
        pm.registerEvents(new HighJump(), this);
        pm.registerEvents(new InventoryMove(), this);
        pm.registerEvents(new LiquidWalk(), this);
        pm.registerEvents(new NoFall(), this);
        pm.registerEvents(new NoSlowDown(), this);
        pm.registerEvents(new Sneak(), this);
        pm.registerEvents(new Speed(), this);
        pm.registerEvents(new Sprint(), this);
        pm.registerEvents(new Step(), this);

        // Player
        pm.registerEvents(new AntiVoid(), this);
        pm.registerEvents(new ChestStealer(), this);
        pm.registerEvents(new FastUse(), this);
        pm.registerEvents(new InventoryCleaner(), this);

        // Render
        pm.registerEvents(new ESP(), this);
        pm.registerEvents(new Tracers(), this);
        pm.registerEvents(new XRay(), this);

        // World
        pm.registerEvents(new FastBreak(), this);
        pm.registerEvents(new FastPlace(), this);
        pm.registerEvents(new Fucker(), this);
        pm.registerEvents(new Nuker(), this);
        pm.registerEvents(new Scaffold(), this);

        // Notification System
        pm.registerEvents(new Violation(), this);
    }

    // ==========================================================
    // METODE API (Folosite în alte clase)
    // ==========================================================
    public static dakotaAC getInstance() { return instance; }

    public static Map<String, Boolean> getChecks() {
        return activeChecks;
    }

    public static boolean isCheckActive(String checkName) {
        return activeChecks.getOrDefault(checkName, false);
    }

    /**
     * Schimbă starea 'enabled' a unui modul și salvează decizia în config.yml permanent!
     */
    public static void toggleCheck(String moduleName) {
        if (activeChecks.containsKey(moduleName)) {
            boolean newState = !activeChecks.get(moduleName);
            activeChecks.put(moduleName, newState);

            // Salvăm folosind noua structură (Modules.NumeModul.enabled)
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
        instance.getConfig().set("Settings.Max-Flags", null); // Curățăm variabila veche
        instance.saveConfig();
    }

    public static void toggleNotifications() {
        flagPlayer.alertsEnabled = !flagPlayer.alertsEnabled;
        instance.getConfig().set("Settings.Notifications", flagPlayer.alertsEnabled);
        instance.saveConfig();
    }
}