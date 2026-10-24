package skypixel.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.BukkitConverters;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedGameProfile;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.lang.reflect.Constructor;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** All version-sensitive packet field access belongs here, rather than inside check algorithms. */
public final class PacketAccess {
    private static final Map<String, PacketType> CLIENT_TYPES = new ConcurrentHashMap<>();
    private static final java.util.Set<String> ABSENT_TYPES = ConcurrentHashMap.newKeySet();

    private PacketAccess() { }

    public static PacketType clientType(String name) {
        if (ABSENT_TYPES.contains(name)) return null;
        PacketType cached = CLIENT_TYPES.get(name);
        if (cached != null) return cached;
        try {
            PacketType type = (PacketType) PacketType.Play.Client.class.getField(name).get(null);
            CLIENT_TYPES.put(name, type);
            return type;
        } catch (ReflectiveOperationException ex) {
            ABSENT_TYPES.add(name);
            return null;
        }
    }

    public static boolean isUseEntity(PacketType type) {
        return type == PacketType.Play.Client.USE_ENTITY || type == clientType("ATTACK");
    }

    public static boolean isSwing(PacketType type) {
        return type == PacketType.Play.Client.ARM_ANIMATION || type == clientType("PUNCH");
    }

    public static boolean isAttack(PacketEvent event) {
        if (event.getPacketType() == clientType("ATTACK")) return true;
        if (event.getPacketType() != PacketType.Play.Client.USE_ENTITY) return false;
        // The old action wrapper expects an ATTACK singleton inside USE_ENTITY. On split protocols it is absent.
        PacketType attack = clientType("ATTACK");
        if (attack != null && attack.isSupported()) return false;
        PacketContainer packet = event.getPacket();
        if (packet.getEnumEntityUseActions().size() > 0) {
            return packet.getEnumEntityUseActions().read(0).getAction() == EnumWrappers.EntityUseAction.ATTACK;
        }
        if (packet.getEntityUseActions().size() > 0) {
            return packet.getEntityUseActions().read(0) == EnumWrappers.EntityUseAction.ATTACK;
        }
        throw new IllegalStateException("USE_ENTITY.action");
    }

    public static double coordinate(PacketContainer packet, int axis) {
        StructureModifier<Double> doubles = packet.getDoubles();
        return PacketFields.coordinate(doubles.size(), index -> doubles.read(index), () -> {
            StructureModifier<Vector> vectors = packet.getVectors();
            return vectors.size() == 0 ? null : vectors.read(0);
        }, axis);
    }

    public static float rotation(PacketContainer packet, int axis) {
        return PacketFields.rotation(packet.getFloat().read(axis));
    }

    public static boolean onGround(PacketContainer packet) {
        StructureModifier<Boolean> booleans = packet.getBooleans();
        StructureModifier<Byte> bytes = packet.getBytes();
        return PacketFields.onGround(booleans.size(), () -> booleans.read(0), bytes.size(), () -> bytes.read(0));
    }

    public static PacketContainer playerInfo(WrappedGameProfile profile) {
        PacketContainer packet = create(PacketType.Play.Server.PLAYER_INFO);
        packet.getPlayerInfoActions().write(0, EnumSet.of(EnumWrappers.PlayerInfoAction.ADD_PLAYER,
                EnumWrappers.PlayerInfoAction.UPDATE_GAME_MODE, EnumWrappers.PlayerInfoAction.UPDATE_LISTED,
                EnumWrappers.PlayerInfoAction.UPDATE_DISPLAY_NAME));
        packet.getPlayerInfoDataLists().write(0, Collections.singletonList(new PlayerInfoData(
                profile.getUUID(), 0, true, EnumWrappers.NativeGameMode.SURVIVAL,
                profile, WrappedChatComponent.fromText(profile.getName()))));
        return packet;
    }

