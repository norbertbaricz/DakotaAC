package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LiquidWalk implements Listener {

    // Folosim ConcurrentHashMap pentru siguranță asincronă pe ProtocolLib
    private final ConcurrentHashMap<UUID, Integer> jesusBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public LiquidWalk() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("LiquidWalk")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele brute din pachet
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // Înregistrăm prima locație și așteptăm următorul pachet
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Ignorăm jucătorii care stau complet nemișcați
                            if (deltaXZ == 0.0 && deltaY == 0.0) {
                                return;
                            }

                            // Salvăm poziția veche pentru a-l trage înapoi dacă trișează
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm memoria pentru următorul pachet
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // --- Delegăm verificarea hărții către Main Thread ---
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Verificăm permisiunile legitime Vanilla
                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isGliding() || player.isRiptiding()) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);
                                Material blockAtFeet = toLoc.getBlock().getType();
                                Material blockUnderFeet = toLoc.clone().subtract(0, 0.1, 0).getBlock().getType();

                                boolean isWaterOrLavaUnder = blockUnderFeet == Material.WATER || blockUnderFeet == Material.LAVA;
                                boolean isWaterOrLavaAtFeet = blockAtFeet == Material.WATER || blockAtFeet == Material.LAVA;

                                int vl = jesusBuffer.getOrDefault(uuid, 0);

                                // Dacă atinge apa și nu se află lângă mal/nuferi/gheață
                                if ((isWaterOrLavaAtFeet || isWaterOrLavaUnder) && !isNearSolidBlock(toLoc)) {

                                    if (player.isSwimming()) {
                                        jesusBuffer.put(uuid, Math.max(0, vl - 1));
                                        return;
                                    }

                                    // LOGICA PRINCIPALĂ
                                    if (deltaY == 0.0) {
                                        vl += 2; // Hover perfect plat
                                    } else if (deltaY > 0.0 && deltaY < 0.1) {
                                        vl += 1; // Dolphin/Bounce micro-salturi
                                    } else {
                                        if (vl > 0) vl--; // Scufundare/înot curat
                                    }

                                    jesusBuffer.put(uuid, vl);

                                    if (vl > 5) {
                                        flagPlayer.addFlag(player, "LiquidWalk", "Unnatural vertical stability in liquid (Y-Speed: " + String.format("%.3f", deltaY) + ")");

                                        // Rubber-Band Subacvatic: Îl teleportăm înapoi și îl tragem în jos 0.5 blocuri
                                        Location pullDownLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1] - 0.5, safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());
                                        player.teleport(pullDownLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        // Resetăm parțial buffer-ul pentru a nu face spam dacă continuă să încerce
                                        jesusBuffer.put(uuid, 2);
                                    }
                                } else {
                                    // Dacă este pe pământ curat, ștergem buffer-ul
                                    if (vl > 0) jesusBuffer.put(uuid, 0);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    /**
     * Sincronizare vitală pentru prevenirea alertelor false la Teleport.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            jesusBuffer.remove(uuid);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        jesusBuffer.remove(uuid);
        lastPosMap.remove(uuid);
    }

    /**
     * Verificare volumetrică: Protejează jucătorii de alerte false când stau pe margini sau pe crini de apă.
     */
    private boolean isNearSolidBlock(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 0; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Block b = loc.getWorld().getBlockAt(x + dx, y + dy, z + dz);
                    Material type = b.getType();

                    if (type.isSolid() || type == Material.LILY_PAD || type.name().contains("ICE") || type.name().contains("CARPET") || type.name().contains("BOAT")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}