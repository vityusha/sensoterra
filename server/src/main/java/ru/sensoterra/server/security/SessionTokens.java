package ru.sensoterra.server.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.ServerAuthentication;
import io.micronaut.security.token.generator.TokenGenerator;
import jakarta.inject.Singleton;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.service.DeviceAccessService;

import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Issues every session JWT (password, Google/Yandex, invite sign-up, refresh).
 * <p>
 * Sliding session: a token lives {@link #TOKEN_TTL_SECONDS}, active clients refresh it, and
 * the refresh is refused {@link #MAX_SESSION_SECONDS} after the login ({@link #AUTH_TIME_CLAIM}).
 * Admins get a much shorter leash: {@link #ADMIN_TOKEN_TTL_SECONDS} /
 * {@link #ADMIN_MAX_SESSION_SECONDS}, and their tokens carry {@link #MFA_CLAIM} — without it
 * DbAccountSecurityRule refuses an ADMIN account.
 */
@Singleton
public class SessionTokens {

    /** JWT lifetime: an idle session expires after this; active clients refresh before it. */
    public static final int TOKEN_TTL_SECONDS = 24 * 3600;
    /** Absolute session limit counted from the login, however active the user is. */
    public static final long MAX_SESSION_SECONDS = 30L * 24 * 3600;
    public static final int ADMIN_TOKEN_TTL_SECONDS = 3600;
    public static final long ADMIN_MAX_SESSION_SECONDS = 12L * 3600;
    /** Claim with the original login time (epoch seconds), kept across refreshes. */
    public static final String AUTH_TIME_CLAIM = "auth_time";
    /** Set on admin tokens issued after the second factor (or with 2FA switched off in dev). */
    public static final String MFA_CLAIM = "mfa";

    private final TokenGenerator tokenGenerator;

    public SessionTokens(TokenGenerator tokenGenerator) {
        this.tokenGenerator = tokenGenerator;
    }

    /** New session from a fresh login. */
    public String issue(AppUser user, boolean mfa) {
        return issue(user, Instant.now().getEpochSecond(), mfa);
    }

    public String issue(AppUser user, long authTime, boolean mfa) {
        List<String> roles = Collections.singletonList(user.getRole());
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", user.getUsername());
        claims.put("roles", roles);
        claims.put(AUTH_TIME_CLAIM, authTime);
        if (mfa) {
            claims.put(MFA_CLAIM, true);
        }
        ServerAuthentication auth = new ServerAuthentication(user.getUsername(), roles, claims);
        return tokenGenerator.generateToken(auth, ttlSeconds(user)).orElseThrow(
                () -> new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Token generation failed"));
    }

    public static boolean isAdmin(AppUser user) {
        return DeviceAccessService.ROLE_ADMIN.equals(user.getRole());
    }

    public static int ttlSeconds(AppUser user) {
        return isAdmin(user) ? ADMIN_TOKEN_TTL_SECONDS : TOKEN_TTL_SECONDS;
    }

    public static long maxSessionSeconds(AppUser user) {
        return isAdmin(user) ? ADMIN_MAX_SESSION_SECONDS : MAX_SESSION_SECONDS;
    }

    public static boolean hasMfa(Authentication authentication) {
        return Boolean.TRUE.equals(authentication.getAttributes().get(MFA_CLAIM));
    }

    /** Login time of the session; tokens issued before auth_time existed count from now. */
    public static long authTimeOf(Authentication authentication) {
        Object value = authentication.getAttributes().get(AUTH_TIME_CLAIM);
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof java.util.Date d) {
            return d.getTime() / 1000;
        }
        return Instant.now().getEpochSecond();
    }
}
