package skypixel.World;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.BlockPosition;
import com.comphenix.protocol.wrappers.EnumWrappers;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.util.RayTraceResult;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

public class Fucker implements Listener {

    public Fucker() {
        // Interceptăm pachetul prin care clientul interacționează cu blocurile (DIG)
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.BLOCK_DIG) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Fucker")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;

                            // Extragem tipul acțiunii
                            EnumWrappers.PlayerDigType action = event.getPacket().getPlayerDigTypes().readSafely(0);

                            // Ne interesează DOAR momentul în care clientul anunță că a TERMINAT de spart blocul.
                            // Aici rulăm verificările, deoarece un jucător legitim trebuie să fie în continuare în raza de acțiune și să se uite la bloc.
                            if (action != EnumWrappers.PlayerDigType.STOP_DESTROY_BLOCK) {
                                return;
                            }

                            // Extragem coordonatele blocului pe care vrea să-l spargă
                            BlockPosition blockPos = event.getPacket().getBlockPositionModifier().readSafely(0);
                            int x = blockPos.getX();
                            int y = blockPos.getY();
                            int z = blockPos.getZ();

                            // Trecem pe Main Thread pentru a citi harta și a face RayTrace-ul în siguranță
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                if (player.getGameMode() == GameMode.CREATIVE || player.getGameMode() == GameMode.SPECTATOR) {
                                    return;
                                }

                                Block targetBlock = player.getWorld().getBlockAt(x, y, z);

                                // Ignorăm iarba, torțele, florile etc.
                                if (!targetBlock.getType().isSolid()) {
                                    return;
                                }

                                Location eyeLoc = player.getEyeLocation();
                                double maxDistance = 6.0;
                                Location blockCenter = targetBlock.getLocation().add(0.5, 0.5, 0.5);
                                double distanceToCenter = eyeLoc.distance(blockCenter);

                                // 1. Verificarea Distanței (Reach)
                                if (distanceToCenter > maxDistance + 0.5) {
                                    flagPlayer.addFlag(player, "Fucker", "Broke block from extreme distance (Reach: " + String.format("%.1f", distanceToCenter) + ").");

                                    // Forțăm clientul să anuleze vizual distrugerea blocului
                                    player.sendBlockChange(targetBlock.getLocation(), targetBlock.getBlockData());
                                    return;
                                }

                                if (distanceToCenter < 1.2) {
                                    return; // Este prea aproape pentru a greși unghiul
                                }

                                // 2. INIMA SISTEMULUI: RayTrace pentru a detecta pereții
                                RayTraceResult result = player.getWorld().rayTraceBlocks(
                                        eyeLoc,
                                        player.getLocation().getDirection(),
                                        maxDistance,
                                        FluidCollisionMode.NEVER,
                                        true
                                );

                                if (result != null && result.getHitBlock() != null) {
                                    Block hitBlock = result.getHitBlock();

                                    if (!hitBlock.getLocation().equals(targetBlock.getLocation())) {
                                        if (hitBlock.getType().isSolid()) {
                                            flagPlayer.addFlag(player, "Fucker", "Tried to break " + targetBlock.getType().name() + " through a solid block (" + hitBlock.getType().name() + ").");

                                            // Anulăm distrugerea
                                            player.sendBlockChange(targetBlock.getLocation(), targetBlock.getBlockData());
                                        }
                                    }
                                } else {
                                    // 3. Verificare Unghi (GhostBreak / Aura)
                                    // Dacă dă cu târnăcopul dar raza nu lovește nimic, e clar hack!
                                    flagPlayer.addFlag(player, "Fucker", "Broke block without looking at it (GhostBreak).");
                                    player.sendBlockChange(targetBlock.getLocation(), targetBlock.getBlockData());
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }
}