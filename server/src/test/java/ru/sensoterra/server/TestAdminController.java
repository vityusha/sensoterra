package ru.sensoterra.server;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.env.Environment;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
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

    public TestAdminController(AppUserRepository users, PasswordEncoder passwordEncoder,
                               SessionTokens tokens, UserPrefService prefs) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.tokens = tokens;
        this.prefs = prefs;
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
