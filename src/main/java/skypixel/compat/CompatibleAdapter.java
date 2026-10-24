package skypixel.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import org.bukkit.plugin.Plugin;

import java.util.LinkedHashSet;
import java.util.Set;

/** Registers logical packet families without referring to fields absent from older ProtocolLib builds. */
public abstract class CompatibleAdapter extends PacketAdapter {
    public CompatibleAdapter(Plugin plugin, PacketType... packets) {
        this(plugin, ListenerPriority.NORMAL, packets);
    }

    public CompatibleAdapter(Plugin plugin, ListenerPriority priority, PacketType... packets) {
        super(PacketAdapter.params(plugin, resolve(packets)).listenerPriority(priority).optionSync());
    }

    private static PacketType[] resolve(PacketType[] packets) {
        Set<PacketType> types = new LinkedHashSet<>();
        for (PacketType packet : packets) {
            if (packet == PacketType.Play.Client.ARM_ANIMATION && !packet.isSupported()) {
                PacketType punch = PacketAccess.clientType("PUNCH");
                if (punch != null && punch.isSupported()) packet = punch;
            }
            if (packet == PacketType.Play.Client.FLYING) {
                PacketType ground = PacketAccess.clientType("GROUND");
                if (ground != null && ground.isSupported()) packet = ground;
            }
            if (!packet.isSupported()) throw new IllegalStateException(packet.name());
            types.add(packet);
            if (packet == PacketType.Play.Client.USE_ENTITY) {
                PacketType attack = PacketAccess.clientType("ATTACK");
                if (attack != null && attack.isSupported()) types.add(attack);
            }
            if (packet == PacketType.Play.Client.CHAT_COMMAND) {
                PacketType signed = PacketAccess.clientType("CHAT_COMMAND_SIGNED");
                if (signed != null && signed.isSupported()) types.add(signed);
            }
        }
        return types.toArray(PacketType[]::new);
    }
}
