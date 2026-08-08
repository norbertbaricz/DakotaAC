package skypixel.Combat;

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
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Velocity implements Listener {

    // Folosim ConcurrentHashMap pentru siguranță asincronă (Thread-Safe)
    private final ConcurrentHashMap<UUID, Long> expectedVelocity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Double> lastYMap = new ConcurrentHashMap<>();

    public Velocity() {
        // Interceptăm DOAR pachetele în care jucătorul își actualizează poziția
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Velocity")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Dacă nu așteptăm niciun knockback de la el, ieșim (optimizare CPU)
                            if (!expectedVelocity.containsKey(uuid)) return;

                            // În ProtocolLib, pentru pachetele POSITION și POSITION_LOOK,
                            // X este la indexul 0, Y la indexul 1, Z la indexul 2.
                            double currentY = event.getPacket().getDoubles().readSafely(1);
                            double previousY = lastYMap.getOrDefault(uuid, currentY);

                            double deltaY = currentY - previousY;

                            // LOGICĂ: A reacționat la knockback?
                            // Dacă s-a ridicat măcar puțin (deltaY > 0.0), a luat knockback-ul curat.
                            if (deltaY > 0.0) {
                                expectedVelocity.remove(uuid);
                            } else {
                                // Dacă nu s-a ridicat, verificăm dacă a expirat timpul de grație
                                long now = System.currentTimeMillis();
                                long timeSinceHit = now - expectedVelocity.get(uuid);

                                // 500ms reprezintă un ping maxim acceptabil de ~250ms (dus-întors) + lag de procesare.
                                if (timeSinceHit > 500) {

                                    // Deoarece pachetul este asincron, e recomandat să trimitem flag-ul pe thread-ul principal
                                    Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                        if (player.isOnline() && !player.isDead()) {
                                            flagPlayer.addFlag(player, "Velocity / AntiKB", "Ignored vertical knockback completely.");
                                        }
                                    });

                                    // Îl ștergem din așteptare ca să nu primească spam de alerte
                                    expectedVelocity.remove(uuid);
                                }
                            }

                            // Actualizăm ultimul Y pentru următorul pachet
                            lastYMap.put(uuid, currentY);

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // --- EVENIMENTE BUKKIT (Aici se fac verificările fizice sigure pentru hărți) ---

    @EventHandler
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!dakotaAC.isCheckActive("Velocity")) return;

        Player player = event.getPlayer();

        if (player.getGameMode() == GameMode.CREATIVE || player.isDead()) return;

        double velocityY = event.getVelocity().getY();

        // Dacă serverul i-a aplicat un knockback vertical relevant (> 0.1)
        if (velocityY > 0.1) {
            // Verificăm dacă locația îi permite să ia knockback legit (Fără să folosim CPU pe thread asincron)
            if (!canTakeKnockback(player)) return;

            // Pornim cronometrul pe rețea
            expectedVelocity.put(player.getUniqueId(), System.currentTimeMillis());

            // Sincronizăm Y-ul curent pentru acuratețe maximă în PacketListener
            lastYMap.put(player.getUniqueId(), player.getLocation().getY());
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        expectedVelocity.remove(uuid);
        lastYMap.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        expectedVelocity.remove(uuid);
        lastYMap.remove(uuid);
    }

    /**
     * Verifică dacă mediul fizic anulează knockback-ul în mod legitim (Vanilla mechanics).
     */
    private boolean canTakeKnockback(Player player) {
        Location loc = player.getLocation();
        Material currentBlock = loc.getBlock().getType();

        // Apa, lava și pânza opresc knockback-ul
        if (currentBlock == Material.WATER || currentBlock == Material.LAVA || currentBlock == Material.COBWEB) {
            return false;
        }

        // Verificăm dacă are un bloc solid fix deasupra capului (Tavan)
        Block blockAbove = loc.clone().add(0, 2.0, 0).getBlock();
        if (blockAbove.getType().isSolid()) {
            return false;
        }

        return true;
    }
}