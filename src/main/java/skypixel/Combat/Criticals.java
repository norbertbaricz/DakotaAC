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
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Criticals implements Listener {

    // =========================================================
    // --- Easy-to-tune thresholds ---
    // =========================================================

    // În câte tick-uri maxime de la despărțirea de sol verificăm salturile false
    private static final int MICRO_JUMP_MAX_TICKS = 2;

    // Înălțimea maximă considerată "imposibilă" pentru primele tick-uri de salt
    private static final double MICRO_JUMP_MAX_HEIGHT = 0.1D;

    // Semnături cunoscute de hack-uri (Offsets). Wurst, Meteor, Impact, etc.
    // Dacă descoperi o valoare nouă de bypass, pur și simplu adaug-o aici!
    private static final double[] KNOWN_CHEAT_OFFSETS = {0.0625D, 0.015625D, 0.11D};

    // Toleranța matematică pentru a preveni bug-urile de virgulă mobilă (nu schimba decât dacă e necesar)
    private static final double MATH_TOLERANCE = 0.0001D;

    // =========================================================

    // Stocăm datele fizice 100% asincron pentru fiecare jucător
    private final ConcurrentHashMap<UUID, Double> lastYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> deltaYMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> onGroundMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> airTicksMap = new ConcurrentHashMap<>();

    public Criticals() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getInstance(),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.LOOK) { // <-- AICI AM ȘTERS PACKET-UL FLYING

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

                                Double pY = lastYMap.get(uuid);
                                Double dY = deltaYMap.get(uuid);
                                Boolean og = onGroundMap.get(uuid);
                                Integer ticks = airTicksMap.get(uuid);

                                if (pY == null || dY == null || og == null || ticks == null) return;

                                // Jucătorii de pe pământ nu dau critice. Hack-urile ocolesc asta spunând 'onGround = false'
                                if (og) return;

                                boolean isFlagged = false;
                                String flagReason = "";

                                // LOGICA 1: Zero Velocity (Ground Spoof)
                                // Hackerul minte că e în aer (onGround=false), dar Y-ul lui este complet static.
                                if (dY == 0.0 && ticks > 0) {
                                    isFlagged = true;
                                    flagReason = "Attacked with zero vertical velocity (Ground Spoof).";
                                }

                                // LOGICA 2: Impossible Jump Height (Packet Critical)
                                // Un salt vanilla începe cu +0.42. Verificăm micro-salturile imposibile uman.
                                else if (!isFlagged && ticks <= MICRO_JUMP_MAX_TICKS && dY > 0.0 && dY < MICRO_JUMP_MAX_HEIGHT) {
                                    isFlagged = true;
                                    flagReason = "Impossible micro-jump detected (Packet Math: " + String.format("%.4f", dY) + ").";
                                }

                                // LOGICA 3: Known Hack Offsets
                                // Verificăm lista noastră ușor configurabilă de valori "magice" folosite de clienții codați.
                                else if (!isFlagged) {
                                    for (double offset : KNOWN_CHEAT_OFFSETS) {
                                        if (Math.abs(dY - offset) < MATH_TOLERANCE) {
                                            isFlagged = true;
                                            flagReason = "Known packet critical signature detected (Offset: " + dY + ").";
                                            break;
                                        }
                                    }
                                }

                                // EXECUTAREA PEDEPSEI
                                if (isFlagged) {
                                    // Anulăm pachetul ASINCRON. Lovitura nici măcar nu ajunge la server, deci playerul legit nu ia damage!
                                    event.setCancelled(true);

                                    final String finalReason = flagReason;

                                    // Trimitem flag-ul în siguranță către Main Thread pentru broadcast
                                    Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> {
                                        if (player.isOnline() && !player.isDead()) {

                                            // Filtre de siguranță Vanilla (Bypass-uri legale)
                                            // Excludem apa, bărcile, elytrele, lianele (care pot afecta gravitația).
                                            if (player.isInWater() || player.getVehicle() != null || player.isGliding() || player.getAllowFlight() || player.isClimbing()) {
                                                return;
                                            }

                                            flagPlayer.addFlag(player, "Criticals", finalReason);
                                        }
                                    });
                                }

                                // ==========================================
                                // 2. ACTUALIZAREA TRACKER-ULUI (Mișcare)
                                // ==========================================
                            } else {
                                // Toate pachetele de zbor (POSITION/POSITION_LOOK/LOOK) conțin starea "onGround" pe poziția 0
                                boolean onGround = event.getPacket().getBooleans().readSafely(0);
                                onGroundMap.put(uuid, onGround);

                                // Calculăm "Air Ticks" (câte pachete a stat în aer)
                                if (onGround) {
                                    airTicksMap.put(uuid, 0);
                                } else {
                                    airTicksMap.put(uuid, airTicksMap.getOrDefault(uuid, 0) + 1);
                                }

                                // Doar pachetele cu locație au axa Y. Calculăm viteza verticală curentă.
                                if (type == PacketType.Play.Client.POSITION || type == PacketType.Play.Client.POSITION_LOOK) {
                                    double y = event.getPacket().getDoubles().readSafely(1);
                                    Double prevY = lastYMap.get(uuid);

                                    if (prevY != null) {
                                        deltaYMap.put(uuid, y - prevY);
                                    }
                                    lastYMap.put(uuid, y);
                                }
                            }

                        } catch (Exception ex) {
                            // Previne erorile în consolă dacă un alt plugin strică structura pachetului
                        }
                    }
                }
        );
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenirea memory leaks-urilor prin ștergerea hărților la ieșire
        UUID uuid = event.getPlayer().getUniqueId();
        lastYMap.remove(uuid);
        deltaYMap.remove(uuid);
        onGroundMap.remove(uuid);
        airTicksMap.remove(uuid);
    }
}