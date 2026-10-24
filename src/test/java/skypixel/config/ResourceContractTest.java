package skypixel.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

class ResourceContractTest {
    private YamlConfiguration resource(String name) {
        return YamlConfiguration.loadConfiguration(new InputStreamReader(getClass().getResourceAsStream("/" + name), StandardCharsets.UTF_8));
    }

    @Test void everyCheckAndAnnotatedDefaultHasAConfigurationEntry() throws Exception {
        YamlConfiguration config = resource("config.yml");
        assertEquals(44, config.getConfigurationSection("Modules").getKeys(false).size());
        Path source = Path.of("src/main/java/skypixel");
        int settings = 0;
        try (var paths = Files.walk(source)) {
            for (Path file : paths.filter(path -> path.toString().endsWith(".java")).toList()) {
                String category = file.getParent().getFileName().toString();
                if (!List.of("Combat", "Exploit", "Fun", "Misc", "Movement", "Player", "Render", "World").contains(category)) continue;
                String module = file.getFileName().toString().replace(".java", "");
                Class<?> type = Class.forName("skypixel." + category + "." + module, false, getClass().getClassLoader());
                for (var field : type.getDeclaredFields()) {
                    Option option = field.getAnnotation(Option.class);
                    if (option == null) continue;
                    String key = "Modules." + module + "." + option.value();
                    assertTrue(config.contains(key), key);
                    assertDoesNotThrow(() -> Settings.convert(config.get(key), field.getType(), option), key);
                    settings++;
                }
                for (String common : List.of("enabled", "cancel-events", "setbacks", "enforce-actions", "flag-enabled", "flag-weight", "punish", "bypass-permission")) {
                    assertTrue(config.contains("Modules." + module + "." + common), module + "." + common);
                }
            }
        }
        assertTrue(settings >= 220, "All threshold defaults must be exposed, not just module toggles.");
    }

    @Test void everyLiteralMessageKeyExistsInEnglishDefaults() throws Exception {
        YamlConfiguration lang = resource("lang.yml");
        Pattern pattern = Pattern.compile("Lang\\.text\\(\\\"([^\\\"]+)\\\"");
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            for (Path path : paths.filter(file -> file.toString().endsWith(".java")).toList()) {
                var matcher = pattern.matcher(Files.readString(path));
                while (matcher.find()) {
                    String key = matcher.group(1);
                    // Prefixes used for dynamic common/type keys are covered by their concrete message entries.
                    if (key.endsWith("-")) continue;
                    assertTrue(lang.isString(key), path + ": " + key);
                }
            }
        }
    }

    @Test void everyCommandAndGlobalPermissionIsDeclared() {
        YamlConfiguration descriptor = resource("plugin.yml");
        assertTrue(descriptor.contains("commands.dakotaac"));
        assertTrue(descriptor.contains("commands.report"));
        assertTrue(descriptor.contains("permissions.dakotaac.notification"));
        assertTrue(descriptor.getStringList("depend").contains("ProtocolLib"));
        assertTrue(descriptor.getStringList("softdepend").contains("ViaVersion"));
    }
}
