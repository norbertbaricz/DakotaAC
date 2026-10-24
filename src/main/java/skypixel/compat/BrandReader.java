package skypixel.compat;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Decodes the VarInt-prefixed UTF-8 string carried by minecraft:brand. */
public final class BrandReader {
    private static final int MAX_STRING_BYTES = 32767;

    private BrandReader() { }

    public static String decode(byte[] payload) {
        int length = 0;
        int offset = 0;
        int value;
        do {
            if (offset >= payload.length || offset == 5) throw new IllegalArgumentException("brand.length");
            value = payload[offset] & 0xff;
            if (offset == 4 && (value & 0xf0) != 0) throw new IllegalArgumentException("brand.length");
            length |= (value & 0x7f) << (7 * offset++);
        } while ((value & 0x80) != 0);
        if (length < 0 || length > MAX_STRING_BYTES || length != payload.length - offset) throw new IllegalArgumentException("brand.length");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(payload, offset, length))
                    .toString().toLowerCase(Locale.ROOT);
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("brand.utf8", ex);
        }
    }
}
