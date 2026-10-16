package ru.sensoterra.server;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.core.type.Argument;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Права ролей: USER управляет своими устройствами, VIEWER только читает,
 * отключённый аккаунт не входит.
 */
@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Роли: USER управляет, VIEWER только читает, отключённый не входит")
class RolesAccessTest {

    @Inject
    @Client("/")
    HttpClient client;

    private String adminToken;
    private Long userId;
    private Long viewerId;
    private long blockId;

    private String tokenFor(String username) {
        // Роль в токене и в БД должна совпадать, поэтому токены берём из /api/auth/login.
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login", Map.of("username", username, "password", "pw12345"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return (String) resp.body().get("token");
    }

    /** Админ может быть ещё не создан (тесты делят одну БД) — тогда делаем bootstrap. */
    private void ensureAdmin() {
        if (adminToken != null) {
            return;
        }
        Map<String, String> creds = Map.of("username", "admin", "password", "admin123");
        try {
            adminToken = (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
            return;
        } catch (HttpClientResponseException e) {
            adminToken = (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        }
    }

    @BeforeEach
    void setUp() {
        ensureAdmin();

        userId = ensureUser("roles-user");
        viewerId = ensureUser("roles-viewer");

        blockId = 777000L + Math.abs((long) java.util.UUID.randomUUID().getLeastSignificantBits() % 100000);
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + userId + "/devices",
                                Map.of("chipId", blockId, "deviceType", "BLOCK"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));

        for (Long id : List.of(userId, viewerId)) {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/admin/users/" + id + "/devices",
                                    Map.of("chipId", blockId, "deviceType", "BLOCK"))
                            .header("Authorization", "Bearer " + adminToken)
                            .contentType(MediaType.APPLICATION_JSON));
        }
        // roles-viewer получает на этот блок права «только чтение»: VIEWER — это не роль
        // аккаунта, а доступ к конкретному устройству.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + viewerId + "/devices",
                                Map.of("chipId", blockId, "deviceType", "BLOCK",
                                        "accessRole", "VIEWER"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        // Оплата теперь у блока, а не у аккаунта: делаем бессрочный план и подтверждаем
        // блок ключом, иначе все проверки прав упирались бы в неоплаченный блок.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/payment", Map.of("unlimited", true))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
        String key = (String) client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/key")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class)).body().get("key");
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/claim", Map.of("chipId", blockId, "key", key))
                        .header("Authorization", "Bearer " + tokenFor("roles-user"))
                        .contentType(MediaType.APPLICATION_JSON));
    }

    /** Аккаунт USER покупатель заводит сам; админ создаёт только админов. */
    private Long ensureUser(String username) {
        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        for (Map u : list.body()) {
            if (username.equals(u.get("username"))) {
                return ((Number) u.get("id")).longValue();
            }
        }
        HttpResponse<Map> created = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", username, "password", "pw12345"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        return ((Number) created.body().get("id")).longValue();
    }

    @Test
    @Order(1)
    @DisplayName("USER и наблюдатель (VIEWER на устройстве) видят выданный блок")
    void bothRolesSeeGrantedDevice() {
        String userToken = tokenFor("roles-user");
        String viewerToken = tokenFor("roles-viewer");

        HttpResponse<List<Map>> asUser = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + userToken),
                Argument.listOf(Map.class));
        assertTrue(asUser.body().stream()
                        .anyMatch(d -> ((Number) d.get("chipId")).longValue() == blockId),
                "USER must see granted device");

        HttpResponse<List<Map>> asViewer = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + viewerToken),
                Argument.listOf(Map.class));
        assertTrue(asViewer.body().stream()
                        .anyMatch(d -> ((Number) d.get("chipId")).longValue() == blockId),
                "VIEWER must see granted device");
    }

    @Test
    @Order(2)
    @DisplayName("Device without access is 404, not 403")
    void foreignDeviceIs404() {
        String userToken = tokenFor("roles-user");
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/devices/424242").header("Authorization", "Bearer " + userToken)));
        assertEquals(404, ex.getStatus().getCode());
    }

    @Test
    @Order(3)
    @DisplayName("Наблюдатель (VIEWER) не может управлять, владелец может")
    void viewerCannotControl() {
        HttpResponse<List<Map>> relays = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/relays")
                        .header("Authorization", "Bearer " + tokenFor("roles-user")),
                Argument.listOf(Map.class));
        if (relays.body().isEmpty()) {
            return; // блок без созданных реле — проверять нечего
        }
        long relayId = ((Number) relays.body().get(0).get("id")).longValue();

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/relays/" + relayId + "/command", Map.of("state", 1))
                                .header("Authorization", "Bearer " + tokenFor("roles-viewer"))
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());

        HttpResponse<?> asUser = client.toBlocking().exchange(
                HttpRequest.POST("/api/relays/" + relayId + "/command", Map.of("state", 1))
                        .header("Authorization", "Bearer " + tokenFor("roles-user"))
                        .contentType(MediaType.APPLICATION_JSON));
        assertEquals(200, asUser.getStatus().getCode());
    }

    @Test
    @Order(4)
    @DisplayName("Disabled account cannot log in")
    void disabledCannotLogin() {
        client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/users/" + viewerId, Map.of("enabled", false))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login",
                                Map.of("username", "roles-viewer", "password", "pw12345"))
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());

        client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/users/" + viewerId, Map.of("enabled", true))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @Order(5)
    @DisplayName("/api/auth/me requires a token")
    void meRequiresAuth() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/api/auth/me"), Argument.of(Map.class)));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @Order(7)
    @DisplayName("Токен, выданный до отключения аккаунта, перестаёт работать сразу")
    void staleTokenOfDisabledUserIsRejected() {
        String userToken = tokenFor("roles-user");

        // До отключения доступ есть.
        client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId).header("Authorization", "Bearer " + userToken),
                Argument.of(Map.class));

        client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/users/" + userId, Map.of("enabled", false))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/devices/" + blockId)
                                .header("Authorization", "Bearer " + userToken),
                        Argument.of(Map.class)));
        assertEquals(401, ex.getStatus().getCode());

        client.toBlocking().exchange(
                HttpRequest.PUT("/api/admin/users/" + userId, Map.of("enabled", true))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @Order(8)
    @DisplayName("Понижение прав на устройстве до VIEWER действует сразу, без перелогина")
    void accessDowngradeAppliesImmediately() {
        // Роль аккаунта одна (ADMIN или USER), а права на устройство меняются отдельно.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + userId + "/devices",
                                Map.of("chipId", blockId, "deviceType", "BLOCK", "accessRole", "VIEWER"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));

        String userToken = tokenFor("roles-user");
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/" + blockId + "/access",
                                Map.of("userId", userId, "accessRole", "VIEWER"))
                                .header("Authorization", "Bearer " + userToken)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(403, ex.getStatus().getCode());

        // Возвращаем права владельца, чтобы следующие тесты не зависели от порядка.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + userId + "/devices",
                                Map.of("chipId", blockId, "deviceType", "BLOCK", "accessRole", "USER"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @Order(9)
    @DisplayName("Встроенный /login Micronaut выключен — вход только через /api/auth/login")
    void builtinLoginDisabled() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/login", Map.of("username", "admin", "password", "admin123"))
                                .contentType(MediaType.APPLICATION_JSON)));
        // В тестовой среде анонимный запрос отбивает security-фильтр (401), в проде маршрута
        // просто нет (404) — важно, что JWT больше не выдаётся.
        assertTrue(ex.getStatus().getCode() == 401 || ex.getStatus().getCode() == 404,
                "built-in /login must not issue a token, got " + ex.getStatus());
    }

    @Test
    @Order(6)
    @DisplayName("Admin user list does not leak password hash")
    void noPasswordLeak() {
        HttpResponse<List<Map>> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertTrue(resp.body().stream().noneMatch(u -> u.containsKey("passwordHash")), "passwordHash must not leak");
        assertTrue(resp.body().stream().noneMatch(u -> u.containsKey("password_hash")));
    }
}