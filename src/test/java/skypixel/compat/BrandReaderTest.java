package skypixel.compat;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BrandReaderTest {
    private byte[] encode(String brand) {
        byte[] value = brand.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int length = value.length;
        do { int part = length & 0x7f; length >>>= 7; bytes.write(part | (length == 0 ? 0 : 0x80)); } while (length != 0);
        bytes.writeBytes(value);
        return bytes.toByteArray();
    }
    @Test void readsVanillaAndNormalizesCase() { assertEquals("vanilla", BrandReader.decode(encode("VANILLA"))); }
    @Test void readsMultipleVarIntBytesAndUtf8() {
        String brand = "Fabric".repeat(30) + "é";
        assertEquals(brand.toLowerCase(java.util.Locale.ROOT), BrandReader.decode(encode(brand)));
    }
    @Test void rejectsTruncatedLength() { assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{(byte) 0x80})); }
    @Test void rejectsTruncatedText() { assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{5, 'a'})); }
    @Test void rejectsTrailingBytes() { assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{1, 'a', 'b'})); }
    @Test void rejectsInvalidUtf8() { assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{1, (byte) 0xff})); }
    @Test void rejectsOversizedVarInt() { assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{-1,-1,-1,-1,-1,0})); }
    @Test void rejectsOverflowInsteadOfDecodingItAsAnEmptyBrand() {
        assertThrows(IllegalArgumentException.class, () -> BrandReader.decode(new byte[]{-128,-128,-128,-128,16}));
    }
}
