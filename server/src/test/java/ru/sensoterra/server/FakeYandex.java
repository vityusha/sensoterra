package ru.sensoterra.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stand-in for oauth.yandex.ru and login.yandex.ru: issues one-time codes, exchanges them
 * for tokens (checking client credentials and PKCE like Yandex does) and serves the profile.
 *
 * Fixed port from the test application.yml, for the same reason as FakeGoogle: the URLs
 * are read when the context starts.
 */
final class FakeYandex {

    static final String CLIENT_ID = "test-yandex-client";
    static final String CLIENT_SECRET = "test-yandex-secret";

    private static final int PORT = 18444;

    private static final FakeYandex INSTANCE = start();

    /** Profile behind a code / token. clientId is what /info reports the token was issued to. */
    record Profile(String id, String login, String email, String clientId) {
    }

    private record Grant(Profile profile, String challenge) {
    }

    private final Map<String, Grant> codes = new ConcurrentHashMap<>();
    private final Map<String, Profile> tokens = new ConcurrentHashMap<>();
    private volatile boolean down;

    static FakeYandex get() {
        return INSTANCE;
    }

    private static FakeYandex start() {
        try {
            FakeYandex fake = new FakeYandex();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/token", fake::token);
            server.createContext("/info", fake::info);
            server.start();
            return fake;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot start fake Yandex", e);
        }
    }

    /** One-time code for a profile, optionally bound to a PKCE S256 challenge. */
    String issueCode(Profile profile, String challenge) {
        String code = "code-" + UUID.randomUUID();
        codes.put(code, new Grant(profile, challenge));
        return code;
    }

    /** Token endpoint answers 500, as during a Yandex outage. */
    void setDown(boolean down) {
        this.down = down;
    }

    static Profile freshProfile() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return new Profile(String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits())),
                "ya-" + tag, "ya" + tag + "@yandex.ru", CLIENT_ID);
    }

    static String challengeOf(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void token(HttpExchange exchange) throws IOException {
        if (down) {
            respond(exchange, 500, "{\"error\":\"server_error\"}");
            return;
        }
        Map<String, String> form = parseForm(new String(exchange.getRequestBody().readAllBytes(),
                StandardCharsets.UTF_8));
        if (!CLIENT_ID.equals(form.get("client_id")) || !CLIENT_SECRET.equals(form.get("client_secret"))) {
            respond(exchange, 401, "{\"error\":\"invalid_client\"}");
            return;
        }
        Grant grant = codes.remove(String.valueOf(form.get("code")));
        if (grant == null
                || (grant.challenge() != null && (form.get("code_verifier") == null
                || !grant.challenge().equals(challengeOf(form.get("code_verifier")))))) {
            respond(exchange, 400, "{\"error\":\"invalid_grant\",\"error_description\":\"Code has expired\"}");
            return;
        }
        String token = "token-" + UUID.randomUUID();
        tokens.put(token, grant.profile());
        respond(exchange, 200, "{\"access_token\":\"" + token + "\",\"token_type\":\"bearer\"}");
    }

    private void info(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        Profile profile = auth != null && auth.startsWith("OAuth ") ? tokens.get(auth.substring(6)) : null;
        if (profile == null) {
            respond(exchange, 401, "");
            return;
        }
        StringBuilder body = new StringBuilder("{\"id\":\"").append(profile.id())
                .append("\",\"login\":\"").append(profile.login())
                .append("\",\"client_id\":\"").append(profile.clientId()).append('"');
        if (profile.email() != null) {
            body.append(",\"default_email\":\"").append(profile.email()).append('"');
        }
        respond(exchange, 200, body.append('}').toString());
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
