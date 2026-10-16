package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Дефолтные опорные точки калибровки влажности (АЦП v0) для типа датчика: воздух,
 * сухая почва (0%), вода (100%). Одна строка на тип; правит админ на странице
 * настроек. Копируются в {@link SensorCalibration} при появлении датчика типа 0.
 */
@Entity
@Table(name = "sensor_calibration_default")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Serdeable
public class SensorCalibrationDefault {

    @Id
    @Column(name = "sensor_type", nullable = false)
    private Short sensorType;

    @Column(name = "value_1", nullable = false)
    private Integer value1;

    @Column(name = "value_2", nullable = false)
    private Integer value2;

    @Column(name = "value_3", nullable = false)
    private Integer value3;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}