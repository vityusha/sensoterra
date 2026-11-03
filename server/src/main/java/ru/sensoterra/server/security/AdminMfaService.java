package ru.sensoterra.server.security;

import io.micronaut.context.annotation.Context;
import io.micronaut.context.annotation.Value;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sensoterra.server.entity.AdminRecoveryCode;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AdminRecoveryCodeRepository;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.service.AdminAuditService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Second factor (TOTP) for every ADMIN account.
 * <p>
 * After the first factor (password, Google, Yandex) an admin gets an opaque challenge
 * ({@link MfaChallengeStore}) instead of a session: either a code is asked, or — with no
 * secret yet — enrollment (QR, first code, recovery codes). Only then a JWT with
 * {@link SessionTokens#MFA_CLAIM} is issued.
 * <p>
 * {@code sensoterra.security.admin-mfa: false} skips the second step for local development;
 * the prod environment refuses to start with it (eager bean, checked in the constructor).
 */
@Context
public class AdminMfaService {

    public static final String ISSUER = "Sensoterra";
    public static final int RECOVERY_CODES = 10;

    private static final Logger log = LoggerFactory.getLogger(AdminMfaService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String RECOVERY_ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789";

    private final boolean enabled;
    private final AppUserRepository userRepository;
    private final AdminRecoveryCodeRepository recoveryCodes;
    private final MfaChallengeStore challenges;
    private final SessionTokens tokens;
    private final AdminAuditService audit;

    public AdminMfaService(@Value("${sensoterra.security.admin-mfa:true}") boolean enabled,
                           Environment environment,
                           AppUserRepository userRepository,
                           AdminRecoveryCodeRepository recoveryCodes,
                           MfaChallengeStore challenges,
                           SessionTokens tokens,
                           AdminAuditService audit) {
        if (!enabled && environment.getActiveNames().contains("prod")) {
            throw new IllegalStateException(
                    "sensoterra.security.admin-mfa=false is not allowed in the prod environment");
        }
        if (!enabled) {
            log.warn("Admin two-factor authentication is DISABLED (SENSOTERRA_ADMIN_MFA=false) — dev only");
        }
        this.enabled = enabled;
        this.userRepository = userRepository;
        this.recoveryCodes = recoveryCodes;
        this.challenges = challenges;
        this.tokens = tokens;
        this.audit = audit;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** What a successful first factor yields: a session, or a challenge for the second step. */
    public record SignInStep(String token, String mfaToken, boolean setupRequired) {
        public boolean complete() {
            return token != null;
        }
    }

    /** Called after the first factor succeeded for an enabled account. */
    public SignInStep afterFirstFactor(AppUser user, String auditEvent) {
        if (!SessionTokens.isAdmin(user)) {
            return new SignInStep(tokens.issue(user, false), null, false);
        }
        audit.record(user, auditEvent, null);
        if (!enabled) {
            return new SignInStep(tokens.issue(user, true), null, false);
        }
        boolean enrollment = user.getTotpSecret() == null;
        return new SignInStep(null, challenges.create(user.getId(), enrollment), enrollment);
    }

    /** Enrollment, step 1: a new secret for the QR code (kept in the challenge until confirmed). */
    public Setup setup(String mfaToken) {
        MfaChallengeStore.Challenge challenge = challenge(mfaToken);
        if (!challenge.enrollment()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Two-factor authentication is already set up");
        }
        AppUser user = user(challenge);
        String secret = challenge.setupSecret();
        if (secret == null) {
            secret = Totp.newSecret();
            challenge.setupSecret(secret);
        }
        return new Setup(secret, Totp.otpauthUri(ISSUER, user.getUsername(), secret));
    }

    @Serdeable
    public record Setup(String secret, String otpauthUri) {
    }

    public record Enrolled(String token, AppUser user, List<String> recoveryCodes) {
    }

    /** Enrollment, step 2: the first code proves the app has the secret. */
    @Transactional
    public Enrolled enroll(String mfaToken, String code) {
        MfaChallengeStore.Challenge challenge = challenge(mfaToken);
        if (!challenge.enrollment() || challenge.setupSecret() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Start the setup first");
        }
        AppUser user = user(challenge);
        OptionalLong step = Totp.verify(challenge.setupSecret(), code, Instant.now().getEpochSecond(), null);
        if (step.isEmpty()) {
            wrongCode(mfaToken, challenge, user);
        }
        challenges.complete(mfaToken);
        user.setTotpSecret(challenge.setupSecret());
        user.setTotpLastStep(step.getAsLong());
        userRepository.update(user);
        List<String> codes = replaceRecoveryCodes(user.getId());
        audit.record(user, AdminAuditService.MFA_ENROLLED, null);
        return new Enrolled(tokens.issue(user, true), user, codes);
    }

    public record Verified(String token, AppUser user, int recoveryCodesLeft) {
    }

    /** Regular second step: a TOTP code or one of the recovery codes. */
    @Transactional
    public Verified verify(String mfaToken, String code, String recoveryCode) {
        MfaChallengeStore.Challenge challenge = challenge(mfaToken);
        if (challenge.enrollment()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Two-factor authentication is not set up yet");
        }
        AppUser user = user(challenge);
        if (recoveryCode != null && !recoveryCode.isBlank()) {
            AdminRecoveryCode match = findUnusedRecoveryCode(user.getId(), recoveryCode);
            if (match == null) {
                wrongCode(mfaToken, challenge, user);
            }
            challenges.complete(mfaToken);
            match.setUsedAt(Instant.now());
            recoveryCodes.update(match);
            int left = unusedRecoveryCodes(user.getId());
            audit.record(user, AdminAuditService.RECOVERY_CODE_USED, "left=" + left);
            return new Verified(tokens.issue(user, true), user, left);
        }
        OptionalLong step = Totp.verify(user.getTotpSecret(), code, Instant.now().getEpochSecond(),
                user.getTotpLastStep());
        if (step.isEmpty()) {
            wrongCode(mfaToken, challenge, user);
        }
        challenges.complete(mfaToken);
        user.setTotpLastStep(step.getAsLong());
        userRepository.update(user);
        audit.record(user, AdminAuditService.MFA_OK, null);
        return new Verified(tokens.issue(user, true), user, unusedRecoveryCodes(user.getId()));
    }

    private void wrongCode(String mfaToken, MfaChallengeStore.Challenge challenge, AppUser user) {
        challenges.failed(mfaToken, challenge);
        audit.record(user, AdminAuditService.MFA_FAIL, null);
        throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid code");
    }

    private MfaChallengeStore.Challenge challenge(String mfaToken) {
        return challenges.find(mfaToken).orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED,
                "Sign-in step expired, sign in again"));
    }

    /** The account may have been disabled, deleted or demoted since the first step. */
    private AppUser user(MfaChallengeStore.Challenge challenge) {
        AppUser user = userRepository.findById(challenge.userId()).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled()) || !SessionTokens.isAdmin(user)) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Sign-in step expired, sign in again");
        }
        return user;
    }

    private List<String> replaceRecoveryCodes(Long userId) {
        recoveryCodes.deleteByUserId(userId);
        List<String> codes = new ArrayList<>();
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = randomRecoveryCode();
            AdminRecoveryCode row = new AdminRecoveryCode();
            row.setUserId(userId);
            row.setCodeHash(hashRecoveryCode(code));
            recoveryCodes.save(row);
            codes.add(code);
        }
        return codes;
    }

    private AdminRecoveryCode findUnusedRecoveryCode(Long userId, String code) {
        byte[] hash = hashRecoveryCode(code).getBytes(StandardCharsets.US_ASCII);
        AdminRecoveryCode found = null;
        // No early exit: the same work whichever code (if any) matches.
        for (AdminRecoveryCode row : recoveryCodes.findByUserId(userId)) {
            if (row.getUsedAt() == null
                    && MessageDigest.isEqual(hash, row.getCodeHash().getBytes(StandardCharsets.US_ASCII))) {
                found = row;
            }
        }
        return found;
    }

    private int unusedRecoveryCodes(Long userId) {
        return (int) recoveryCodes.findByUserId(userId).stream().filter(r -> r.getUsedAt() == null).count();
    }

    /** 10 characters (~49 bits) shown as xxxxx-xxxxx; no 0/o/1/l/i to avoid misreading. */
    static String randomRecoveryCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(RECOVERY_ALPHABET.charAt(RANDOM.nextInt(RECOVERY_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** SHA-256 hex of the code without separators and case: codes are random, no salt needed. */
    public static String hashRecoveryCode(String code) {
        String normalized = code.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
