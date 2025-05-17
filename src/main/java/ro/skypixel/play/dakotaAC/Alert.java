package ro.skypixel.play.dakotaAC;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

// Using ConcurrentHashMap for thread safety, as Alert might be accessed from various contexts.
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Alert {
    // Singleton instance
    private static final Alert instance = new Alert();

    // Punishment manager instance
    private Punishment punishment;

    // Stores the total violence level for each player.
    // Key: Player UUID, Value: Total violence level.
    private final Map<UUID, Integer> violenceLevels = new ConcurrentHashMap<>();

    // Stores the violence level for each specific type of suspicion for each player.
    // Key: Player UUID, Value: (Map of Type -> Type-specific violence level).
    private final Map<UUID, Map<String, Integer>> suspicionTypes = new ConcurrentHashMap<>();

    // Private constructor for Singleton pattern
    private Alert() {
    }

    /**
     * Sets the punishment manager instance.
     * This should be called once, typically during plugin initialization.
     * @param punishment The Punishment instance.
     */
    public void setPunishment(Punishment punishment) {
        this.punishment = punishment;
    }

    /**
     * Gets the singleton instance of the Alert class.
     * @return The Alert instance.
     */
    public static Alert getInstance() {
        return instance;
    }

    /**
     * Records an alert for a player, increments violence levels, notifies operators,
     * logs the suspicion, and potentially triggers a punishment.
     *
     * @param type   The type of suspicion/cheat detected (e.g., "Speed", "Fly").
     * @param player The player suspected.
     */
    public void alert(String type, Player player) {
        if (player == null || type == null || type.isEmpty()) {
            Bukkit.getLogger().warning("Alert: Null player or invalid type provided.");
            return;
        }

        UUID playerId = player.getUniqueId();

        // Increment total violence level for the player using ConcurrentHashMap's merge method.
        // If player not present, starts at 1. Otherwise, increments existing value by 1.
        int newTotalViolenceLevel = violenceLevels.merge(playerId, 1, Integer::sum);

        // Get or create the map for player-specific suspicion types.
        Map<String, Integer> playerSuspicionTypesMap = suspicionTypes.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>());

        // Increment violence level for the specific type of suspicion.
        playerSuspicionTypesMap.merge(type, 1, Integer::sum);
        // No need for: suspicionTypes.put(playerId, playerSuspicionTypesMap);
        // because computeIfAbsent returns a live map that's already part of suspicionTypes.

        // Construct the message for operators.
        String messageToOps = "§4§lDAC §c" + player.getName() + " §7suspected for §c" + type + " §7(VL: §c" + newTotalViolenceLevel + "§7)";

        // Notify online operators.
        // The condition `newTotalViolenceLevel % 1 == 0` is always true for integers.
        // This means OPs are notified on every alert.
        // If notifications should only occur at specific thresholds (e.g., every 5 VL), change this condition.
        if (newTotalViolenceLevel % 1 == 0) { // Currently notifies on every alert
            for (Player onlinePlayer : Bukkit.getOnlinePlayers()) {
                if (onlinePlayer.isOp()) {
                    onlinePlayer.sendMessage(messageToOps);
                }
            }
        }

        // Construct and log a detailed message to the server console.
        StringBuilder logMessageBuilder = new StringBuilder();
        logMessageBuilder.append(player.getName()).append(" suspected for ").append(type)
                .append(" (Total VL: ").append(newTotalViolenceLevel).append(") [");

        boolean firstEntry = true;
        for (Map.Entry<String, Integer> entry : playerSuspicionTypesMap.entrySet()) {
            if (!firstEntry) {
                logMessageBuilder.append(", ");
            }
            logMessageBuilder.append(entry.getKey()).append(": ").append(entry.getValue());
            firstEntry = false;
        }
        logMessageBuilder.append("]");
        Bukkit.getLogger().info(logMessageBuilder.toString());

        // Check and apply punishment if a punishment manager is set.
        if (punishment != null) {
            punishment.checkAndPunish(player, type);
        }
    }

    /**
     * Resets all violence levels (total and type-specific) for a given player.
     * @param player The player whose violence levels are to be reset.
     */
    public void resetViolenceLevel(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        violenceLevels.remove(playerId);
        suspicionTypes.remove(playerId);
        Bukkit.getLogger().info("Violence levels reset for " + player.getName());
    }

    /**
     * Gets the total violence level for a given player.
     * @param player The player.
     * @return The total violence level, or 0 if the player has no recorded violence.
     */
    public int getViolenceLevel(Player player) {
        if (player == null) return 0;
        return violenceLevels.getOrDefault(player.getUniqueId(), 0);
    }

    /**
     * Gets the violence level for a specific type of suspicion for a given player.
     * @param player The player.
     * @param type   The type of suspicion.
     * @return The violence level for that type, or 0 if not recorded.
     */
    public int getViolenceLevelForType(Player player, String type) {
        if (player == null || type == null) return 0;
        Map<String, Integer> playerSuspicionTypesMap = suspicionTypes.getOrDefault(player.getUniqueId(), new ConcurrentHashMap<>()); // Return empty map if player not found
        return playerSuspicionTypesMap.getOrDefault(type, 0);
    }
}
