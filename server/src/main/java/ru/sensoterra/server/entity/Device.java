package ru.sensoterra.server.entity;

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
    @Column(name = "device_key", length = 32)
    private String deviceKey;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
