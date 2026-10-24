package skypixel.compat;

import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PacketFieldsTest {
    @Test void readsFlatCoordinatesWithoutLookingForVectors() {
        double[] fields = {1.25, 64, -3.5};
        assertEquals(64.0, PacketFields.coordinate(fields.length, axis -> fields[axis], () -> { throw new AssertionError(); }, 1));
    }
    @Test void readsVectorCoordinatesWhenFlatDoublesAreAbsent() {
        assertEquals(-4.25, PacketFields.coordinate(0, axis -> { throw new AssertionError(); }, () -> new Vector(2.5, 80, -4.25), 2));
    }
    @Test void readsBooleanGroundStateWithoutReadingFlags() {
        assertTrue(PacketFields.onGround(2, () -> true, 0, () -> { throw new AssertionError(); }));
        assertFalse(PacketFields.onGround(2, () -> false, 0, () -> { throw new AssertionError(); }));
    }
    @Test void decodesGroundBitWithoutConfusingHorizontalCollision() {
        assertTrue(PacketFields.onGround(0, () -> { throw new AssertionError(); }, 1, () -> 3));
        assertFalse(PacketFields.onGround(0, () -> { throw new AssertionError(); }, 1, () -> 2));
    }
    @Test void missingFieldsAreReportedInsteadOfBecomingZeroCoordinates() {
        assertThrows(IllegalStateException.class, () -> PacketFields.coordinate(0, axis -> 0, () -> null, 0));
        assertThrows(IllegalStateException.class, () -> PacketFields.onGround(0, () -> false, 0, () -> 0));
    }
    @Test void rejectsNonFiniteMovementValues() {
        assertThrows(IllegalArgumentException.class, () -> PacketFields.coordinate(3, axis -> Double.NaN, () -> null, 0));
        assertThrows(IllegalArgumentException.class, () -> PacketFields.coordinate(0, axis -> 0, () -> new Vector(0, Double.POSITIVE_INFINITY, 0), 1));
        assertThrows(IllegalArgumentException.class, () -> PacketFields.rotation(Float.POSITIVE_INFINITY));
    }
}
