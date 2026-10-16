package ru.sensoterra.server.service;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTClaimsVerifier;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.text.ParseException;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Проверка id_token от Google.
 *
 * Токен — подписанный JWT, поэтому подпись проверяется по публичным ключам Google
 * из JWKS, а пришедшим полям мы не доверяем. Дополнительно сверяем aud со своим
 * client-id: иначе токен, выданный для другого приложения, прошёл бы проверку.
 *
 * Ключи публичные, секретов здесь нет — в отличие от серверного OAuth-клиента, где
 * пришлось бы хранить client_secret. Поэтому внешнего сервиса не добавляем, nimbus-jose-jwt
 * уже приходит с micronaut-security-jwt.
 */
@Singleton
public class GoogleIdentityVerifier {

    private static final Logger log = LoggerFactory.getLogger(GoogleIdentityVerifier.class);

    private static final String ISSUER = "https://accounts.google.com";

    /** Расхождение часов: часы Google и нашего сервера не синхронизированы до секунды. */
    private static final Duration CLOCK_SKEW = Duration.ofMinutes(2);

    private final String clientId;
    private final String jwksUrl;
    private final boolean enabled;

    /**
     * Ключи Google достаём лениво и навсегда: при старте сервера ходить за ними незачем,
     * а временная недоступность JWKS не должна ронять весь /api/auth.
     */
    private volatile JWTProcessor<SecurityContext> processor;

    public GoogleIdentityVerifier(@Value("${sensoterra.oauth.google.client-id:}") String clientId,
                                  @Value("${sensoterra.oauth.google.jwks-url:https://www.googleapis.com/oauth2/v3/certs}") String jwksUrl,
                                  @Value("${sensoterra.oauth.enabled:false}") boolean enabled) {
        this.clientId = clientId == null ? "" : clientId.trim();
        this.jwksUrl = jwksUrl;
        this.enabled = enabled;
    }

    /** Вход через Google не настроен: кнопку на фронте прячем, эндпоинт отвечает 501. */
    public boolean isEnabled() {
        return enabled && !clientId.isEmpty();
    }

    /** Публичный client_id Google — его и так видно в коде страницы. */
    public String clientId() {
        return clientId;
    }

    private JWTProcessor<SecurityContext> processor() {
        if (!isEnabled()) {
            throw new HttpStatusException(HttpStatus.NOT_IMPLEMENTED, "Google login is not configured");
        }
        JWTProcessor<SecurityContext> result = processor;
        if (result == null) {
            synchronized (this) {
                result = processor;
                if (result == null) {
                    result = buildProcessor(clientId, jwksUrl);
                    processor = result;
                }
            }
        }
        return result;
    }

    private static JWTProcessor<SecurityContext> buildProcessor(String clientId, String jwksUrl) {
        try {
            // JWKSourceBuilder кэширует ключи сам и перезапрашивает их на незнакомый kid
            // (Google ротирует подписи), поэтому своего кэша у нас нет.
            JWKSource<SecurityContext> jwks = JWKSourceBuilder.create(new URL(jwksUrl)).build();
            // Первый аргумент — принимаемые значения audience, второй — claim'ы на
            // точное совпадение, третий и четвёртый — обязательные и запрещённые claim'ы
            // (пустые, всё нужное проверяем сами). Сверка aud со своим client-id защищает
            // от токена, выданного для чужого приложения.
            // Именно HashSet, а не Set.of(): nimbus 9.37.3 в конструкторе зовёт
            // acceptedAudience.contains(null) (проверяет, разрешён ли токен без aud), а
            // неизменяемый Set.of на null бросает NPE.
            DefaultJWTClaimsVerifier<SecurityContext> claimsVerifier =
                    new DefaultJWTClaimsVerifier<>(new HashSet<>(Set.of(clientId)),
                            new JWTClaimsSet.Builder().issuer(ISSUER).build(), Set.of(), Set.of());
            claimsVerifier.setMaxClockSkew((int) CLOCK_SKEW.toSeconds());
            ConfigurableJWTProcessor<SecurityContext> jwtProcessor = new DefaultJWTProcessor<>();
            jwtProcessor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, jwks));
            jwtProcessor.setJWTClaimsSetVerifier(claimsVerifier);
            return jwtProcessor;
        } catch (Exception e) {
            log.error("Cannot initialize Google JWKS from {}", jwksUrl, e);
            throw new HttpStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "Google login is temporarily unavailable");
        }
    }

    /**
     * Проверенная личность Google. Любая проблема с подписью, сроком, издателем или
     * адресатом — единый 401: различать «просрочен» и «не наш audience» покупателю
     * нечего, а лишних подсказок злоумышленнику давать не стоит.
     */
    public GoogleIdentity verify(String idToken) {
        JWTProcessor<SecurityContext> jwtProcessor = processor();
        if (idToken == null || idToken.isBlank()) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "id_token is required");
        }
        JWTClaimsSet claims;
        try {
            claims = jwtProcessor.process(idToken, null);
        } catch (ParseException | BadJOSEException | JOSEException e) {
            log.warn("Rejected Google id_token: {}", e.getMessage());
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        String subject = str(claims, "sub");
        String email = str(claims, "email");
        if (subject == null || subject.isBlank() || email == null || email.isBlank()) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Invalid Google token");
        }
        // Без подтверждённого адреса аккаунт не заводим: иначе Google отдаёт provisional
        // адрес, владение которым ещё не доказано.
        if (!isEmailVerified(claims)) {
            throw new HttpStatusException(HttpStatus.UNAUTHORIZED, "Google email is not verified");
        }
        return new GoogleIdentity(subject, email.toLowerCase(), str(claims, "name"));
    }

    /** claim встречается и булевым, и строкой "true" — оба варианта у Google в ходу миграций. */
    private static boolean isEmailVerified(JWTClaimsSet claims) {
        Object value = claims.getClaim("email_verified");
        if (value instanceof Boolean b) {
            return b;
        }
        return "true".equalsIgnoreCase(String.valueOf(value));
    }

    private static String str(JWTClaimsSet claims, String name) {
        Object value = claims.getClaim(name);
        return value == null ? null : String.valueOf(value);
    }

    /** Провайдер для app_user.auth_provider. */
    public static String provider() {
        return "google";
    }

    /**
     * Личность от Google: sub — настоящий ключ аккаунта, email — для показа и линковки
     * с уже заведённым парольным аккаунтом.
     */
    public record GoogleIdentity(String subject, String email, String name) {
    }
}