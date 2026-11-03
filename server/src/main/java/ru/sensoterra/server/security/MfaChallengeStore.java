package ru.sensoterra.server.security;

import jakarta.inject.Singleton;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Second step of an admin sign-in: the password (or Google/Yandex) is accepted, the TOTP code
 * is not yet. The client gets an opaque random token, not a JWT: Micronaut would accept any
 * JWT signed with our key as a full session.
 * <p>
 * In memory on purpose: a restart only means signing in again. Anyone can create challenges
 * by knowing an admin password only, so the map is capped and every challenge expires.
 */
@Singleton
public class MfaChallengeStore {

    public static final long TTL_MILLIS = 5 * 60 * 1000;
    public static final int MAX_ATTEMPTS = 5;
    static final int MAX_CHALLENGES = 1000;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();

    /** Pending sign-in of an admin; {@code setupSecret} is set once enrollment started. */
    public static final class Challenge {
        final long userId;
        final boolean enrollment;
        final long expiresAt;
        int attempts;
        volatile String setupSecret;

        Challenge(long userId, boolean enrollment, long expiresAt) {
            this.userId = userId;
            this.enrollment = enrollment;
            this.expiresAt = expiresAt;
        }

        public long userId() {
            return userId;
        }

        /** True when the admin has no TOTP yet and must enroll. */
        public boolean enrollment() {
            return enrollment;
        }

        public String setupSecret() {
            return setupSecret;
        }

        public void setupSecret(String secret) {
            this.setupSecret = secret;
        }
    }

    public String create(long userId, boolean enrollment) {
        long now = System.currentTimeMillis();
        if (challenges.size() >= MAX_CHALLENGES) {
            challenges.values().removeIf(c -> c.expiresAt < now);
            if (challenges.size() >= MAX_CHALLENGES) {
                // Still full of live challenges: drop them all rather than grow without bound.
                challenges.clear();
            }
        }
        byte[] raw = new byte[32];
        RANDOM.nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        challenges.put(token, new Challenge(userId, enrollment, now + TTL_MILLIS));
        return token;
    }

    /** Live challenge for the token, or empty if unknown/expired. */
    public Optional<Challenge> find(String token) {
        if (token == null) {
            return Optional.empty();
        }
        Challenge c = challenges.get(token);
        if (c == null) {
            return Optional.empty();
        }
        if (c.expiresAt < System.currentTimeMillis()) {
            challenges.remove(token);
            return Optional.empty();
        }
        return Optional.of(c);
    }

    /** Counts a wrong code; the challenge dies after {@link #MAX_ATTEMPTS}. */
    public void failed(String token, Challenge challenge) {
        synchronized (challenge) {
            challenge.attempts++;
            if (challenge.attempts >= MAX_ATTEMPTS) {
                challenges.remove(token);
            }
        }
    }

    /** Successful second step: the challenge is single-use. */
    public void complete(String token) {
        challenges.remove(token);
    }
}
