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

public class NoFall implements Listener {

    // Stocăm coordonatele asincron pentru citire stabilă pe ProtocolLib
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public NoFall() {
        // Interceptăm coordonatele la secunda în care sunt trimise de jucător
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("NoFall")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele noi
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            // EXTRAGEREA CHEIE: Variabila onGround trimisă de client
                            boolean onGround = event.getPacket().getBooleans().readSafely(0);

                            double[] fromPos = lastPosMap.get(uuid);

                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];

                            // Actualizăm memoria pentru următorul pachet
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // 1. Optimizare extremă pe thread-ul de rețea:
                            // Dacă jucătorul urcă (deltaY >= 0) sau raportează corect că este în aer (onGround == false),
                            // pachetul este complet curat. Oprim procesarea aici!
                            if (deltaY >= 0.0 || !onGround) {
                                return;
                            }

                            // Avem un pachet care spune "onGround = true", deși jucătorul este în cădere liberă (deltaY < 0).
                            // Delegăm verificările fizice către Main Thread.
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                // 2. Excepții Vanilla (Zbor, Elytra, Vehicule)
                                if (player.getAllowFlight() || player.isGliding() || player.isInsideVehicle() || player.isSwimming()) {
                                    return;
                                }

                                // 3. Verificarea mediului (Apă, Lavă, Pânze)
                                Material currentBlock = player.getLocation().getBlock().getType();
                                if (currentBlock == Material.WATER || currentBlock == Material.LAVA ||
                                        currentBlock == Material.COBWEB || currentBlock == Material.LADDER ||
                                        currentBlock == Material.VINE) {
                                    return;
                                }

                                // 4. Nucleul Logicii NoFall
                                // Un jucător legitim care aterizează pe un bloc va avea deltaY negativ și onGround=true.
                                // Verificăm fizic dacă există un bloc real care să justifice acest onGround.
                                if (deltaY < -0.1) {
                                    if (!hasSolidBlockNear(toLoc)) {

                                        flagPlayer.addFlag(player, "NoFall", "Spoofed ground status mid-air (dY: " + String.format("%.4f", deltaY) + ")");

                                        // Pedeapsă: Putem forța un anumit damage pentru a anula beneficiul hack-ului
                                        // double fallDamage = (Math.abs(deltaY) * 10.0);
                                        // player.damage(fallDamage);
                                    }
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
     * Sincronizăm teleportările serverului pentru a preveni alertele false (False Positives).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim scurgerile de memorie
        lastPosMap.remove(event.getPlayer().getUniqueId());
    }

    /**
     * Scanare volumetrică optimizată pentru a preveni flag-urile false când
     * jucătorul alunecă de pe marginea unui slab sau a unei scări.
     */
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