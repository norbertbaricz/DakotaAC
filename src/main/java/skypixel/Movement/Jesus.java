package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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

public class Jesus implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Câte pachete de "hover" pe apă sunt permise înainte de flag?
    private static final int MAX_VIOLATIONS = 5;

    // Viteza maximă pe axa Y pentru modul "Dolphin/Bounce" al hack-urilor Jesus
    private static final double MAX_BOUNCE_Y = 0.1;
    // ==========================================

    // Folosim ConcurrentHashMap pentru siguranță asincronă
    private final ConcurrentHashMap<UUID, Integer> jesusBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Jesus() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Jesus")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.SPECTATOR || player.getGameMode() == GameMode.CREATIVE) return;
                            UUID uuid = player.getUniqueId();

                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Ignorăm complet jucătorii care stau nemișcați
                            if (deltaXZ == 0.0 && deltaY == 0.0) {
                                return;
                            }

                            // === OPTIMIZARE ASINCRONĂ EXTREMĂ (SALVĂM TPS-UL) ===
                            // Jucătorii legitimi care cad în apă au deltaY < -0.1. Cei care sar din apă au > 0.4.
                            // Hack-ul Jesus forțează jucătorul să stea la deltaY == 0.0 sau să facă bounce-uri mici.
                            // Deci, dacă pachetul are o viteză mare de cădere sau de salt, îl excludem asincron!
                            if (deltaY < -0.1 || deltaY > 0.42) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                jesusBuffer.remove(uuid); // Curățăm buffer-ul, joacă curat
                                return;
                            }

                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // Doar dacă pachetul pare suspect, delegăm către Main Thread
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isGliding() || player.isRiptiding()) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                // === FILTRU DE USCAT (SALVĂM PROCESORUL) ===
                                // Dacă blocul de sub el și de la picioare nu este lichid, înseamnă că aleargă pe iarbă.
                                // Ștergem buffer-ul și oprim execuția înainte de for-loop-ul 3D greoi.
                                if (!isLiquidAt(toLoc) && !isLiquidAt(toLoc.clone().subtract(0, 0.1, 0))) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                // Dacă atinge apa, dar e lângă mal/nuferi/gheață sau un bloc Waterlogged, e curat
                                if (isNearSolidBlock(toLoc)) {
                                    jesusBuffer.remove(uuid);
                                    return;
                                }

                                // Dacă jucătorul înoată legitim cu animația de 1.13+ (Dolphin sprint)
                                if (player.isSwimming()) {
                                    int vl = jesusBuffer.getOrDefault(uuid, 0);
                                    if (vl > 0) jesusBuffer.put(uuid, vl - 1);
                                    return;
                                }

                                int vl = jesusBuffer.getOrDefault(uuid, 0);

                                // LOGICA PRINCIPALĂ
                                if (deltaY == 0.0) {
                                    vl += 2; // Hover perfect plat pe apă (Solid Jesus)
                                } else if (deltaY > 0.0 && deltaY <= MAX_BOUNCE_Y) {
                                    vl += 1; // Dolphin/Bounce micro-salturi
                                } else {
                                    if (vl > 0) vl--; // Scufundare legitimă
                                }

                                jesusBuffer.put(uuid, vl);

                                if (vl > MAX_VIOLATIONS) {
                                    flagPlayer.addFlag(player, "Jesus", "Unnatural vertical stability in liquid (Y-Speed: " + String.format("%.3f", deltaY) + ")");

                                    // Rubber-Band Subacvatic: Îl teleportăm înapoi și îl tragem în jos intenționat 0.5 blocuri
                                    Location pullDownLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1] - 0.5, safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());
                                    player.teleport(pullDownLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                    // Resetăm parțial buffer-ul
                                    jesusBuffer.put(uuid, 2);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

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
     * Verificăm dacă blocul face parte din noua mecanică acvatică.
     */
    private boolean isLiquidAt(Location loc) {
        Material type = loc.getBlock().getType();
        return type == Material.WATER || type == Material.LAVA ||
                type.name().contains("KELP") || type.name().contains("SEAGRASS") ||
                type.name().contains("BUBBLE_COLUMN");
    }

    /**
     * Verificare volumetrică pentru margini, bărci și blocuri waterlogged.
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

                    // Protecție 1.13+: Blocul e în apă, dar este o scară/slab solid (Waterlogged)
                    if (b.getBlockData() instanceof org.bukkit.block.data.Waterlogged) {
                        if (type.isSolid()) return true;
                    }
                }
            }
        }
        return false;
    }
}