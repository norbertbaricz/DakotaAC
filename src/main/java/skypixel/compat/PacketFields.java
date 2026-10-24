package skypixel.compat;

import org.bukkit.util.Vector;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.IntToDoubleFunction;
import java.util.function.Supplier;

/** Shape-based readers, independent of server bootstrapping. Missing fields never become fake zero values. */
public final class PacketFields {
    private PacketFields() { }

    public static double coordinate(int doubleCount, IntToDoubleFunction doubles, Supplier<Vector> vector, int axis) {
        if (axis < 0 || axis > 2) throw new IllegalArgumentException("axis");
        double value;
        if (doubleCount >= 3) value = doubles.applyAsDouble(axis);
        else {
            Vector position = vector.get();
            if (position == null) throw new IllegalStateException("movement.position");
            value = switch (axis) { case 0 -> position.getX(); case 1 -> position.getY(); default -> position.getZ(); };
        }
        if (!Double.isFinite(value)) throw new IllegalArgumentException("movement.non-finite-position");
        return value;
    }

    public static boolean onGround(int booleanCount, BooleanSupplier booleans, int byteCount, IntSupplier bytes) {
        if (booleanCount > 0) return booleans.getAsBoolean();
        if (byteCount > 0) return (bytes.getAsInt() & 1) != 0;
        throw new IllegalStateException("movement.onGround");
    }

    public static float rotation(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("movement.non-finite-rotation");
        return value;
    }
}
