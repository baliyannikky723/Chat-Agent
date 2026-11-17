package app.orrery.core.util;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * UUIDv7 identifiers (RFC 9562): the first 48 bits are the Unix time in milliseconds,
 * so ids sort by creation time and index inserts stay append-only.
 */
public final class Ids {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern UUID = Pattern.compile("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Ids() {}

    public static String uuidv7() {
        return uuidv7(System.currentTimeMillis());
    }

    public static String uuidv7(long epochMillis) {
        byte[] b = new byte[16];
        RANDOM.nextBytes(b);
        b[0] = (byte) (epochMillis >>> 40);
        b[1] = (byte) (epochMillis >>> 32);
        b[2] = (byte) (epochMillis >>> 24);
        b[3] = (byte) (epochMillis >>> 16);
        b[4] = (byte) (epochMillis >>> 8);
        b[5] = (byte) epochMillis;
        b[6] = (byte) ((b[6] & 0x0f) | 0x70);
        b[8] = (byte) ((b[8] & 0x3f) | 0x80);
        StringBuilder sb = new StringBuilder(36);
        for (int i = 0; i < 16; i++) {
            if (i == 4 || i == 6 || i == 8 || i == 10) sb.append('-');
            sb.append(HEX[(b[i] >> 4) & 0xF]).append(HEX[b[i] & 0xF]);
        }
        return sb.toString();
    }

    public static boolean isUuid(String value) {
        return value != null && UUID.matcher(value).matches();
    }
}
