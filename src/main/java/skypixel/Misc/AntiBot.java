package skypixel.Misc;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import com.comphenix.protocol.wrappers.WrappedPositionMoveRotation;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Client-side two-stage AntiBot check for Paper 1.21.11 / ProtocolLib 5.5.0-SNAPSHOT.
 * The fake players only exist for the tested client; no Bukkit entities are created.
 */
public class AntiBot implements Listener {

    // =========================================================
    // --- Easy-to-tune thresholds ---
    // =========================================================

    // Setari Nivel 1 (Bot rotativ)
    private static final int LEVEL_ONE_HITS_TO_ADVANCE = 5;
    private static final long LEVEL_ONE_TIMEOUT_MS = 2_000L;
    private static final double LEVEL_ONE_RADIUS = 1.4D;
    private static final double LEVEL_ONE_ROTATION_SPEED = 0.3D; // Cat de repede se invarte
    private static final double LEVEL_ONE_Y_OFFSET = 0.2D;       // Cat de sus sta fata de jucator

    // Setari Nivel 2 (Bot urmaritor pe la spate)
    private static final int LEVEL_TWO_HITS_TO_FLAG = 4;
    private static final long LEVEL_TWO_TIMEOUT_MS = 60_000L;
    private static final double LEVEL_TWO_DISTANCE = 3.6D;
    private static final double LEVEL_TWO_Y_OFFSET = 0.0D;       // Cat de sus sta fata de jucator

    // =========================================================

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(3_000_000);
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final Map<Integer, UUID> botOwners = new HashMap<>();
    private static final Random RANDOM = new Random();

    // Nume randomizate preluate de la Tracers pentru a le pune pe TAB
    private static final String[] FAKE_NAMES = {
            "SolarFlare", "LunarEcho", "CrimsonBolt", "AzureBlade", "EmberHeart", "VoidWalker",
            "IronTide", "SerpentFang", "StormBreaker", "FrostByte", "ShadowClaw", "RogueWave",
            "BlazeRunner", "NightShade", "QuantumLeap", "Vortexia", "GraveStone", "PhoenixAsh",
            "CyberPunk", "OmegaZero", "ZenithPeak", "MysticGaze", "ArcaneSoul", "HeliosPrime",
            "NemesisX", "Joltara", "Raptor_Z", "EchoSphere", "TitanSlayer", "GigaDrill",
            "WraithKing", "Polaris_B", "AxionRay", "CipherCode", "Dreadnought", "FusionCore",
            "HavocReign", "Inferno_IX", "JaguarPaw", "KiloByte", "LaserFish", "MagmaFlow",
            "NeutronStar", "OrionBelt", "PulsarBeam", "QuasarFlow", "RazorEdge", "SonicBoom",
            "TerraFirm", "UltraViolet", "ViperStrike", "WarpDrive", "XenoMorph", "YottaFlux",
            "ZephyrWind", "AlphaWolf", "BravoSix", "Charlie_One", "DeltaForce", "EchoSeven"
    };

