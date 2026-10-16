package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;

@Entity
@Serdeable
@Table(name = "command_log")
@Data
public class CommandLog {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "sensor_id")
    private Long sensorId;

    @Column(name = "command_type", nullable = false)
    private String commandType;

    @Column(nullable = false)
    private String payload;

    @Column(nullable = false)
    private String topic;

    @Column(name = "sent_at")
    private Instant sentAt = Instant.now();

    private boolean confirmed = false;
}
