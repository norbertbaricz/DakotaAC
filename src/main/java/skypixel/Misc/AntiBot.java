package skypixel.Misc;

import skypixel.compat.PacketAccess;
import skypixel.compat.Diagnostics;
import skypixel.config.CheckPolicy;
import skypixel.config.Lang;
import skypixel.config.Option;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import skypixel.compat.CompatibleAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import com.comphenix.protocol.wrappers.WrappedGameProfile;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
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
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Original detection formulas; annotated defaults below are loaded from Modules.AntiBot. */
public class AntiBot implements Listener {
    @Option(value = "bot-boots", minSize = 1)
    private static volatile Material[] BOT_BOOTS = {Material.DIAMOND_BOOTS, Material.IRON_BOOTS, Material.CHAINMAIL_BOOTS, Material.LEATHER_BOOTS};

    @Option(value = "bot-leggings", minSize = 1)
    private static volatile Material[] BOT_LEGGINGS = {Material.DIAMOND_LEGGINGS, Material.IRON_LEGGINGS, Material.CHAINMAIL_LEGGINGS, Material.LEATHER_LEGGINGS};

    @Option(value = "bot-chestplates", minSize = 1)
    private static volatile Material[] BOT_CHESTPLATES = {Material.DIAMOND_CHESTPLATE, Material.IRON_CHESTPLATE, Material.CHAINMAIL_CHESTPLATE, Material.LEATHER_CHESTPLATE};

    @Option(value = "bot-helmets", minSize = 1)
    private static volatile Material[] BOT_HELMETS = {Material.DIAMOND_HELMET, Material.IRON_HELMET, Material.CHAINMAIL_HELMET, Material.LEATHER_HELMET};

    @Option(value = "bot-swords", minSize = 1)
    private static volatile Material[] BOT_SWORDS = {Material.DIAMOND_SWORD, Material.IRON_SWORD, Material.NETHERITE_SWORD, Material.STONE_SWORD};

    @Option(value = "level-one-hits-to-advance", min = 1.0, max = 1.0E9)
    private static volatile int LEVEL_ONE_HITS_TO_ADVANCE = 5;
    @Option(value = "level-one-timeout-ms", min = 1.0, max = 1.0E9)
    private static volatile long LEVEL_ONE_TIMEOUT_MS = 2_000L;
    @Option(value = "level-one-radius", min = 0.0, max = 1.0E9)
    private static volatile double LEVEL_ONE_RADIUS = 1.4D;
    @Option(value = "level-one-rotation-speed", min = 0.0, max = 1.0E9)
    private static volatile double LEVEL_ONE_ROTATION_SPEED = 0.3D; 
    @Option(value = "level-one-y-offset", min = 0.0, max = 1.0E9)
    private static volatile double LEVEL_ONE_Y_OFFSET = 0.2D;       

    @Option(value = "level-two-hits-to-flag", min = 1.0, max = 1.0E9)
    private static volatile int LEVEL_TWO_HITS_TO_FLAG = 4;
    @Option(value = "level-two-timeout-ms", min = 1.0, max = 1.0E9)
    private static volatile long LEVEL_TWO_TIMEOUT_MS = 60_000L;
    @Option(value = "level-two-distance", min = 0.0, max = 1.0E9)
    private static volatile double LEVEL_TWO_DISTANCE = 3.6D;
    @Option(value = "level-two-y-offset", min = 0.0, max = 1.0E9)
    private static volatile double LEVEL_TWO_Y_OFFSET = 0.0D;       

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(3_000_000);
    private static final Map<UUID, Session> sessions = new HashMap<>();
    private static final Map<Integer, UUID> botOwners = new java.util.concurrent.ConcurrentHashMap<>();
    private static final Random RANDOM = new Random();

