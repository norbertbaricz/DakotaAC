package skypixel.config;

import org.bukkit.entity.Player;
import skypixel.dakotaAC;

/** Common module controls, independent of the translated display name of a flag. */
public final class CheckPolicy {
    private CheckPolicy() { }

    public static boolean cancel(String check) {
        return dakotaAC.getInstance().getConfig().getBoolean("Modules." + check + ".cancel-events", true);
    }

    public static boolean setbacks(String check) {
        return dakotaAC.getInstance().getConfig().getBoolean("Modules." + check + ".setbacks", true);
    }

    public static boolean enforce(String check) {
        return dakotaAC.getInstance().getConfig().getBoolean("Modules." + check + ".enforce-actions", true);
    }

    public static boolean exempt(Player player, String check) {
        String permission = dakotaAC.getInstance().getConfig().getString("Modules." + check + ".bypass-permission", "");
        return !permission.isBlank() && player.hasPermission(permission);
    }
}
