package ru.sensoterra.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    /**
     * The owner lets admins fully control and manage all of their devices (granted and
     * revoked by the owner from the main menu). Off: admins only view the owner's devices.
     */
    @Column(name = "admin_access")
    private Boolean adminAccess = Boolean.FALSE;

    @Column(name = "admin_access_since")
    private Instant adminAccessSince;

    /** Base32 TOTP secret of an admin; null until enrolled. Never serialized. */
    @JsonIgnore
    @Column(name = "totp_secret", length = 64)
    private String totpSecret;

    /** Last accepted TOTP time step: a code is accepted only once. */
    @JsonIgnore
    @Column(name = "totp_last_step")
    private Long totpLastStep;
}
