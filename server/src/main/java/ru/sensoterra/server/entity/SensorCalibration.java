package ru.sensoterra.server.entity;

import io.micronaut.serde.annotation.Serdeable;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Калибровка влажности датчика типа 0 (щуп влажности, канал v0 / moisture_adc):
 * три опорные точки АЦП — на воздухе (value_1), в сухой почве 0% (value_2),
 * в воде 100% (value_3). По ним ADC переводится в относительную влажность.
 * Дефолты заносятся при появлении датчика, правятся админом на странице датчика.
 */
@Entity
@Table(name = "sensor_calibration")
@Serdeable
@Data
@NoArgsConstructor
@AllArgsConstructor
public class SensorCalibration {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sensor_id", nullable = false, unique = true)
    private Long sensorId;

    /** v0 на воздухе. */
    @Column(name = "value_1", nullable = false)
    private Integer value1;

    /** v0 в полностью сухой почве (0% относительной влажности). */
    @Column(name = "value_2", nullable = false)
    private Integer value2;

    /** v0 полностью в воде (100% относительной влажности). */
    @Column(name = "value_3", nullable = false)
    private Integer value3;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}