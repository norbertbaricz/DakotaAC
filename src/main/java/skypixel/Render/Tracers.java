package skypixel.Render;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import skypixel.Notification.flagPlayer;
import skypixel.dakotaAC;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Paper 1.21.11 + ProtocolLib 5.5.0-SNAPSHOT. */
public class Tracers implements Listener {

    // ==========================================
    // SETĂRI UȘOR DE REGLAT (EASY TO TUNE)
    // ==========================================
    private static final int DECOYS_PER_SECOND = 20; // Câte entități false spawnăm per jucător
    private static final long BATCH_INTERVAL_TICKS = 20L; // Intervalul de reîmprospătare (20 ticks = 1 secundă)
    private static final double MIN_DECOY_DISTANCE = 15.0D; // Distanța minimă a NPC-ului fals
    private static final double MAX_DECOY_DISTANCE = 25.0D; // Distanța maximă a NPC-ului fals
    private static final int MAX_VIEW_COUNT_BEFORE_FLAG = 15; // De câte ori are voie să privească direct spre invizibili
    // ==========================================

    private static final ProtocolManager PROTOCOL = ProtocolLibrary.getProtocolManager();
    private static final AtomicInteger NEXT_ENTITY_ID = new AtomicInteger(2_000_000);
    private static final Random RANDOM = new Random();

    private static final Map<UUID, List<FakeNpc>> fakeNpcs = new HashMap<>();
    private static final Map<UUID, Integer> viewCounts = new HashMap<>();
    private static final Set<UUID> flaggedPlayers = new HashSet<>();

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

    public Tracers() {
        ProtocolLibrary.getProtocolManager().addPacketListener(new PacketAdapter(
                dakotaAC.getPlugin(dakotaAC.class),
                PacketType.Play.Client.LOOK,
                PacketType.Play.Client.POSITION_LOOK
        ) {
            @Override
            public void onPacketReceiving(PacketEvent event) {
                // Packet listeners may run on Netty's thread; Bukkit/player state is handled on the main thread.
                Bukkit.getScheduler().runTask(dakotaAC.getInstance(), () -> handlePlayerLook(event.getPlayer()));
            }
        });

        // A new batch replaces the previous one once per second, regardless of movement.
        Bukkit.getScheduler().runTaskTimer(dakotaAC.getInstance(), this::spawnBatches, 20L, BATCH_INTERVAL_TICKS);
    }

    private void spawnBatches() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (player.getGameMode() != GameMode.SURVIVAL
                    || !dakotaAC.isCheckActive("Tracers")
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
        // Spawn in a ring around the player, never immediately beside them.
        double angle = RANDOM.nextDouble() * Math.PI * 2.0D;
        double distance = MIN_DECOY_DISTANCE
                + RANDOM.nextDouble() * (MAX_DECOY_DISTANCE - MIN_DECOY_DISTANCE);
        Location location = player.getLocation().clone().add(
                Math.cos(angle) * distance,
                RANDOM.nextInt(7) - 3,
                Math.sin(angle) * distance
        );
        UUID profileId = UUID.randomUUID();
        WrappedGameProfile profile = new WrappedGameProfile(
                profileId, FAKE_NAMES[RANDOM.nextInt(FAKE_NAMES.length)]
        );
        return new FakeNpc(NEXT_ENTITY_ID.getAndIncrement(), location, profile);
    }

    private boolean spawnNpc(Player player, FakeNpc npc) {
        try {
            PROTOCOL.sendServerPacket(player, createPlayerInfoAdd(npc));
            PROTOCOL.sendServerPacket(player, createPlayerSpawn(npc));
            PROTOCOL.sendServerPacket(player, createInvisibleMetadata(npc));
            PROTOCOL.sendServerPacket(player, createHeadRotation(npc));
            return true;
        } catch (RuntimeException exception) {
            dakotaAC.getInstance().getLogger().warning("Could not spawn Tracers decoy: " + exception.getMessage());
            exception.printStackTrace();
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
                } catch (RuntimeException ignored) {
                    // The entity is removed at the next batch even if the tab cleanup fails.
                }
            }
        }, 5L);
    }

    private void handlePlayerLook(Player player) {
        if (player == null || !dakotaAC.isCheckActive("Tracers")
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
                flagPlayer.addFlag(player, "Tracers", "Repeatedly looking at invisible entities.");
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
                && player.getEyeLocation().getDirection().angle(toNpc) < Math.toRadians(1.0D);
    }

    private PacketContainer createPlayerInfoAdd(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.PLAYER_INFO);
        packet.getModifier().writeDefaults();
        packet.getPlayerInfoActions().write(0, EnumSet.of(
                EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE,
                EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME
        ));
        packet.getPlayerInfoDataLists().write(0, Collections.singletonList(new PlayerInfoData(
                npc.profile.getUUID(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                npc.profile, WrappedChatComponent.fromText(npc.profile.getName()), true, 0, null
        )));
        return packet;
    }

    private PacketContainer createPlayerSpawn(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, npc.entityId);
        packet.getUUIDs().write(0, npc.profile.getUUID());
        packet.getEntityTypeModifier().write(0, EntityType.PLAYER);
        packet.getDoubles().write(0, npc.location.getX()).write(1, npc.location.getY()).write(2, npc.location.getZ());
        packet.getBytes().write(0, angleToByte(npc.location.getPitch()))
                .write(1, angleToByte(npc.location.getYaw()))
                .write(2, angleToByte(npc.location.getYaw()));
        packet.getIntegers().write(1, 0);
        return packet;
    }

    @SuppressWarnings({"deprecation", "removal"})
    private PacketContainer createInvisibleMetadata(FakeNpc npc) {
        PacketContainer packet = PROTOCOL.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getModifier().writeDefaults();
        packet.getIntegers().write(0, npc.entityId);

        // Entity metadata index 0 is the entity-flags byte. Bit 0x20 = invisible.
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
        packet.getUUIDLists().write(0, Collections.singletonList(npc.profile.getUUID()));
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
            } catch (RuntimeException ignored) {
                // Continue removing the rest of the batch.
            }
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        removeFakeNpcs(event.getPlayer());
        flaggedPlayers.remove(event.getPlayer().getUniqueId());
    }

    public static void cleanupAllDecoys() {
        // Called by dakotaAC#onDisable before ProtocolLib unregisters this plugin.
        for (Map.Entry<UUID, List<FakeNpc>> entry : new HashMap<>(fakeNpcs).entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                continue;
            }

            for (FakeNpc npc : entry.getValue()) {
                try {
                    PROTOCOL.sendServerPacket(player, createEntityDestroy(npc));
                    PROTOCOL.sendServerPacket(player, createPlayerInfoRemove(npc));
                } catch (RuntimeException ignored) {
                    // The connection may already be closing during shutdown.
                }
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

        private FakeNpc(int entityId, Location location, WrappedGameProfile profile) {
            this.entityId = entityId;
            this.location = location;
            this.profile = profile;
        }
    }
}