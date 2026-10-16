package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;

@Entity
@Serdeable
@Table(name = "sensor_registration",
       uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "slot"}))
@Data
public class SensorRegistration {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "slot", nullable = false)
    private Short slot;

    @Column(name = "sensor_chip_id")
    private Long sensorChipId;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}