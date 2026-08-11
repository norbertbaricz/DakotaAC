package skypixel.Player;

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
import java.net.URL;

public class AntiVPN implements Listener {

    // ==========================================
    // EASY TO TUNE SETTINGS
    // ==========================================

    // The API used (free, no key required)
    private static final String API_URL = "https://blackbox.ipinfo.app/lookup/";

    // Do you want to just flag to admins, or automatically kick the player?
    private static final boolean KICK_ON_DETECTION = false;
    private static final String KICK_MESSAGE = "§c[Skypixel] §fVPN / Proxy connections are not allowed!";

    // Players with this permission (e.g., staff) can bypass the VPN check
    private static final String BYPASS_PERMISSION = "dakotaac.bypass.vpn";

    // ==========================================
    // CORE LOGIC
    // ==========================================

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!dakotaAC.isCheckActive("AntiVPN")) return;

        Player player = event.getPlayer();

        // 1. Check bypass permission
        if (player.hasPermission(BYPASS_PERMISSION)) return;

        // Extract the player's IP
        // Note: If you are on BungeeCord/Velocity, make sure IP Forwarding is enabled in configs!
        String ip = player.getAddress().getAddress().getHostAddress();

        // Ignore local addresses (when connecting from localhost or local network)
        if (ip.equals("127.0.0.1") || ip.startsWith("192.168.") || ip.startsWith("10.")) return;

        // 2. Make the API request asynchronously to prevent server lag
        Bukkit.getScheduler().runTaskAsynchronously(dakotaAC.getPlugin(dakotaAC.class), () -> {
            try {
                URL url = new URL(API_URL + ip);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");

                // Set a short timeout (3 seconds) so we don't wait forever if the API goes down
                connection.setConnectTimeout(3000);
                connection.setReadTimeout(3000);

                BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String response = reader.readLine();
                reader.close();

                // 3. The API returns "Y" if the IP belongs to a VPN / Data Center / Proxy
                if ("Y".equals(response)) {

                    // Return to the main thread to flag or kick the player
                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                        if (!player.isOnline()) return;

                        // Send flag to admins
                        flagPlayer.addFlag(player, "AntiVPN", "Connected using a VPN/Proxy (IP: " + ip + ").");

                        // If the kick option is enabled in Easy Tune
                        if (KICK_ON_DETECTION) {
                            player.kickPlayer(KICK_MESSAGE);
                        }
                    });
                }
            } catch (Exception e) {
                // If the API is down or times out, fail silently.
                // It's better to let a hacker slip by than crash the server.
            }
        });
    }
}