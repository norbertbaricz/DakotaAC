package skypixel.Player;

import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

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

import java.util.UUID;

/** Original detection formulas; annotated defaults below are loaded from Modules.Version. */
public class Version implements Listener, PluginMessageListener {

    @Option(value = "join-sample-delay-ticks", min = 1.0, max = 1.0E9)
    private static volatile long JOIN_SAMPLE_DELAY_TICKS = 20L;

    @Option(value = "min-protocol-version", min = 1.0, max = 1.0E9)
    private static volatile int MIN_PROTOCOL_VERSION = 754;

    @Option("allowed-clients")
    private static volatile String[] ALLOWED_CLIENTS = {
            "vanilla", "lunar", "feather", "badlion", "optifine", "labymod", "geyser", "salwyrr", "aries"
    };

    @Option("mod-loaders")
    private static volatile String[] MOD_LOADERS = {
            "forge", "fabric", "quilt", "liteloader", "neoforge"
    };

    public Version() {
        Bukkit.getMessenger().registerIncomingPluginChannel(dakotaAC.getPlugin(dakotaAC.class), "minecraft:brand", this);
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (!dakotaAC.isCheckActive("Version")) return;

        if (channel.equals("minecraft:brand")) {
            try {

                String brand = skypixel.compat.BrandReader.decode(message);

                checkClientBrand(player, brand);

            } catch (Exception | LinkageError ex) {
                Diagnostics.report("Version", ex);
            }
        }
    }

    private void checkClientBrand(Player player, String brand) {

        for (String allowed : ALLOWED_CLIENTS) {
            if (brand.contains(allowed)) {
                return; 
            }
        }

        for (String loader : MOD_LOADERS) {
            if (brand.contains(loader)) {
                Diagnostics.sync(() -> {
                    if (player.isOnline()) {
                        flagPlayer.addFlag(player, "Version", Lang.text("checks.Version.name-version-modded"), Lang.text("checks.Version.details-playing-on-a-modloader-possible-illegal-mods", "brand", brand));
                    }
                });
                return;
            }
        }

        Diagnostics.sync(() -> {
            if (player.isOnline()) {
                flagPlayer.addFlag(player, "Version", Lang.text("checks.Version.name-version-suspicious"), Lang.text("checks.Version.details-unknown-or-unapproved-client-detected", "brand", brand));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player != null && CheckPolicy.exempt(player, "Version")) return;

        Bukkit.getScheduler().runTaskLater(dakotaAC.getPlugin(dakotaAC.class), () -> {
            if (!player.isOnline() || !dakotaAC.isCheckActive("Version")) return;

            int protocolVersion = -1;

            if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
                try {
                    Class<?> via = Class.forName("com.viaversion.viaversion.api.Via");
                    Object api = via.getMethod("getAPI").invoke(null);
                    protocolVersion = (int) Class.forName("com.viaversion.viaversion.api.ViaAPI").getMethod("getPlayerVersion", UUID.class).invoke(api, player.getUniqueId());
                } catch (Exception | LinkageError e) { Diagnostics.report("Version", e); }
            }

            if (protocolVersion == -1) {
                protocolVersion = ProtocolLibrary.getProtocolManager().getProtocolVersion(player);
            }

            if (protocolVersion < MIN_PROTOCOL_VERSION) {
                flagPlayer.addFlag(player, "Version", Lang.text("checks.Version.name-version"), Lang.text("checks.Version.details-joined-using-an-outdated-version-protocol-ac-checks", "protocol-version", protocolVersion));
            }
        }, JOIN_SAMPLE_DELAY_TICKS); 
    }
}
