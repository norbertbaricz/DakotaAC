package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FastBreak implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Timpul minim absolut (în milisecunde) cu Efficiency V + Haste II + Netherite:
    // Obsidian / Crying Obsidian / Respawn Anchor = ~2250ms (Vanilla). Lăsăm 1500ms pentru lag.
    private static final long MIN_OBSIDIAN_TIME = 1500L;

    // Ancient Debris / Ender Chest / Anvil = ~600ms (Vanilla). Lăsăm 350ms pentru lag.
    private static final long MIN_HEAVY_TIME = 350L;

    // Deepslate (Orice tip, inclusiv minereuri de deepslate) = ~300ms (Vanilla). Lăsăm 150ms.
    private static final long MIN_DEEPSLATE_TIME = 150L;

    // Câte alarme false acceptăm din cauza posibilelor căderi masive de internet (TCP Bursts)?
    private static final int MAX_VIOLATIONS = 2;
    // ==========================================

    // Stocăm timpul de START și durata FINALĂ calculată direct pe placa de rețea
    private final ConcurrentHashMap<UUID, Long> breakStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactBreakDuration = new ConcurrentHashMap<>();

    // Buffer pentru a ierta lag-ul
    private final ConcurrentHashMap<UUID, Integer> breakVL = new ConcurrentHashMap<>();

    public FastBreak() {
        // Interceptăm pachetul prin care clientul interacționează fizic cu blocurile
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("FastBreak")) return;

                            Player player = event.getPlayer();
                            if (player == null || player.getGameMode() == GameMode.CREATIVE) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem acțiunea (START, ABORT, STOP)
                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);
                            long now = System.currentTimeMillis();

                            if (action == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                // Jucătorul a început să lovească blocul
                                breakStartTimes.put(uuid, now);
                            }
                            else if (action == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                                // Jucătorul a terminat de spart blocul
                                long start = breakStartTimes.getOrDefault(uuid, 0L);

                                if (start > 0) {
                                    exactBreakDuration.put(uuid, now - start);
                                } else {
                                    // Dacă nu a trimis pachet de START sau le-a trimis simultan (Insta-Mine Hack / Nuker)
                                    exactBreakDuration.put(uuid, 0L);
                                }
                            }
                            else if (action == EnumWrappers.PlayerDigType.ABORT_DESTROY_BLOCK) {
                                // S-a oprit din spart (a luat ținta de pe bloc)
                                breakStartTimes.remove(uuid);
                            }

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // DELEGAREA CĂTRE MAIN THREAD: Când Bukkit aprobă spargerea
    // Priority LOWEST pentru a anula înainte ca blocul să dropeze item-ul
    // ========================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("FastBreak")) return;

        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Material blockType = event.getBlock().getType();
        String blockName = blockType.name();

        // Preluăm timpul de spargere precis, calculat de Netty
        // Setăm default la -1 pentru a ignora distrugerile generate de server (ex: explozii)
        long timeTaken = exactBreakDuration.getOrDefault(uuid, -1L);

        if (timeTaken == -1L) return;

        boolean isFlagged = false;
        String flagReason = "";
        int vl = breakVL.getOrDefault(uuid, 0);

        // --------------------------------------------------------
        // LOGICA DE TIMP IMPOSIBIL (Verificăm doar blocurile fizic imposibile)
        // --------------------------------------------------------

        // Nivelul 1: Blocurile Extreme (Obsidian, Respawn Anchor, Crying Obsidian)
        if (blockName.contains("OBSIDIAN") || blockName.equals("RESPAWN_ANCHOR")) {
            if (timeTaken < MIN_OBSIDIAN_TIME) {
                isFlagged = true;
                flagReason = "Broke Obsidian extremely fast (" + timeTaken + "ms).";
            }
        }
        // Nivelul 2: Blocurile Grele (Debris, Ender Chest, Anvil)
        else if (blockName.equals("ANCIENT_DEBRIS") || blockName.equals("ENDER_CHEST") || blockName.contains("ANVIL")) {
            if (timeTaken < MIN_HEAVY_TIME) {
                isFlagged = true;
                flagReason = "Broke heavy block extremely fast (" + timeTaken + "ms).";
            }
        }
        // Nivelul 3: Deepslate (Nu suportă Insta-Mine Vanilla absolut niciodată)
        else if (blockName.contains("DEEPSLATE")) {
            if (timeTaken < MIN_DEEPSLATE_TIME) {
                isFlagged = true;
                flagReason = "Insta-mined deepslate block (" + timeTaken + "ms).";
            }
        }

        // ACȚIUNE: Dacă durata Netty este sub limitele matematice
        if (isFlagged) {
            vl++;
            breakVL.put(uuid, vl);

            if (vl >= MAX_VIOLATIONS) {
                flagPlayer.addFlag(player, "FastBreak", flagReason);

                // Anulăm evenimentul.
                event.setCancelled(true);

                // Forțăm clientul să re-afișeze blocul curat, evitând bug-ul de "Ghost Block" în care
                // hacker-ul crede că a spart blocul și încearcă să treacă prin el.
                player.sendBlockChange(event.getBlock().getLocation(), event.getBlock().getBlockData());

                breakVL.put(uuid, MAX_VIOLATIONS - 1); // Resetăm parțial buffer-ul
            }
        } else {
            // Dacă sparge corect, îl iertăm de eventualele lag spikes anterioare
            if (vl > 0) breakVL.put(uuid, vl - 1);
        }

        // Curățăm datele procesate pentru a fi pregătiți de următorul bloc
        exactBreakDuration.remove(uuid);
        breakStartTimes.remove(uuid);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        // Prevenim scurgerile de memorie
        UUID uuid = event.getPlayer().getUniqueId();
        breakStartTimes.remove(uuid);
        exactBreakDuration.remove(uuid);
        breakVL.remove(uuid);
    }
}