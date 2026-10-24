package skypixel.Notification;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViolationTest {
    @Test void retainsOriginalMultipleBasedThresholds() {
        assertFalse(Violation.crossed(798, 799, 800));
        assertTrue(Violation.crossed(799, 800, 800));
        assertFalse(Violation.crossed(800, 801, 800));
        assertTrue(Violation.crossed(1599, 1600, 800));
        assertTrue(Violation.crossed(1999, 2000, 2000));
    }
    @Test void weightedFlagsCannotSkipAPunishmentBoundary() {
        assertTrue(Violation.crossed(799, 803, 800));
        assertFalse(Violation.crossed(10, 14, 800));
    }
    @Test void invalidIntervalsNeverDivideByZero() { assertFalse(Violation.crossed(0, 1, 0)); }
}
