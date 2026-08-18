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
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Velocity implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Cât la sută din mișcarea verticală trebuie să respecte? (Ex: 0.35 = 35%)
    // Oprește modulele: Modify, Reduce, Grim/Hypixel bypasses.
    private static final double MIN_VERTICAL_RATIO = 0.35;

    // Cât la sută din mișcarea orizontală trebuie să respecte?
    // Este mai mic pentru că mecanici legit precum W-Tap, Sprint Reset sau JumpReset reduc orizontala.
    private static final double MIN_HORIZONTAL_RATIO = 0.15;

    // Timpul maxim în care așteptăm ca jucătorul să reacționeze la lovitură (Ping maxim admis + Lag)
    private static final long MAX_PING_DELAY_MS = 600;
    // ==========================================

    // Creăm un obiect intern pentru a stoca toate datele necesare unei lovituri
    private static class ExpectedKnockback {
        Vector expectedVelocity;
        long timestamp;

        ExpectedKnockback(Vector v, long t) {
            this.expectedVelocity = v;
            this.timestamp = t;
        }
    }

    private final ConcurrentHashMap<UUID, ExpectedKnockback> expectedVelocityMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();

    public Velocity() {
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
                            if (player == null || !player.isOnline()) return;
                            UUID uuid = player.getUniqueId();

                            ExpectedKnockback kbData = expectedVelocityMap.get(uuid);
                            if (kbData == null) return;

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

                            long timeSinceHit = System.currentTimeMillis() - kbData.timestamp;

                            // LOGICA SUPREMĂ: Verificăm procentajul de mișcare
                            double expectedY = kbData.expectedVelocity.getY();
                            double expectedHorizontal = Math.hypot(kbData.expectedVelocity.getX(), kbData.expectedVelocity.getZ());
                            double actualHorizontal = Math.hypot(deltaX, deltaZ);

                            // Jucătorul a trimis o mișcare în sus. Verificăm dacă e un fake (Modify/Reduce)
                            if (deltaY > 0.0) {

                                // JumpReset Bypass fix: Dacă sare, deltaY va fi în jur de 0.42 (Vanilla Jump).
                                // Îl iertăm dacă a sărit legitim, altfel verificăm procentul.
                                boolean isJumpReset = deltaY >= 0.4;

                                if (!isJumpReset && deltaY < (expectedY * MIN_VERTICAL_RATIO)) {
                                    flagSync(player, "Velocity (Vertical Reduce)", "Took only " + String.format("%.1f", (deltaY/expectedY)*100) + "% vertical KB");
                                }

                                // Strafe / Reduce Bypass fix: Verificăm dacă măcar încearcă să fie împins orizontal
                                else if (expectedHorizontal > 0.1 && actualHorizontal < (expectedHorizontal * MIN_HORIZONTAL_RATIO)) {
                                    // Dacă nu a făcut JumpReset (care taie orizontala grav), și totuși nu se mișcă orizontal
                                    if (!isJumpReset) {
                                        flagSync(player, "Velocity (Horizontal Reduce)", "Ignored horizontal KB");
                                    }
                                }

                                // Odată ce a răspuns la knockback cu o viteză Y pozitivă care a trecut de verificări, îl curățăm
                                expectedVelocityMap.remove(uuid);

                            } else {
                                // Expirare timp: modul "Lag" din imagine amână pachetele, noi tăiem firul la MAX_PING_DELAY_MS
                                if (timeSinceHit > MAX_PING_DELAY_MS) {
                                    flagSync(player, "Velocity (Cancel/Lag)", "No reaction to KB after " + MAX_PING_DELAY_MS + "ms");
                                    expectedVelocityMap.remove(uuid);
                                }
                            }

                            // Actualizăm poziția
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // Funcție separată pentru a declanșa flag-ul în siguranță pe thread-ul principal
    private void flagSync(Player player, String type, String details) {
        Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
            if (player.isOnline() && !player.isDead()) {
                flagPlayer.addFlag(player, type, details);
            }
        });
    }

    // --- EVENIMENTE BUKKIT ---

    @EventHandler
    public void onPlayerVelocity(PlayerVelocityEvent event) {
        if (!dakotaAC.isCheckActive("Velocity")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE || player.isDead()) return;

        Vector velocity = event.getVelocity();

        // Verificăm doar dacă knockback-ul este suficient de mare pentru a merita calculat
        if (velocity.getY() > 0.1) {
            if (!canTakeKnockback(player)) return;

            // Salvăm tot vectorul, nu doar Y-ul, ca să putem prinde hack-urile orizontale (Strafe/Reduce)
            expectedVelocityMap.put(player.getUniqueId(), new ExpectedKnockback(velocity.clone(), System.currentTimeMillis()));

            Location loc = player.getLocation();
            lastPosMap.put(player.getUniqueId(), new double[]{loc.getX(), loc.getY(), loc.getZ()});
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        UUID uuid = event.getEntity().getUniqueId();
        expectedVelocityMap.remove(uuid);
        lastPosMap.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        expectedVelocityMap.remove(uuid);
        lastPosMap.remove(uuid);
    }

    private boolean canTakeKnockback(Player player) {
        Location loc = player.getLocation();
        Material currentBlock = loc.getBlock().getType();

        // Blocuri lichide sau care blochează knockback-ul Vanilla
        if (currentBlock.name().contains("WATER") || currentBlock.name().contains("LAVA") ||
                currentBlock.name().contains("COBWEB") || currentBlock.name().contains("VINE") ||
                currentBlock.name().contains("HONEY")) {
            return false;
        }

        // Verificăm tavanul (hitbox-ul jucătorului este 1.8 blocuri înălțime)
        Block blockAbove = loc.clone().add(0, 2.0, 0).getBlock();
        if (blockAbove.getType().isSolid()) {
            return false;
        }

        return true;
    }
}