package skypixel.Movement;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffectType;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Step implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    // Viteza de salt maximă permisă pur Vanilla
    private static final double BASE_JUMP_Y = 0.425;

    // Înălțimea maximă a unui Step (Slab-urile / Scările au 0.5, Paturile ~0.56, folosim 0.6)
    private static final double MAX_STEP_Y = 0.6;

    // Multiplicatorul pentru licoarea de Jump Boost
    private static final double JUMP_BOOST_MULTIPLIER = 0.15;

    // Limita fizică a lumii pentru a preveni crash-urile
    private static final double MAX_WORLD_COORDINATE = 3.0E7;

    // Câte mișcări imposibile iertăm din cauza lag-ului de rețea?
    private static final int MAX_VIOLATIONS = 2;
    // ==========================================

    // Colecții concurente pentru siguranță asincronă totală
    private final ConcurrentHashMap<UUID, Long> bounceCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, double[]> lastPosMap = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Location> lastSafeLocation = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Long> teleportGrace = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> stepBuffer = new ConcurrentHashMap<>();

    public Step() {
        ProtocolLibrary.getProtocolManager().addPacketListener(
                new PacketAdapter(dakotaAC.getPlugin(dakotaAC.class),
                        com.comphenix.protocol.events.ListenerPriority.NORMAL,
                        PacketType.Play.Client.POSITION,
                        PacketType.Play.Client.POSITION_LOOK) {

                    @Override
                    public void onPacketReceiving(PacketEvent event) {
                        try {
                            if (!dakotaAC.isCheckActive("Step")) return;

                            Player player = event.getPlayer();
                            if (player == null) return;
                            UUID uuid = player.getUniqueId();

                            // Extragem coordonatele noi brute din pachet
                            double toX = event.getPacket().getDoubles().readSafely(0);
                            double toY = event.getPacket().getDoubles().readSafely(1);
                            double toZ = event.getPacket().getDoubles().readSafely(2);

                            // === PROTECȚIE CRASH EXPLOITS ===
                            if (Double.isNaN(toX) || Double.isNaN(toY) || Double.isNaN(toZ) ||
                                    Double.isInfinite(toX) || Double.isInfinite(toY) || Double.isInfinite(toZ) ||
                                    Math.abs(toX) > MAX_WORLD_COORDINATE || Math.abs(toZ) > MAX_WORLD_COORDINATE) {
                                event.setCancelled(true);
                                return;
                            }

                            double[] fromPos = lastPosMap.get(uuid);

                            // Înregistrăm coordonatele la prima mișcare
                            if (fromPos == null) {
                                lastPosMap.put(uuid, new double[]{toX, toY, toZ});
                                return;
                            }

                            double deltaY = toY - fromPos[1];

                            // Desync / Blink Filter: Dacă diferența este masivă, e un Ghost Packet
                            if (deltaY > 10.0 || deltaY < -10.0) {
                                return;
                            }

                            // Salvăm locația veche într-o variabilă temporară imuabilă
                            final double[] safeFromPos = {fromPos[0], fromPos[1], fromPos[2]};

                            // Actualizăm matricea pentru pachetul viitor
                            lastPosMap.put(uuid, new double[]{toX, toY, toZ});

                            // Optimizare Extremă pe Rețea: Ignorăm mersul drept sau căderea
                            if (deltaY <= 0.0) {
                                return;
                            }

                            // Delegăm verificările hărții pe Main Thread (Bukkit)
                            Bukkit.getScheduler().runTask(dakotaAC.getPlugin(dakotaAC.class), () -> {
                                if (!player.isOnline() || player.isDead()) return;

                                Location toLoc = new Location(player.getWorld(), toX, toY, toZ);

                                // Perioada de grație pentru TP / Immediate Respawn
                                long lastTeleport = teleportGrace.getOrDefault(uuid, 0L);
                                if (System.currentTimeMillis() - lastTeleport < 1000) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                Location fromLoc = new Location(player.getWorld(), safeFromPos[0], safeFromPos[1], safeFromPos[2], player.getLocation().getYaw(), player.getLocation().getPitch());

                                // Bypass-uri legitime
                                if (player.getAllowFlight() || player.isInsideVehicle() || player.isSwimming() || player.isGliding() || player.hasPotionEffect(PotionEffectType.LEVITATION)) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Knockback de la damage (săritură forțată de server)
                                if (player.getNoDamageTicks() > 10 || player.getVelocity().getY() > 0.43) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Verificăm impulsurile de la Slime Blocks / Paturi
                                if (isNearBouncingBlock(toLoc) || isNearBouncingBlock(fromLoc)) {
                                    bounceCooldowns.put(uuid, System.currentTimeMillis() + 2000L);
                                }

                                if (bounceCooldowns.containsKey(uuid) && bounceCooldowns.get(uuid) > System.currentTimeMillis()) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // Verificăm blocurile pe care se află (scări, apă, schele)
                                Material currentBlock = toLoc.getBlock().getType();
                                if (currentBlock == Material.WATER || currentBlock == Material.LAVA ||
                                        currentBlock == Material.LADDER || currentBlock == Material.VINE ||
                                        currentBlock.name().contains("SCAFFOLDING") || currentBlock.name().contains("BUBBLE_COLUMN") ||
                                        currentBlock == Material.POWDER_SNOW) {
                                    lastSafeLocation.put(uuid, toLoc);
                                    return;
                                }

                                // --- LOGICA DE VERIFICARE ---
                                double maxAllowedY = BASE_JUMP_Y;

                                if (player.hasPotionEffect(PotionEffectType.JUMP_BOOST)) {
                                    int level = player.getPotionEffect(PotionEffectType.JUMP_BOOST).getAmplifier() + 1;
                                    maxAllowedY += (level * JUMP_BOOST_MULTIPLIER);
                                }

                                boolean canStepNaturally = isNearStepBlock(toLoc) || isNearStepBlock(fromLoc);
                                if (canStepNaturally) {
                                    maxAllowedY = Math.max(maxAllowedY, MAX_STEP_Y);
                                }

                                int vl = stepBuffer.getOrDefault(uuid, 0);

                                // EVALUAREA FINALĂ
                                if (deltaY > maxAllowedY) {
                                    vl++;
                                    stepBuffer.put(uuid, vl);

                                    if (vl >= MAX_VIOLATIONS) {
                                        flagPlayer.addFlag(player, "Step", "Exceeded max Y-step: " + String.format("%.3f", deltaY) + " (Limit: " + maxAllowedY + ")");

                                        // Oferim imunitate ca Rubber-Band-ul să nu genereze alarme false la HighJump/Fly
                                        teleportGrace.put(uuid, System.currentTimeMillis() + 1000L);

                                        // Rubber-Band: Tragem jucătorul înapoi în siguranță pe pământ
                                        Location safe = lastSafeLocation.getOrDefault(uuid, fromLoc);
                                        player.teleport(safe, PlayerTeleportEvent.TeleportCause.PLUGIN);

                                        // Reducem buffer-ul parțial
                                        stepBuffer.put(uuid, 1);
                                    }
                                } else {
                                    // Urcare legitimă
                                    if (vl > 0) stepBuffer.put(uuid, vl - 1);
                                    lastSafeLocation.put(uuid, toLoc);
                                }
                            });

                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
        );
    }

    // ========================================================
    // PROTECȚII LA ALARME FALSE (TELEPORT / RESPAWN)
    // ========================================================

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getTo();

        if (to != null) {
            teleportGrace.put(uuid, System.currentTimeMillis()); // 1 secundă grație
            lastSafeLocation.put(uuid, to);
            lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Location to = event.getRespawnLocation();

        teleportGrace.put(uuid, System.currentTimeMillis());

        lastSafeLocation.put(uuid, to);
        lastPosMap.put(uuid, new double[]{to.getX(), to.getY(), to.getZ()});
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        bounceCooldowns.remove(uuid);
        lastPosMap.remove(uuid);
        lastSafeLocation.remove(uuid);
        teleportGrace.remove(uuid);
        stepBuffer.remove(uuid);
    }

    // ========================================================
    // VERIFICĂRI DE FIZICĂ
    // ========================================================

    private boolean isNearBouncingBlock(Location loc) {
        int x = loc.getBlockX();
        int minY = loc.getBlockY() - 2;
        int maxY = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = minY; dy <= maxY; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    if (type == Material.SLIME_BLOCK || type.name().contains("BED")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean isNearStepBlock(Location loc) {
        int x = loc.getBlockX();
        int y = loc.getBlockY();
        int z = loc.getBlockZ();

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = y - 1; dy <= y; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    Material type = loc.getWorld().getBlockAt(x + dx, dy, z + dz).getType();
                    String name = type.name();

                    if (name.contains("SLAB") || name.contains("STAIRS") || name.contains("STEP") ||
                            name.contains("SNOW") || name.contains("BED") || name.contains("DAYLIGHT_DETECTOR") ||
                            name.contains("PATH") || name.contains("FARMLAND") || name.contains("CARPET") ||
                            name.contains("CAMPFIRE")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}