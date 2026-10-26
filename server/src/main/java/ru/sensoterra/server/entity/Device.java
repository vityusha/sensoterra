package ru.sensoterra.server.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;

@Entity
@Serdeable
@Table(name = "device")
@Data
public class Device {

    @Id
    private Long chipId;

    private String name;

    @Column(name = "device_type", nullable = false)
    private String deviceType;

    @Column(name = "tz_offset_minutes", nullable = false)
    private Integer tzOffsetMinutes = 180;

    /**
     * Ключ устройства из комплектного листа. В открытом виде: админ смотрит и копирует,
     * чтобы отпечатать лист. Покупатель вводит пару «ID + ключ» и привязывает устройство
     * к своему аккаунту. NULL = ключ ещё не выпущен.
     */
    // Never serialized: anyone who sees the device (incl. read-only guests) would get the key
    // and could claim the device. Admins read it via GET /api/devices/{chipId}/key.
    @JsonIgnore
    @Column(name = "device_key", length = 32)
    private String deviceKey;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();

    /**
     * Последний STATUS устройства. device_status_log чистится retention-политикой,
     * поэтому последнее значение хранится здесь. Пишется только нативным SQL
     * (TelemetryService.saveDeviceStatus) — JPA не перезаписывает его устаревшим значением.
     */
    @Column(name = "last_status", length = 20, insertable = false, updatable = false)
    private String lastStatus;

    @Column(name = "last_status_at", insertable = false, updatable = false)
    private Instant lastStatusAt;
}
