package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Смена собственного пароля. Главное в ней — что старый пароль спрашивается:
 * без этого утёкший токен давал бы не только чтение датчиков, но и вечный захват
 * аккаунта. Поэтому проверяем и отказ при неверном текущем пароле, и что неудачная
 * попытка ничего не ломает.
 */
@MicronautTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@DisplayName("Смена собственного пароля")
class PasswordChangeTest {

    private static final String OLD_PASSWORD = "oldpass123";
    private static final String NEW_PASSWORD = "newpass456";

    @Inject
    @Client("/")
    HttpClient client;

    private static FakeGoogle google;
    private static String userToken;

    @BeforeAll
    static void startGoogle() {
        google = FakeGoogle.get();
    }

    /** true, если вход удался; «неверный пароль» и «аккаунт отключён» оба считаются провалом. */
    private boolean loginWorks(String username, String password) {
        try {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/login", Map.of("username", username, "password", password)),
                    Argument.of(Map.class));
            return true;
        } catch (HttpClientResponseException ex) {
            return false;
        }
    }

    private Map change(String token, String current, String next) {
        return client.toBlocking().retrieve(
                HttpRequest.PUT("/api/auth/password",
                        Map.of("currentPassword", current, "newPassword", next))
                        .header("Authorization", "Bearer " + token),
                Map.class);
    }

    private int statusOfChange(String current, String next) {
        try {
            change(userToken, current, next);
            return 200;
        } catch (HttpClientResponseException ex) {
            return ex.getStatus().getCode();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Регистрируем пользователя и получаем токен")
    void registerUser() {
        Map body = client.toBlocking().retrieve(
                HttpRequest.POST("/api/auth/register",
                        Map.of("username", "pwd-user", "password", OLD_PASSWORD)), Map.class);
        userToken = (String) body.get("token");
        assertNotNull(userToken);
        assertTrue(loginWorks("pwd-user", OLD_PASSWORD));
    }

    @Test
    @Order(2)
    @DisplayName("Без токена смена пароля не проходит")
    void changeRequiresAuth() {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class, () ->
                client.toBlocking().exchange(
                        HttpRequest.PUT("/api/auth/password",
                                Map.of("currentPassword", OLD_PASSWORD, "newPassword", NEW_PASSWORD)),
                        Argument.of(Map.class)));
        assertEquals(401, ex.getStatus().getCode());
    }

    @Test
    @Order(3)
    @DisplayName("Неверный текущий пароль отклоняется и ничего не меняет")
    void wrongCurrentPassword() {
        assertEquals(401, statusOfChange("wrong-" + OLD_PASSWORD, NEW_PASSWORD));
        assertTrue(loginWorks("pwd-user", OLD_PASSWORD));
        assertFalse(loginWorks("pwd-user", NEW_PASSWORD));
    }

    @Test
    @Order(4)
    @DisplayName("Слишком короткий и совпадающий с текущим новый пароль отклоняются")
    void weakNewPasswordsRejected() {
        assertEquals(400, statusOfChange(OLD_PASSWORD, "short"));
        assertEquals(400, statusOfChange(OLD_PASSWORD, OLD_PASSWORD));
        assertTrue(loginWorks("pwd-user", OLD_PASSWORD));
    }

    @Test
    @Order(5)
    @DisplayName("Пустые поля паролем не считаются")
    void blankFieldsRejected() {
        assertEquals(400, statusOfChange("", NEW_PASSWORD));
        assertEquals(400, statusOfChange(OLD_PASSWORD, ""));
        assertEquals(400, statusOfChange(OLD_PASSWORD, "   "));
        assertTrue(loginWorks("pwd-user", OLD_PASSWORD));
    }

    @Test
    @Order(6)
    @DisplayName("Успешная смена: старый пароль перестаёт работать, новый работает")
    void successfulChange() {
        assertTrue(change(userToken, OLD_PASSWORD, NEW_PASSWORD).containsKey("ok"));
        assertFalse(loginWorks("pwd-user", OLD_PASSWORD));
        assertTrue(loginWorks("pwd-user", NEW_PASSWORD));
    }

    @Test
    @Order(7)
    @DisplayName("Токен, выданный до смены, продолжает работать")
    void oldTokenStillValid() {
        Map profile = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + userToken), Map.class);
        assertEquals("pwd-user", profile.get("username"));
    }

    @Test
    @Order(8)
    @DisplayName("У Google-аккаунта пароля нет: профиль это показывает, смена отклоняется")
    void externalAccountCannotChangePassword() {
        Map<String, String> id = FakeGoogle.freshIdentity();
        Map body = client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/oauth/google",
                        Map.of("idToken", google.issueToken(id.get("sub"), id.get("email"), true)))
                        .contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body();
        String googleToken = (String) body.get("token");
        assertNotNull(googleToken);

        Map profile = client.toBlocking().retrieve(
                HttpRequest.GET("/api/auth/me").header("Authorization", "Bearer " + googleToken), Map.class);
        assertEquals("google", profile.get("authProvider"));

        assertThrows(HttpClientResponseException.class, () -> change(googleToken, NEW_PASSWORD, "brandnew789"));
    }
}