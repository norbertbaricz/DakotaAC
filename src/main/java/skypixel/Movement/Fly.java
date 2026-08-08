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
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Fly implements Listener {

    // Stocăm datele asincron pentru citire stabilă pe ProtocolLib
    private final ConcurrentHashMap<UUID, Integer> airTicks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Fly() {
        // Interceptăm coordonatele la secunda în care sunt trimise de mouse-ul/tastatura jucătorului
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fly")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem locația nouă din pachet
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

                            // 0. Extreme Optimization: Dacă doar a mișcat capul (Yaw/Pitch), ignorăm pachetul
                            if (deltaX == 0.0 && deltaY == 0.0 && deltaZ == 0.0) {
                                return;
                            }

                            // Actualizăm memoria asincronă cu noua poziție
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // --- TRIMITEM VERIFICAREA CĂTRE MAIN THREAD PENTRU A CITI BLOCURILE ---
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch());

                                // 1. Verificăm excepțiile legitime de zbor/mișcare
                                if (player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                if (player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                Material currentBlock = player.getLocation().getBlock().getType();
                                if (currentBlock == Material.WATER || currentBlock == Material.LAVA ||
                                        currentBlock == Material.LADDER || currentBlock == Material.VINE) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                // 2. Verificăm blocurile solide de sub și de lângă jucător
                                if (hasSolidBlockNear(toLoc)) {
                                    resetTicksAndSafeLocation(uuid, toLoc);
                                    return;
                                }

                                // 3. Verificarea gravității naturale
                                if (deltaY < 0.0) {
                                    // Jucătorul cade. Scădem airTicks sau le resetăm, dar nu dăm flag aici.
                                    airTicks.put(uuid, 0);
                                    return;
                                }

                                // 4. Logica "Jump Buffer" (Fly Check)
                                // Jucătorul este în aer, nu cade (deltaY >= 0) și nu are niciun bloc sub el.
                                int currentAirTicks = airTicks.getOrDefault(uuid, 0) + 1;
                                airTicks.put(uuid, currentAirTicks);

                                // Un salt normal (Jump) generează deltaY pozitiv timp de 5-6 tick-uri.
                                // La 10 tick-uri (jumătate de secundă de urcat/plutit constant), e clar Fly hack.
                                if (currentAirTicks > 10) {
                                    String flyType = (deltaY == 0.0) ? "Hover/AirWalk" : "Ascension/Fly";
                                    flagPlayer.addFlag(player, "Fly (" + flyType + ")", "In air for " + currentAirTicks + " ticks (dY: " + String.format("%.4f", deltaY) + ")");

                                    // Rubber-Band: Îl tragem înapoi la ultima locație pe care a atins-o legitim
                                    Location safe = lastSafeLocation.getOrDefault(uuid, player.getLocation());
                                    player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                    airTicks.put(uuid, 0);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    private void resetTicksAndSafeLocation(UUID uuid, Location loc) {
        airTicks.put(uuid, 0);
        lastSafeLocation.put(uuid, loc);
    }

    /**
     * Sincronizăm teleportările serverului pentru a preveni alertele false (False Positives).
     * Dacă un admin dă TP unui jucător, locația sigură trebuie resetată imediat.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            resetTicksAndSafeLocation(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        airTicks.remove(uuid);
        lastSafeLocation.remove(uuid);
        lastPosMap.remove(uuid);
    }

    private boolean hasSolidBlockNear(Location loc) {
        Block blockDirectlyBelow = loc.clone().subtract(0, 0.1, 0).getBlock();
        if (blockDirectlyBelow.getType().isSolid() || blockDirectlyBelow.isLiquid()) {
            return true;
        }

        double expand = 0.3;
        for (double x = -expand; x <= expand; x += expand) {
            for (double z = -expand; z <= expand; z += expand) {
                for (double y = 0.1; y <= 1.5; y += 0.5) {
                    Block block = loc.clone().add(x, -y, z).getBlock();
                    if (block.getType().isSolid() || block.isLiquid()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}