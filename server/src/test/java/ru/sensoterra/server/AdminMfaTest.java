package ru.sensoterra.server;

import io.micronaut.context.annotation.Property;
import io.micronaut.context.env.Environment;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.security.authentication.ServerAuthentication;
import io.micronaut.security.token.generator.TokenGenerator;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.security.AdminMfaService;
import ru.sensoterra.server.security.SessionTokens;
import ru.sensoterra.server.security.Totp;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@MicronautTest(transactional = false)
@Property(name = "sensoterra.security.admin-mfa", value = "true")
@DisplayName("Admin 2FA (TOTP), admin session limits")
class AdminMfaTest {

    private static final String PASSWORD = "admin-pass-123";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AppUserRepository users;

    @Inject
    TokenGenerator tokenGenerator;

    private String newAdmin() {
        String name = "mfa-" + UUID.randomUUID().toString().substring(0, 8);
        client.toBlocking().exchange(HttpRequest.POST("/api/test/admin", Map.of("username", name, "password", PASSWORD)));
        return name;
    }

    private Map post(String path, Map<String, ?> body) {
        return client.toBlocking().retrieve(HttpRequest.POST(path, body), Map.class);
    }

    private int postStatus(String path, Map<String, ?> body) {
        try {
            client.toBlocking().exchange(HttpRequest.POST(path, body));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private int getStatus(String path, String token) {
        try {
            client.toBlocking().exchange(HttpRequest.GET(path).header("Authorization", "Bearer " + token));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private Map login(String name) {
        return post("/api/auth/login", Map.of("username", name, "password", PASSWORD));
    }

    private static Map verifyBody(String mfaToken, String code, String recoveryCode) {
        Map<String, Object> body = new HashMap<>();
        body.put("mfaToken", mfaToken);
        if (code != null) body.put("code", code);
        if (recoveryCode != null) body.put("recoveryCode", recoveryCode);
        return body;
    }

    private static Map claims(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        try {
            return io.micronaut.json.JsonMapper.createDefault().readValue(payload, Argument.of(Map.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long nowStep() {
        return Totp.stepAt(Instant.now().getEpochSecond());
    }

    @Test
    @DisplayName("First sign-in enrolls; later sign-ins need a fresh code or a recovery code")
    void enrollmentAndVerification() {
        String name = newAdmin();

        Map first = login(name);
        assertNull(first.get("token"), "no session before the second factor");
        assertEquals(Boolean.TRUE, first.get("mfaSetupRequired"));
        String enrollToken = (String) first.get("mfaToken");

        Map setup = post("/api/auth/mfa/setup", Map.of("mfaToken", enrollToken));
        String secret = (String) setup.get("secret");
        assertTrue(((String) setup.get("otpauthUri")).contains("secret=" + secret));
        assertNull(users.findByUsername(name).orElseThrow().getTotpSecret(), "not stored before confirmation");

        assertEquals(401, postStatus("/api/auth/mfa/enroll", Map.of("mfaToken", enrollToken, "code", "000000")));
        long enrolledStep = nowStep();
        Map enrolled = post("/api/auth/mfa/enroll",
                Map.of("mfaToken", enrollToken, "code", Totp.code(secret, enrolledStep)));
        String token = (String) enrolled.get("token");
        List<String> recovery = (List<String>) enrolled.get("recoveryCodes");
        assertEquals(AdminMfaService.RECOVERY_CODES, recovery.size());
        assertEquals(AdminMfaService.RECOVERY_CODES, Set.copyOf(recovery).size());

        Map c = claims(token);
        assertEquals(Boolean.TRUE, c.get(SessionTokens.MFA_CLAIM));
        assertEquals((long) SessionTokens.ADMIN_TOKEN_TTL_SECONDS,
                ((Number) c.get("exp")).longValue() - ((Number) c.get("iat")).longValue());
        assertEquals(200, getStatus("/api/admin/users", token));
        // The challenge is single-use.
        assertEquals(401, postStatus("/api/auth/mfa/enroll",
                Map.of("mfaToken", enrollToken, "code", Totp.code(secret, enrolledStep + 1))));

        // Second sign-in: a code, not enrollment. The code used for enrollment can't be replayed.
        Map second = login(name);
        assertEquals(Boolean.TRUE, second.get("mfaRequired"));
        String challengeA = (String) second.get("mfaToken");
        assertEquals(401, postStatus("/api/auth/mfa/verify", verifyBody(challengeA, Totp.code(secret, enrolledStep), null)));

        // Recovery code works once.
        Map viaRecovery = post("/api/auth/mfa/verify", verifyBody(challengeA, null, recovery.get(0).toUpperCase()));
        assertNotNull(viaRecovery.get("token"));
        assertEquals(AdminMfaService.RECOVERY_CODES - 1, ((Number) viaRecovery.get("recoveryCodesLeft")).intValue());
        String challengeB = (String) login(name).get("mfaToken");
        assertEquals(401, postStatus("/api/auth/mfa/verify", verifyBody(challengeB, null, recovery.get(0))));

        // Five wrong codes kill the challenge, even for a right code afterwards.
        String challengeC = (String) login(name).get("mfaToken");
        for (int i = 0; i < 5; i++) {
            assertEquals(401, postStatus("/api/auth/mfa/verify", verifyBody(challengeC, "000000", null)));
        }
        long next = Math.max(nowStep(), enrolledStep) + 1;
        assertEquals(401, postStatus("/api/auth/mfa/verify", verifyBody(challengeC, Totp.code(secret, next), null)));

        // A fresh challenge with a fresh code.
        String challengeD = (String) login(name).get("mfaToken");
        Map ok = post("/api/auth/mfa/verify", verifyBody(challengeD, Totp.code(secret, next), null));
        assertEquals(200, getStatus("/api/admin/users", (String) ok.get("token")));
    }

    @Test
    @DisplayName("An ADMIN account is refused without the mfa claim (promotion with an old USER token)")
    void tokenWithoutSecondFactorIsRefused() {
        String name = "promoted-" + UUID.randomUUID().toString().substring(0, 8);
        Map reg = post("/api/auth/register", Map.of("username", name, "password", "pw-123456"));
        String userToken = (String) reg.get("token");
        assertEquals(200, getStatus("/api/devices", userToken));

        AppUser user = users.findByUsername(name).orElseThrow();
        user.setRole("ADMIN");
        users.update(user);
        assertEquals(401, getStatus("/api/devices", userToken));
        assertEquals(401, getStatus("/api/admin/users", userToken));
        var refresh = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/refresh", Map.of()).header("Authorization", "Bearer " + userToken)));
        assertEquals(401, refresh.getStatus().getCode());
    }

    @Test
    @DisplayName("Admin refresh is refused after the 12 h admin session limit")
    void adminSessionLimit() {
        String name = newAdmin();
        long tooOld = Instant.now().getEpochSecond() - SessionTokens.ADMIN_MAX_SESSION_SECONDS - 60;
        String old = tokenGenerator.generateToken(new ServerAuthentication(name, List.of("ADMIN"),
                        Map.of("sub", name, "roles", List.of("ADMIN"), SessionTokens.AUTH_TIME_CLAIM, tooOld,
                                SessionTokens.MFA_CLAIM, true)), 3600)
                .orElseThrow();
        assertEquals(200, getStatus("/api/admin/users", old));
        var ex = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/refresh", Map.of()).header("Authorization", "Bearer " + old)));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Audit log records admin sign-in steps")
    void auditLog() {
        String name = newAdmin();
        Map first = login(name);
        String mfaToken = (String) first.get("mfaToken");
        String secret = (String) post("/api/auth/mfa/setup", Map.of("mfaToken", mfaToken)).get("secret");
        postStatus("/api/auth/mfa/enroll", Map.of("mfaToken", mfaToken, "code", "000000"));
        String token = (String) post("/api/auth/mfa/enroll",
                Map.of("mfaToken", mfaToken, "code", Totp.code(secret, nowStep()))).get("token");

        List<Map> entries = client.toBlocking().retrieve(HttpRequest.GET("/api/admin/audit?limit=200")
                .header("Authorization", "Bearer " + token), Argument.listOf(Map.class));
        List<String> mine = entries.stream().filter(e -> name.equals(e.get("username")))
                .map(e -> (String) e.get("event")).toList();
        assertEquals(List.of("MFA_ENROLLED", "MFA_FAIL", "LOGIN_PASSWORD_OK"), mine);
    }

    @Test
    @DisplayName("2FA can't be switched off in the prod environment")
    void prodRefusesDisabledMfa() {
        Environment prod = mock(Environment.class);
        when(prod.getActiveNames()).thenReturn(Set.of("prod"));
        assertThrows(IllegalStateException.class,
                () -> new AdminMfaService(false, prod, null, null, null, null, null));
        Environment dev = mock(Environment.class);
        when(dev.getActiveNames()).thenReturn(Set.of("dev"));
        assertFalse(new AdminMfaService(false, dev, null, null, null, null, null).isEnabled());
    }
}
