package skypixel.config;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SettingsTest {
    @Option(value = "limit", min = 1, max = 100) private static int limit;
    @Option(value = "balance", min = -1000, max = -1) private static long balance;
    @Option(value = "names", minSize = 1) private static String[] names;
    private Option option(String name) throws Exception { return getClass().getDeclaredField(name).getAnnotation(Option.class); }

    @Test void acceptsTypedBoundedIntegers() throws Exception { assertEquals(25, Settings.convert(25, int.class, option("limit"))); }
    @Test void rejectsFractionalIntegerThresholds() throws Exception {
        Option option = option("limit");
        assertThrows(IllegalArgumentException.class, () -> Settings.convert(2.5, int.class, option));
    }
    @Test void rejectsNonFiniteAndOutOfRangeThresholds() throws Exception {
        Option option = option("limit");
        for (double value : new double[]{0, 101, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> Settings.convert(value, double.class, option));
        }
    }
    @Test void retainsNegativeTimerBalanceThresholds() throws Exception { assertEquals(-250L, Settings.convert(-250, long.class, option("balance"))); }
    @Test void rejectsMistypedBooleanValues() throws Exception {
        Option option = option("limit");
        assertThrows(IllegalArgumentException.class, () -> Settings.convert("false", boolean.class, option));
        assertEquals(false, Settings.convert(false, boolean.class, option));
    }
    @Test void acceptsEmptyOptionalLists() throws Exception {
        assertArrayEquals(new String[0], (String[]) Settings.convert(java.util.List.of(), String[].class, option("limit")));
    }
    @Test void rejectsEmptyListsUsedForRandomDecoySelection() throws Exception {
        Option option = option("names");
        assertThrows(IllegalArgumentException.class, () -> Settings.convert(java.util.List.of(), String[].class, option));
    }
}
