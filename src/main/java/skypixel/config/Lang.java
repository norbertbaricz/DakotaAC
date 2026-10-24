package skypixel.config;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.YamlConfiguration;
import skypixel.dakotaAC;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Immutable message snapshot. Inserted text cannot expand other placeholders. */
public final class Lang {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([A-Za-z0-9_-]+)}");
    private static volatile Map<String, String> messages = Map.of();

    private Lang() { }

    public static void reload(dakotaAC plugin) throws IOException, org.bukkit.configuration.InvalidConfigurationException {
        File file = new File(plugin.getDataFolder(), "lang.yml");
        if (!file.exists()) plugin.saveResource("lang.yml", false);
        YamlConfiguration defaults;
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(plugin.getResource("lang.yml")), StandardCharsets.UTF_8)) {
            defaults = YamlConfiguration.loadConfiguration(reader);
        }
        if (messages.isEmpty()) {
            Map<String, String> initial = new HashMap<>();
            for (String key : defaults.getKeys(true)) if (defaults.isString(key)) initial.put(key, defaults.getString(key));
            messages = Map.copyOf(initial);
        }
        YamlConfiguration custom = new YamlConfiguration();
        custom.load(file);
        boolean changed = false;
        Map<String, String> snapshot = new HashMap<>();
        for (String key : defaults.getKeys(true)) {
            if (!defaults.isString(key)) continue;
            if (!custom.contains(key)) {
                custom.set(key, defaults.getString(key));
                changed = true;
            }
            if (!custom.isString(key)) throw new org.bukkit.configuration.InvalidConfigurationException(key);
            snapshot.put(key, custom.getString(key));
        }
        if (changed) custom.save(file);
        messages = Map.copyOf(snapshot);
    }

    public static String text(String key, Object... values) {
        Map<String, Object> replacements = new HashMap<>();
        replacements.put("prefix", messages.getOrDefault("prefix", ""));
        for (int i = 0; i + 1 < values.length; i += 2) replacements.put(String.valueOf(values[i]), values[i + 1]);
        return ChatColor.translateAlternateColorCodes('&', format(messages.getOrDefault(key, key), replacements));
    }

    public static String format(String template, Map<String, ?> values) {
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder output = new StringBuilder();
        while (matcher.find()) {
            Object value = values.get(matcher.group(1));
            matcher.appendReplacement(output, Matcher.quoteReplacement(value == null ? matcher.group() : value.toString()));
        }
        matcher.appendTail(output);
        return output.toString();
    }
}
