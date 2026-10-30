package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the security audit fixes: device key leak, device owner, telemetry
 * access without a sensor record, stale ADMIN role in a JWT, HTTP Basic login.
 */
@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Security fixes")
class SecurityFixesTest {

    private static final String PASSWORD = "secret-pass-1";

    @Inject
    @Client("/")
    HttpClient client;

    private final String run = UUID.randomUUID().toString().substring(0, 6);
    private final long chipId = 880000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);

    private String adminToken;
    private String deviceKey;
    private String ownerToken;
    private String guestToken;
    private String viewerToken;
    private Long ownerId;
    private Long guestId;
    private Long viewerId;

    @BeforeAll
    void setUp() {
        adminToken = adminToken();
        ownerToken = register("sec-owner-" + run);
        guestToken = register("sec-guest-" + run);
        viewerToken = register("sec-viewer-" + run);
        ownerId = me(ownerToken);
        guestId = me(guestToken);
        viewerId = me(viewerToken);

        // The device appears through a read-only assignment (no owner yet), then the buyer claims
        // it; a USER assigned before the claim would itself become the owner (see grant()).
        assign(viewerId, "VIEWER");
        exchange(HttpRequest.POST("/api/devices/" + chipId + "/payment", Map.of("unlimited", true)), adminToken);
        deviceKey = (String) body(exchange(HttpRequest.GET("/api/devices/" + chipId + "/key"), adminToken)).get("key");
        Map claim = body(exchange(HttpRequest.POST("/api/devices/claim", Map.of("chipId", chipId, "key", deviceKey)),
                ownerToken));
        assertEquals("OWNER", claim.get("accessRole"), "the first key claim makes the owner");
        assign(guestId, "USER");
        assertEquals("USER", accessRoleOf(guestId));
    }

    // ----- device key -----

    @Test
    @DisplayName("The device key is not in device JSON for a read-only guest")
    void deviceKeyHidden() {
        List<Map> devices = client.toBlocking().retrieve(auth(HttpRequest.GET("/api/devices"), viewerToken),
                Argument.listOf(Map.class));
        Map device = devices.stream().filter(d -> ((Number) d.get("chipId")).longValue() == chipId).findFirst()
                .orElseThrow();
        assertFalse(device.containsKey("deviceKey"), "list: " + device.keySet());
        Map single = body(exchange(HttpRequest.GET("/api/devices/" + chipId), viewerToken));
        assertFalse(single.containsKey("deviceKey"), "get: " + single.keySet());
    }

    // ----- owner -----

    @Test
    @DisplayName("A second key claim gives USER, the owner stays OWNER")
    void secondClaimIsNotOwner() {
        Map claim = body(exchange(HttpRequest.POST("/api/devices/claim", Map.of("chipId", chipId, "key", deviceKey)),
                guestToken));
        assertEquals("USER", claim.get("accessRole"));
        assertEquals("OWNER", accessRoleOf(ownerId));
    }

    @Test
    @DisplayName("A USER guest can't manage access or send invites")
    void guestCantManageAccess() {
        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", viewerId, "accessRole", "USER")), guestToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + viewerId), guestToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + ownerId), guestToken));
        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/invites",
                Map.of("accessRole", "VIEWER")), guestToken));
        assertEquals("OWNER", accessRoleOf(ownerId));
    }

    @Test
    @DisplayName("The owner manages guests, but can't be removed or downgraded")
    void ownerManagesGuests() {
        assertEquals(200, status(HttpRequest.POST("/api/devices/" + chipId + "/invites",
                Map.of("accessRole", "VIEWER")), ownerToken));
        assertEquals(409, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", ownerId, "accessRole", "VIEWER")), ownerToken));
        assertEquals(409, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + ownerId), ownerToken));
        assertEquals(400, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", viewerId, "accessRole", "OWNER")), ownerToken), "OWNER is not grantable");
        assertEquals("OWNER", accessRoleOf(ownerId));
    }

    @Test
    @DisplayName("Admins are hidden from the device access list")
    void adminsHiddenFromAccessList() {
        Long adminId = me(adminToken);
        assign(adminId, "VIEWER");
        try {
            List<Map> entries = client.toBlocking().retrieve(
                    auth(HttpRequest.GET("/api/devices/" + chipId + "/access"), adminToken),
                    Argument.listOf(Map.class));
            assertTrue(entries.stream().noneMatch(e -> "ADMIN".equals(e.get("role"))));
            assertTrue(entries.stream().noneMatch(e -> adminId.equals(((Number) e.get("userId")).longValue())));
        } finally {
            assertEquals(204, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + adminId), adminToken));
        }
    }

    // ----- telemetry -----

    @Test
    @DisplayName("Telemetry by an unknown sensorChipId: 404 for users, allowed for admins")
    void telemetryWithoutSensorRecord() {
        long unknownChip = 990000000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 1000000);
        assertEquals(404, status(HttpRequest.GET("/api/telemetry/sensor?sensorChipId=" + unknownChip), guestToken));
        assertEquals(404, status(HttpRequest.GET("/api/telemetry/sensor/start?sensorChipId=" + unknownChip), guestToken));
        assertEquals(200, status(HttpRequest.GET("/api/telemetry/sensor?sensorChipId=" + unknownChip), adminToken));
    }

    // ----- stale admin token -----

    @Test
    @DisplayName("A demoted or disabled admin's old token no longer passes ADMIN endpoints")
    void staleAdminToken() {
        String name = "sec-admin-" + run;
        Map created = body(exchange(HttpRequest.POST("/api/admin/users",
                Map.of("username", name, "password", PASSWORD, "role", "ADMIN")), adminToken));
        Long id = ((Number) created.get("id")).longValue();
        String oldToken = login(name);
        assertEquals(200, status(HttpRequest.GET("/api/admin/users"), oldToken));

        exchange(HttpRequest.PUT("/api/admin/users/" + id, Map.of("role", "USER")), adminToken);
        assertEquals(403, status(HttpRequest.POST("/api/admin/users",
                Map.of("username", name + "-x", "password", PASSWORD, "role", "ADMIN")), oldToken));

        exchange(HttpRequest.PUT("/api/admin/users/" + id, Map.of("enabled", false)), adminToken);
        int disabled = status(HttpRequest.GET("/api/devices"), oldToken);
        assertTrue(disabled == 401 || disabled == 403, "disabled account -> " + disabled);
    }

    // ----- command log -----

    @Test
    @DisplayName("Command log: odd limits don't fail, results are capped")
    void commandLogLimits() {
        assertEquals(200, status(HttpRequest.GET("/api/commands?limit=-5&offset=-1"), guestToken));
        List<Map> page = client.toBlocking().retrieve(auth(HttpRequest.GET("/api/commands?limit=100000"), adminToken),
                Argument.listOf(Map.class));
        assertTrue(page.size() <= 200, "capped, got " + page.size());
    }

    // ----- HTTP Basic -----

    @Test
    @DisplayName("HTTP Basic credentials do not authenticate (only POST /api/auth/login does)")
    void basicAuthRejected() {
        String basic = Base64.getEncoder().encodeToString(
                ("sec-owner-" + run + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8));
        int code = status(HttpRequest.GET("/api/devices").header("Authorization", "Basic " + basic), null);
        assertEquals(401, code);
    }

    // ----- helpers -----

    private String adminToken() {
        Map<String, String> creds = Map.of("username", "admin", "password", "admin-pass-123");
        try {
            return (String) body(client.toBlocking().exchange(HttpRequest.POST("/api/auth/login", creds), Map.class))
                    .get("token");
        } catch (HttpClientResponseException e) {
            return (String) body(client.toBlocking().exchange(HttpRequest.POST("/api/auth/bootstrap", creds),
                    Map.class)).get("token");
        }
    }

    private String register(String username) {
        return (String) client.toBlocking().retrieve(HttpRequest.POST("/api/auth/register",
                Map.of("username", username, "password", PASSWORD)), Map.class).get("token");
    }

    private String login(String username) {
        return (String) client.toBlocking().retrieve(HttpRequest.POST("/api/auth/login",
                Map.of("username", username, "password", PASSWORD)), Map.class).get("token");
    }

    private Long me(String token) {
        return ((Number) body(exchange(HttpRequest.GET("/api/auth/me"), token)).get("id")).longValue();
    }

    private void assign(Long userId, String accessRole) {
        exchange(HttpRequest.POST("/api/admin/users/" + userId + "/devices",
                Map.of("chipId", chipId, "deviceType", "BLOCK", "accessRole", accessRole)), adminToken);
    }

    private String accessRoleOf(Long userId) {
        List<Map> entries = client.toBlocking().retrieve(
                auth(HttpRequest.GET("/api/devices/" + chipId + "/access"), adminToken), Argument.listOf(Map.class));
        return entries.stream().filter(e -> userId.equals(((Number) e.get("userId")).longValue()))
                .map(e -> (String) e.get("accessRole")).findFirst().orElse(null);
    }

    private <B> MutableHttpRequest<B> auth(MutableHttpRequest<B> request, String token) {
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return request.contentType(MediaType.APPLICATION_JSON);
    }

    private HttpResponse<Map> exchange(MutableHttpRequest<?> request, String token) {
        return client.toBlocking().exchange(auth(request, token), Map.class);
    }

    private int status(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(auth(request, token), String.class).code();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private static Map body(HttpResponse<Map> response) {
        return response.body();
    }
}
