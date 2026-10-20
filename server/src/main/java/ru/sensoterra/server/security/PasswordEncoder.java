package ru.sensoterra.server.security;

import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.mindrot.jbcrypt.BCrypt;

@Singleton
public class PasswordEncoder {

    /** Стоимость BCrypt для новых хэшей; у старых она записана в самом хэше, проверка от неё не зависит. */
    private final int cost;

    public PasswordEncoder(@Value("${sensoterra.security.bcrypt-cost:12}") int cost) {
        this.cost = cost;
    }

    public String encode(String rawPassword) {
        return BCrypt.hashpw(rawPassword, BCrypt.gensalt(cost));
    }

    public boolean matches(String rawPassword, String encodedPassword) {
        if (encodedPassword == null || encodedPassword.isEmpty()) {
            return false;
        }
        try {
            return BCrypt.checkpw(rawPassword, encodedPassword);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
