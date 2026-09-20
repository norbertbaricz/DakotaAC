package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Criticals implements Listener {

    // Memorie precisă asincronă pentru fiecare jucător
    private final ConcurrentHashMap<UUID, Double> lastYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> deltaYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> lastDeltaYMap = new ConcurrentHashMap<>(); // Urmărim curba precedentă a săriturii
    private final ConcurrentHashMap<UUID, Boolean> onGroundMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> airTicksMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> lastGroundTimeMap = new ConcurrentHashMap<>(); // Pentru a prinde Timer / Blink

    public Criticals() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getInstance(),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Criticals")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();
                            PacketType type = event.getPacketType();

                            // ==========================================
                            // 1. ANALIZA ATACULUI (USE_ENTITY)
                            // ==========================================
                            if (type == PacketType.Play.Client.USE_ENTITY) {
                                EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().readSafely(0).getAction();
                                if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                                Double dY = deltaYMap.get(uuid);
                                Double lastDY = lastDeltaYMap.get(uuid);
                                Boolean og = onGroundMap.get(uuid);
                                Integer ticks = airTicksMap.get(uuid);
                                Long lastGroundTime = lastGroundTimeMap.get(uuid);

                                // Dacă nu avem destule date încă, îl lăsăm (previne erori la conectare)
                                if (dY == null || og == null || ticks == null || lastGroundTime == null) return;

                                // Dacă e pe pământ, nu poate da critice Vanilla, deci îl ignorăm
                                if (og) return;

                                boolean isFlagged = false;
                                String flagReason = "";

                                long timeInAir = System.currentTimeMillis() - lastGroundTime;

                                // --- LOGICA SUPREMĂ PENTRU LIQUIDBOUNCE ---

                                // 1. Ground Spoof / NoGround Mode (Zboară dar stă pe loc vertical)
                                if (Math.abs(dY) < 0.0001 && ticks > 0) {
                                    isFlagged = true;
                                    flagReason = "Zero vertical velocity mid-air (NoGround).";
                                }
                                // 2. Packet Mode (Săritură ireală din pachete. Saltul Vanilla pur are minim +0.41)
                                else if (dY > 0.0 && dY < 0.41 && ticks <= 2) {
                                    isFlagged = true;
                                    flagReason = "Impossible micro-jump detected (Y: " + String.format("%.4f", dY) + ").";
                                }
                                // 3. Blink / Timer Mode (Trimit toate pachetele instantaneu)
                                // Jucătorul "cade" spre inamic (dY < 0), dar el a părăsit pământul abia de 50 milisecunde!
                                else if (dY < 0.0 && lastDY != null && lastDY > 0.0 && timeInAir < 50) {
                                    isFlagged = true;
                                    flagReason = "Packet Burst (Blink/Timer) - Jump cycle completed in " + timeInAir + "ms.";
                                }

                                // EXECUTAREA PEDEPSEI
                                if (isFlagged) {
                                    // Anulăm pachetul asincron. Atacul fals nu face deloc damage!
                                    event.setCancelled(true);
                                    final String finalReason = flagReason;

                                    Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> {
                                        if (player.isOnline() && !player.isDead()) {
                                            // Filtre Vanilla (Apă, Elytra, Scări, Poțiuni)
                                            if (player.isInWater() || player.getVehicle() != null || player.isGliding() ||
                                                    player.getAllowFlight() || player.isClimbing() ||
                                                    player.hasPotionEffect(PotionEffectType.JUMP_BOOST) ||
                                                    player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                                return;
                                            }

                                            // Previne alarmele false dacă este în pânze de păianjen
                                            if (player.getLocation().getBlock().getType().name().contains("COBWEB")) {
                                                return;
                                            }

                                            flagPlayer.addFlag(player, "Criticals", finalReason);
                                        }
                                    });
                                }
                            }
                            // ==========================================
                            // 2. ACTUALIZAREA TRACKER-ULUI GEOMETRIC
                            // ==========================================
                            else {
                                boolean onGround = event.getPacket().getBooleans().readSafely(0);
                                onGroundMap.put(uuid, onGround);

                                if (onGround) {
                                    airTicksMap.put(uuid, 0);
                                    lastGroundTimeMap.put(uuid, System.currentTimeMillis());
                                } else {
                                    airTicksMap.put(uuid, airTicksMap.getOrDefault(uuid, 0) + 1);
                                }

                                if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.POSITION_LOOK) {
                                    double y = event.getPacket().getDoubles().readSafely(1);
                                    Double prevY = lastYMap.get(uuid);

                                    if (prevY != null) {
                                        double currentDelta = y - prevY;
                                        Double prevDelta = deltaYMap.getOrDefault(uuid, 0.0);

                                        // Salvăm cu un pas în spate pentru a analiza curba săriturii
                                        lastDeltaYMap.put(uuid, prevDelta);
                                        deltaYMap.put(uuid, currentDelta);
                                    }
                                    lastYMap.put(uuid, y);
                                }
                            }

                        } catch (Exception ex) {
                            // Ignorăm silențios pachetele corupte trimise intenționat de "Crash Exploits"
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastYMap.remove(uuid);
        deltaYMap.remove(uuid);
        lastDeltaYMap.remove(uuid);
        onGroundMap.remove(uuid);
        airTicksMap.remove(uuid);
        lastGroundTimeMap.remove(uuid);
    }
}