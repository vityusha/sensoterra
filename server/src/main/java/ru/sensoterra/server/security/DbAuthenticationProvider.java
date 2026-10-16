package ru.sensoterra.server.security;

import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import io.micronaut.context.annotation.Primary;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.authentication.AuthenticationFailed;
import io.micronaut.security.authentication.AuthenticationFailureReason;
import io.micronaut.security.authentication.AuthenticationRequest;
import io.micronaut.security.authentication.AuthenticationResponse;
import io.micronaut.security.authentication.provider.AuthenticationProvider;
import jakarta.inject.Singleton;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Primary
@Singleton
public class DbAuthenticationProvider implements AuthenticationProvider<HttpRequest<?>, String, String> {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public DbAuthenticationProvider(AppUserRepository userRepository, PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public AuthenticationResponse authenticate(HttpRequest<?> httpRequest,
                                               AuthenticationRequest<String, String> authenticationRequest) {
        String username = authenticationRequest.getIdentity();
        String password = authenticationRequest.getSecret();
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPasswordHash())) {
            return new AuthenticationFailed(AuthenticationFailureReason.CREDENTIALS_DO_NOT_MATCH);
        }
        if (Boolean.FALSE.equals(user.getEnabled())) {
            return new AuthenticationFailed(AuthenticationFailureReason.USER_DISABLED);
        }
        List<String> roles = Collections.singletonList(user.getRole());
        return AuthenticationResponse.success(username, roles, Map.of("sub", username, "roles", roles));
    }
}