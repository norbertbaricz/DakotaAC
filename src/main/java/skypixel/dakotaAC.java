package skypixel;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import skypixel.Commands.Dakota;
import skypixel.Commands.Report;
import skypixel.Notification.Violation;
import skypixel.Notification.flagPlayer;
import skypixel.compat.Diagnostics;
import skypixel.config.Lang;
import skypixel.config.Settings;
import skypixel.Combat.*;
import skypixel.Exploit.*;
import skypixel.Fun.*;
import skypixel.Misc.*;
import skypixel.Movement.*;
import skypixel.Player.*;
import skypixel.Render.*;
import skypixel.World.*;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public final class dakotaAC extends JavaPlugin {
    // Legacy configuration keys and check names remain stable for existing installations.
    private static final Map<String, Class<? extends Listener>> MODULE_TYPES = new LinkedHashMap<>();
    private static final Map<String, Boolean> activeChecks = new LinkedHashMap<>();
    private static dakotaAC instance;
    private static boolean autoKick;
    private static boolean autoBan;
    private static int maxKickFlags;
    private static int maxBanFlags;
    private volatile long generation;
    private final List<Listener> listeners = new ArrayList<>();
    private final Map<String, String> unavailableChecks = new LinkedHashMap<>();

    static {
        MODULE_TYPES.put("AimBot", AimBot.class);
        MODULE_TYPES.put("AutoArmor", AutoArmor.class);
        MODULE_TYPES.put("AutoClicker", AutoClicker.class);
        MODULE_TYPES.put("Criticals", Criticals.class);
        MODULE_TYPES.put("Hitbox", Hitbox.class);
        MODULE_TYPES.put("KillAura", KillAura.class);
        MODULE_TYPES.put("Reach", Reach.class);
        MODULE_TYPES.put("Velocity", Velocity.class);
        MODULE_TYPES.put("GhostHand", GhostHand.class);
        MODULE_TYPES.put("GodMode", GodMode.class);
        MODULE_TYPES.put("Phase", Phase.class);
        MODULE_TYPES.put("PingSpoof", PingSpoof.class);
        MODULE_TYPES.put("Plugins", Plugins.class);
        MODULE_TYPES.put("ServerCrasher", ServerCrasher.class);
        MODULE_TYPES.put("Blink", Blink.class);
        MODULE_TYPES.put("Regen", Regen.class);
        MODULE_TYPES.put("Derp", Derp.class);
        MODULE_TYPES.put("Spammer", Spammer.class);
        MODULE_TYPES.put("AntiBot", AntiBot.class);
        MODULE_TYPES.put("Fly", Fly.class);
        MODULE_TYPES.put("HighJump", HighJump.class);
        MODULE_TYPES.put("InventoryMove", InventoryMove.class);
        MODULE_TYPES.put("Jesus", Jesus.class);
        MODULE_TYPES.put("NoFall", NoFall.class);
        MODULE_TYPES.put("NoSlowDown", NoSlowDown.class);
        MODULE_TYPES.put("Sneak", Sneak.class);
        MODULE_TYPES.put("Speed", Speed.class);
        MODULE_TYPES.put("Sprint", Sprint.class);
        MODULE_TYPES.put("Step", Step.class);
        MODULE_TYPES.put("AntiVoid", AntiVoid.class);
        MODULE_TYPES.put("ChestStealer", ChestStealer.class);
        MODULE_TYPES.put("FastUse", FastUse.class);
        MODULE_TYPES.put("InventoryCleaner", InventoryCleaner.class);
        MODULE_TYPES.put("Version", Version.class);
        MODULE_TYPES.put("AntiVPN", AntiVPN.class);
        MODULE_TYPES.put("ESP", ESP.class);
        MODULE_TYPES.put("Tracers", Tracers.class);
        MODULE_TYPES.put("XRay", XRay.class);
        MODULE_TYPES.put("FastBreak", FastBreak.class);
        MODULE_TYPES.put("FastPlace", FastPlace.class);
        MODULE_TYPES.put("Fucker", Fucker.class);
        MODULE_TYPES.put("Nuker", Nuker.class);
        MODULE_TYPES.put("Scaffold", Scaffold.class);
        MODULE_TYPES.put("Timer", Timer.class);
    }

    @Override
    public void onEnable() {
        instance = this;
        try {
            saveDefaultConfig();
            Lang.reload(this);
            if (!getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
                getLogger().severe(Lang.text("system.missing-protocollib"));
                getServer().getPluginManager().disablePlugin(this);
                return;
            }
            readConfiguration();
            initializeChecks();
            Dakota dakota = new Dakota();
            Report report = new Report();
            bindCommand("dakotaac", dakota, dakota);
            bindCommand("report", report, report);
            getLogger().info(Lang.text("system.started", "version", getDescription().getVersion(),
                    "active", activeChecks.values().stream().filter(Boolean::booleanValue).count(), "total", MODULE_TYPES.size()));
            boolean healthy = unavailableChecks.isEmpty() && Diagnostics.failureCount() == 0;
            notifyStaff(Lang.text(healthy ? "system.staff-started" : "system.staff-degraded"), !healthy);
        } catch (Exception | LinkageError ex) {
            Diagnostics.report("startup", ex);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void bindCommand(String name, org.bukkit.command.CommandExecutor executor, org.bukkit.command.TabCompleter completer) {
        PluginCommand command = Objects.requireNonNull(getCommand(name), name);
        command.setExecutor(executor);
        command.setTabCompleter(completer);
    }

    @Override
    public void onDisable() {
        stopChecks();
        flagPlayer.clearAll();
        if (instance != null) getLogger().info(Lang.text("system.stopped"));
    }

    private void readConfiguration() throws Exception {
        // Parse strictly before touching the running checks. Invalid YAML must not silently reset protection.
        YamlConfiguration strict = new YamlConfiguration();
        strict.load(new File(getDataFolder(), "config.yml"));
        reloadConfig();
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        boolean changed = false;
        for (String key : defaults.getKeys(true)) {
            if (defaults.isConfigurationSection(key)) continue;
            if (!strict.contains(key)) {
                getConfig().set(key, defaults.get(key));
                changed = true;
            }
        }
        if (changed) saveConfig();
        autoKick = getConfig().getBoolean("Settings.Auto-Kick", true);
        autoBan = getConfig().getBoolean("Settings.Auto-Ban", true);
        maxKickFlags = getConfig().getInt("Settings.Max-Kick-Flags", 800);
        maxBanFlags = getConfig().getInt("Settings.Max-Ban-Flags", 2000);
        if (maxKickFlags < 1 || maxBanFlags <= maxKickFlags) {
            maxKickFlags = 800;
            maxBanFlags = 2000;
            getLogger().warning(Lang.text("system.invalid-setting", "path", "Settings.Max-Kick-Flags / Settings.Max-Ban-Flags"));
        }
        flagPlayer.alertsEnabled = getConfig().getBoolean("Settings.Notifications", true);
        Diagnostics.configure(getConfig().getLong("Compatibility.Error-Log-Interval-Ms", 30000L),
                getConfig().getBoolean("Compatibility.Print-Stack-Traces", false));
        Settings.bind(flagPlayer.class, "Alerts");
        Settings.bind(Report.class, "Reports");
    }

    public void loadConfigSettings() {
        try {
            // Both resources are validated before listeners and tasks are replaced.
            Lang.reload(this);
            readConfiguration();
            stopChecks();
            initializeChecks();
        } catch (Exception | LinkageError ex) {
            Diagnostics.report("reload", ex);
            throw new IllegalStateException(ex);
        }
    }

    private void initializeChecks() {
        activeChecks.clear();
        unavailableChecks.clear();
        for (Map.Entry<String, Class<? extends Listener>> entry : MODULE_TYPES.entrySet()) {
            String name = entry.getKey();
            activeChecks.put(name, false);
            if (!getConfig().getBoolean("Modules." + name + ".enabled", true)) continue;
            Set<com.comphenix.protocol.events.PacketListener> beforePackets = Set.copyOf(ProtocolLibrary.getProtocolManager().getPacketListeners());
            Set<Integer> beforeTasks = ownTaskIds();
            try {
                Settings.bind(entry.getValue(), "Modules." + name);
                Listener listener = entry.getValue().getDeclaredConstructor().newInstance();
                getServer().getPluginManager().registerEvents(listener, this);
                listeners.add(listener);
                activeChecks.put(name, true);
            } catch (Exception | LinkageError ex) {
                // Roll back a constructor that registered a listener/task before failing.
                for (com.comphenix.protocol.events.PacketListener listener : Set.copyOf(ProtocolLibrary.getProtocolManager().getPacketListeners())) {
                    if (listener.getPlugin() == this && !beforePackets.contains(listener)) ProtocolLibrary.getProtocolManager().removePacketListener(listener);
                }
                for (BukkitTask task : Bukkit.getScheduler().getPendingTasks()) {
                    if (task.getOwner() == this && !beforeTasks.contains(task.getTaskId())) task.cancel();
                }
                unavailableChecks.put(name, ex.getClass().getSimpleName());
                Diagnostics.report(name, ex);
                getLogger().warning(Lang.text("system.check-unavailable", "check", name));
            }
        }
        getServer().getPluginManager().registerEvents(new Violation(), this);
    }

    private Set<Integer> ownTaskIds() {
        return Bukkit.getScheduler().getPendingTasks().stream().filter(task -> task.getOwner() == this)
                .map(BukkitTask::getTaskId).collect(Collectors.toSet());
    }

    private void stopChecks() {
        generation++;
        for (Listener listener : listeners) {
            try {
                if (listener instanceof Tracers) Tracers.cleanupAllDecoys();
                if (listener instanceof ESP) ESP.cleanupAll();
                if (listener instanceof AntiBot) AntiBot.cleanupAllBots();
                if (listener instanceof AutoArmor) AutoArmor.cleanupAllDecoys();
                if (listener instanceof XRay xray) xray.cleanup();
            } catch (RuntimeException | LinkageError ex) { Diagnostics.report(listener.getClass().getSimpleName(), ex); }
        }
        listeners.clear();
        Bukkit.getScheduler().cancelTasks(this);
        HandlerList.unregisterAll(this);
        Bukkit.getMessenger().unregisterIncomingPluginChannel(this);
        if (getServer().getPluginManager().isPluginEnabled("ProtocolLib")) {
            try { ProtocolLibrary.getProtocolManager().removePacketListeners(this); }
            catch (RuntimeException | LinkageError ex) { Diagnostics.report("shutdown", ex); }
        }
        activeChecks.replaceAll((key, value) -> false);
    }

    public void notifyStaff(String message, boolean error) {
        if (!getConfig().getBoolean("Staff-Notifications.enabled", true)) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!isAdmin(player)) continue;
            player.sendMessage(Lang.text("prefix") + message);
            if (!getConfig().getBoolean("Staff-Notifications.sounds", true)) continue;
            try {
                String path = "Staff-Notifications." + (error ? "error" : "success");
                Sound sound = org.bukkit.Registry.SOUNDS.get(org.bukkit.NamespacedKey.minecraft(getConfig().getString(path + ".sound", error ? "block.note_block.bass" : "entity.experience_orb.pickup")));
                if (sound != null) player.playSound(player.getLocation(), sound,
                        (float) getConfig().getDouble(path + ".volume", 1.0), (float) getConfig().getDouble(path + ".pitch", error ? 0.5 : 1.0));
            } catch (RuntimeException ex) { Diagnostics.report("staff-sound", ex); }
        }
    }

    public static dakotaAC getInstance() { return instance; }
    public long getGeneration() { return generation; }
    public static Map<String, Boolean> getChecks() { return Collections.unmodifiableMap(activeChecks); }
    public Map<String, String> getUnavailableChecks() { return Collections.unmodifiableMap(unavailableChecks); }
    public static boolean isCheckActive(String name) { return activeChecks.getOrDefault(name, false); }
    public static boolean isAdmin(org.bukkit.command.CommandSender sender) {
        return sender.isOp() || sender.hasPermission(instance.getConfig().getString("Permissions.admin", "dakotaac.admin"));
    }

    public static void toggleCheck(String name) {
        if (!MODULE_TYPES.containsKey(name)) return;
        instance.getConfig().set("Modules." + name + ".enabled", !instance.getConfig().getBoolean("Modules." + name + ".enabled", true));
        instance.saveConfig();
        instance.stopChecks();
        instance.initializeChecks();
    }

    public static boolean isAutoKick() { return autoKick; }
    public static boolean isAutoBan() { return autoBan; }
    public static int getMaxKickFlags() { return maxKickFlags; }
    public static int getMaxBanFlags() { return maxBanFlags; }
    public static void toggleAutoKick() { autoKick = !autoKick; instance.getConfig().set("Settings.Auto-Kick", autoKick); instance.saveConfig(); }
    public static void toggleAutoBan() { autoBan = !autoBan; instance.getConfig().set("Settings.Auto-Ban", autoBan); instance.saveConfig(); }
    public static void setFlagLimits(int kick, int ban) {
        if (kick < 1 || ban <= kick) throw new IllegalArgumentException("limits");
        maxKickFlags = kick; maxBanFlags = ban;
        instance.getConfig().set("Settings.Max-Kick-Flags", kick);
        instance.getConfig().set("Settings.Max-Ban-Flags", ban);
        instance.saveConfig();
    }
    public static void toggleNotifications() {
        flagPlayer.alertsEnabled = !flagPlayer.alertsEnabled;
        instance.getConfig().set("Settings.Notifications", flagPlayer.alertsEnabled);
        instance.saveConfig();
    }
}
