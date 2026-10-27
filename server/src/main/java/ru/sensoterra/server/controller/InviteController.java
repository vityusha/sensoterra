package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.DeviceInvite;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.security.AuthController;
import ru.sensoterra.server.security.CredentialsPolicy;
import ru.sensoterra.server.security.PasswordEncoder;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.InviteService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.ServerAuthentication;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.serde.annotation.Serdeable;

import java.security.Principal;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Ссылки-приглашения на доступ к устройству.
 *
 * GET  /api/devices/{chipId}/invites          — список (кто управляет устройством)
 * POST /api/devices/{chipId}/invites          — выдать приглашение
 * DELETE /api/devices/{chipId}/invites/{id}   — отозвать
 * GET  /api/invites/{token}                   — что внутри ссылки (анонимно)
 * POST /api/invites/{token}/accept            — принять (аккаунт уже есть)
 * POST /api/invites/{token}/register          — зарегистрироваться по ссылке и получить доступ
 */
@Controller
public class InviteController {

    private final InviteService invites;
    private final DeviceAccessService access;
    private final AppUserRepository userRepository;
    private final ru.sensoterra.server.service.UserPrefService prefs;
    private final PasswordEncoder passwordEncoder;
    private final TokenGenerator tokenGenerator;

    public InviteController(InviteService invites,
                            DeviceAccessService access,
                            AppUserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            TokenGenerator tokenGenerator,
                            ru.sensoterra.server.service.UserPrefService prefs) {
        this.invites = invites;
        this.access = access;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenGenerator = tokenGenerator;
        this.prefs = prefs;
    }

    @Get("/api/devices/{chipId}/invites")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public List<InviteView> list(Long chipId) {
        access.requireShare(chipId);
        return invites.listFor(chipId).stream().map(this::view).toList();
    }

    @Post("/api/devices/{chipId}/invites")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<InviteView> create(Long chipId, @Body InviteCreateRequest request) {
        access.requireShare(chipId);
        return HttpResponse.ok(view(invites.issue(chipId, access.currentUser().getId(),
                request != null ? request.accessRole() : null)));
    }

    @Delete("/api/devices/{chipId}/invites/{id}")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> revoke(Long chipId, Long id) {
        access.requireShare(chipId);
        invites.revoke(chipId, id);
        return HttpResponse.noContent();
    }

    /**
     * Убирает из списка завершённые приглашения устройства (отозванные и истёкшие).
     * Принятые остаются: они объясняют, кому выдавался доступ.
     */
    @Delete("/api/devices/{chipId}/invites/finished")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public Map<String, Object> clearFinished(Long chipId) {
        access.requireShare(chipId);
        return Map.of("removed", invites.clearFinished(chipId));
    }

    /**
     * Полная очистка приглашений устройства: принятые и живые тоже. Вызывать только
     * после подтверждения пользователем — живые ссылки перестанут работать.
     */
    @Delete("/api/devices/{chipId}/invites/all")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public Map<String, Object> clearAll(Long chipId) {
        access.requireShare(chipId);
        return Map.of("removed", invites.clearAll(chipId));
    }

    /** Анонимно: приглашённый открывает ссылку и видит, к какому устройству его зовут. */
    @Get("/api/invites/{token}")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public InvitePreview preview(String token) {
        DeviceInvite invite = invites.requireInvite(token);
        Device device = access.device(invite.getChipId());
        return new InvitePreview(invite.getChipId(), device.getDeviceType(), device.getName(),
                invites.usernameOf(invite.getCreatedBy()), invite.getAccessRole(),
                invite.getExpiresAt(), invite.isUsable());
    }

    /** Приглашённый уже залогинен — просто забирает доступ. */
    @Post("/api/invites/{token}/accept")
    @Secured(SecurityRule.IS_AUTHENTICATED)
    public HttpResponse<?> accept(String token, Principal principal) {
        invites.accept(token, userRepository.findByUsername(principal.getName())
                .orElseThrow(() -> new HttpStatusException(HttpStatus.UNAUTHORIZED, "User not found")));
        return HttpResponse.noContent();
    }

    /**
     * Аккаунта ещё нет — по ссылке создаётся USER и сразу получает доступ к устройству.
     * Функции не включаются: управлять блоком можно после ввода ключа активации (V17).
     */
    @Post("/api/invites/{token}/register")
    @Secured(SecurityRule.IS_ANONYMOUS)
    public HttpResponse<?> register(String token, @Body InviteRegisterRequest request) {
        String username = CredentialsPolicy.requireUsername(request.username());
        CredentialsPolicy.requirePassword(request.password(), username);
        if (userRepository.findByUsername(username).isPresent()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        // Приглашение проверяем до создания аккаунта, чтобы по битой ссылке не плодить юзеров.
        invites.requireInvite(token);

        AppUser user = new AppUser();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole("USER");
        user.setEnabled(Boolean.TRUE);
        user.setCreatedAt(Instant.now());
        userRepository.save(user);
        prefs.seedDefaults(user.getId());

        invites.accept(token, user);
        return HttpResponse.ok(Map.of(
                "token", generateToken(user),
                "user", Map.of("id", user.getId(), "username", user.getUsername(),
                        "role", user.getRole())));
    }

    private String generateToken(AppUser user) {
        List<String> roles = Collections.singletonList(user.getRole());
        ServerAuthentication authentication = new ServerAuthentication(
                user.getUsername(), roles, Map.of("sub", user.getUsername(), "roles", roles,
                        AuthController.AUTH_TIME_CLAIM, java.time.Instant.now().getEpochSecond()));
        return tokenGenerator.generateToken(authentication, AuthController.TOKEN_TTL_SECONDS).orElseThrow(
                () -> new HttpStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Token generation failed"));
    }

    private InviteView view(DeviceInvite i) {
        return new InviteView(i.getId(), i.getToken(), i.getChipId(), invites.usernameOf(i.getCreatedBy()),
                i.getAccessRole(), i.getCreatedAt(), i.getExpiresAt(), i.getAcceptedAt(), i.isUsable());
    }

    @Serdeable
    public record InviteView(Long id, String token, Long chipId, String createdBy, String accessRole,
                             Instant createdAt, Instant expiresAt, Instant acceptedAt, boolean usable) {

    }

    @Serdeable
    public record InvitePreview(Long chipId, String deviceType, String deviceName, String invitedBy,
                                String accessRole, Instant expiresAt, boolean usable) {}

    /** accessRole: USER — принявший будет управлять, VIEWER — только читать. */
    @Serdeable
    public record InviteCreateRequest(String accessRole) {}

    @Serdeable
    public record InviteRegisterRequest(String username, String password) {}
}