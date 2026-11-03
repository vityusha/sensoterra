package ru.sensoterra.server;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ru.sensoterra.server.cli.AdminCli;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** The CLI runs on plain JDBC; here against the same in-memory H2 the test context uses. */
@MicronautTest(transactional = false)
@DisplayName("Admin CLI")
class AdminCliTest {

    private static final String H2_URL = "jdbc:h2:mem:testdb;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE";

    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    AppUserRepository users;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private int cli(List<String> passwords, String... args) throws Exception {
        Deque<String> queue = new ArrayDeque<>(passwords);
        output.reset();
        try (Connection db = DriverManager.getConnection(H2_URL, "sa", "")) {
            return new AdminCli(db, prompt -> queue.isEmpty() ? null : queue.poll().toCharArray(),
                    new PrintStream(output, true, StandardCharsets.UTF_8)).execute(List.of(args));
        }
    }

    private String out() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private int login(String name, String password) {
        try {
            client.toBlocking().exchange(HttpRequest.POST("/api/auth/login",
                    Map.of("username", name, "password", password)));
            return 200;
        } catch (HttpClientResponseException e) {
            return e.getStatus().getCode();
        }
    }

    private static String name() {
        return "cli-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @Test
    @DisplayName("create makes an ADMIN who can sign in; existing names and bad passwords are refused")
    void create() throws Exception {
        String name = name();
        assertEquals(0, cli(List.of("cli-pass-123", "cli-pass-123"), "create", name), out());
        AppUser admin = users.findByUsername(name).orElseThrow();
        assertEquals("ADMIN", admin.getRole());
        assertNull(admin.getTotpSecret());
        assertEquals(200, login(name, "cli-pass-123"));

        assertEquals(1, cli(List.of("cli-pass-123", "cli-pass-123"), "create", name));
        assertTrue(out().contains("already exists"));

        String other = name();
        assertEquals(1, cli(List.of("cli-pass-123", "cli-pass-124"), "create", other));
        assertTrue(out().contains("do not match"));
        assertEquals(1, cli(List.of("short", "short"), "create", other));
        assertTrue(users.findByUsername(other).isEmpty(), "nothing is written on a failed command");
    }

    @Test
    @DisplayName("promote / demote / reset-password / reset-2fa")
    void manage() throws Exception {
        String name = name();
        client.toBlocking().exchange(HttpRequest.POST("/api/auth/register",
                Map.of("username", name, "password", "pw-123456")));

        assertEquals(0, cli(List.of(), "promote", name), out());
        assertEquals("ADMIN", users.findByUsername(name).orElseThrow().getRole());
        assertEquals(1, cli(List.of(), "promote", name));

        AppUser admin = users.findByUsername(name).orElseThrow();
        admin.setTotpSecret("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        admin.setTotpLastStep(42L);
        users.update(admin);
        assertEquals(0, cli(List.of(), "reset-2fa", name), out());
        AppUser reset = users.findByUsername(name).orElseThrow();
        assertNull(reset.getTotpSecret());
        assertNull(reset.getTotpLastStep());

        assertEquals(0, cli(List.of("new-pass-456", "new-pass-456"), "reset-password", name), out());
        assertEquals(200, login(name, "new-pass-456"));
        assertEquals(401, login(name, "pw-123456"));

        assertEquals(0, cli(List.of("x"), "list"));
        assertTrue(out().contains(name));

        assertEquals(0, cli(List.of(), "demote", name), out());
        assertEquals("USER", users.findByUsername(name).orElseThrow().getRole());

        assertEquals(1, cli(List.of(), "promote", "no-such-user-" + name));
        assertEquals(2, cli(List.of(), "frobnicate"));
    }
}
