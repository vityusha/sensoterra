package ru.sensoterra.server;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Подставной Google для тестов: раздаёт JWKS с нашим RSA-ключом и подписывает им
 * id_token. Настоящий Google в тестах не участвует — сеть не нужна, а проверка
 * подписи остаётся настоящей (тот же nimbus, те же JWKS).
 *
 * Адрес JWKS и client-id лежат в application.yml тестов на фиксированном порту:
 * конфиг Micronaut снимается при старте контекста, а ключи читаются лениво, поэтому
 * сервер успевает подняться в @BeforeAll, уже после чтения конфига.
 */
final class FakeGoogle implements AutoCloseable {

    static final String CLIENT_ID = "test-client.apps.googleusercontent.com";
    static final String ISSUER = "https://accounts.google.com";

    /** Порт из application.yml тестов: адрес JWKS должен быть известен до старта контекста. */
    private static final int PORT = 18443;

    private static final FakeGoogle INSTANCE = start();

    private final RSAKey rsaKey;
    private final HttpServer server;

    private FakeGoogle(RSAKey rsaKey, HttpServer server) {
        this.rsaKey = rsaKey;
        this.server = server;
    }

    static FakeGoogle get() {
        return INSTANCE;
    }

    private static FakeGoogle start() {
        try {
            RSAKey rsaKey = new RSAKeyGenerator(2048).keyID("test-key-1").generate();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/certs", exchange -> {
                byte[] body = new JWKSet(rsaKey.toPublicJWK()).toString()
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
            return new FakeGoogle(rsaKey, server);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot start fake Google JWKS", e);
        }
    }

    /** Токен с нашими клеймами: подпись настоящая, ключи Google подменены на тестовые. */
    String issueToken(String subject, String email, boolean emailVerified) {
        return issueToken(subject, email, emailVerified, CLIENT_ID,
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), 3600);
    }

    /** Токен под чужой audience: подпись настоящая, но выдан для другого приложения. */
    String issueTokenForOtherAudience(String subject, String email) {
        return issueToken(subject, email, true, "other-client.apps.googleusercontent.com",
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), 3600);
    }

    /** Токен с истёкшим сроком — проверка clock skew. */
    String issueExpiredToken(String subject, String email) {
        return issueToken(subject, email, true, CLIENT_ID,
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(rsaKey.getKeyID()).build(), -7200);
    }

    /** Подпись чужим ключом: JWKS наш, а подпись не наша. */
    String issueTokenSignedByStranger(String subject, String email) {
        try {
            RSAKey stranger = new RSAKeyGenerator(2048).keyID("stranger-key").generate();
            JWTClaimsSet claims = claims(subject, email, true, CLIENT_ID, 3600);
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(stranger.getKeyID()).build(), claims);
            jwt.sign(new RSASSASigner(stranger));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Мусор вместо JWT. */
    String issueGarbage() {
        return "not-a-jwt-" + UUID.randomUUID();
    }

    private String issueToken(String subject, String email, boolean emailVerified, String audience,
                              JWSHeader header, int ttlSeconds) {
        try {
            SignedJWT jwt = new SignedJWT(header, claims(subject, email, emailVerified, audience, ttlSeconds));
            jwt.sign(new RSASSASigner(rsaKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static JWTClaimsSet claims(String subject, String email, boolean emailVerified,
                                       String audience, int ttlSeconds) {
        Instant now = Instant.now();
        return new JWTClaimsSet.Builder()
                .subject(subject)
                .issuer(ISSUER)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(ttlSeconds)))
                .claim("email", email)
                .claim("email_verified", emailVerified)
                .build();
    }

    /** Свежая пара «адрес + sub» для тестов, чтобы не путать кейсы между собой. */
    static Map<String, String> freshIdentity() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        return Map.of("sub", "sub-" + tag, "email", "buyer" + tag + "@example.com");
    }

    @Override
    public void close() {
        server.stop(0);
    }
}