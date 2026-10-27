package ru.sensoterra.server.security;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Rules for usernames and passwords of local accounts, shared by every place that sets one:
 * self-registration, registration by invite, first-admin bootstrap, admin create/update and
 * password change. The client mirrors them in {@code client/src/utils/credentials.js}, so the
 * user sees the reason before submitting; keep both in sync.
 * <p>
 * Messages are machine-stable English: the client shows its own Russian text for them.
 */
public final class CredentialsPolicy {

    public static final int MIN_USERNAME_LENGTH = 3;
    /** Column app_user.username is VARCHAR(50). */
    public static final int MAX_USERNAME_LENGTH = 50;
    public static final int MIN_PASSWORD_LENGTH = 8;
    /** BCrypt ignores everything after 72 bytes: a longer password would only look stronger. */
    public static final int MAX_PASSWORD_BYTES = 72;

    /** Letters (any script), digits and . _ - @ + — enough for names and e-mail-like logins. */
    private static final Pattern USERNAME = Pattern.compile("[\\p{L}\\p{N}._@+-]+");

    /** The most common passwords that pass the length rule; checked case-insensitively. */
    private static final Set<String> COMMON = Set.of(
            "12345678", "123456789", "1234567890", "87654321", "11111111", "00000000",
            "password", "password1", "passw0rd", "qwertyui", "qwerty123", "qwertyuiop",
            "1q2w3e4r", "1qaz2wsx", "zaq12wsx", "asdfghjk", "iloveyou", "sunshine",
            "admin123", "administrator", "welcome1", "letmein1", "abc12345", "abcd1234",
            "sensoterra", "йцукенгш");

    private CredentialsPolicy() {
    }

    /** Trimmed, validated username; 400 otherwise. */
    public static String requireUsername(String raw) {
        String username = raw == null ? "" : raw.trim();
        if (username.isEmpty()) {
            throw bad("Username is required");
        }
        if (username.length() < MIN_USERNAME_LENGTH || username.length() > MAX_USERNAME_LENGTH) {
            throw bad("Username must be " + MIN_USERNAME_LENGTH + "-" + MAX_USERNAME_LENGTH + " characters");
        }
        if (!USERNAME.matcher(username).matches()) {
            throw bad("Username may contain only letters, digits and . _ - @ +");
        }
        return username;
    }

    /** 400 if the password breaks the policy; username may be null. */
    public static void requirePassword(String password, String username) {
        if (password == null || password.isBlank()) {
            throw bad("Password is required");
        }
        if (password.length() < MIN_PASSWORD_LENGTH) {
            throw bad("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw bad("Password must be at most " + MAX_PASSWORD_BYTES + " bytes");
        }
        String lower = password.toLowerCase(Locale.ROOT);
        if (username != null && lower.equals(username.trim().toLowerCase(Locale.ROOT))) {
            throw bad("Password must not be the same as the username");
        }
        if (password.chars().distinct().count() == 1 || COMMON.contains(lower)) {
            throw bad("Password is too common");
        }
    }

    private static HttpStatusException bad(String message) {
        return new HttpStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
