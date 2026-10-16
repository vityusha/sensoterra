package ru.sensoterra.server.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.Instant;

/**
 * Приглашение на доступ к устройству. Токен одноразовый: у блока он живёт 7 дней,
 * у автономного датчика срока нет (expires_at пустой) — срок отключения ему не нужен.
 * По принятии заполняется accepted_by/accepted_at.
 */
@Entity
@Table(name = "device_invite")
@Data
public class DeviceInvite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "chip_id", nullable = false)
    private Long chipId;

    @Column(name = "created_by", nullable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Какие права достанутся принявшему: USER — управление, VIEWER — только чтение. */
    @Column(name = "access_role", nullable = false, length = 16)
    private String accessRole = "USER";

    @Column(name = "accepted_by")
    private Long acceptedBy;

    @Column(name = "accepted_at")
    private Instant acceptedAt;

    @Column(nullable = false)
    private Boolean revoked = Boolean.FALSE;

    /** Приглашение ещё можно принять: не отозвано и не использовано. */
    public boolean isUsable() {
        return !Boolean.TRUE.equals(revoked)
                && acceptedAt == null
                && (expiresAt == null || expiresAt.isAfter(Instant.now()));
    }
}