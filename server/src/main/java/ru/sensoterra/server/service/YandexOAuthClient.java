package ru.sensoterra.server.service;

import io.micronaut.context.annotation.Value;
import io.micronaut.core.type.Argument;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ru.sensoterra.server.service.ExternalAccountService.ExternalIdentity;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Yandex ID sign-in, authorization code flow.
 *
 * The browser brings back a one-time code; the server exchanges it for an access token
 * with the client secret and reads the profile from login.yandex.ru/info. Yandex issues
 * no signed id_token here, so the secret never leaves the server and the token never
 * reaches the browser. The profile's client_id is checked as well: a token issued to
 * another application must not sign anyone in.
 */
@Singleton
public class YandexOAuthClient {

    private static final Logger log = LoggerFactory.getLogger(YandexOAuthClient.class);

    private static final String PROVIDER = "yandex";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final boolean enabled;
    private final String clientId;
    private final String clientSecret;
    private final String tokenUrl;
    private final String infoUrl;
    private final JsonMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public YandexOAuthClient(@Value("${sensoterra.oauth.yandex.enabled:false}") boolean enabled,
                             @Value("${sensoterra.oauth.yandex.client-id:}") String clientId,
                             @Value("${sensoterra.oauth.yandex.client-secret:}") String clientSecret,
                             @Value("${sensoterra.oauth.yandex.token-url:`https://oauth.yandex.ru/token`}") String tokenUrl,
                             @Value("${sensoterra.oauth.yandex.info-url:`https://login.yandex.ru/info`}") String infoUrl,
                             JsonMapper json) {
        this.enabled = enabled;
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.tokenUrl = tokenUrl;
        this.infoUrl = infoUrl;
        this.json = json;
    }

    /** Without client id and secret the button is hidden and the endpoint answers 501. */
    public boolean isEnabled() {
        return enabled && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    /** Public client id: the browser puts it into the authorize URL anyway. */
    public String clientId() {
        return clientId;
    }

    public static String provider() {
        return PROVIDER;
    }

    /**
     * Exchanges the code and returns the verified identity. A bad, expired or reused code
     * is 401; Yandex being down or rejecting our own credentials is 503.
     */
    public ExternalIdentity identify(String code, String codeVerifier) {
        if (!isEnabled()) {
            throw new HttpStatusException(HttpStatus.NOT_IMPLEMENTED, "Yandex login is not configured");
        }
        if (code == null || code.isBlank()) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "code is required");
        }
        String accessToken = exchangeCode(code.trim(), codeVerifier);
        Map<String, Object> info = fetchInfo(accessToken);

        String subject = str(info, "id");
        if (subject == null || subject.isBlank()) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Yandex profile");
        }
        if (!clientId.equals(str(info, "client_id"))) {
            log.warn("Rejected Yandex token issued to client_id={}", str(info, "client_id"));
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Yandex token");
        }
        // Yandex lists only confirmed addresses, so default_email needs no extra check.
        // It is missing when the app was not granted the email permission.
        String email = str(info, "default_email");
        email = email == null || email.isBlank() ? null : email.trim().toLowerCase();
        String login = str(info, "login");
        return new ExternalIdentity(PROVIDER, subject, email, login != null ? login : email);
    }

    private String exchangeCode(String code, String codeVerifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("client_id", clientId);
        form.put("client_secret", clientSecret);
        if (codeVerifier != null && !codeVerifier.isBlank()) {
            form.put("code_verifier", codeVerifier);
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(tokenUrl))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(form)))
                .build();
        HttpResponse<byte[]> response = send(request);
        Map<String, Object> body = parse(response);
        if (response.statusCode() == 200 && str(body, "access_token") != null) {
            return str(body, "access_token");
        }
        String error = str(body, "error");
        // invalid_grant: the code is wrong, expired, already used or the verifier does not match.
        if (response.statusCode() == 400 && "invalid_grant".equals(error)) {
            log.warn("Rejected Yandex code: {}", str(body, "error_description"));
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Yandex code");
        }
        // Anything else (invalid_client, 5xx) is our configuration or Yandex itself.
        log.error("Yandex token exchange failed: status={} error={} description={}",
                response.statusCode(), error, str(body, "error_description"));
        throw unavailable();
    }

    private Map<String, Object> fetchInfo(String accessToken) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(infoUrl + "?format=json"))
                .timeout(TIMEOUT)
                .header("Authorization", "OAuth " + accessToken)
                .GET()
                .build();
        HttpResponse<byte[]> response = send(request);
        if (response.statusCode() == 401) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Yandex token");
        }
        if (response.statusCode() != 200) {
            log.error("Yandex profile request failed: status={}", response.statusCode());
            throw unavailable();
        }
        return parse(response);
    }

    private HttpResponse<byte[]> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            log.error("Cannot reach Yandex at {}", request.uri(), e);
            throw unavailable();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable();
        }
    }

    private Map<String, Object> parse(HttpResponse<byte[]> response) {
        try {
            Map<String, Object> body = json.readValue(response.body(),
                    Argument.mapOf(String.class, Object.class));
            return body == null ? Map.of() : body;
        } catch (IOException e) {
            log.error("Unreadable Yandex response from {}: status={}", response.uri(), response.statusCode());
            throw unavailable();
        }
    }

    private static HttpStatusException unavailable() {
        return new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Yandex login is temporarily unavailable");
    }

    private static String formBody(Map<String, String> form) {
        return form.entrySet().stream()
                .map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String str(Map<String, Object> map, String name) {
        Object value = map.get(name);
        return value == null ? null : String.valueOf(value);
    }
}
