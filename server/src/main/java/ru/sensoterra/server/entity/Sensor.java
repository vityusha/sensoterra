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

    @Enumerated(EnumType.STRING)
    @Column(name = "battery_type", nullable = false, length = 16)
    private BatteryType batteryType = BatteryType.ALKALINE;

    @Column(name = "warning_voltage", nullable = false)
    private Integer warningVoltage = BatteryType.ALKALINE.getWarningVoltageMv();

    @Column(name = "fields_json")
    private String fieldsJson;

    @Column(name = "v1_limit_1")
    private Double v1Limit1;

    @Column(name = "v1_limit_2")
    private Double v1Limit2;

    @Column(name = "v1_type_alarm", nullable = false)
    private Short v1TypeAlarm = 0;

    @Column(name = "v2_limit_1")
    private Double v2Limit1;

    @Column(name = "v2_limit_2")
    private Double v2Limit2;

    @Column(name = "v2_type_alarm", nullable = false)
    private Short v2TypeAlarm = 0;

    @Column(name = "v3_limit_1")
    private Double v3Limit1;

    @Column(name = "v3_limit_2")
    private Double v3Limit2;

    @Column(name = "v3_type_alarm", nullable = false)
    private Short v3TypeAlarm = 0;

    @Column(name = "moisture_adc")
    private Integer moistureAdc;

    @Column(name = "v0_limit_1")
    private Double v0Limit1;

    @Column(name = "v0_limit_2")
    private Double v0Limit2;

    @Column(name = "v0_type_alarm", nullable = false)
    private Short v0TypeAlarm = 0;

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
