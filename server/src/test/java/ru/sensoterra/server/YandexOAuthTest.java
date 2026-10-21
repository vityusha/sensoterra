package ru.sensoterra.server;

import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.FakeYandex.Profile;
import ru.sensoterra.server.repository.AppUserRepository;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Yandex ID sign-in: the code is exchanged server-side, and no account appears when the
 * code is bad, the token belongs to another app, PKCE does not match or Yandex is down.
 */
@MicronautTest
@DisplayName("Yandex ID sign-in: code exchange and account")
class YandexOAuthTest {

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AppUserRepository userRepository;

    private final FakeYandex yandex = FakeYandex.get();

    @BeforeEach
    void ensureAdmin() {
        yandex.setDown(false);
        Map status = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/status"), Argument.of(Map.class)).body();
        if (Boolean.TRUE.equals(status.get("bootstrapNeeded"))) {
            client.toBlocking().exchange(
                    HttpRequest.POST("/api/auth/bootstrap", Map.of("username", "admin", "password", "admin123"))
                            .contentType(MediaType.APPLICATION_JSON),
                    Argument.of(Map.class));
        }
    }

    private Map yandexLogin(String code, String verifier, Long chipId, String key) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", code);
        body.put("codeVerifier", verifier);
        body.put("chipId", chipId);
        body.put("key", key);
        return client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/oauth/yandex", body).contentType(MediaType.APPLICATION_JSON),
                Argument.of(Map.class)).body();
    }

    private int failedStatus(String code, String verifier) {
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> yandexLogin(code, verifier, null, null));
        return ex.getStatus().getCode();
    }

    @Test
    @DisplayName("Status exposes Yandex client id")
    void statusReportsYandex() {
        Map status = client.toBlocking().exchange(
                HttpRequest.GET("/api/auth/status"), Argument.of(Map.class)).body();
        assertEquals(Boolean.TRUE, status.get("yandexEnabled"));
        assertEquals(FakeYandex.CLIENT_ID, status.get("yandexClientId"));
    }

    @Test
    @DisplayName("First sign-in creates a USER named after the Yandex login; second one reuses it")
    void firstLoginCreatesUserThenReuses() {
        Profile profile = FakeYandex.freshProfile();
        Map first = yandexLogin(yandex.issueCode(profile, null), null, null, null);
        assertEquals("USER", first.get("role"));
        assertEquals(profile.login(), first.get("username"));
        assertEquals(profile.email(), first.get("email"));
        assertNotNull(first.get("token"));

        Map second = yandexLogin(yandex.issueCode(profile, null), null, null, null);
        assertEquals(first.get("id"), second.get("id"), "same account by provider + id");
    }

    @Test
    @DisplayName("Profile without email still gets an account")
    void loginWithoutEmail() {
        Profile base = FakeYandex.freshProfile();
        Profile profile = new Profile(base.id(), base.login(), null, base.clientId());
        Map body = yandexLogin(yandex.issueCode(profile, null), null, null, null);
        assertNull(body.get("email"));
    }

    @Test
    @DisplayName("Unknown or reused code is 401")
    void badCodeRejected() {
        assertEquals(401, failedStatus("code-unknown", null));

        String code = yandex.issueCode(FakeYandex.freshProfile(), null);
        yandexLogin(code, null, null, null);
        assertEquals(401, failedStatus(code, null), "code is one-time");
    }

    @Test
    @DisplayName("Token issued to another application is 401")
    void foreignClientRejected() {
        Profile base = FakeYandex.freshProfile();
        Profile foreign = new Profile(base.id(), base.login(), base.email(), "other-client");
        long before = userRepository.count();
        assertEquals(401, failedStatus(yandex.issueCode(foreign, null), null));
        assertEquals(before, userRepository.count());
    }

    @Test
    @DisplayName("PKCE: wrong verifier is 401, right one signs in")
    void pkceChecked() {
        String verifier = "verifier-" + java.util.UUID.randomUUID();
        Profile profile = FakeYandex.freshProfile();
        assertEquals(401, failedStatus(
                yandex.issueCode(profile, FakeYandex.challengeOf(verifier)), "wrong-verifier"));

        Map body = yandexLogin(yandex.issueCode(profile, FakeYandex.challengeOf(verifier)), verifier, null, null);
        assertEquals(profile.login(), body.get("username"));
    }

    @Test
    @DisplayName("Yandex outage is 503, not 500")
    void outageIsUnavailable() {
        yandex.setDown(true);
        assertEquals(503, failedStatus(yandex.issueCode(FakeYandex.freshProfile(), null), null));
    }

    @Test
    @DisplayName("Wrong device key leaves no account")
    void wrongDeviceKeyLeavesNoAccount() {
        long before = userRepository.count();
        HttpClientResponseException ex = assertThrows(HttpClientResponseException.class,
                () -> yandexLogin(yandex.issueCode(FakeYandex.freshProfile(), null), null,
                        999999L, "WRONG-KEY-WRONG-1"));
        assertEquals(400, ex.getStatus().getCode());
        assertEquals(before, userRepository.count(), "transaction rolled back with the account");
    }

    @Test
    @DisplayName("Email of an existing account is not taken over")
    void emailOfExistingAccountIsNotTakenOver() {
        Profile profile = FakeYandex.freshProfile();
        yandexLogin(yandex.issueCode(profile, null), null, null, null);

        Profile other = new Profile(profile.id() + "1", profile.login() + "x", profile.email(), profile.clientId());
        assertEquals(409, failedStatus(yandex.issueCode(other, null), null));
    }
}
