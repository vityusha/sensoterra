package ru.sensoterra.server;

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
import ru.sensoterra.server.security.AuthController;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Sliding session: POST /api/auth/refresh extends an active session up to the absolute limit. */
@MicronautTest
@DisplayName("Token refresh")
class TokenRefreshTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    TokenGenerator tokenGenerator;

    private String register() {
        String name = "refresh-" + UUID.randomUUID().toString().substring(0, 8);
        Map body = client.toBlocking().retrieve(HttpRequest.POST("/api/auth/register",
                Map.of("username", name, "password", "refresh-pass-1")), Map.class);
        return (String) body.get("token");
    }

    private HttpRequest<?> refreshRequest(String token) {
        return HttpRequest.POST("/api/auth/refresh", Map.of()).header("Authorization", "Bearer " + token);
    }

    private static Map claims(String jwt) {
        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        try {
            return io.micronaut.json.JsonMapper.createDefault().readValue(payload, Argument.of(Map.class));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("Refresh returns a new working token that keeps the original login time")
    void refreshKeepsAuthTime() throws Exception {
        String token = register();
        Number authTime = (Number) claims(token).get(AuthController.AUTH_TIME_CLAIM);
        assertNotNull(authTime, "login tokens carry auth_time");

        Thread.sleep(1100); // exp/iat have second precision
        Map body = client.toBlocking().retrieve(refreshRequest(token), Map.class);
        String fresh = (String) body.get("token");
        assertNotEquals(token, fresh);
        assertEquals(authTime.longValue(), ((Number) claims(fresh).get(AuthController.AUTH_TIME_CLAIM)).longValue());
        assertTrue(((Number) claims(fresh).get("exp")).longValue() > ((Number) claims(token).get("exp")).longValue());

        Map me = client.toBlocking().retrieve(HttpRequest.GET("/api/auth/me")
                .header("Authorization", "Bearer " + fresh), Map.class);
        assertNotNull(me.get("username"));
    }

    @Test
    @DisplayName("Refresh is refused after the absolute session limit and without a token")
    void refreshLimits() {
        String token = register();
        String username = (String) claims(token).get("sub");
        long tooOld = Instant.now().getEpochSecond() - AuthController.MAX_SESSION_SECONDS - 60;
        String old = tokenGenerator.generateToken(new ServerAuthentication(username, List.of("USER"),
                Map.of("sub", username, "roles", List.of("USER"), AuthController.AUTH_TIME_CLAIM, tooOld)), 3600)
                .orElseThrow();

        var ex = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(refreshRequest(old)));
        assertEquals(401, ex.getStatus().getCode());

        var anon = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/refresh", Map.of())));
        assertEquals(401, anon.getStatus().getCode());
    }
}
