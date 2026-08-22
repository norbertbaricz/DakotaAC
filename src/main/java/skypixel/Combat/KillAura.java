package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class KillAura implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Cât de precis trebuie să fie unghiul de atac?
    // 0.6 = ~53 grade (foarte permisiv pentru a evita false positives de la distanță mică)
    // 0.8 = ~36 grade (strict, bun dacă serverul are lag mic)
    private static final double MIN_DOT_PRODUCT = 0.6;

    // Timpul minim între lovirea a două entități DIFERITE.
    // KillAura / MultiAura lovește 2-3 entități în același tick (0-10ms diferență).
    // Oamenii au nevoie de cel puțin 100-150ms pentru a muta mouse-ul și a da click.
    private static final long MIN_SWITCH_DELAY_MS = 100L;

    // De câte ori are voie să lovească greșit înainte să ia flag? (Absoarbe lagul)
    private static final int MAX_ANGLE_VL = 3;
    private static final int MAX_WALL_VL = 3;
    // ==========================================

    // Salvăm datele despre unghi preluate direct din pachet (Asincron -> Sincron)
    private final ConcurrentHashMap<UUID, ClientAimData> packetAimData = new ConcurrentHashMap<>();

    // Istoricul hit-urilor pentru MultiAura / Fast Switch
    private final ConcurrentHashMap<UUID, HitData> hitTracker = new ConcurrentHashMap<>();

    // Buffere de alerte (Violation Levels)
    private final ConcurrentHashMap<UUID, Integer> angleVL = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> wallVL = new ConcurrentHashMap<>();

    public KillAura() {
        // ====================================================================
        // PASUL 1: PACHETE (ProtocolLib) - Capturăm unghiul în milisecunda click-ului
        // ====================================================================
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        ListenerPriority.NORMAL,
                        PacketType.Play.Client.USE_ENTITY) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("KillAura")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;

                            // Verificăm acțiunea (doar ATTACK)
                            EnumWrappers.EntityUseAction action = event.getPacket().getEnumEntityUseActions().readSafely(0).getAction();
                            if (action != EnumWrappers.EntityUseAction.ATTACK) return;

                            // Salvăm vectorii exact în momentul interceptării
                            Location eyeLoc = player.getEyeLocation();
                            Vector lookVector = eyeLoc.getDirection().normalize();

                            packetAimData.put(player.getUniqueId(), new ClientAimData(eyeLoc, lookVector));

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ====================================================================
    // PASUL 2: LOGICA (Bukkit API) - Verificăm unghiul, pereții și viteza de switch
    // Folosim Priority.LOWEST ca să anulăm înainte ca alte pluginuri să proceseze
    // ====================================================================
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerAttack(EntityDamageByEntityEvent event) {
        try {
            if (!dakotaAC.isCheckActive("KillAura")) return;

            if (!(event.getDamager() instanceof Player)) return;
            Player player = (Player) event.getDamager();

            if (player.getGameMode() == GameMode.CREATIVE) return;

            UUID uuid = player.getUniqueId();
            Entity target = event.getEntity();

            // Preluăm datele de unghi precise capturate din pachet
            ClientAimData aimData = packetAimData.get(uuid);

            // Dacă aimData este null, fie e un hit fals de la un alt plugin,
            // fie este atacul secundar de la SWEEPING EDGE. Îl ignorăm complet!
            if (aimData == null) return;

            boolean isClean = true;

            // === 1. VERIFICARE ANGLE / FOV (Dot Product folosind pachetul) ===
            Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);
            Vector directionToTarget = targetCenter.toVector().subtract(aimData.eyeLoc.toVector()).normalize();

            double dotProduct = aimData.lookVector.dot(directionToTarget);

            if (dotProduct < MIN_DOT_PRODUCT) {
                isClean = false;
                int vl = angleVL.getOrDefault(uuid, 0) + 1;
                angleVL.put(uuid, vl);

                if (vl >= MAX_ANGLE_VL) {
                    flagPlayer.addFlag(player, "KillAura (Angle)", "Unnatural hit angle (Dot: " + String.format("%.2f", dotProduct) + ").");
                    event.setCancelled(true);
                    angleVL.put(uuid, MAX_ANGLE_VL - 1);
                }
            } else {
                decreaseVL(angleVL, uuid);
            }

            // === 2. VERIFICARE WALL-AURA (Line of Sight) ===
            if (!player.hasLineOfSight(target)) {
                isClean = false;
                int vl = wallVL.getOrDefault(uuid, 0) + 1;
                wallVL.put(uuid, vl);

                if (vl >= MAX_WALL_VL) {
                    flagPlayer.addFlag(player, "KillAura (Wall)", "Attempted to attack through solid blocks repeatedly.");
                    event.setCancelled(true);
                    wallVL.put(uuid, MAX_WALL_VL - 1);
                }
            } else {
                decreaseVL(wallVL, uuid);
            }

            // === 3. VERIFICARE MULTI-AURA / FAST SWITCH ===
            long currentTime = System.currentTimeMillis();
            int currentTargetId = target.getEntityId();

            if (hitTracker.containsKey(uuid)) {
                HitData previousHit = hitTracker.get(uuid);

                // Dacă inamicul lovit acum este diferit de cel de tura trecută
                if (previousHit.targetId != currentTargetId) {
                    long timeDifference = currentTime - previousHit.timestamp;

                    // A mutat ținta instant? Este Multi-Aura / SwitchBot
                    if (timeDifference < MIN_SWITCH_DELAY_MS) {
                        isClean = false;
                        flagPlayer.addFlag(player, "KillAura (Multi)", "Inhuman target switch (" + timeDifference + "ms).");
                        event.setCancelled(true);
                    }
                }
            }

            // Dacă atacul a fost validat ca fiind curat, salvăm noua țintă pentru verificarea Switch
            if (isClean) {
                hitTracker.put(uuid, new HitData(currentTargetId, currentTime));
            }

            // Curățăm memoria temporară a pachetului. Asta ne asigură că evadăm
            // damage-urile "fantomă" generate de Sweeping Edge în același tick!
            packetAimData.remove(uuid);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private void decreaseVL(ConcurrentHashMap<UUID, Integer> map, UUID uuid) {
        int vl = map.getOrDefault(uuid, 0);
        if (vl > 0) {
            map.put(uuid, vl - 1);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        hitTracker.remove(uuid);
        packetAimData.remove(uuid);
        angleVL.remove(uuid);
        wallVL.remove(uuid);
    }

    private static final class ClientAimData {
        final Location eyeLoc;
        final Vector lookVector;

        public ClientAimData(Location eyeLoc, Vector lookVector) {
            this.eyeLoc = eyeLoc;
            this.lookVector = lookVector;
        }
    }

    private static final class HitData {
        final int targetId;
        final long timestamp;

        public HitData(int targetId, long timestamp) {
            this.targetId = targetId;
            this.timestamp = timestamp;
        }
    }
}