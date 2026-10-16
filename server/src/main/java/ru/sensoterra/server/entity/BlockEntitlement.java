package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;

import java.time.Instant;

/**
 * Оплата блока: сколько у него осталось рабочего времени.
 *
 * Срок отсчитывается с первого подтверждения пары «ID + ключ» и общий для всех, кто
 * имеет доступ к блоку, — завести нового пользователя с тем же листом нельзя лишь
 * потому, что он новый: отсчёт уже идёт.
 *
 * Пустая строка (её нет) означает «плана нет» — тогда действует дефолт из server_setting.
 * started_at = NULL означает, что блок ещё ни разу не подтверждён ключом: срок неизвестен,
 * пока не случится первая регистрация.
 */
@Entity
@Serdeable
@Table(name = "block_entitlement")
@Data
public class BlockEntitlement {

    @Id
    @Column(name = "chip_id")
    private Long chipId;

    /** Срок в днях, назначенный этому блоку админом. NULL = брать дефолт. */
    @Column(name = "plan_days")
    private Integer planDays;

    /** Бессрочный доступ к блоку: админ назначил осознанно. */
    @Column(nullable = false)
    private Boolean unlimited = Boolean.FALSE;

    /** Момент первого подтверждения ключом — с него идёт отсчёт. */
    @Column(name = "started_at")
    private Instant startedAt;

    /** Кто подтвердил ключом первым. */
    @Column(name = "started_by_user_id")
    private Long startedByUserId;

    /** NULL вместе с unlimited = true — бессрочно. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