    @Option(value = "fake-names", minSize = 1)
    private static volatile String[] FAKE_NAMES = {
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
        PROTOCOL.addPacketListener(new CompatibleAdapter(
                dakotaAC.getPlugin(dakotaAC.class), PacketType.Play.Client.USE_ENTITY
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                try {
                    if (!PacketAccess.isAttack(event)) {
                        return;
                    }

                    int entityId = event.getPacket().getIntegers().read(0);
                    UUID ownerId = botOwners.get(entityId);
                    if (ownerId != null && ownerId.equals(event.getPlayer().getUniqueId())) {
                        Diagnostics.sync(() -> handleBotAttack(event.getPlayer(), entityId));
                    }
                } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("AntiBot", ignored); }
            }
        });

        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), this::tickSessions, 1L, 1L);
    }

    public static void executeReportCheck(Player player) {
        if (!dakotaAC.isCheckActive("AntiBot") || player == null || !player.isOnline()) {
            return;
        }

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
        Vector direction = player.getLocation().getDirection().setY(0);
        if (direction.lengthSquared() == 0.0D) {
            direction = new Vector(1, 0, 0);
        }
        direction.normalize();
        return player.getLocation().clone()
                .add(direction.multiply(-LEVEL_TWO_DISTANCE))
                .add(0, LEVEL_TWO_Y_OFFSET, 0);
    }

    private static void handleBotAttack(Player player, int entityId) {
        Session session = sessions.get(player.getUniqueId());
        if (session == null || session.bot == null || session.bot.entityId != entityId) {
            return;
        }

        session.hits++; 

        if (session.level == Level.ONE) {
            if (session.hits >= LEVEL_ONE_HITS_TO_ADVANCE) {
                startLevelTwo(player, session);
            }
        } else {

            if (session.hits >= LEVEL_TWO_HITS_TO_FLAG) {
                flagPlayer.addFlag(player, "AntiBot", Lang.text("checks.AntiBot.name-antibot"), Lang.text("checks.AntiBot.details-attacked-the-level-two-inspection-player-times", "hits", session.hits));
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

            if (player == null || !player.isOnline() || !dakotaAC.isCheckActive("AntiBot") || CheckPolicy.exempt(player, "AntiBot")) {
                if (session.bot != null) botOwners.remove(session.bot.entityId);
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
                facePlayer(session.bot, player); 
                updateBotPositionAndRotation(player, session.bot);

            } else if (session.level == Level.TWO) {
                session.bot.location = levelTwoLocation(player);
                facePlayer(session.bot, player); 
                updateBotPositionAndRotation(player, session.bot);
            }
        }
    }

    private static void facePlayer(VirtualBot bot, Player player) {
        Vector direction = player.getEyeLocation().toVector().subtract(bot.location.toVector());
        if (direction.lengthSquared() > 0.0D) {
            bot.location.setDirection(direction);
        }
    }

    private static void updateBotPositionAndRotation(Player player, VirtualBot bot) {
        sendTeleport(player, bot);
        try {

            PROTOCOL.sendServerPacket(player, headRotation(bot));
        } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("AntiBot", ignored); }
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
        } catch (RuntimeException | LinkageError exception) {
            botOwners.remove(bot.entityId);
            sessions.remove(player.getUniqueId());
            removeBot(player, bot);
            Diagnostics.report("AntiBot", exception);
        }
    }

    private static void removeBot(Player player, VirtualBot bot) {
        if (bot == null) return;
        botOwners.remove(bot.entityId);
        if (player == null || !player.isOnline()) return;
        try {
            PROTOCOL.sendServerPacket(player, destroyEntity(bot));
            PROTOCOL.sendServerPacket(player, playerInfoRemove(bot));
        } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("AntiBot", ignored); }
    }

    private static PacketContainer playerInfoAdd(VirtualBot bot) {
        return PacketAccess.playerInfo(bot.profile);
    }

    private static PacketContainer spawnPlayer(VirtualBot bot) {
        return PacketAccess.spawnPlayer(bot.entityId, bot.profile, bot.location);
    }

    private static PacketContainer headRotation(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, bot.entityId);
        packet.getBytes().write(0, angleToByte(bot.location.getYaw()));
        return packet;
    }

    private static void sendTeleport(Player player, VirtualBot bot) {
        try { PROTOCOL.sendServerPacket(player, PacketAccess.teleport(bot.entityId, bot.location)); }
        catch (RuntimeException | LinkageError ex) { Diagnostics.report("AntiBot", ex); }
    }

    private static PacketContainer createRandomEquipment(VirtualBot bot) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_EQUIPMENT);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, bot.entityId);

        packet.getSlotStackPairLists().write(0, Arrays.asList(
                new Pair<>(EnumWrappers.ItemSlot.MAINHAND, new ItemStack(BOT_SWORDS[RANDOM.nextInt(BOT_SWORDS.length)])),
                new Pair<>(EnumWrappers.ItemSlot.HEAD, new ItemStack(BOT_HELMETS[RANDOM.nextInt(BOT_HELMETS.length)])),
                new Pair<>(EnumWrappers.ItemSlot.CHEST, new ItemStack(BOT_CHESTPLATES[RANDOM.nextInt(BOT_CHESTPLATES.length)])),
                new Pair<>(EnumWrappers.ItemSlot.LEGS, new ItemStack(BOT_LEGGINGS[RANDOM.nextInt(BOT_LEGGINGS.length)])),
                new Pair<>(EnumWrappers.ItemSlot.FEET, new ItemStack(BOT_BOOTS[RANDOM.nextInt(BOT_BOOTS.length)]))
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
