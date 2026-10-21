package ru.sensoterra.server.service;

import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.security.PasswordEncoder;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Accounts of external sign-in providers (Google, Yandex).
 *
 * Lives outside the controller so the provider round trip (JWKS, code exchange) runs
 * before the transaction: a slow provider must not hold a pooled DB connection.
 */
@Singleton
public class ExternalAccountService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final DeviceKeyService deviceKeys;
    private final UserPrefService prefs;

    public ExternalAccountService(AppUserRepository userRepository,
                                  PasswordEncoder passwordEncoder,
                                  DeviceKeyService deviceKeys,
                                  UserPrefService prefs) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.deviceKeys = deviceKeys;
        this.prefs = prefs;
    }

    /** Verified identity from a provider. email may be null when the provider did not share it. */
    public record ExternalIdentity(String provider, String subject, String email, String usernameBase) {
    }

    public record SignIn(AppUser user, boolean deviceBound) {
    }

    /**
     * Finds or creates the account and, if a device pair was sent, claims the device.
     * Ключ устройства обрабатывается так же, как в /register: в той же транзакции,
     * чтобы неверная пара не оставила полузаведённый аккаунт.
     */
    @Transactional
    public SignIn signIn(ExternalIdentity identity, Long chipId, String key) {
        AppUser user = findOrCreate(identity);
        boolean claimsDevice = chipId != null || (key != null && !key.isBlank());
        if (claimsDevice) {
            deviceKeys.claim(chipId, key, user.getId());
        }
        return new SignIn(user, claimsDevice);
    }

    /**
     * Аккаунт по паре (провайдер, sub). Если такой уже есть — просто вход.
     *
     * Совпадение email с чужим аккаунтом доказательством не считаем: адрес мог
     * освободиться и достаться другому человеку, поэтому линковки по почте здесь нет
     * вовсе. Админы тем более не трогаются — у них остаётся парольный вход как
     * аварийный доступ, который нельзя потерять из-за внешней учётной записи.
     */
    private AppUser findOrCreate(ExternalIdentity identity) {
        Optional<AppUser> existing = userRepository.findByAuthProviderAndAuthSubject(
                identity.provider(), identity.subject());
        if (existing.isPresent()) {
            AppUser user = existing.get();
            if (Boolean.FALSE.equals(user.getEnabled())) {
                throw new HttpStatusException(HttpStatus.FORBIDDEN, "Account is disabled");
            }
            return user;
        }
        if (identity.email() != null && userRepository.findByEmailIgnoreCase(identity.email()).isPresent()) {
            // The address belongs to another account (password or another provider):
            // the user has to sign in with that one.
            throw new HttpStatusException(HttpStatus.CONFLICT,
                    "This email is already used by another account — sign in with it");
        }
        AppUser user = new AppUser();
        user.setUsername(uniqueUsername(identity.usernameBase()));
        user.setEmail(identity.email());
        // Пароля нет и не будет: хеш случайных байт делает парольный вход невозможным,
        // а колонка остаётся NOT NULL — значит, и null-ветку в login() вводить не надо.
        user.setPasswordHash(passwordEncoder.encode(randomSecret()));
        user.setRole(DeviceAccessService.ROLE_USER);
        user.setEnabled(Boolean.TRUE);
        user.setCreatedAt(Instant.now());
        user.setAuthProvider(identity.provider());
        user.setAuthSubject(identity.subject());
        AppUser saved = userRepository.save(user);
        prefs.seedDefaults(saved.getId());
        return saved;
    }

    /** Username from the provider's hint (email local part, Yandex login); a number is appended if taken. */
    private String uniqueUsername(String hint) {
        String base = hint == null ? "" : hint;
        int at = base.indexOf('@');
        if (at > 0) {
            base = base.substring(0, at);
        }
        base = base.replaceAll("[^A-Za-z0-9._-]", "");
        if (base.isEmpty()) {
            base = "user";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String candidate = base;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            suffix++;
            candidate = base + suffix;
        }
        return candidate;
    }

    private static String randomSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