    public static PacketContainer spawnPlayer(int entityId, WrappedGameProfile profile, Location location) {
        PacketContainer packet = create(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, profile.getUUID());
        packet.getEntityTypeModifier().write(0, EntityType.PLAYER);
        writePosition(packet, location);
        packet.getBytes().write(0, angle(location.getPitch())).write(1, angle(location.getYaw()));
        if (packet.getBytes().size() > 2) packet.getBytes().write(2, angle(location.getYaw()));
        if (packet.getIntegers().size() > 1) packet.getIntegers().write(1, 0);
        return packet;
    }

    public static PacketContainer teleport(int entityId, Location location) {
        PacketContainer packet = create(PacketType.Play.Server.ENTITY_TELEPORT);
        packet.getIntegers().write(0, entityId);
        if (packet.getDoubles().size() >= 3) {
            writePosition(packet, location);
            packet.getBytes().write(0, angle(location.getYaw())).write(1, angle(location.getPitch()));
        } else {
            // PositionMoveRotation was introduced in 1.21.2. Use its runtime shape, not a 5.5-only wrapper.
            boolean written = false;
            for (int index = 0; index < packet.getModifier().size(); index++) {
                Class<?> type = packet.getModifier().getField(index).getType();
                if (type.isPrimitive() || !type.isRecord()) continue;
                try {
                    Class<?> vectorType = MinecraftReflection.getVec3DClass();
                    Constructor<?> constructor = type.getDeclaredConstructor(vectorType, vectorType, float.class, float.class);
                    constructor.setAccessible(true);
                    Object position = BukkitConverters.getVectorConverter().getGeneric(location.toVector());
                    Object velocity = BukkitConverters.getVectorConverter().getGeneric(new Vector());
                    packet.getModifier().write(index, constructor.newInstance(position, velocity, location.getYaw(), location.getPitch()));
                    written = true;
                    break;
                } catch (NoSuchMethodException ignored) {
                    // Other record fields are not the movement record.
                } catch (ReflectiveOperationException ex) {
                    throw new IllegalStateException(type.getName(), ex);
                }
            }
            if (!written) throw new IllegalStateException("ENTITY_TELEPORT.position");
            // New teleport packets carry relative-movement flags; an empty set means absolute coordinates.
            for (int index = 0; index < packet.getModifier().size(); index++) {
                if (java.util.Set.class.isAssignableFrom(packet.getModifier().getField(index).getType())) {
                    packet.getModifier().write(index, Collections.emptySet());
                }
            }
        }
        packet.getBooleans().write(0, false);
        return packet;
    }

    private static void writePosition(PacketContainer packet, Location location) {
        if (packet.getDoubles().size() >= 3) {
            packet.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
        } else if (packet.getVectors().size() > 0) {
            packet.getVectors().write(0, location.toVector());
        } else {
            throw new IllegalStateException(packet.getType().name() + ".position");
        }
    }

    public static void fakeInventorySlot(Player player, int slot, ItemStack item) {
        // This packet targets player-inventory slots directly and does not use a container state id.
        try {
            PacketType inventory = (PacketType) PacketType.Play.Server.class.getField("SET_PLAYER_INVENTORY").get(null);
            if (inventory.isSupported()) {
                PacketContainer packet = create(inventory);
                packet.getIntegers().write(0, slot);
                packet.getItemModifier().write(0, item);
                ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
                return;
            }
        } catch (NoSuchFieldException ignored) {
            // Older ProtocolLib APIs expose the container packet only.
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("SET_PLAYER_INVENTORY", ex);
        }
        PacketContainer packet = create(PacketType.Play.Server.SET_SLOT);
        StructureModifier<Integer> integers = packet.getIntegers();
        // The supported modern layout is container id, state id, slot. Never send a partly populated packet.
        if (integers.size() != 3) throw new IllegalStateException("SET_SLOT.layout");
        integers.write(0, 0).write(1, 0).write(2, slot);
        packet.getItemModifier().write(0, item);
        ProtocolLibrary.getProtocolManager().sendServerPacket(player, packet);
    }

    private static PacketContainer create(PacketType type) {
        PacketContainer packet = ProtocolLibrary.getProtocolManager().createPacket(type);
        packet.getModifier().writeDefaults();
        return packet;
    }

    private static byte angle(float degrees) { return (byte) (degrees * 256.0F / 360.0F); }
}
