package skypixel.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class LangTest {
    @Test void replacesRepeatedPlaceholders() {
        assertEquals("Max -> Max: 3", Lang.format("{player} -> {player}: {flags}", Map.of("player", "Max", "flags", 3)));
    }
    @Test void neverExpandsPlaceholdersInsidePlayerSuppliedText() {
        assertEquals("{hack} $1 \\ - Fly", Lang.format("{player} - {hack}", Map.of("player", "{hack} $1 \\", "hack", "Fly")));
    }
    @Test void preservesUnknownPlaceholdersForTemplateEditing() {
        assertEquals("{missing}", Lang.format("{missing}", Map.of()));
    }
    @Test void supportsEmptyMessagesAndNewlines() {
        assertEquals("", Lang.format("", Map.of()));
        assertEquals("A\nB", Lang.format("A\n{name}", Map.of("name", "B")));
    }
}
