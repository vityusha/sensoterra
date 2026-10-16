package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;
import java.time.LocalTime;

@Entity
@Serdeable
@Table(name = "relay",
       uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "relay_number"}))
@Data
public class Relay {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "relay_number", nullable = false)
    private Short relayNumber;

    private String name;

    private Short state;

    private Boolean activity;

    @Column(name = "type_enum")
    private String typeEnum;

    @Column(name = "timer_enabled")
    private Boolean timerEnabled;

    @Column(name = "data_type")
    private Short dataType;

    @Column(name = "main_time")
    private LocalTime mainTime;

    @Column(name = "duration_main")
    private Short durationMain;

    @Column(name = "extra_time")
    private LocalTime extraTime;

    @Column(name = "duration_extra")
    private Short durationExtra;

    @Column(name = "interval_h")
    private Short intervalH;

    @Column(name = "sensor_id")
    private Long sensorId;

    @Column(name = "start_time")
    private LocalTime startTime;

    @Column(name = "finish_time")
    private LocalTime finishTime;

    @Column(name = "one_limit")
    private Integer oneLimit;

    @Column(name = "low_limit")
    private Integer lowLimit;

    @Column(name = "high_limit")
    private Integer highLimit;

    @Column(name = "rssi_r")
    private Integer rssiR;

    private Float vbat;

    private Float vacc;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
