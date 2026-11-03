package ru.sensoterra.server;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.transaction.Transactional;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.repository.DeviceRepository;
import ru.sensoterra.server.security.PasswordEncoder;
import ru.sensoterra.server.security.SessionTokens;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.UserPrefService;

import java.util.Map;

/**
 * Test-only replacement for the removed HTTP bootstrap: in production admins come from the
 * server CLI (AdminCli). Creates an ADMIN if the name is free (409 otherwise) and returns a
 * session as if the 2FA step had passed — the MFA flow itself is covered by AdminMfaTest.
 */
@Requires(env = Environment.TEST)
@Controller("/api/test/admin")
@Secured(SecurityRule.IS_ANONYMOUS)
public class TestAdminController {

    private final AppUserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final SessionTokens tokens;
    private final UserPrefService prefs;
    private final DeviceRepository devices;
    private final DeviceAccessService access;

    public TestAdminController(AppUserRepository users, PasswordEncoder passwordEncoder,
                               SessionTokens tokens, UserPrefService prefs,
                               DeviceRepository devices, DeviceAccessService access) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.prefs = prefs;
        this.devices = devices;
        this.access = access;
    }

    /**
     * Fixture grant with the pre-restriction admin semantics: works on owned devices too
     * (the real admin endpoint refuses that — see OwnedDeviceAdminTest). Same body as
     * POST /api/admin/users/{id}/devices.
     */
    @Post("/users/{id}/devices")
    @Transactional
    public Map<String, Object> grant(Long id, @Body Map<String, Object> request) {
        Long chipId = ((Number) request.get("chipId")).longValue();
        if (devices.findByChipId(chipId).isEmpty()) {
            Device device = new Device();
            device.setChipId(chipId);
            Object type = request.get("deviceType");
            device.setDeviceType(type != null ? type.toString() : "BLOCK");
            devices.save(device);
        }
        Object role = request.get("accessRole");
        if (DeviceAccessService.ACCESS_OWNER.equals(role)) {
            access.setOwner(id, chipId);
        } else {
            access.forceAccessRole(id, chipId, role != null ? role.toString() : DeviceAccessService.ACCESS_USER);
        }
        return Map.of("chipId", chipId, "accessRole", access.accessRoleOf(id, chipId));
    }

    @Delete("/users/{id}/devices/{chipId}")
    public void revoke(Long id, Long chipId) {
        access.revoke(id, chipId);
    }

    @Post
    public Map<String, Object> create(@Body Map<String, String> request) {
        String username = request.get("username");
        if (users.findByUsername(username).isPresent()) {
            throw new HttpStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        AppUser admin = new AppUser();
        admin.setUsername(username);
        admin.setPasswordHash(passwordEncoder.encode(request.get("password")));
        admin.setRole(DeviceAccessService.ROLE_ADMIN);
        admin.setEnabled(Boolean.TRUE);
        users.save(admin);
        prefs.seedDefaults(admin.getId());
        return Map.of("token", tokens.issue(admin, true),
                "user", Map.of("id", admin.getId(), "username", admin.getUsername(), "role", admin.getRole()));
    }
}
