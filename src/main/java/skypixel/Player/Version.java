package skypixel.Player;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.messaging.PluginMessageListener;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class Version implements Listener, PluginMessageListener {

    // ==========================================
    // SETĂRI UȘOR DE MODIFICAT (Easy to Tune)
    // ==========================================

    // Versiunea minimă de protocol acceptată fără a da alertă
    // 754 = 1.16.5 | 763 = 1.20.1 | 47 = 1.8.x
    private static final int MIN_PROTOCOL_VERSION = 754;

    // Lista Albă: Clienți Vanilla sau de PvP legitimi (nu trimitem nicio alertă)
    private static final List<String> ALLOWED_CLIENTS = Arrays.asList(
            "vanilla", "lunar", "feather", "badlion", "optifine", "labymod", "geyser", "salwyrr", "aries"
    );

    // Lista Gri: Modloadere (trimitem alertă informativă, deoarece pot avea și mod-uri ilegale)
    private static final List<String> MOD_LOADERS = Arrays.asList(
            "forge", "fabric", "quilt", "liteloader", "neoforge"
    );

    // ==========================================
    // LOGICA DE BAZĂ
    // ==========================================

    public Version() {
        Bukkit.getMessenger().registerIncomingPluginChannel(dakotaAC.getPlugin(dakotaAC.class), "minecraft:brand", this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!dakotaAC.isCheckActive("Version")) return;

        if (channel.equals("minecraft:brand")) {
            try {
                // Clientul trimite brand-ul ca un array de biți. Convertim în text și curățăm caracterele.
                String brand = new String(message, StandardCharsets.UTF_8).replaceAll("[^a-zA-Z0-9_-]", "").toLowerCase();

                checkClientBrand(player, brand);

            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    private void checkClientBrand(Player player, String brand) {
        // 1. Verificăm Lista Albă
        for (String allowed : ALLOWED_CLIENTS) {
            if (brand.contains(allowed)) {
                return; // E curat, nu trimitem nimic
            }
        }

        // 2. Verificăm Lista Gri
        for (String loader : MOD_LOADERS) {
            if (brand.contains(loader)) {
                Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                    if (player.isOnline()) {
                        flagPlayer.addFlag(player, "Version (Modded)", "Playing on a modloader (" + brand + "). Possible illegal mods.");
                    }
                });
                return;
            }
        }

        // 3. Dacă nu e nici în Lista Albă, nici în Lista Gri, intră în Lista Neagră (Suspect)
        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
            if (player.isOnline()) {
                flagPlayer.addFlag(player, "Version (Suspicious)", "Unknown or unapproved client detected: " + brand);
            }
        });
    }

    // ==========================================================
    // DETECȚIE VERSIUNE MINECRAFT (PROTOCOL)
    // ==========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        // Lăsăm un delay mic ca ProtocolLib și ViaVersion să termine handshake-ul
        Bukkit.getScheduler().runTaskLater(dakotaAC.getPlugin(dakotaAC.class), () -> {
            if (!player.isOnline()) return;

            // Extragem Protocol Version prin ProtocolLib
            int protocolVersion = ProtocolLibrary.getProtocolManager().getProtocolVersion(player);

            // Folosim constanta din secțiunea "Easy to Tune"
            if (protocolVersion < MIN_PROTOCOL_VERSION) {
                flagPlayer.addFlag(player, "Version", "Joined using an outdated version (Protocol: " + protocolVersion + "). AC checks might be unstable.");
            }
        }, 20L); // 1 secundă după conectare
    }
}