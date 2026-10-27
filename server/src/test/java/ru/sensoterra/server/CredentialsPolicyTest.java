package ru.sensoterra.server;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.security.CredentialsPolicy;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@MicronautTest
@DisplayName("Username and password policy")
class CredentialsPolicyTest {

    @Inject
    @Client("/")
    HttpClient client;

    private static String reason(Runnable check) {
        return assertThrows(HttpStatusException.class, check::run).getMessage();
    }

    @Test
    @DisplayName("Weak passwords are rejected with a reason")
    void weakPasswords() {
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("", "bob")).contains("required"));
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("short1", "bob")).contains("at least 8"));
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("a".repeat(73), "bob")).contains("at most 72"));
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("Johnsmith", "johnsmith")).contains("username"));
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("aaaaaaaaaa", "bob")).contains("common"));
        assertTrue(reason(() -> CredentialsPolicy.requirePassword("Password1", "bob")).contains("common"));
        // 36 Cyrillic letters = 72 bytes in UTF-8: still fits.
        assertDoesNotThrow(() -> CredentialsPolicy.requirePassword("пароль".repeat(6), "bob"));
        assertDoesNotThrow(() -> CredentialsPolicy.requirePassword("green-field-42", "bob"));
    }

    @Test
    @DisplayName("Usernames: length and characters")
    void usernames() {
        assertEquals("ivan.petrov", CredentialsPolicy.requireUsername("  ivan.petrov "));
        assertEquals("иван@дом", CredentialsPolicy.requireUsername("иван@дом"));
        assertTrue(reason(() -> CredentialsPolicy.requireUsername("ab")).contains("3-50"));
        assertTrue(reason(() -> CredentialsPolicy.requireUsername("x".repeat(51))).contains("3-50"));
        assertTrue(reason(() -> CredentialsPolicy.requireUsername("ivan petrov")).contains("only letters"));
        assertTrue(reason(() -> CredentialsPolicy.requireUsername("ivan;drop")).contains("only letters"));
    }

    @Test
    @DisplayName("Registration enforces the policy")
    void registrationUsesPolicy() {
        String name = "policy-" + UUID.randomUUID().toString().substring(0, 8);
        var ex = assertThrows(HttpClientResponseException.class, () -> client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register", Map.of("username", name, "password", "12345678"))));
        assertEquals(400, ex.getStatus().getCode());
        assertEquals(200, client.toBlocking().exchange(
                HttpRequest.POST("/api/auth/register", Map.of("username", name, "password", "green-field-42"))).code());
    }
}
