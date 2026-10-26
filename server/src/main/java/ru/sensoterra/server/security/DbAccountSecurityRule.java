package ru.sensoterra.server.security;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpAttributes;
import io.micronaut.http.HttpRequest;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.authentication.AuthorizationException;
import io.micronaut.security.rules.SecuredAnnotationRule;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.security.rules.SecurityRuleResult;
import io.micronaut.web.router.MethodBasedRouteMatch;
import io.micronaut.web.router.RouteMatch;
import jakarta.inject.Singleton;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Mono;
import ru.sensoterra.server.entity.AppUser;
import ru.sensoterra.server.repository.AppUserRepository;
import ru.sensoterra.server.service.DeviceAccessService;

import java.util.Arrays;
import java.util.List;

/**
 * Checks the account in the database before the annotation rules run.
 * <p>
 * A JWT lives 24 hours and carries the role it was issued with. Without this rule a
 * disabled, deleted or demoted admin keeps passing {@code @Secured("ADMIN")} with an old
 * token until it expires (create admins, delete devices). Here:
 * <ul>
 *   <li>authenticated request, account missing or disabled → 401;</li>
 *   <li>route requires ADMIN, role in the database is not ADMIN → REJECTED;</li>
 *   <li>otherwise UNKNOWN: the regular rules ({@link SecuredAnnotationRule}) decide.</li>
 * </ul>
 * Anonymous requests are not touched.
 */
@Singleton
public class DbAccountSecurityRule implements SecurityRule<HttpRequest<?>> {

    /** Before SecuredAnnotationRule: the first non-UNKNOWN result wins. */
    public static final int ORDER = SecuredAnnotationRule.ORDER - 100;

    private final AppUserRepository userRepository;

    public DbAccountSecurityRule(AppUserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public Publisher<SecurityRuleResult> check(HttpRequest<?> request, @Nullable Authentication authentication) {
        if (authentication == null) {
            return Mono.just(SecurityRuleResult.UNKNOWN);
        }
        return Mono.fromCallable(() -> decide(request, authentication.getName()));
    }

    private SecurityRuleResult decide(HttpRequest<?> request, String username) {
        AppUser user = userRepository.findByUsername(username).orElse(null);
        if (user == null || Boolean.FALSE.equals(user.getEnabled())) {
            // The token no longer stands for a usable account: 401 like an invalid token
            // (REJECTED would give 403 for an authenticated request).
            throw new AuthorizationException(null);
        }
        if (routeRequiresAdmin(request) && !DeviceAccessService.ROLE_ADMIN.equals(user.getRole())) {
            return SecurityRuleResult.REJECTED;
        }
        return SecurityRuleResult.UNKNOWN;
    }

    private static boolean routeRequiresAdmin(HttpRequest<?> request) {
        RouteMatch<?> route = request.getAttribute(HttpAttributes.ROUTE_MATCH, RouteMatch.class).orElse(null);
        if (!(route instanceof MethodBasedRouteMatch<?, ?> method)) {
            return false;
        }
        List<String> roles = method.getValue(Secured.class, String[].class)
                .map(Arrays::asList)
                .orElse(List.of());
        return roles.contains(DeviceAccessService.ROLE_ADMIN);
    }

    @Override
    public int getOrder() {
        return ORDER;
    }
}
