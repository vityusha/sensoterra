package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ключ устройства из комплектного листа: админ смотрит и копирует ключ, покупатель вводит
 * пару «ID + ключ» и получает права управления. Роль аккаунта при этом не меняется —
 * он остаётся USER, VIEWER — это доступ на чужое устройство.
 */
@MicronautTest
@DisplayName("Ключ устройства подтверждает владение и привязывает блок")
class DeviceKeyTest {

    @Inject
    @Client("/")
    HttpClient client;

    private String adminToken;
    private Long blockId;
    private int unique = 0;

    @BeforeEach
    void setUp() {
        adminToken = adminToken();
        blockId = 700000L + Math.abs((long) java.util.UUID.randomUUID().getLeastSignificantBits() % 100000)
                + (unique++) * 100000L;
    }

    private String adminToken() {
        Map<String, String> creds = Map.of("username", "admin", "password", "admin-pass-123");
        try {
            return (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        } catch (HttpClientResponseException e) {
            return (String) client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class)).body().get("token");
        }
    }

    private String keyOf(Long chipId) {
        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + chipId + "/key")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.of(Map.class));
        return (String) resp.body().get("key");
    }

    private String selfRegister(String username) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON));
        return (String) client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/login",
                        Map.of("username", username, "password", "pw-123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body().get("token");
    }

    private void makeDevice(Long chipId) {
        client.toBlocking().exchange(
                HttpRequest.POST("/api/admin/users/" + adminId() + "/devices",
                                Map.of("chipId", chipId, "deviceType", "BLOCK"))
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    private Long adminId() {
        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        return ((Number) list.body().stream()
                .filter(u -> "admin".equals(u.get("username")))
                .findFirst().orElseThrow().get("id")).longValue();
    }

    @Test
    @DisplayName("Админ получает читаемый ключ, он не меняется при повторном запросе")
    void adminReadsKey() {
        makeDevice(blockId);
        String key = keyOf(blockId);
        assertNotNull(key);
        assertTrue(key.matches("[A-Z2-9]{4}-[A-Z2-9]{4}-[A-Z2-9]{4}"), "нечитабельные символы исключены: " + key);
        assertEquals(key, keyOf(blockId), "ключ выпускается один раз и не меняется сам");
    }

    @Test
    @DisplayName("Ключ и ID с листа привязывают блок к новому аккаунту")
    void claimWithSheetPair() {
        makeDevice(blockId);
        String key = keyOf(blockId);
        String token = selfRegister("key-buyer");

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/claim", Map.of("chipId", blockId, "key", key))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals(blockId, ((Number) resp.body().get("chipId")).longValue());
        assertEquals("USER", resp.body().get("accessRole"));

        // Блок появился в списке устройств аккаунта.
        HttpResponse<List<Map>> devices = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + token),
                Argument.listOf(Map.class));
        assertTrue(devices.body().stream()
                .anyMatch(d -> ((Number) d.get("chipId")).longValue() == blockId));
    }

    @Test
    @DisplayName("Ключ вводят как угодно: регистр, пробелы и дефисы не важны")
    void keyIsForgivingToInput() {
        makeDevice(blockId);
        String key = keyOf(blockId);
        String token = selfRegister("key-sloppy");

        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/claim",
                                Map.of("chipId", blockId, "key", key.toLowerCase().replace("-", " ")))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    @DisplayName("Неверный ключ не привязывает устройство")
    void wrongKeyRejected() {
        makeDevice(blockId);
        keyOf(blockId);
        String token = selfRegister("key-wrong");

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/claim",
                                Map.of("chipId", blockId, "key", "AAAA-BBBB-CCCC"))
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(400, ex.getStatus().getCode());

        HttpResponse<List<Map>> devices = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices").header("Authorization", "Bearer " + token),
                Argument.listOf(Map.class));
        assertTrue(devices.body().isEmpty(), "устройство не должно появиться");
    }

    @Test
    @DisplayName("Ошибка одна и та же для неверного ключа и несуществующего ID")
    void noDeviceEnumeration() {
        String token = selfRegister("key-probe");

        HttpClientResponseException byKey = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/claim",
                                Map.of("chipId", blockId, "key", "AAAA-BBBB-CCCC"))
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)));
        HttpClientResponseException byId = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/claim",
                                Map.of("chipId", 555555555L, "key", "AAAA-BBBB-CCCC"))
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)));
        assertEquals(byKey.getStatus().getCode(), byId.getStatus().getCode());
        assertEquals(400, byId.getStatus().getCode());
    }

    @Test
    @DisplayName("Регистрация сразу привязывает блок: аккаунт и устройство одним шагом")
    void registerWithDeviceKey() {
        makeDevice(blockId);
        String key = keyOf(blockId);

        HttpResponse<Map> reg = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", "key-combined", "password", "pw-123456",
                                        "chipId", blockId, "key", key))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        assertEquals("USER", reg.body().get("role"));
        assertEquals(Boolean.TRUE, reg.body().get("deviceBound"), "привязка была в этом же ответе");

        // Токен отдаётся сразу: покупатель не вводит пароль второй раз.
        String token = (String) reg.body().get("token");
        assertNotNull(token);
        client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId).header("Authorization", "Bearer " + token),
                Argument.of(Map.class));
    }

    @Test
    @DisplayName("Неверный ключ при регистрации не создаёт аккаунт")
    void failedClaimRollsBackRegistration() {
        makeDevice(blockId);
        keyOf(blockId);

        assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/register",
                                Map.of("username", "key-rollback", "password", "pw-123456",
                                        "chipId", blockId, "key", "AAAA-BBBB-CCCC"))
                                .contentType(MediaType.APPLICATION_JSON)));

        HttpResponse<List<Map>> list = client.toBlocking().exchange(
                HttpRequest.GET("/api/admin/users").header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        assertTrue(list.body().stream().noneMatch(u -> "key-rollback".equals(u.get("username"))),
                "аккаунт не должен остаться в базе");
    }

    @Test
    @DisplayName("Повторный ввод пары «ID + ключ» ничего не ломает")
    void claimIsIdempotent() {
        makeDevice(blockId);
        String key = keyOf(blockId);
        String token = selfRegister("key-twice");

        for (int i = 0; i < 2; i++) {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/devices/claim", Map.of("chipId", blockId, "key", key))
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class));
        }
        HttpResponse<List<Map>> access = client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId + "/access")
                        .header("Authorization", "Bearer " + adminToken),
                Argument.listOf(Map.class));
        long forBuyer = access.body().stream()
                .filter(e -> "USER".equals(e.get("role")) || "USER".equals(e.get("accessRole")))
                .count();
        assertTrue(forBuyer >= 1);
    }

    @Test
    @DisplayName("Перевыпуск ключа обесценивает прежний лист, доступы сохраняются")
    void regenerateInvalidatesOldSheet() {
        makeDevice(blockId);
        String oldKey = keyOf(blockId);
        String token = selfRegister("key-regen");
        client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/claim", Map.of("chipId", blockId, "key", oldKey))
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON));

        HttpResponse<Map> resp = client.toBlocking().exchange(
                HttpRequest.POST("/api/devices/" + blockId + "/key/regenerate", Map.of())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        String newKey = (String) resp.body().get("key");
        assertTrue(!oldKey.equals(newKey));

        String other = selfRegister("key-old-sheet");
        assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.POST("/api/devices/claim", Map.of("chipId", blockId, "key", oldKey))
                                .header("Authorization", "Bearer " + other)
                                .contentType(MediaType.APPLICATION_JSON)));

        // Уже привязанный покупатель доступ не теряет.
        client.toBlocking().exchange(
                HttpRequest.GET("/api/devices/" + blockId).header("Authorization", "Bearer " + token),
                Argument.of(Map.class));
    }

    @Test
    @DisplayName("Ключ устройства недоступен обычному покупателю")
    void keyIsAdminOnly() {
        makeDevice(blockId);
        keyOf(blockId);
        String token = selfRegister("key-peek");

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.GET("/api/devices/" + blockId + "/key")
                                .header("Authorization", "Bearer " + token)));
        assertEquals(403, ex.getStatus().getCode());
    }
}