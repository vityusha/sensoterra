package ru.sensoterra.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.security.Totp;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("TOTP (RFC 6238)")
class TotpTest {

    /** ASCII "12345678901234567890" — the SHA-1 key of RFC 6238 Appendix B. */
    private static final String RFC_SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";

    @Test
    @DisplayName("RFC 6238 Appendix B vectors (last 6 of the 8 digits)")
    void rfcVectors() {
        assertEquals("287082", Totp.code(RFC_SECRET, Totp.stepAt(59)));
        assertEquals("081804", Totp.code(RFC_SECRET, Totp.stepAt(1111111109)));
        assertEquals("050471", Totp.code(RFC_SECRET, Totp.stepAt(1111111111)));
        assertEquals("005924", Totp.code(RFC_SECRET, Totp.stepAt(1234567890)));
        assertEquals("279037", Totp.code(RFC_SECRET, Totp.stepAt(2000000000)));
    }

    @Test
    @DisplayName("Accepts ±1 step, rejects older drift, replays and garbage")
    void verifyWindowAndReplay() {
        long now = 1_700_000_000L;
        long step = Totp.stepAt(now);
        assertEquals(step, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, null).orElseThrow());
        assertEquals(step - 1, Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 1), now, null).orElseThrow());
        assertEquals(step + 1, Totp.verify(RFC_SECRET, " " + Totp.code(RFC_SECRET, step + 1), now, null).orElseThrow());
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step - 2), now, null).isEmpty());
        // Already used: the same or an earlier step is refused.
        assertTrue(Totp.verify(RFC_SECRET, Totp.code(RFC_SECRET, step), now, step).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, "12345", now, null).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, "abcdef", now, null).isEmpty());
        assertTrue(Totp.verify(RFC_SECRET, null, now, null).isEmpty());
    }

    @Test
    @DisplayName("New secrets are 160-bit Base32 and work in otpauth URIs")
    void newSecret() {
        String secret = Totp.newSecret();
        assertEquals(32, secret.length());
        assertTrue(secret.matches("[A-Z2-7]+"));
        assertNotEquals(secret, Totp.newSecret());
        String uri = Totp.otpauthUri("Sensoterra", "ad min", secret);
        assertTrue(uri.startsWith("otpauth://totp/Sensoterra:ad%20min?secret=" + secret));
        assertTrue(uri.contains("issuer=Sensoterra"));
    }
}
