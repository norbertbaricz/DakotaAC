package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Speed implements Listener {

    // Colecții concurente pentru siguranță asincronă (Thread-Safe)
    private final ConcurrentHashMap<UUID, Double> violations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();

    // NOU: Memorie pentru imunitatea la respawn/tp
    private final ConcurrentHashMap<UUID, Long> teleportGrace = new ConcurrentHashMap<>();

    public Speed() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Speed")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele noi din pachet
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // Înregistrăm coordonatele la prima mișcare și ieșim
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaZ = toZ - fromPos[2];

                            // Calculăm viteza pe orizontală matematic
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // FIX 1: Desync / Teleport Filter
                            // Niciun hack de viteză nu depășește 10 blocuri într-o milisecundă.
                            // Dacă distanța e uriașă, este clar un "Ghost Packet" de la un Respawn instant.
                            // Orice salt nepermis de această magnitudine este prins de modulul "Teleport", nu de "Speed".
                            if (deltaXZ > 10.0) {
                                return;
                            }

                            // Salvăm locația veche într-o constantă pentru acces sigur pe Main Thread
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm memoria asincronă cu noua poziție
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // Optimizare extremă Netty: ignorăm pachetele unde jucătorul stă pe loc orizontal
                            if (deltaXZ == 0.0) {
                                return;
                            }

                            // Trimitem datele către Bukkit pentru verificarea mediului
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // FIX 2: Perioada de grație pentru TP / Immediate Respawn
                                long lastTeleport = teleportGrace.getOrDefault(uuid, 0L);
                                if (System.currentTimeMillis() - lastTeleport < 1000) {
                                    violations.put(uuid, 0.0); // Îi ștergem suspiciunile
                                    lastSafeLocation.put(uuid, new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch()));
                                    return;
                                }

                                // Excepții legitime Vanilla (zbor, apă, vehicule, elytra)
                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isSwimming() || player.isGliding()) {
                                    lastSafeLocation.put(uuid, player.getLocation());
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // Viteza de bază Vanilla (~0.28 blocuri per tick la sprint)
                                double maxSpeed = 0.28;

                                // Adăugăm multiplicatorii de poțiuni
                                for (PotionEffect effect : player.getActivePotionEffects()) {
                                    if (effect.getType().equals(PotionEffectType.SPEED)) {
                                        maxSpeed *= (1.0 + (0.2 * (effect.getAmplifier() + 1)));
                                    }
                                }

                                // Marjă de toleranță pentru lag / impuls
                                double threshold = maxSpeed * 1.15;

                                // --- FIX PENTRU GHEAȚĂ ȘI TAVAN ---
                                boolean isOnIce = isBlockIce(fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType()) ||
                                        isBlockIce(fromLoc.clone().subtract(0, 1.0, 0).getBlock().getType());

                                boolean hasLowCeiling = !fromLoc.clone().add(0, 2.0, 0).getBlock().getType().isAir();

                                if (isOnIce && hasLowCeiling) {
                                    threshold = 1.3;
                                } else if (isOnIce) {
                                    threshold = 0.65;
                                }

                                // --- LOGICA DE BAZĂ (Flag) ---
                                if (deltaXZ > threshold) {
                                    double vl = violations.getOrDefault(uuid, 0.0) + (deltaXZ - threshold);
                                    violations.put(uuid, vl);

                                    if (vl > 2.0) {
                                        flagPlayer.addFlag(player, "Speed", "Sustained high speed (Speed: " + String.format("%.2f", deltaXZ) + ")");

                                        // Rubber-Band: Îl teleportăm înapoi la ultima locație stabilă
                                        Location safe = lastSafeLocation.getOrDefault(uuid, fromLoc);
                                        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        violations.put(uuid, 1.0); // Resetăm parțial pentru a nu face spam
                                    }
                                } else {
                                    // Dacă joacă curat, reducem nivelul de suspiciune treptat și actualizăm locația sigură
                                    double vl = violations.getOrDefault(uuid, 0.0);
                                    if (vl > 0) {
                                        violations.put(uuid, Math.max(0, vl - 0.1));
                                    }
                                    lastSafeLocation.put(uuid, new Location(player.getWorld(), toX, toY, toZ, player.getLocation().getYaw(), player.getLocation().getPitch()));
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
     * Sincronizare vitală pentru prevenirea Rubber-Band-ului fals la /tp.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            teleportGrace.put(uuid, System.currentTimeMillis()); // 1 secundă de grație
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    /**
     * Sincronizare pentru prevenirea flag-urilor false la reînvierea jucătorului.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        teleportGrace.put(uuid, System.currentTimeMillis()); // 1 secundă de grație

        // Actualizăm memoria cu locația de la spawn pentru a tăia calculul distanței de la deces
        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});

        // Curățăm complet istoricul de încălcări pentru a începe viața nouă "pe curat"
        violations.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Curățarea memoriei la deconectare
        UUID uuid = event.getPlayer().getUniqueId();
        violations.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
    }

    /**
     * Funcție ajutătoare pentru a verifica toate tipurile de gheață.
     */
    private boolean isBlockIce(Material material) {
        return material == Material.ICE ||
                material == Material.PACKED_ICE ||
                material == Material.BLUE_ICE ||
                material == Material.FROSTED_ICE;
    }
}