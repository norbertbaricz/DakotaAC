package skypixel.config;

import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import skypixel.dakotaAC;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reloads explicitly annotated fields in our own checks. Packet layouts are never configured here. */
public final class Settings {
    private static final Map<Field, Object> DEFAULTS = new HashMap<>();

    private Settings() { }

    public static void bind(Class<?> type, String section) throws ReflectiveOperationException {
        FileConfiguration config = dakotaAC.getInstance().getConfig();
        for (Field field : type.getDeclaredFields()) {
            Option option = field.getAnnotation(Option.class);
            if (option == null) continue;
            field.setAccessible(true);
            if (!DEFAULTS.containsKey(field)) DEFAULTS.put(field, field.get(null));
            Object fallback = DEFAULTS.get(field);
            String path = section + "." + option.value();
            Object value;
            try {
                value = convert(config.get(path, fallback), field.getType(), option);
            } catch (IllegalArgumentException ex) {
                value = fallback;
                dakotaAC.getInstance().getLogger().warning(Lang.text("system.invalid-setting", "path", path));
            }
            field.set(null, value);
        }
    }

    static Object convert(Object input, Class<?> type, Option option) {
        if (type == boolean.class && input instanceof Boolean) return input;
        if (type == String.class && input instanceof String) return input;
        if (type == String[].class && input instanceof List<?> list && list.stream().allMatch(String.class::isInstance)) {
            if (list.size() < option.minSize()) throw new IllegalArgumentException();
            return list.toArray(String[]::new);
        }
        if (type == Material[].class && input instanceof List<?> list) {
            if (list.size() < option.minSize()) throw new IllegalArgumentException();
            return list.stream().map(value -> {
                Material material = Material.matchMaterial(String.valueOf(value).toUpperCase(Locale.ROOT));
                if (material == null) throw new IllegalArgumentException();
                return material;
            }).toArray(Material[]::new);
        }
        if (input instanceof Number number) {
            double value = number.doubleValue();
            if (!Double.isFinite(value) || value < option.min() || value > option.max()) throw new IllegalArgumentException();
            if (type == double.class) return value;
            if (type == float.class && Math.abs(value) <= Float.MAX_VALUE) return (float) value;
            if (value != Math.rint(value)) throw new IllegalArgumentException();
            if (type == int.class && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) return (int) value;
            if (type == long.class && value >= Long.MIN_VALUE && value <= Long.MAX_VALUE) return number.longValue();
        }
        throw new IllegalArgumentException();
    }
}
