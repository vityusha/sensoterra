package ru.sensoterra.server;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.mqtt.MqttClientService;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.annotation.MockBean;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@MicronautTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Admin user management + device assignment")
class AdminUserTest {

    @Inject
    @Client("/")
    HttpClient client;

    private String adminToken;
    private Long clientId;

    @MockBean(MqttClientService.class)
    MqttClientService mqttClientService() {
        return mock(MqttClientService.class);
    }

    @BeforeAll
    void bootstrapAdmin() {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/bootstrap",
                    Map.of("username", "admin", "password", "admin123")));
        } catch (HttpClientResponseException ignored) {
        }
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", "admin", "password", "admin123")),
                Argument.of(Map.class));
        adminToken = (String) resp.body().get("token");
    }

    /**
     * USER заводит сам покупатель: аккаунт без блока бесполезен, блок он подтверждает
     * ключом с комплектного листа (см. DeviceKeyTest). Админ такие аккаунты не создаёт.
     */
    @Test
    @Order(1)
    @DisplayName("USER регистрируется сам через /api/auth/register")
    void selfRegisterUser() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", "tester", "password", "pw123"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertEquals("tester", body.get("username"));
        assertEquals("USER", body.get("role"));
        clientId = ((Number) body.get("id")).longValue();
    }

    @Test
    @Order(2)
    @DisplayName("Duplicate username returns 409")
    void duplicateUser() {
        assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/register",
                                        Map.of("username", "tester", "password", "x"))
                                .contentType(MediaType.APPLICATION_JSON)));
    }

    @Test
    @Order(2)
    @DisplayName("Админ создаёт только админов: попытка завести USER даёт 403")
    void adminCannotCreateUser() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/admin/users",
                                        Map.of("username", "made-by-admin", "password", "pw123",
                                                "role", "USER"))
                                .header("Authorization", "Bearer " + adminToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());

        HttpResponse<Map> admin = client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users",
                                Map.of("username", "admin-assistant", "password", "pw123", "role", "ADMIN"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals("ADMIN", admin.body().get("role"));
        Long created = ((Number) admin.body().get("id")).longValue();
        client.toBlocking().exchange(
                HttpRequest.DELETE("/api/admin/users/" + created)
                        .header("Authorization", "Bearer " + adminToken));
    }

    @Test
    @Order(3)
    @DisplayName("List users contains admin and tester")
    void listUsers() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        assertTrue(resp.body().size() >= 2);
    }

    @Test
    @Order(4)
    @DisplayName("Assign device to user")
    void assignDevice() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + clientId + "/devices",
                                Map.of("chipId", 111111L, "deviceType", "BLOCK"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertEquals(111111L, ((Number) body.get("chipId")).longValue());

        HttpResponse<List<Map>> access = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/111111/access").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(1, access.body().size());
        assertEquals(clientId, ((Number) access.body().get(0).get("userId")).longValue());
    }

    @Test
    @Order(5)
    @DisplayName("User devices list shows assigned device")
    void userDevices() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users/" + clientId + "/devices")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        assertEquals(1, resp.body().size());
    }

    @Test
    @Order(6)
    @DisplayName("User sees own device in /api/devices")
    void clientSeesOwnDevice() {
        HttpResponse<Map> login = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", "tester", "password", "pw123")),
                Argument.of(Map.class));
        String token = (String) login.body().get("token");

        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + token),
                Argument.listOf(Map.class));
        assertEquals(1, resp.body().size());
        assertEquals(111111L, ((Number) ((Map) resp.body().get(0)).get("chipId")).longValue());
    }

    @Test
    @Order(7)
    @DisplayName("User cannot see device without access (404, existence not revealed)")
    void clientCannotSeeOtherDevice() {
        HttpResponse<?> assignResp = client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + clientId + "/devices",
                                Map.of("chipId", 999999L, "deviceType", "BLOCK"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(200, assignResp.getStatus().getCode());

        HttpResponse<?> unassign = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/admin/users/" + clientId + "/devices/999999")
                        .header("Authorization", "Bearer " + adminToken));
        assertEquals(204, unassign.getStatus().getCode());

        HttpResponse<Map> login = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", "tester", "password", "pw123")),
                Argument.of(Map.class));
        String token = (String) login.body().get("token");

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/devices/999999").header("Authorization", "Bearer " + token)));
        assertEquals(404, ex.getStatus().getCode());
    }

    @Test
    @Order(8)
    @DisplayName("Unassign device")
    void unassignDevice() {
        HttpResponse<?> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/admin/users/" + clientId + "/devices/111111")
                        .header("Authorization", "Bearer " + adminToken));
        assertEquals(204, resp.getStatus().getCode());

        HttpResponse<List<Map>> userDevices = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users/" + clientId + "/devices")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertEquals(0, userDevices.body().size());
    }

    @Test
    @Order(9)
    @DisplayName("Delete user")
    void deleteUser() {
        HttpResponse<?> resp = client.toBlocking().exchange(
                HttpRequest.DELETE("/api/admin/users/" + clientId)
                        .header("Authorization", "Bearer " + adminToken));
        assertEquals(204, resp.getStatus().getCode());
    }
}
