package ru.sensoterra.server;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.mqtt.MqttClientService;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * An admin only views a device that has an owner: control, settings and its access list are
 * the owner's. Service operations (ownership transfer, key, payment, reset) stay with the admin.
 */
@MicronautTest(transactional = false)
@DisplayName("Admin on owned devices: view only, service operations allowed")
class OwnedDeviceAdminTest {

    @Inject
    @Client("/")
    HttpClient client;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    private String adminToken;
    private String ownerToken;
    private Long ownerId;
    private Long guestId;
    private long chipId;

    private Map post(String path, Object body, String token) {
        return client.toBlocking().retrieve(auth(HttpRequest.POST(path, body), token), Map.class);
    }

    private static MutableHttpRequest<?> auth(MutableHttpRequest<?> request, String token) {
        return token == null ? request : request.header("Authorization", "Bearer " + token);
    }

    private int status(MutableHttpRequest<?> request, String token) {
        try {
            return client.toBlocking().exchange(auth(request, token)).getStatus().getCode();
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private String register(String name) {
        return (String) post("/api/auth/register", Map.of("username", name, "password", "pw-123456"), null).get("token");
    }

    private Long me(String token) {
        return ((Number) client.toBlocking().retrieve(auth(HttpRequest.GET("/api/auth/me"), token), Map.class)
                .get("id")).longValue();
    }

    @BeforeEach
    void setUp() {
        String run = UUID.randomUUID().toString().substring(0, 8);
        adminToken = (String) post("/api/test/admin",
                Map.of("username", "own-admin-" + run, "password", "admin-pass-123"), null).get("token");
        ownerToken = register("own-owner-" + run);
        ownerId = me(ownerToken);
        guestId = me(register("own-guest-" + run));
        chipId = 880000L + Math.abs(UUID.randomUUID().getLeastSignificantBits() % 100000);
        // Standalone sensor: not chargeable, so the owner needs no payment for access management.
        post("/api/admin/users/" + ownerId + "/devices",
                Map.of("chipId", chipId, "deviceType", "INDIVIDUAL_SENSOR"), adminToken);
        assertEquals("OWNER", ownerRole(), "the first full grant on an ownerless device makes the owner");
    }

    private String ownerRole() {
        return (String) client.toBlocking().retrieve(auth(HttpRequest.GET("/api/devices/" + chipId + "/access"),
                adminToken), io.micronaut.core.type.Argument.listOf(Map.class)).stream()
                .filter(e -> ownerId.equals(((Number) e.get("userId")).longValue()))
                .map(e -> e.get("accessRole")).findFirst().orElse(null);
    }

    @Test
    @DisplayName("Admin sees the owned device but can't control or configure it")
    void viewOnly() {
        Map device = client.toBlocking().retrieve(auth(HttpRequest.GET("/api/devices/" + chipId), adminToken), Map.class);
        assertNotNull(device.get("ownerUsername"), "device card tells the client who owns it");
        assertEquals(200, status(HttpRequest.GET("/api/devices/" + chipId + "/access"), adminToken));

        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/retained/clear", Map.of()), adminToken));
        assertEquals(403, status(HttpRequest.PUT("/api/sensors/" + chipId + "/moisture-scale",
                Map.of("minPct", 10, "maxPct", 90, "enabled", true)), adminToken));
        // The owner still can (mocked MQTT is "not connected": 503 means the rights check passed).
        assertNotEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/retained/clear", Map.of()), ownerToken));
    }

    @Test
    @DisplayName("Admin can't add, change or remove the guests of an owned device")
    void accessIsTheOwners() {
        assertEquals(403, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));
        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", guestId, "accessRole", "VIEWER")), adminToken));

        // The owner adds a guest; the admin can neither downgrade nor remove them, nor the owner.
        assertEquals(200, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", guestId, "accessRole", "USER")), ownerToken));
        assertEquals(403, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));
        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", guestId, "accessRole", "VIEWER")), adminToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + guestId), adminToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/admin/users/" + guestId + "/devices/" + chipId), adminToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/admin/users/" + ownerId + "/devices/" + chipId), adminToken));
        assertEquals(403, status(HttpRequest.POST("/api/admin/users/" + ownerId + "/devices",
                Map.of("chipId", chipId, "accessRole", "USER")), adminToken), "no owner downgrade");

        // The owner manages them as before.
        assertEquals(204, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + guestId), ownerToken));
    }

    @Test
    @DisplayName("Service operations stay with the admin: ownership transfer, key, reset")
    void serviceOperations() {
        assertEquals(200, status(HttpRequest.GET("/api/devices/" + chipId + "/key"), adminToken));
        assertEquals(200, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "OWNER")), adminToken));
        assertEquals("USER", ownerRole(), "the previous owner keeps control as USER");
        assertEquals(200, status(HttpRequest.POST("/api/devices/" + chipId + "/payment/reset", Map.of()), adminToken));
        // No owner any more: the admin controls and manages access again.
        assertEquals(200, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));
    }

    @Test
    @DisplayName("Owner grants admins full access and takes it back; ownership stays with the owner")
    void ownerGrantsAdminAccess() {
        Map me = client.toBlocking().retrieve(auth(HttpRequest.PUT("/api/auth/admin-access",
                Map.of("enabled", true)), ownerToken), Map.class);
        assertEquals(Boolean.TRUE, me.get("adminAccess"));
        assertNotNull(me.get("adminAccessSince"));
        Map device = client.toBlocking().retrieve(auth(HttpRequest.GET("/api/devices/" + chipId), adminToken), Map.class);
        assertEquals(Boolean.TRUE, device.get("ownerAdminAccess"));

        // Full control and access management for the admin now...
        assertNotEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/retained/clear", Map.of()), adminToken));
        assertEquals(200, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));
        assertEquals(200, status(HttpRequest.POST("/api/devices/" + chipId + "/access",
                Map.of("userId", guestId, "accessRole", "USER")), adminToken));
        assertEquals(204, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + guestId), adminToken));
        // ...but not over the owner: no downgrade, no removal.
        assertEquals(409, status(HttpRequest.POST("/api/admin/users/" + ownerId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));
        assertEquals(403, status(HttpRequest.DELETE("/api/devices/" + chipId + "/access/" + ownerId), adminToken));
        assertEquals("OWNER", ownerRole());

        // Taken back: read-only again at once.
        client.toBlocking().exchange(auth(HttpRequest.PUT("/api/auth/admin-access", Map.of("enabled", false)), ownerToken));
        assertEquals(403, status(HttpRequest.POST("/api/devices/" + chipId + "/retained/clear", Map.of()), adminToken));
        assertEquals(403, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", chipId, "accessRole", "VIEWER")), adminToken));

        java.util.List<Map> audit = client.toBlocking().retrieve(auth(HttpRequest.GET("/api/admin/audit?limit=200"),
                adminToken), io.micronaut.core.type.Argument.listOf(Map.class));
        String owner = (String) client.toBlocking().retrieve(auth(HttpRequest.GET("/api/auth/me"), ownerToken), Map.class)
                .get("username");
        assertEquals(java.util.List.of("OWNER_ADMIN_ACCESS_OFF", "OWNER_ADMIN_ACCESS_ON"), audit.stream()
                .filter(e -> owner.equals(e.get("username"))).map(e -> e.get("event")).toList());
    }

    @Test
    @DisplayName("Admins can't grant admin access to themselves")
    void adminCantSelfGrant() {
        assertEquals(400, status(HttpRequest.PUT("/api/auth/admin-access", Map.of("enabled", true)), adminToken));
    }

    @Test
    @DisplayName("Without an owner the admin keeps full control")
    void ownerlessDevice() {
        long free = chipId + 1;
        assertEquals(200, status(HttpRequest.POST("/api/admin/users/" + guestId + "/devices",
                Map.of("chipId", free, "deviceType", "INDIVIDUAL_SENSOR", "accessRole", "VIEWER")), adminToken));
        assertNotEquals(403, status(HttpRequest.POST("/api/devices/" + free + "/retained/clear", Map.of()), adminToken));
        assertEquals(204, status(HttpRequest.DELETE("/api/admin/users/" + guestId + "/devices/" + free), adminToken));
    }
}
