package ru.sensoterra.server.security;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.DeviceKeyService;
import ru.sensoterra.server.service.ExternalAccountService;
import ru.sensoterra.server.service.ExternalAccountService.ExternalIdentity;
import ru.sensoterra.server.service.ExternalAccountService.SignIn;
import ru.sensoterra.server.service.GoogleIdentityVerifier;
import ru.sensoterra.server.service.GoogleIdentityVerifier.GoogleIdentity;
import ru.sensoterra.server.service.YandexOAuthClient;
import ru.sensoterra.server.service.AdminAuditService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.*;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Controller("/api/auth")
@Secured(SecurityRule.IS_ANONYMOUS)
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AppUserRepository userRepository;
    private final ru.sensoterra.server.service.UserPrefService prefs;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokens tokens;
    private final AdminMfaService mfa;
    private final AdminAuditService audit;
    private final DeviceAccessService access;
    private final DeviceKeyService deviceKeys;
    private final GoogleIdentityVerifier googleVerifier;
    private final YandexOAuthClient yandexClient;
    private final ExternalAccountService externalAccounts;

    public AuthController(AppUserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          SessionTokens tokens,
                          AdminMfaService mfa,
                          AdminAuditService audit,
                          DeviceAccessService access,
                          DeviceKeyService deviceKeys,
                          GoogleIdentityVerifier googleVerifier,
                          YandexOAuthClient yandexClient,
                          ExternalAccountService externalAccounts,
                          ru.sensoterra.server.service.UserPrefService prefs) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.mfa = mfa;
        this.audit = audit;
        this.access = access;
        this.deviceKeys = deviceKeys;
        this.googleVerifier = googleVerifier;
        this.yandexClient = yandexClient;
        this.externalAccounts = externalAccounts;
        this.prefs = prefs;
    }

    @Post("/login")
    @Produces(MediaType.APPLICATION_JSON)
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> login(@Body LoginRequest request) {
        // На мобильных клавиатурах логин нередко приходит с пробелом/с большой буквы —
        // сравнение в БД точное, поэтому режем пробелы.
        String username = request.username() == null ? null : request.username().trim();
        AppUser user = username == null ? null : userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            log.warn("Failed login: username='{}' userFound={}", username, user != null);
            // У внешнего аккаунта пароля нет, поэтому подсказываем чем войти.
            if (user != null && user.isExternal()) {
                throw new HttpStatusException(HttpStatus.UNAUTHORIZED,
                        "This account signs in with " + user.getAuthProvider());
            }
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials");
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            log.warn("Login rejected: account disabled username='{}'", username);
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Account is disabled");
        }
        AdminMfaService.SignInStep step = mfa.afterFirstFactor(user, AdminAuditService.LOGIN_PASSWORD_OK);
        if (!step.complete()) {
            return HttpResponse.ok(challengeOf(step));
        }
        return HttpResponse.ok(Map.of("token", step.token(),
                "user", responseOf(user)));
    }

    @Get("/me")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public UserResponse me(Principal principal) {
        return responseOf(principalUser(principal));
    }

    /**
     * The owner lets admins fully control and manage all of their devices, or takes it back.
     * Takes effect at once (DeviceAccessService reads the flag per request) and is logged in
     * the admin audit so admins see who trusted them and when.
     */
    @Put("/admin-access")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    public UserResponse setAdminAccess(Principal principal, @Body AdminAccessRequest request) {
        AppUser user = principalUser(principal);
        if (SessionTokens.isAdmin(user)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Admins don't grant admin access");
        }
        boolean enabled = request.enabled();
        if (enabled != Boolean.TRUE.equals(user.getAdminAccess())) {
            user.setAdminAccess(enabled);
            user.setAdminAccessSince(enabled ? Instant.now() : null);
            userRepository.update(user);
            audit.record(user, enabled ? AdminAuditService.OWNER_ADMIN_ACCESS_GRANTED
                    : AdminAuditService.OWNER_ADMIN_ACCESS_REVOKED, null);
        }
        return responseOf(user);
    }

    /**
     * Смена собственного пароля.
     *
     * Текущий пароль спрашиваем обязательно: иначе утёкший токен дал бы не только доступ
     * к датчикам, но и возможность навсегда забрать аккаунт, выкинув хозяина через смену
     * пароля. Внешний аккаунт (Google) пароля не имеет — там пароль в Google, и заводить
     * второй способ входа без явного решения администратора нельзя.
     *
     * Токен при этом остаётся действительным: JWT у нас без серого списка, поэтому других
     * устройств разлогинить здесь нечем. Смена пароля защищает от будущего входа по старому.
     */
    @Put("/password")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    @Transactional
    public Map<String, Object> changePassword(Principal principal, @Body ChangePasswordRequest request) {
        AppUser user = principalUser(principal);
        if (user.isExternal()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "This account signs in with " + user.getAuthProvider());
        }
        String current = request.currentPassword();
        String next = request.newPassword();
        if (current == null || current.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Current password is required");
        }
        if (next == null || next.isBlank()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "New password is required");
        }
        CredentialsPolicy.requirePassword(next, user.getUsername());
        if (!passwordEncoder.matches(current, user.getPasswordHash())) {
            log.warn("Password change rejected: wrong current password username='{}'", user.getUsername());
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Current password is wrong");
        }
        if (passwordEncoder.matches(next, user.getPasswordHash())) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "New password must differ from the current one");
        }
        user.setPasswordHash(passwordEncoder.encode(next));
        // Сущность получена из репозитория внутри транзакции, то есть managed:
        // save здесь это UPDATE, а не попытка вставить новую строку.
        userRepository.save(user);
        log.info("Password changed for username='{}'", user.getUsername());
        return Map.of("ok", true);
    }

    /**
     * Самостоятельная регистрация покупателя. Аккаунты USER заводит только сам пользователь:
     * админ создаёт других админов, а обычный покупатель приходит со своим блоком.
     *
     * Если передан ID и ключ с комплектного листа, устройство привязывается к новому
     * аккаунту в той же транзакции: при неверной паре аккаунт не создаётся вовсе.
     */
    @Post("/register")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @Transactional
    public HttpResponse<?> register(@Body RegisterRequest request) {
        String username = CredentialsPolicy.requireUsername(request.username());
        CredentialsPolicy.requirePassword(request.password(), username);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(DeviceAccessService.ROLE_USER);
        user.setEnabled(Boolean.TRUE);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        prefs.seedDefaults(user.getId());

        boolean claimsDevice = request.chipId() != null || (request.key() != null && !request.key().isBlank());
        if (claimsDevice) {
            deviceKeys.claim(request.chipId(), request.key(), user.getId());
        }
        // Сразу отдаём токен: покупатель после регистрации не должен вводить пароль второй раз.
        return HttpResponse.ok(RegisterResponse.of(user, tokens.issue(user, false), claimsDevice));
    }

    /**
     * Вход и регистрация через Google. Фронт присылает id_token, который сам получил
     * от Google Identity Services; мы проверяем подпись и заводим (либо находим) аккаунт.
     */
    @Post("/oauth/google")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> loginWithGoogle(@Body GoogleLoginRequest request) {
        GoogleIdentity identity = googleVerifier.verify(request.idToken());
        SignIn result = externalAccounts.signIn(
                new ExternalIdentity(GoogleIdentityVerifier.provider(), identity.subject(),
                        identity.email(), identity.email()),
                request.chipId(), request.key());
        return externalSession(result);
    }

    /**
     * Sign-in and sign-up with Yandex ID. The browser comes back from Yandex with a
     * one-time code (plus the PKCE verifier it generated); the server exchanges it itself.
     * Runs on the blocking pool: the exchange is two HTTP calls to Yandex.
     */
    @Post("/oauth/yandex")
    @Secured(SecurityRule.IS_ANONYMOUS)
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<?> loginWithYandex(@Body YandexLoginRequest request) {
        ExternalIdentity identity = yandexClient.identify(request.code(), request.codeVerifier());
        SignIn result = externalAccounts.signIn(identity, request.chipId(), request.key());
        return externalSession(result);
    }

    /** An admin signing in through Google/Yandex still has to pass the TOTP step. */
    private HttpResponse<?> externalSession(SignIn result) {
        AdminMfaService.SignInStep step = mfa.afterFirstFactor(result.user(), AdminAuditService.LOGIN_EXTERNAL_OK);
        if (!step.complete()) {
            return HttpResponse.ok(challengeOf(step));
        }
        return HttpResponse.ok(RegisterResponse.of(result.user(), step.token(), result.deviceBound()));
    }

    @Get("/status")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public Map<String, Object> status() {
        // client_id Google публичный и виден в коде страницы, поэтому отдаём его
        // вместе с флагом: фронту не нужно знать про Vite-переменные и .env.
        return Map.of("googleEnabled", googleVerifier.isEnabled(),
                "googleClientId", googleVerifier.clientId(),
                "yandexEnabled", yandexClient.isEnabled(),
                "yandexClientId", yandexClient.clientId());
    }

    /**
     * Admin second step, enrollment 1/2: secret and otpauth:// URI for the QR code.
     * Admins are created only by the server CLI and enroll on their first sign-in.
     */
    @Post("/mfa/setup")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public AdminMfaService.Setup mfaSetup(@Body MfaRequest request) {
        return mfa.setup(request.mfaToken());
    }

    /** Enrollment 2/2: the first code from the app; returns the session and the recovery codes once. */
    @Post("/mfa/enroll")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public Map<String, Object> mfaEnroll(@Body MfaRequest request) {
        AdminMfaService.Enrolled enrolled = mfa.enroll(request.mfaToken(), request.code());
        return Map.of("token", enrolled.token(), "user", responseOf(enrolled.user()),
                "recoveryCodes", enrolled.recoveryCodes());
    }

    /** Regular second step: TOTP code or a one-time recovery code. Wrong code -> 401. */
    @Post("/mfa/verify")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public Map<String, Object> mfaVerify(@Body MfaRequest request) {
        AdminMfaService.Verified verified = mfa.verify(request.mfaToken(), request.code(), request.recoveryCode());
        return Map.of("token", verified.token(), "user", responseOf(verified.user()),
                "recoveryCodesLeft", verified.recoveryCodesLeft());
    }

    /**
     * Sliding session: the client calls this while the user is active (token past half its
     * life), so an active user is never logged out, while a session idle for more than the
     * token lifetime expires. DbAccountSecurityRule has already checked that the account
     * exists and is enabled.
     * <p>
     * The original login time travels in the auth_time claim; after
     * SessionTokens.maxSessionSeconds (30 days, 12 h for admins) the refresh is refused (401) and the user must sign in again — a stolen token can't be
     * kept alive forever.
     */
    @Post("/refresh")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> refresh(Authentication authentication) {
        AppUser user = userRepository.findByUsername(authentication.getName())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        long authTime = SessionTokens.authTimeOf(authentication);
        boolean admin = SessionTokens.isAdmin(user);
        // The limit follows the current role: an account promoted to ADMIN gets the admin one.
        if (Instant.now().getEpochSecond() - authTime > SessionTokens.maxSessionSeconds(user)) {
            if (admin) {
                audit.record(user, AdminAuditService.SESSION_REFUSED, "session limit");
            }
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Session expired");
        }
        // DbAccountSecurityRule already refuses an admin token without the claim; keep it explicit.
        if (admin && !SessionTokens.hasMfa(authentication)) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Session expired");
        }
        return HttpResponse.ok(Map.of("token", tokens.issue(user, authTime, admin), "user", responseOf(user)));
    }

    private AppUser principalUser(Principal principal) {
        AppUser user = userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (Boolean.FALSE.equals(user.getEnabled())) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "Account is disabled");
        }
        return user;
    }

    private static UserResponse responseOf(AppUser user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(), user.getRole(),
                user.getAuthProvider(), Boolean.TRUE.equals(user.getAdminAccess()), user.getAdminAccessSince());
    }

    /** Response of a first factor that needs the TOTP step: no session yet, only the challenge. */
    private static Map<String, Object> challengeOf(AdminMfaService.SignInStep step) {
        Map<String, Object> body = new HashMap<>();
        body.put(step.setupRequired() ? "mfaSetupRequired" : "mfaRequired", true);
        body.put("mfaToken", step.mfaToken());
        return body;
    }

    @Serdeable
    public record LoginRequest(String username, String password) {}

    @Serdeable
    public record RegisterRequest(String username, String password, Long chipId, String key) {}

    /** Вход через Google: подписанный id_token и, опционально, привязка своего блока. */
    @Serdeable
    public record GoogleLoginRequest(String idToken, Long chipId, String key) {}

    /** Yandex ID: one-time code, optional PKCE verifier and, optionally, the buyer's device pair. */
    @Serdeable
    public record YandexLoginRequest(String code, String codeVerifier, Long chipId, String key) {}

    /** Admin second step: code from the app, or recoveryCode instead when the phone is lost. */
    @Serdeable
    public record MfaRequest(String mfaToken, String code, String recoveryCode) {}

    @Serdeable
    public record ChangePasswordRequest(String currentPassword, String newPassword) {}

    /**
     * Профиль аккаунта. Платных полей нет: срок относится к блоку, а не к клиенту, и
     * приходит вместе с данными устройства.
     *
     * authProvider непустой у внешнего входа (google, yandex) — по нему фронт прячет форму смены
     * пароля и объясняет, где пароль на самом деле.
     */
    @Serdeable
    public record UserResponse(Long id, String username, String email, String role, String authProvider,
                               boolean adminAccess, Instant adminAccessSince) {}

    @Serdeable
    public record AdminAccessRequest(boolean enabled) {}

    /** Ответ регистрации: профиль аккаунта плюс токен и факт привязки устройства. */
    @Serdeable
    public record RegisterResponse(Long id, String username, String email, String role,
                                   String authProvider, String token, boolean deviceBound,
                                   boolean adminAccess, Instant adminAccessSince) {
        static RegisterResponse of(AppUser user, String token, boolean deviceBound) {
            return new RegisterResponse(user.getId(), user.getUsername(), user.getEmail(),
                    user.getRole(), user.getAuthProvider(), token, deviceBound,
                    Boolean.TRUE.equals(user.getAdminAccess()), user.getAdminAccessSince());
        }
    }
}