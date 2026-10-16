package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.time.Instant;

@Entity
@Serdeable
@Table(name = "sensor",
       uniqueConstraints = @UniqueConstraint(columnNames = {"device_id", "sensor_chip_id"}))
@Data
public class Sensor {

    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false)
    private Long deviceId;

    @Column(name = "sensor_chip_id")
    private Long sensorChipId;

    @Column(name = "sensor_number")
    private Short sensorNumber;

    private String name;

    @Column(name = "sensor_type")
    private Short sensorType;

    private Integer sleep;

    private Integer send;

    @Column(name = "percent_n")
    private Short percentN;

    @Column(name = "percent_s")
    private Short percentS;

    @Column(name = "min_voltage")
    private Integer minVoltage;

    @Column(name = "fields_json")
    private String fieldsJson;

    @Column(name = "moisture_adc")
    private Integer moistureAdc;

    private Float temperature;

    private Float humidity;

    private Integer light;

    private Float battery;

    private Float batload;

    @Column(name = "bat_min")
    private Float batMin;

    private Float power;

    private Integer rssi;

    @Column(name = "rssi_block")
    private Integer rssiBlock;

    @Column(name = "vbat_block")
    private Float vbatBlock;

    @Column(name = "vacc_block")
    private Float vaccBlock;

    @Column(name = "num_message")
    private Integer numMessage;

    @Column(name = "firmware_version")
    private Integer firmwareVersion;

    private String error;

    @Column(name = "last_time")
    private Instant lastTime;

    @Column(name = "updated_at")
    private Instant updatedAt = Instant.now();
}
