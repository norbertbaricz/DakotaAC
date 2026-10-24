package skypixel.Render;

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
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Original detection formulas; annotated defaults below are loaded from Modules.Tracers. */
public class Tracers implements Listener {

    @Option(value = "initial-delay-ticks", min = 0.0, max = 1.0E9)
    private static volatile long INITIAL_DELAY_TICKS = 20L;
    @Option(value = "tab-remove-delay-ticks", min = 1.0, max = 1.0E9)
    private static volatile long TAB_REMOVE_DELAY_TICKS = 5L;
    @Option(value = "look-tolerance-degrees", min = 0.0, max = 1.0E9)
    private static volatile double LOOK_TOLERANCE_DEGREES = 1.0D;
    @Option(value = "vertical-random-spread", min = 0.0, max = 64.0)
    private static volatile int VERTICAL_RANDOM_SPREAD = 3;

    @Option(value = "decoys-per-second", min = 1.0, max = 256.0)
    private static volatile int DECOYS_PER_SECOND = 20;
    @Option(value = "batch-interval-ticks", min = 1.0, max = 1.0E9)
    private static volatile long BATCH_INTERVAL_TICKS = 20L;
    @Option(value = "min-decoy-distance", min = 0.0, max = 1.0E9)
    private static volatile double MIN_DECOY_DISTANCE = 15.0D;
    @Option(value = "max-decoy-distance", min = 0.0, max = 1.0E9)
    private static volatile double MAX_DECOY_DISTANCE = 25.0D;
    @Option(value = "max-view-count-before-flag", min = 1.0, max = 1.0E9)
    private static volatile int MAX_VIEW_COUNT_BEFORE_FLAG = 15;

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);
    private static final Random RANDOM = new Random();

    private static final Map<UUID, List<FakeNpc>> fakeNpcs = new HashMap<>();
    private static final Map<UUID, Integer> viewCounts = new HashMap<>();
    private static final Set<UUID> flaggedPlayers = new HashSet<>();

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

    public Tracers() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new CompatibleAdapter(
                dakotaAC.getPlugin(dakotaAC.class),
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.POSITION_LOOK
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                Diagnostics.sync(() -> handlePlayerLook(event.getPlayer()));
            }
        });

        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), this::spawnBatches, INITIAL_DELAY_TICKS, BATCH_INTERVAL_TICKS);
    }

    private void spawnBatches() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (player.getGameMode() != GameMode.SURVIVAL
                    || !dakotaAC.isCheckActive("Tracers") || CheckPolicy.exempt(player, "Tracers")
                    || flaggedPlayers.contains(playerId)) {
                removeFakeNpcs(player);
                continue;
            }

            removeFakeNpcs(player);
            List<FakeNpc> batch = new ArrayList<>(DECOYS_PER_SECOND);
            for (int i = 0; i < DECOYS_PER_SECOND; i++) {
                FakeNpc npc = createNpc(player);
                if (spawnNpc(player, npc)) {
                    batch.add(npc);
                }
            }

            if (!batch.isEmpty()) {
                fakeNpcs.put(playerId, batch);
                removeFromTabListAfterSpawn(player, batch);
            }
        }
    }

    private FakeNpc createNpc(Player player) {
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
        double distance = MIN_DECOY_DISTANCE
                + RANDOM.nextDouble() * (MAX_DECOY_DISTANCE - MIN_DECOY_DISTANCE);
        Location location = player.getLocation().clone().add(
                Math.cos(angle) * distance,
                RANDOM.nextInt(VERTICAL_RANDOM_SPREAD * 2 + 1) - VERTICAL_RANDOM_SPREAD,
                Math.sin(angle) * distance
        );
        UUID profileId = UUID.randomUUID();
        String randomName = FAKE_NAMES[RANDOM.nextInt(FAKE_NAMES.length)];
        WrappedGameProfile profile = new WrappedGameProfile(profileId, randomName);

        return new FakeNpc(NEXT_ENTITY_ID.getAndIncrement(), location, profile, profileId, randomName);
    }

    private boolean spawnNpc(Player player, FakeNpc npc) {
        try {
            PROTOCOL.sendServerPacket(player, createPlayerInfoAdd(npc));
            PROTOCOL.sendServerPacket(player, createPlayerSpawn(npc));
            PROTOCOL.sendServerPacket(player, createInvisibleMetadata(npc));
            PROTOCOL.sendServerPacket(player, createHeadRotation(npc));
            return true;
        } catch (RuntimeException | LinkageError exception) {
            Diagnostics.report("Tracers", exception);
            return false;
        }
    }

    private void removeFromTabListAfterSpawn(Player player, List<FakeNpc> batch) {
        Bukkit.getScheduler().runTaskLater(dakotaAC.getInstance(), () -> {
            if (!player.isOnline()) {
                return;
            }
            for (FakeNpc npc : batch) {
                try {
                    PROTOCOL.sendServerPacket(player, createPlayerInfoRemove(npc));
                } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("Tracers", ignored); }
            }
        }, TAB_REMOVE_DELAY_TICKS);
    }

    private void handlePlayerLook(Player player) {
        if (player == null || !dakotaAC.isCheckActive("Tracers") || CheckPolicy.exempt(player, "Tracers")
                || flaggedPlayers.contains(player.getUniqueId())) {
            return;
        }

        List<FakeNpc> batch = fakeNpcs.get(player.getUniqueId());
        if (batch == null) {
            return;
        }

        for (FakeNpc npc : batch) {
            if (!isLookingAtNpc(player, npc.location)) {
                continue;
            }

            int count = viewCounts.getOrDefault(player.getUniqueId(), 0) + 1;
            viewCounts.put(player.getUniqueId(), count);
            if (count > MAX_VIEW_COUNT_BEFORE_FLAG) {
                flagPlayer.addFlag(player, "Tracers", Lang.text("checks.Tracers.name-tracers"), Lang.text("checks.Tracers.details-repeatedly-looking-at-invisible-entities"));
                flaggedPlayers.add(player.getUniqueId());
                removeFakeNpcs(player);
            }
            return;
        }
    }

    private boolean isLookingAtNpc(Player player, Location npcLocation) {
        if (!player.getWorld().equals(npcLocation.getWorld())) {
            return false;
        }

        org.bukkit.util.Vector toNpc = npcLocation.toVector().subtract(player.getEyeLocation().toVector());
        return toNpc.lengthSquared() > 0.0D
                && player.getEyeLocation().getDirection().angle(toNpc) < Math.toRadians(LOOK_TOLERANCE_DEGREES);
    }

    private PacketContainer createPlayerInfoAdd(FakeNpc npc) {
        return PacketAccess.playerInfo(npc.profile);
    }

    private PacketContainer createPlayerSpawn(FakeNpc npc) {
        return PacketAccess.spawnPlayer(npc.entityId, npc.profile, npc.location);
    }

    @SuppressWarnings({"deprecation", "removal"})
    private PacketContainer createInvisibleMetadata(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, npc.entityId);

        WrappedDataValue flags = new WrappedDataValue(
                0, WrappedDataWatcher.Registry.get(Byte.class, false), (byte) 0x20
        );
        packet.getDataValueCollectionModifier().write(0, Collections.singletonList(flags));
        return packet;
    }

    private PacketContainer createHeadRotation(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_HEAD_ROTATION);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, npc.entityId);
        packet.getBytes().write(0, angleToByte(npc.location.getYaw()));
        return packet;
    }

    private static PacketContainer createPlayerInfoRemove(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.PLAYER_INFO_REMOVE);
        packet.getModifier().writeDefaults();
        packet.getUUIDLists().write(0, Collections.singletonList(npc.uuid));
        return packet;
    }

    private static PacketContainer createEntityDestroy(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getModifier().writeDefaults();
        packet.getIntLists().write(0, Collections.singletonList(npc.entityId));
        return packet;
    }

    private byte angleToByte(float angle) {
        return (byte) (angle * 256.0F / 360.0F);
    }

    private void removeFakeNpcs(Player player) {
        List<FakeNpc> batch = fakeNpcs.remove(player.getUniqueId());
        viewCounts.remove(player.getUniqueId());

        if (batch == null || !player.isOnline()) {
            return;
        }

        for (FakeNpc npc : batch) {
            try {
                PROTOCOL.sendServerPacket(player, createEntityDestroy(npc));
                PROTOCOL.sendServerPacket(player, createPlayerInfoRemove(npc));
            } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("Tracers", ignored); }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeFakeNpcs(event.getPlayer());
        viewCounts.remove(event.getPlayer().getUniqueId());
        flaggedPlayers.remove(event.getPlayer().getUniqueId());
    }

    public static void cleanupAllDecoys() {
        for (Map.Entry<UUID, List<FakeNpc>> entry : new HashMap<>(fakeNpcs).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }

            for (FakeNpc npc : entry.getValue()) {
                try {
                    PROTOCOL.sendServerPacket(player, createEntityDestroy(npc));
                    PROTOCOL.sendServerPacket(player, createPlayerInfoRemove(npc));
                } catch (RuntimeException | LinkageError ignored) { Diagnostics.report("Tracers", ignored); }
            }
        }
        fakeNpcs.clear();
        viewCounts.clear();
        flaggedPlayers.clear();
    }

    private static final class FakeNpc {
        private final int entityId;
        private final Location location;
        private final WrappedGameProfile profile;
        private final UUID uuid;
        private final String name;

        private FakeNpc(int entityId, Location location, WrappedGameProfile profile, UUID uuid, String name) {
            this.entityId = entityId;
            this.location = location;
            this.profile = profile;
            this.uuid = uuid;
            this.name = name;
        }
    }
}
