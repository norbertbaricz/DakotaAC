package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Sneak implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Viteza de bază pentru furișare (Sneak) în Vanilla este de aprox. 0.13.
    // Folosim 0.145 pentru a lăsa o marjă minusculă de lag client-side.
    private static final double MAX_SNEAK_SPEED_BASE = 0.145;

    // Cât adaugă fiecare nivel de licoare de Speed (Viteză)
    private static final double SPEED_POTION_MULTIPLIER = 0.045;

    // Cât adaugă fiecare nivel al enchantment-ului Swift Sneak (Furișare Rapidă)
    private static final double SWIFT_SNEAK_MULTIPLIER = 0.035;

    // Câte încălcări sunt necesare pentru a trage jucătorul înapoi?
    private static final int MAX_VIOLATIONS = 3;
    // ==========================================

    // Utilizăm ConcurrentHashMap pentru a garanta stabilitatea memoriei asincrone Netty
    private final ConcurrentHashMap<UUID, Integer> sneakBuffer = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportImmunity = new ConcurrentHashMap<>();

    public Sneak() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Sneak")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele direct din zbor
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            double[] fromPos = lastPosMap.get(uuid);

                            // La prima conectare/mișcare, doar salvăm datele
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            // 1. Verificare Imunitate (Teleport/Respawn)
                            if (teleportImmunity.containsKey(uuid) && teleportImmunity.get(uuid) > System.currentTimeMillis()) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaX = toX - fromPos[0];
                            double deltaY = toY - fromPos[1];
                            double deltaZ = toZ - fromPos[2];

                            // Calculăm viteza orizontală pură
                            double deltaXZ = Math.hypot(deltaX, deltaZ);

                            // Stocăm valorile anterioare ca variabile imuabile pentru thread-ul principal
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm matricea pentru pachetul imediat următor
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // 2. Optimizare Extremă Netty
                            // Dacă jucătorul stă pe loc sau e în cădere, ignorăm pachetul.
                            if (deltaXZ < 0.01 || deltaY < 0.0) {
                                return;
                            }

                            // 3. Delegăm verificările logice către Bukkit (Main Thread)
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                // Verificăm starea de Sneak
                                if (!player.isSneaking()) {
                                    // Scădem buffer-ul dacă merge normal, pentru iertare
                                    int vl = sneakBuffer.getOrDefault(uuid, 0);
                                    if (vl > 0) sneakBuffer.put(uuid, vl - 1);
                                    return;
                                }

                                // Excepții legitime Vanilla (Zbor, Zbor cu Elytra, Vehicule)
                                if (player.isInsideVehicle() || player.isGliding() || player.getAllowFlight()) {
                                    return;
                                }

                                // Protecție Knockback (A fost lovit recent sau a luat damage)
                                if (player.getNoDamageTicks() > 10 || player.getVelocity().lengthSquared() > 0.05) {
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // Protecție pentru gheață și slime
                                Material blockUnder = fromLoc.clone().subtract(0, 0.1, 0).getBlock().getType();
                                if (blockUnder.name().contains("ICE") || blockUnder == Material.SLIME_BLOCK) {
                                    return;
                                }

                                // LOGICA DE BAZĂ (Matematica Vitezei Drepte)
                                double maxSneakSpeed = MAX_SNEAK_SPEED_BASE;

                                // Modificator Licoare de Viteză
                                if (player.hasPotionEffect(PotionEffectType.SPEED)) {
                                    int amplifier = player.getPotionEffect(PotionEffectType.SPEED).getAmplifier();
                                    maxSneakSpeed += SPEED_POTION_MULTIPLIER * (amplifier + 1);
                                }

                                // Modificator Swift Sneak (Furișare Rapidă din 1.19+)
                                ItemStack leggings = player.getInventory().getLeggings();
                                if (leggings != null && leggings.containsEnchantment(Enchantment.SWIFT_SNEAK)) {
                                    int swiftSneakLevel = leggings.getEnchantmentLevel(Enchantment.SWIFT_SNEAK);
                                    maxSneakSpeed += SWIFT_SNEAK_MULTIPLIER * swiftSneakLevel;
                                }

                                int vl = sneakBuffer.getOrDefault(uuid, 0);

                                if (deltaXZ > maxSneakSpeed) {
                                    vl++;
                                    sneakBuffer.put(uuid, vl);

                                    if (vl >= MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Sneak", "Moving too fast while sneaking (Speed: " + String.format("%.3f", deltaXZ) + " | Max: " + String.format("%.3f", maxSneakSpeed) + ")");

                                        // Oferim imunitate ca să nu dăm trigger la alte flag-uri din cauza Rubber-Band-ului
                                        teleportImmunity.put(uuid, System.currentTimeMillis() + 1000L);

                                        // Rubber-Band: Tragem jucătorul înapoi fizic
                                        player.teleport(fromLoc, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        // Pedeapsă: Oprim forțat starea de sneak pentru a sparge loop-ul hack-ului
                                        player.setSneaking(false);

                                        sneakBuffer.put(uuid, 1); // Resetăm parțial pentru a evita spam-ul
                                    }
                                } else {
                                    if (vl > 0) sneakBuffer.put(uuid, vl - 1);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // PROTECȚII LA ALARME FALSE (TELEPORT / RESPAWN)
    // ========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
            teleportImmunity.put(uuid, System.currentTimeMillis() + 1000L);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        teleportImmunity.put(uuid, System.currentTimeMillis() + 3000L);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        teleportImmunity.put(uuid, System.currentTimeMillis() + 1500L);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim memory leaks
        UUID uuid = event.getPlayer().getUniqueId();
        sneakBuffer.remove(uuid);
        lastPosMap.remove(uuid);
        teleportImmunity.remove(uuid);
    }
}