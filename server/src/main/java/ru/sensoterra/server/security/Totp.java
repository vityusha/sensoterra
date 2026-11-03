package ru.sensoterra.server.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * TOTP (RFC 6238): HMAC-SHA1, 6 digits, 30 s steps — the defaults every authenticator app
 * (Google Authenticator, Yandex Key, Aegis, 1Password) understands without parameters.
 */
public final class Totp {

    public static final int STEP_SECONDS = 30;
    public static final int DIGITS = 6;
    /** Accepted clock drift between the phone and the server, in steps either way. */
    public static final int WINDOW = 1;

    private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Totp() {
    }

    /** New random secret, 160 bits (the HMAC-SHA1 key size), Base32 without padding. */
    public static String newSecret() {
        byte[] key = new byte[20];
        RANDOM.nextBytes(key);
        return base32Encode(key);
    }

    public static long stepAt(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /** Code for a time step, zero-padded to {@link #DIGITS}. */
    public static String code(String secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 is unavailable", e);
        }
    }

    /**
     * Time step the code matches within ±{@link #WINDOW} of {@code epochSeconds}, newer than
     * {@code lastStep} (replay protection); empty if none.
     */
    public static OptionalLong verify(String secret, String code, long epochSeconds, Long lastStep) {
        if (secret == null || code == null) {
            return OptionalLong.empty();
        }
        String digits = code.replaceAll("\\s", "");
        if (!digits.matches("\\d{" + DIGITS + "}")) {
            return OptionalLong.empty();
        }
        long now = stepAt(epochSeconds);
        for (long step = now - WINDOW; step <= now + WINDOW; step++) {
            if (lastStep != null && step <= lastStep) {
                continue;
            }
            if (constantTimeEquals(code(secret, step), digits)) {
                return OptionalLong.of(step);
            }
        }
        return OptionalLong.empty();
    }

    /** otpauth:// URI for the QR code; the issuer shows up as the entry name in the app. */
    public static String otpauthUri(String issuer, String account, String secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static boolean constantTimeEquals(String a, String b) {
        return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII),
                b.getBytes(StandardCharsets.US_ASCII));
    }

    static String base32Encode(byte[] data) {
        StringBuilder out = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff);
            bits += 8;
            while (bits >= 5) {
                out.append(BASE32.charAt((buffer >> (bits - 5)) & 0x1f));
                bits -= 5;
            }
        }
        if (bits > 0) {
            out.append(BASE32.charAt((buffer << (5 - bits)) & 0x1f));
        }
        return out.toString();
    }

    static byte[] base32Decode(String text) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int buffer = 0;
        int bits = 0;
        for (char c : text.toUpperCase(Locale.ROOT).toCharArray()) {
            if (c == '=' || c == ' ') {
                continue;
            }
            int value = BASE32.indexOf(c);
            if (value < 0) {
                throw new IllegalArgumentException("Invalid Base32 character");
            }
            buffer = (buffer << 5) | value;
            bits += 5;
            if (bits >= 8) {
                out.write((buffer >> (bits - 8)) & 0xff);
                bits -= 8;
            }
        }
        return out.toByteArray();
    }
}
