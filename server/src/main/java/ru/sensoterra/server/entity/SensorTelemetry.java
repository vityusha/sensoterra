package ru.sensoterra.server.entity;

import jakarta.persistence.*;
import io.micronaut.serde.annotation.Serdeable;
import lombok.Data;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Serdeable
@Table(name = "sensor_telemetry")
@IdClass(SensorTelemetry.PK.class)
@Data
public class SensorTelemetry {

    @Id
    private Instant time;

    @Id @Column(name = "device_id")
    private Long deviceId;

    @Id @Column(name = "sensor_chip_id")
    private Long sensorChipId;

    @Column(name = "sensor_name")
    private String sensorName;

    @Column(name = "sensor_type")
    private Short sensorType;

    @Column(name = "moisture_adc")
    private Integer moistureAdc;

    private Float temperature;

    private Float humidity;

    private Integer light;

    private Float battery;

    private Float batload;

    private Float power;

    private Integer rssi;

    private Float vbat;

    private Float vacc;

    @Column(name = "rssi_block")
    private Integer rssiBlock;

    @Column(name = "num_message")
    private Integer numMessage;

    @Column(name = "fields_json")
    private String fieldsJson;

    private String error;

    /**
     * Только для точек из непрерывных агрегатов (TelemetryAggregateService): "hour" / "day".
     * У сырых строк null — в JSON не попадает.
     */
    @Transient
    private String resolution;

    /** Число сырых сообщений в бакете агрегата; у сырых строк null. */
    @Transient
    private Long samples;

    public static class PK implements Serializable {
        private Instant time;
        private Long deviceId;
        private Long sensorChipId;

        public PK() {}

        public PK(Instant time, Long deviceId, Long sensorChipId) {
            this.time = time;
            this.deviceId = deviceId;
            this.sensorChipId = sensorChipId;
        }

        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(time, pk.time)
                && Objects.equals(deviceId, pk.deviceId)
                && Objects.equals(sensorChipId, pk.sensorChipId);
        }

        @Override public int hashCode() {
            return Objects.hash(time, deviceId, sensorChipId);
        }
    }
}
