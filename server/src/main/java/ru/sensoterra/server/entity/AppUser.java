package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;

@Entity
@Serdeable
@Table(name = "app_user")
@Data
public class AppUser {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String username;

    @Column(unique = true)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    /**
     * Внешний провайдер входа ("google") либо null для парольного аккаунта.
     * Идентичность аккаунта — пара (auth_provider, auth_subject): email у Google
     * меняется, а локальная часть адреса ключом быть не может.
     */
    @Column(name = "auth_provider", length = 20)
    private String authProvider;

    /** Неизменяемый идентификатор пользователя у провайдера (в Google — `sub`). */
    @Column(name = "auth_subject", length = 64)
    private String authSubject;

    /** Аккаунт заведён через внешний провайдер, а не по паролю. */
    public boolean isExternal() {
        return authProvider != null;
    }

    @Column(nullable = false, length = 10)
    private String role;

    @Column(nullable = false)
    private Boolean enabled = Boolean.TRUE;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
