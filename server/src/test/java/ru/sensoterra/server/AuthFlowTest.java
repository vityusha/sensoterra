package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Auth flow: bootstrap -> login -> me -> register -> role access")
class AuthFlowTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static String adminToken;
    private static String clientToken;

    @Test
    @Order(1)
    @DisplayName("Bootstrap creates admin user and returns token")
    void bootstrap() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/bootstrap",
                        Map.of("username", "admin", "password", "admin123")),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertNotNull(body.get("token"));
        assertNotNull(body.get("user"));
        adminToken = (String) body.get("token");
    }

    @Test
    @Order(2)
    @DisplayName("Login as admin returns token and user info")
    void loginAdmin() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", "admin", "password", "admin123")),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertNotNull(body.get("token"));
        Map user = (Map) body.get("user");
        assertEquals("admin", user.get("username"));
        assertEquals("ADMIN", user.get("role"));
    }

    @Test
    @Order(3)
    @DisplayName("/api/auth/me returns current user")
    void me() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/me")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertEquals("admin", body.get("username"));
        assertEquals("ADMIN", body.get("role"));
    }

    @Test
    @Order(4)
    @DisplayName("Register USER via admin token (requires ADMIN token)")
    void registerClient() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                        Map.of("username", "client1", "password", "pass1234", "role", "USER"))
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        Map body = resp.body();
        assertEquals("client1", body.get("username"));
        assertEquals("USER", body.get("role"));
    }

    @Test
    @Order(5)
    @DisplayName("Login as USER")
    void loginClient() {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", "client1", "password", "pass1234")),
                Argument.of(Map.class));
        assertEquals(200, resp.getStatus().getCode());
        clientToken = (String) resp.body().get("token");
        assertNotNull(clientToken);
    }

    @Test
    @Order(6)
    @DisplayName("Duplicate bootstrap fails with 403")
    void duplicateBootstrap() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/bootstrap",
                                Map.of("username", "admin", "password", "other"))));
        assertEquals(403, ex.getStatus().getCode());
    }

    @Test
    @Order(7)
    @DisplayName("Wrong password returns 401")
    void wrongPassword() {
        assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login",
                                Map.of("username", "admin", "password", "wrong"))));
    }

    @Test
    @Order(8)
    @DisplayName("USER cannot access admin endpoints")
    void clientCannotAccessAdmin() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/admin/users")
                                .header("Authorization", "Bearer " + clientToken)));
        assertEquals(403, ex.getStatus().getCode());
    }

    @Test
    @Order(9)
    @DisplayName("Unauthenticated access returns 401")
    void unauthenticated() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(HttpRequest.GET("/api/devices")));
        assertEquals(401, ex.getStatus().getCode());
    }
}
