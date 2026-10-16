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
 * Доступ аккаунта к устройству. Один аккаунт может иметь доступ к многим устройствам,
 * на одно устройство — много аккаунтов (владелец и наблюдатели). Права задаются
 * на устройство: один и тот же человек на своём блоке — хозяин (USER), на чужом —
 * наблюдатель (VIEWER).
 */
@Entity
@Table(name = "device_access")
@Data
public class DeviceAccess {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "chip_id", nullable = false)
    private Long chipId;

    /** USER — управление устройством, VIEWER — только чтение. */
    @Column(name = "access_role", nullable = false, length = 16)
    private String accessRole = "USER";

    @Column(name = "granted_at", nullable = false)
    private Instant grantedAt = Instant.now();
}
