package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import io.micronaut.transaction.TransactionOperations;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Вход через Google. Проверяем ровно то, ради чего он нужен: подпись настоящая
 * (JWKS подменён тестовым ключом, но проверка та же), а аккаунт не заводится,
 * если токен чужой, подписан чужим ключом, просрочен или почта не подтверждена.
 */
@MicronautTest
@DisplayName("Вход через Google: подпись токена и аккаунт")
class GoogleOAuthTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AppUserRepository userRepository;

    @Inject
    TransactionOperations<Connection> tx;

    private static FakeGoogle google;

    @BeforeAll
    static void startGoogle() {
        google = FakeGoogle.get();
    }

    @BeforeEach
    void ensureAdmin() {
        Map<String, String> creds = Map.of("username", "admin", "password", "admin-pass-123");
        Map status = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/status"), Argument.of(Map.class)).body();
        if (Boolean.TRUE.equals(status.get("bootstrapNeeded"))) {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", creds)
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class));
        }
    }

    private Map<String, Object> googleLogin(String idToken, Long chipId, String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("idToken", idToken);
        if (chipId != null) {
            body.put("chipId", chipId);
        }
        if (key != null) {
            body.put("key", key);
        }
        return client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/oauth/google", body)
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body();
    }

    @Test
    @DisplayName("Статус сообщает, что вход через Google включён")
    void statusReportsGoogleEnabled() {
        Map status = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/status"), Argument.of(Map.class)).body();
        assertEquals(Boolean.TRUE, status.get("googleEnabled"));
    }

    @Test
    @DisplayName("Первый вход заводит USER и сразу выдаёт наш токен")
    void firstLoginCreatesUser() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        Map body = googleLogin(google.issueToken(id.get("sub"), id.get("email"), true), null, null);

        assertEquals("USER", body.get("role"));
        assertEquals("google", body.get("authProvider"), "client shows the provider without a reload");
        assertNotNull(body.get("token"), "свой JWT, не гугловый");
        assertEquals(Boolean.FALSE, body.get("deviceBound"));

        // Токен действителен: устройств нет, но /me отвечает.
        String token = (String) body.get("token");
        Map me = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + token),
                Argument.of(Map.class)).body();
        assertEquals(id.get("email"), me.get("email"), "email отдаётся как есть");
    }

    @Test
    @DisplayName("Повторный вход того же sub не плодит аккаунты")
    void secondLoginIsSameAccount() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        Map first = googleLogin(google.issueToken(id.get("sub"), id.get("email"), true), null, null);
        Map second = googleLogin(google.issueToken(id.get("sub"), id.get("email"), true), null, null);
        assertEquals(first.get("id"), second.get("id"), "тот же аккаунт по паре провайдер+sub");
    }

    @Test
    @DisplayName("Токен, подписанный чужим ключом, не принимается")
    void foreignSignatureRejected() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueTokenSignedByStranger(id.get("sub"), id.get("email")),
                        null, null));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Токен для другого приложения не принимается: сверяем aud со своим client-id")
    void foreignAudienceRejected() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueTokenForOtherAudience(id.get("sub"), id.get("email")),
                        null, null));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Просроченный токен не принимается")
    void expiredTokenRejected() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueExpiredToken(id.get("sub"), id.get("email")), null, null));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Неподтверждённая почта не заводит аккаунт")
    void unverifiedEmailRejected() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueToken(id.get("sub"), id.get("email"), false), null, null));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Мусор вместо токена — 401, а не 500")
    void garbageTokenRejected() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueGarbage(), null, null));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @DisplayName("Парольный аккаунт на тот же email Google не занимает")
    void emailOfPasswordAccountIsNotTakenOver() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        // Парольный аккаунт с адресом: /register адрес не принимает, а у уже
        // заведённого покупателя он есть — так и проверяем коллизию.
        client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register",
                                Map.of("username", "pw-" + id.get("sub").substring(4),
                                        "password", "pw123456"))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class));
        // Адрес ставим тем же соединением, в котором идёт транзакция: /register его не
        // принимает, а у заведённого покупателя адрес есть. Через repository.update()
        // запись уходит в другую сессию и наружу не видна.
        String username = "pw-" + id.get("sub").substring(4);
        tx.executeWrite(status -> {
            try (var ps = status.getConnection().prepareStatement(
                    "UPDATE app_user SET email = ? WHERE username = ?")) {
                ps.setString(1, id.get("email"));
                ps.setString(2, username);
                ps.executeUpdate();
            }
            return null;
        });

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueToken(id.get("sub"), id.get("email"), true), null, null));
        assertEquals(409, ex.getStatus().getCode(), "адрес занят парольным аккаунтом — войти им");
    }

    @Test
    @DisplayName("Вход по Google с неверным ключом устройства не заводит аккаунт")
    void wrongDeviceKeyLeavesNoAccount() {
        String sub = "sub-wrongkey";
        String email = "wrongkey@example.com";
        long before = userRepository.count();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> googleLogin(google.issueToken(sub, email, true), 999999L, "WRONG-KEY-WRONG-1"));
        assertEquals(400, ex.getStatus().getCode());
        assertEquals(before, userRepository.count(), "транзакция откатилась вместе с аккаунтом");
    }

    @Test
    @DisplayName("Парольный вход для Google-аккаунта подсказывает, чем войти")
    void passwordLoginPointsToGoogle() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        Map body = googleLogin(google.issueToken(id.get("sub"), id.get("email"), true), null, null);
        String username = String.valueOf(body.get("username"));

        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> client.toBlocking().exchange(
                        HttpRequest.POST("/api/auth/login",
                                        Map.of("username", username, "password", "whatever"))
                                .contentType(MediaType.APPLICATION_JSON),
                        Argument.of(Map.class)));
        assertEquals(401, ex.getStatus().getCode());
        assertTrue(ex.getResponse().getBody(String.class).orElse("").contains("google"),
                "подсказка: входи через Google");
    }
}