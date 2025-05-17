package ro.skypixel.play.dakotaAC;

import org.bukkit.BanList;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.Date;
import java.util.concurrent.TimeUnit; // For more readable time calculations

public class Punishment {

    private final Alert alert = Alert.getInstance();
    private final JavaPlugin plugin;
    private File configFile;
    private FileConfiguration config;

    // Configuration keys and default values
    private static final String CONFIG_BAN_THRESHOLD = "ban-threshold";
    private static final int DEFAULT_BAN_THRESHOLD = 500;
    private static final String CONFIG_BAN_DURATION_HOURS = "ban-duration-hours"; // Example for future use
    private static final long DEFAULT_BAN_DURATION_HOURS = 24L;

    // Store the ban threshold in memory for quick access
    private int banThreshold;
    private long banDurationMillis; // Store ban duration in milliseconds

    public Punishment(JavaPlugin plugin) {
        this.plugin = plugin;
        // Ensure the Alert instance has this Punishment instance set.
        // This should be done after this Punishment instance is fully constructed.
        // Consider a setter in Alert or a more robust initialization sequence.
        Alert.getInstance().setPunishment(this);
        loadAndProcessConfig();
    }

    /**
     * Loads the configuration file or creates it with default values if it doesn't exist.
     * Processes the loaded configuration to set internal fields.
     */
    private void loadAndProcessConfig() {
        if (!plugin.getDataFolder().exists()) {
            boolean created = plugin.getDataFolder().mkdirs();
            if (!created) {
                plugin.getLogger().severe("Could not create plugin data folder!");
                // Handle error appropriately, maybe disable punishment functionality
            }
        }

        configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            try {
                plugin.getLogger().info("Creating default config.yml...");
                boolean fileCreated = configFile.createNewFile();
                if (!fileCreated) {
                    plugin.getLogger().severe("Failed to create new config.yml file.");
                    // Fallback to default values if file creation fails
                    this.banThreshold = DEFAULT_BAN_THRESHOLD;
                    this.banDurationMillis = TimeUnit.HOURS.toMillis(DEFAULT_BAN_DURATION_HOURS);
                    return;
                }

                config = YamlConfiguration.loadConfiguration(configFile);
                config.set(CONFIG_BAN_THRESHOLD, DEFAULT_BAN_THRESHOLD);
                config.set(CONFIG_BAN_DURATION_HOURS, DEFAULT_BAN_DURATION_HOURS); // Save default duration
                config.save(configFile);
                plugin.getLogger().info("Created default config.yml with ban-threshold: " + DEFAULT_BAN_THRESHOLD +
                        " and ban-duration-hours: " + DEFAULT_BAN_DURATION_HOURS);
            } catch (IOException e) {
                plugin.getLogger().severe("Failed to create or save default config.yml: " + e.getMessage());
                e.printStackTrace();
                // Fallback to default values in case of IO error
                this.banThreshold = DEFAULT_BAN_THRESHOLD;
                this.banDurationMillis = TimeUnit.HOURS.toMillis(DEFAULT_BAN_DURATION_HOURS);
                return;
            }
        } else {
            config = YamlConfiguration.loadConfiguration(configFile);
        }

        // Load values from config, using defaults if keys are missing
        this.banThreshold = config.getInt(CONFIG_BAN_THRESHOLD, DEFAULT_BAN_THRESHOLD);
        long banDurationHours = config.getLong(CONFIG_BAN_DURATION_HOURS, DEFAULT_BAN_DURATION_HOURS);
        this.banDurationMillis = TimeUnit.HOURS.toMillis(banDurationHours);

        plugin.getLogger().info("Punishment config loaded. Ban threshold: " + this.banThreshold +
                ", Ban duration: " + banDurationHours + " hours.");
    }

    /**
     * Reloads the punishment configuration from the config.yml file.
     * This method can be called by an admin command.
     */
    public void reloadPunishmentConfig() {
        plugin.getLogger().info("Reloading punishment configuration...");
        loadAndProcessConfig();
    }

    /**
     * Checks the player's violence level and applies a temporary ban if it exceeds the threshold.
     *
     * @param player        The player to check.
     * @param suspicionType The type of suspicion that triggered this check.
     */
    public void checkAndPunish(Player player, String suspicionType) {
        if (player == null || suspicionType == null) return;

        int currentViolenceLevel = alert.getViolenceLevel(player);

        if (currentViolenceLevel >= this.banThreshold) {
            applyTemporaryBan(player, suspicionType, currentViolenceLevel);
        }
    }

    /**
     * Applies a temporary ban to the player.
     *
     * @param player        The player to ban.
     * @param suspicionType The reason for the ban.
     * @param violenceLevel The violence level at the time of banning.
     */
    private void applyTemporaryBan(Player player, String suspicionType, int violenceLevel) {
        // Calculate expiration date based on the configured duration
        Date expirationDate = new Date(System.currentTimeMillis() + this.banDurationMillis);

        String banReasonMessage = "§cSuspicious activity detected (" + suspicionType + ")\n" +
                "§7Violence Level: §c" + violenceLevel + "\n" +
                "§7Banned until: §e" + expirationDate.toString() + "\n" + // Inform player about ban duration
                "§7For appeals, contact us on §bdiscord.gg/dakotaAC"; // Replace with actual Discord link

        // Add player to Bukkit's ban list
        // The "source" parameter is who issued the ban, in this case, the plugin itself.
        Bukkit.getBanList(BanList.Type.NAME).addBan(
                player.getName(),    // Player's name
                banReasonMessage,    // Reason displayed to the player
                expirationDate,      // When the ban expires (null for permanent)
                "DakotaAC"           // Source of the ban
        );

        // Kick the player from the server with the ban message
        // It's important to kick after adding to ban list to prevent immediate rejoin if kick fails.
        // Run kick on the main server thread if not already on it (though checkAndPunish is likely from main thread)
        if (Bukkit.isPrimaryThread()) {
            player.kickPlayer(banReasonMessage);
        } else {
            Bukkit.getScheduler().runTask(plugin, () -> player.kickPlayer(banReasonMessage));
        }


        // Reset the player's violence level after banning to prevent immediate re-ban on a technicality
        // or to allow them a "clean slate" if the ban expires and they rejoin.
        alert.resetViolenceLevel(player);

        // Broadcast the ban to online operators
        String broadcastMessageToOps = "§4§lDAC §c" + player.getName() + " §7has been temporarily banned for §c" + suspicionType +
                " §7(VL: §c" + violenceLevel + "§7, Duration: " +
                TimeUnit.MILLISECONDS.toHours(this.banDurationMillis) + "h)";

        for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
            if (onlinePlayer.isOp()) {
                onlinePlayer.sendMessage(broadcastMessageToOps);
            }
        }

        // Log the ban to the server console
        Bukkit.getLogger().info(player.getName() + " has been temporarily banned for " + suspicionType +
                " (VL: " + violenceLevel + ", Duration: " +
                TimeUnit.MILLISECONDS.toHours(this.banDurationMillis) + " hours). Expiration: " + expirationDate);
    }
}
