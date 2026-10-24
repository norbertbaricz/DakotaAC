package skypixel.compat;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.utility.MinecraftVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketFamilyTest {
    @BeforeAll static void setProtocolMetadataVersion() {
        // These are packet-name metadata tests, not a running Minecraft server.
        String version = System.getProperty("dakota.test.paper-version", "1.21.11").split("-")[0];
        String[] parts = version.split("\\.");
        int patch = parts.length > 2 && parts[2].matches("\\d+") ? Integer.parseInt(parts[2]) : 0;
        MinecraftVersion.setCurrentVersion(new MinecraftVersion(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), patch));
    }
    @Test void optionalPacketLookupWorksWithOldAndNewProtocolLibApis() {
        assertSame(PacketType.Play.Client.USE_ENTITY, PacketAccess.clientType("USE_ENTITY"));
        assertNull(PacketAccess.clientType("DAKOTA_MISSING_PACKET"));
        assertNull(PacketAccess.clientType("DAKOTA_MISSING_PACKET"));
    }
    @Test void swingFamilyAcceptsLegacyAndNewNamesOnly() {
        assertTrue(PacketAccess.isSwing(PacketType.Play.Client.ARM_ANIMATION));
        PacketType punch = PacketAccess.clientType("PUNCH");
        if (punch != null) assertTrue(PacketAccess.isSwing(punch));
        assertFalse(PacketAccess.isSwing(PacketType.Play.Client.LOOK));
    }
    @Test void attackFamilyAcceptsSplitAttackPacketsWhenPresent() {
        assertTrue(PacketAccess.isUseEntity(PacketType.Play.Client.USE_ENTITY));
        PacketType attack = PacketAccess.clientType("ATTACK");
        if (attack != null) assertTrue(PacketAccess.isUseEntity(attack));
        assertFalse(PacketAccess.isUseEntity(PacketType.Play.Client.LOOK));
    }
}