    public AntiBot() {
        PROTOCOL.addPacketListener(new PacketAdapter(
                dakotaAC.getPlugin(dakotaAC.class), PacketType.Play.Client.USE_ENTITY
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    if (event.getPacket().getEnumEntityUseActions().read(0).getAction()
                            != EnumWrappers.EntityUseAction.ATTACK) {
                        return;
                    }

                    int entityId = event.getPacket().getIntegers().read(0);
                    UUID ownerId = botOwners.get(entityId);
                    if (ownerId != null && ownerId.equals(event.getPlayer().getUniqueId())) {
                        Bukkit.getScheduler().runTask(dakotaAC.getInstance(),
                                () -> handleBotAttack(event.getPlayer(), entityId));
                    }
                } catch (RuntimeException ignored) {
                }
            }
        });

        // Loop rulat la 1 tick pentru miscare perfecta
        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), this::tickSessions, 1L, 1L);
    }

    /**
     * Aceasta este metoda principala care va fi apelata de comanda /report
     * pentru a declansa fortat testul AntiBot pe un jucator.
     */
    public static void executeReportCheck(Player player) {
        if (!dakotaAC.isCheckActive("AntiBot") || player == null || !player.isOnline()) {
            return;
        }

        // Daca jucatorul este deja in test sau are gamemode creativ, ignoram
        if (sessions.containsKey(player.getUniqueId()) || player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        startLevelOne(player);
    }

    private static void startLevelOne(Player player) {
        if (!player.isOnline() || sessions.containsKey(player.getUniqueId())) {
            return;
        }

        Session session = new Session(player.getUniqueId());
        session.level = Level.ONE;
        session.startedAt = System.currentTimeMillis();
        session.angle = 0.0D;
        session.bot = new VirtualBot(player.getLocation().clone());
        sessions.put(player.getUniqueId(), session);
        spawnBot(player, session.bot);
    }

    private static void startLevelTwo(Player player, Session session) {
        removeBot(player, session.bot);
        session.level = Level.TWO;
        session.startedAt = System.currentTimeMillis();
        session.hits = 0;
        session.bot = new VirtualBot(levelTwoLocation(player));
        spawnBot(player, session.bot);
    }

    private static Location levelTwoLocation(Player player) {
        Vector direction = player.getLocation().getDirection().setY(0).normalize();
        if (direction.lengthSquared() == 0.0D) {
            direction = new Vector(1, 0, 0);
        }
        return player.getLocation().clone()
                .add(direction.multiply(-LEVEL_TWO_DISTANCE))
                .add(0, LEVEL_TWO_Y_OFFSET, 0);
    }

    private static void handleBotAttack(Player player, int entityId) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.bot == null || session.bot.entityId != entityId) {
            return;
        }

        session.hits++; // Nu moare instant

        if (session.level == Level.ONE) {
            if (session.hits >= LEVEL_ONE_HITS_TO_ADVANCE) {
                startLevelTwo(player, session);
            }
        } else {
            // Suntem in nivelul 2
            if (session.hits >= LEVEL_TWO_HITS_TO_FLAG) {
                flagPlayer.addFlag(player, "AntiBot", "Attacked the level-two inspection player " + session.hits + " times.");
                endSession(player);
            }
        }
    }

    private void tickSessions() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            Session session = entry.getValue();

            if (player == null || !player.isOnline() || !dakotaAC.isCheckActive("AntiBot")) {
                if (player != null) removeBot(player, session.bot);
                iterator.remove();
                continue;
            }

            long timeout = session.level == Level.ONE ? LEVEL_ONE_TIMEOUT_MS : LEVEL_TWO_TIMEOUT_MS;
            if (now - session.startedAt > timeout) {
                removeBot(player, session.bot);
                iterator.remove();
                continue;
            }

            if (session.level == Level.ONE) {
                session.angle += LEVEL_ONE_ROTATION_SPEED;
                Location location = player.getLocation().clone().add(
                        Math.cos(session.angle) * LEVEL_ONE_RADIUS,
                        LEVEL_ONE_Y_OFFSET,
                        Math.sin(session.angle) * LEVEL_ONE_RADIUS
                );
                session.bot.location = location;
                facePlayer(session.bot, player); // Botul se uita la jucator
                updateBotPositionAndRotation(player, session.bot);

            } else if (session.level == Level.TWO) {
                session.bot.location = levelTwoLocation(player);
                facePlayer(session.bot, player); // Botul se uita la jucator
                updateBotPositionAndRotation(player, session.bot);
            }
        }
    }

    // --- Logica prin care botul pune ochii pe jucator ---
    private static void facePlayer(VirtualBot bot, Player player) {
        Vector direction = player.getEyeLocation().toVector().subtract(bot.location.toVector());
        if (direction.lengthSquared() > 0.0D) {
            bot.location.setDirection(direction);
        }
    }

    private static void updateBotPositionAndRotation(Player player, VirtualBot bot) {
        sendTeleport(player, bot);
        try {
            // Trimitem pachetul de Head Rotation ca sa intoarca capul fizic
            PROTOCOL.sendServerPacket(player, headRotation(bot));
        } catch (RuntimeException ignored) {
        }
    }

    private static void endSession(Player player) {
        Session session = sessions.remove(player.getUniqueId());
        if (session != null) {
            removeBot(player, session.bot);
        }
    }

    private static void spawnBot(Player player, VirtualBot bot) {
        try {
            botOwners.put(bot.entityId, player.getUniqueId());
            PROTOCOL.sendServerPacket(player, playerInfoAdd(bot));
            PROTOCOL.sendServerPacket(player, spawnPlayer(bot));
            PROTOCOL.sendServerPacket(player, headRotation(bot));
            PROTOCOL.sendServerPacket(player, createRandomEquipment(bot));
        } catch (RuntimeException exception) {
            botOwners.remove(bot.entityId);
            dakotaAC.getInstance().getLogger().warning("Could not create AntiBot virtual player: " + exception.getMessage());
        }
    }

    private static void removeBot(Player player, VirtualBot bot) {
        if (bot == null) return;
        botOwners.remove(bot.entityId);
        if (player == null || !player.isOnline()) return;
        try {
            PROTOCOL.sendServerPacket(player, destroyEntity(bot));
            PROTOCOL.sendServerPacket(player, playerInfoRemove(bot));
        } catch (RuntimeException ignored) {
        }
    }

    private static PacketContainer playerInfoAdd(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getModifier().writeDefaults();
        packet.getPlayerInfoActions().write(0, EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
        ));
        packet.getPlayerInfoDataLists().write(0, Collections.singletonList(new PlayerInfoData(
                bot.profile.getUUID(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                bot.profile, WrappedChatComponent.fromText(bot.profile.getName()), true, 0, null
        )));
        return packet;
    }

    private static PacketContainer spawnPlayer(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, bot.entityId);
        packet.getUUIDs().write(0, bot.profile.getUUID());
        packet.getEntityTypeModifier().write(0, EntityType.PLAYER);
        packet.getDoubles().write(0, bot.location.getX()).write(1, bot.location.getY()).write(2, bot.location.getZ());
        packet.getBytes().write(0, angleToByte(bot.location.getPitch()))
                .write(1, angleToByte(bot.location.getYaw()))
                .write(2, angleToByte(bot.location.getYaw()));
        packet.getIntegers().write(1, 0);
        return packet;
    }

    private static PacketContainer headRotation(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, bot.entityId);
        packet.getBytes().write(0, angleToByte(bot.location.getYaw()));
        return packet;
    }

    private static void sendTeleport(Player player, VirtualBot bot) {
        try {
            PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_TELEPORT);
            packet.getModifier().writeDefaults();
            packet.getIntegers().write(0, bot.entityId);
            packet.getPositionMoveRotation().write(0, WrappedPositionMoveRotation.create(
                    bot.location.toVector(), new Vector(0, 0, 0), bot.location.getYaw(), bot.location.getPitch()
            ));
            packet.getBooleans().write(0, false);
            PROTOCOL.sendServerPacket(player, packet);
        } catch (RuntimeException ignored) {
        }
    }

    private static PacketContainer createRandomEquipment(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, bot.entityId);

        Material[] swords = {Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.NETHERITE_SWORD, Material.STONE_SWORD};
        Material[] helmets = {Material.DIAMOND_HELMET, Material.IRON_HELMET, Material.CHAINMAIL_HELMET, Material.LEATHER_HELMET};
        Material[] chestplates = {Material.DIAMOND_CHESTPLATE, Material.IRON_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.LEATHER_CHESTPLATE};
        Material[] leggings = {Material.DIAMOND_LEGGINGS, Material.IRON_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.LEATHER_LEGGINGS};
        Material[] boots = {Material.DIAMOND_BOOTS, Material.IRON_BOOTS, Material.CHAINMAIL_BOOTS, Material.LEATHER_BOOTS};

        packet.getSlotStackPairLists().write(0, Arrays.asList(
                new Pair<>(EnumWrappers.ItemSlot.MAINHAND, new ItemStack(swords[RANDOM.nextInt(swords.length)])),
                new Pair<>(EnumWrappers.ItemSlot.HEAD, new ItemStack(helmets[RANDOM.nextInt(helmets.length)])),
                new Pair<>(EnumWrappers.ItemSlot.CHEST, new ItemStack(chestplates[RANDOM.nextInt(chestplates.length)])),
                new Pair<>(EnumWrappers.ItemSlot.LEGS, new ItemStack(leggings[RANDOM.nextInt(leggings.length)])),
                new Pair<>(EnumWrappers.ItemSlot.FEET, new ItemStack(boots[RANDOM.nextInt(boots.length)]))
        ));
        return packet;
    }

    private static PacketContainer playerInfoRemove(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        packet.getModifier().writeDefaults();
        packet.getUUIDLists().write(0, Collections.singletonList(bot.profile.getUUID()));
        return packet;
    }

    private static PacketContainer destroyEntity(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getModifier().writeDefaults();
        packet.getIntLists().write(0, Collections.singletonList(bot.entityId));
        return packet;
    }

    private static byte angleToByte(float angle) {
        return (byte) (angle * 256.0F / 360.0F);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        endSession(event.getPlayer());
    }

    public static void cleanupAllBots() {
        for (Map.Entry<UUID, Session> entry : new HashMap<>(sessions).entrySet()) {
            removeBot(Bukkit.getPlayer(entry.getKey()), entry.getValue().bot);
        }
        sessions.clear();
        botOwners.clear();
    }

    private enum Level { ONE, TWO }

    private static final class Session {
        private final UUID ownerId;
        private Level level;
        private long startedAt;
        private int hits;
        private double angle;
        private VirtualBot bot;

        private Session(UUID ownerId) {
            this.ownerId = ownerId;
        }
    }

    private static final class VirtualBot {
        private final int entityId = NEXT_ENTITY_ID.getAndIncrement();
        private final WrappedGameProfile profile;
        private Location location;

        private VirtualBot(Location location) {
            String randomName = FAKE_NAMES[RANDOM.nextInt(FAKE_NAMES.length)];
            this.profile = new WrappedGameProfile(UUID.randomUUID(), randomName);
            this.location = location;
        }
    }
}