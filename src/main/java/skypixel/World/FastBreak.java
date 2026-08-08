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

    // Stocăm timpul de START și durata FINALĂ calculată direct pe placa de rețea
    private final ConcurrentHashMap<UUID, Long> breakStartTimes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> exactBreakDuration = new ConcurrentHashMap<>();

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
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem acțiunea (START, ABORT, STOP)
                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);
                            long now = System.currentTimeMillis();

                            if (action == EnumWrappers.PlayerDigType.START_DESTROY_BLOCK) {
                                // Jucătorul a început să lovească blocul
                                breakStartTimes.put(uuid, now);
                            }
                            else if (action == EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                                // Jucătorul a spart blocul. Calculăm timpul INSTANTANEU, fără delay-ul Bukkit.
                                long start = breakStartTimes.getOrDefault(uuid, 0L);

                                if (start > 0) {
                                    exactBreakDuration.put(uuid, now - start);
                                } else {
                                    // Dacă nu a trimis pachet de START, sau le-a trimis pe ambele simultan (Insta-Mine Hack)
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
    // ========================================================

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!dakotaAC.isCheckActive("FastBreak")) return;

        Player player = event.getPlayer();

        // Jucătorii pe Creativ sparg instant legal
        if (player.getGameMode() == GameMode.CREATIVE) return;

        UUID uuid = player.getUniqueId();
        Material blockType = event.getBlock().getType();
        String blockName = blockType.name();

        // Preluăm timpul de spargere precis, calculat de Netty
        // Setăm default la -1 pentru a ignora distrugerile generate de pluginuri (ex: explozii, worldedit)
        long timeTaken = exactBreakDuration.getOrDefault(uuid, -1L);

        if (timeTaken == -1L) return;

        boolean isFlagged = false;
        String flagReason = "";

        // --------------------------------------------------------
        // LOGICA DE TIMP IMPOSIBIL (Math Limits)
        // --------------------------------------------------------

        // Nivelul 1: Blocurile Extreme (Obsidian)
        if (blockName.contains("OBSIDIAN") || blockName.equals("RESPAWN_ANCHOR")) {
            if (timeTaken < 1000) {
                isFlagged = true;
                flagReason = "Broke Obsidian impossibly fast (" + timeTaken + "ms).";
            }
        }
        // Nivelul 2: Blocurile Grele (Debris, Ender Chest, Anvil)
        else if (blockName.equals("ANCIENT_DEBRIS") || blockName.equals("ENDER_CHEST") || blockName.contains("ANVIL")) {
            if (timeTaken < 400) {
                isFlagged = true;
                flagReason = "Broke heavy block impossibly fast (" + timeTaken + "ms).";
            }
        }
        // Nivelul 3: Minereuri și Deepslate (Nu suportă Insta-Mine Vanilla niciodată)
        else if (blockName.contains("ORE") || blockName.contains("DEEPSLATE")) {
            if (timeTaken < 70) {
                isFlagged = true;
                flagReason = "Insta-mined hard block (" + timeTaken + "ms).";
            }
        }

        // ACȚIUNE: Dacă durata Netty este sub limitele matematice fizic posibile
        if (isFlagged) {
            flagPlayer.addFlag(player, "FastBreak", flagReason);

            // Anulăm evenimentul pe server. Bukkit îi va forța automat clientului să arate
            // blocul înapoi (Rollback vizual) fără să scriem noi cod în plus!
            event.setCancelled(true);
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
    }
}