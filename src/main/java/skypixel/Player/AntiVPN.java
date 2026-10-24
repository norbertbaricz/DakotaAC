package skypixel.Player;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;

/** Original detection formulas; annotated defaults below are loaded from Modules.AntiVPN. */
public class AntiVPN implements Listener {

    @Option(value = "connect-timeout-ms", min = 1.0, max = 1.0E9)
    private static volatile int CONNECT_TIMEOUT_MS = 3000;
    @Option(value = "read-timeout-ms", min = 1.0, max = 1.0E9)
    private static volatile int READ_TIMEOUT_MS = 3000;

    @Option("api-url")
    private static volatile String API_URL = "https://blackbox.ipinfo.app/lookup/";

    @Option("kick-on-detection")
    private static volatile boolean KICK_ON_DETECTION = false;

    @Option("bypass-permission")
    private static volatile String BYPASS_PERMISSION = "dakotaac.bypass.vpn";

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!dakotaAC.isCheckActive("AntiVPN") || CheckPolicy.exempt(player, "AntiVPN")) return;
        if (!BYPASS_PERMISSION.isBlank() && player.hasPermission(BYPASS_PERMISSION)) return;
        if (player.getAddress() == null || player.getAddress().getAddress() == null) return;
        java.net.InetAddress address = player.getAddress().getAddress();
        if (address.isLoopbackAddress() || address.isSiteLocalAddress() || address.isAnyLocalAddress()) return;
        String ip = address.getHostAddress();
        dakotaAC plugin = dakotaAC.getInstance();
        long generation = plugin.getGeneration();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            HttpURLConnection connection = null;
            try {
                connection = (HttpURLConnection) java.net.URI.create(API_URL + ip).toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                String response;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    response = reader.readLine();
                }
                if (!"Y".equals(response)) return;
                Diagnostics.sync(() -> {
                    if (!player.isOnline() || generation != plugin.getGeneration() || !dakotaAC.isCheckActive("AntiVPN") || CheckPolicy.exempt(player, "AntiVPN")) return;
                    flagPlayer.addFlag(player, "AntiVPN", Lang.text("checks.AntiVPN.name-antivpn"),
                            Lang.text("checks.AntiVPN.details-connected-using-a-vpn-proxy-ip", "ip", ip));
                    if (KICK_ON_DETECTION && CheckPolicy.enforce("AntiVPN")) player.kickPlayer(Lang.text("checks.AntiVPN.kick-message"));
                });
            } catch (Exception | LinkageError ex) {
                if (generation == plugin.getGeneration()) Diagnostics.report("AntiVPN", ex);
            } finally {
                if (connection != null) connection.disconnect();
            }
        });
    }
}
