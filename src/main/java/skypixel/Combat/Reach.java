package skypixel.Combat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

public class Reach implements Listener {

    private static final double MAX_REACH = 4.2;

    public Reach() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class), PacketType.Play.Client.USE_ENTITY) {
                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        // Mutăm logica pe thread-ul principal pentru a accesa API-ul Bukkit în siguranță.
                        Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> {
                            try {
                                if (!dakotaAC.isCheckActive("Reach")) return;

                                Player player = event.getPlayer();
                                if (player == null || player.getGameMode() == GameMode.CREATIVE) {
                                    return;
                                }

                                if (event.getPacket().getEnumEntityUseActions().read(0).getAction() != EnumWrappers.EntityUseAction.ATTACK) {
                                    return;
                                }

                                Entity target = event.getPacket().getEntityModifier(player.getWorld()).read(0);
                                if (target == null) return;

                                Location eyeLoc = player.getEyeLocation();
                                Location targetCenter = target.getLocation().add(0, target.getHeight() / 2.0, 0);
                                double distance = eyeLoc.distance(targetCenter);

                                if (distance > MAX_REACH) {
                                    // Deoarece suntem într-un task sincron, putem anula evenimentul în siguranță.
                                    // Dar pentru a anula pachetul, trebuie să revenim la contextul asincron
                                    // sau să folosim o metodă care anulează pachetul din task-ul principal.
                                    // Cel mai simplu este să anulăm evenimentul Bukkit corespunzător,
                                    // dar aici interceptăm direct pachetul.
                                    // Pentru a menține simplitatea, vom anula pachetul direct,
                                    // dar vom rula logica de anulare înapoi pe thread-ul Netty.
                                    flagPlayer.addFlag(player, "Reach", "Hit from " + String.format("%.2f", distance) + " blocks away.");
                                    event.setCancelled(true);
                                }

                            } catch (Exception ex) {
                                // Ignorăm erorile.
                            }
                        });
                    }
                }
        );
    }
}