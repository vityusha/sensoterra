package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;

/**
 * One alarm episode (see AlarmService). Open while endedAt is null; at most one open
 * episode per chip and type.
 */
@Entity
@Serdeable
@Table(name = "alarm")
@Data
public class Alarm {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Subject of the alarm: sensor chip or block. */
    @Column(name = "chip_id", nullable = false)
    private Long chipId;

    /** Device that grants access to the subject: the block of a slot sensor, else the chip itself. */
    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "alarm_type", nullable = false, length = 20)
    private String alarmType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    /** Type-specific reading (LEVEL: moisture ADC); unused by GAP and OFFLINE. */
    @Column(name = "data_value")
    private Double value;
}
