package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Timer implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    private static final long PACKET_COST_MS = 50L; // Cât "costă" un pachet (50ms = 20 TPS)
    private static final long MAX_LAG_BUFFER = 150L; // Cât lag permitem jucătorului să recupereze (3 pachete)
    private static final long FLAG_THRESHOLD = -250L; // Cât de mult poate pica sub zero înainte de flag (5 pachete trimise prea repede)

    private static final int MAX_VIOLATIONS = 3; // Câte alarme așteptăm înainte să îl tragem înapoi vizual
    // ==========================================

    private final ConcurrentHashMap<UUID, Long> lastPacketTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> packetBalance = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> violations = new ConcurrentHashMap<>();

    public Timer() {
        // Ascultăm absolut toată familia de pachete de mișcare (Flying)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.FLYING,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK,
                        PacketType.Play.Client.LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Timer")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            long now = System.currentTimeMillis();
                            long lastTime = lastPacketTime.getOrDefault(uuid, now);
                            long diff = now - lastTime;

                            lastPacketTime.put(uuid, now);

                            // Dacă diferența este prea mare (ex: un lag spike masiv sau a schimbat dimensiunea), ignorăm pachetul și resetăm
                            if (diff > 2000L) {
                                packetBalance.put(uuid, 0L);
                                return;
                            }

                            // 1. CALCULUL BALANȚEI
                            long balance = packetBalance.getOrDefault(uuid, 0L);
                            balance += diff; // Adăugăm timpul real care a trecut de la ultimul pachet
                            balance -= PACKET_COST_MS; // Scădem prețul pachetului actual

                            // 2. TĂIEREA LAG-ULUI ACUMULAT
                            // Dacă a lagat și a acumulat prea mult "timp", îl tăiem.
                            // Hack-urile avansate creează lag fals tocmai ca să facă Timer pe banii acumulați.
                            if (balance > MAX_LAG_BUFFER) {
                                balance = MAX_LAG_BUFFER;
                            }

                            // 3. DETECȚIA SUPREMĂ (TIMER HACK)
                            // A cheltuit mai mult timp decât a produs?
                            if (balance < FLAG_THRESHOLD) {

                                // Anulăm pachetul! Literalmente anulăm trecerea timpului pentru el!
                                event.setCancelled(true);

                                // Nu îl lăsăm să scadă la infinit în minus
                                balance = FLAG_THRESHOLD;

                                int vl = violations.getOrDefault(uuid, 0) + 1;
                                violations.put(uuid, vl);

                                if (vl > MAX_VIOLATIONS) {
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline() && !player.isDead()) {
                                            flagPlayer.addFlag(player, "Timer", "Sent packets too fast (Game speed artificially increased).");
                                        }
                                    });
                                    // Resetăm VL-ul ca să nu facem spam infinit
                                    violations.put(uuid, 0);
                                }
                            } else {
                                // Scădem suspiciunea dacă s-a potolit și se joacă legitim
                                int vl = violations.getOrDefault(uuid, 0);
                                if (vl > 0 && balance > (FLAG_THRESHOLD / 2)) {
                                    violations.put(uuid, vl - 1);
                                }
                            }

                            packetBalance.put(uuid, balance);

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // PREVENIREA ALARMELOR FALSE (TELEPORT / RESPAWN)
    // ========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        resetPlayer(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        lastPacketTime.remove(uuid);
        packetBalance.remove(uuid);
        violations.remove(uuid);
    }

    private void resetPlayer(UUID uuid) {
        lastPacketTime.put(uuid, System.currentTimeMillis());
        packetBalance.put(uuid, 0L);
        violations.remove(uuid);
    }
}