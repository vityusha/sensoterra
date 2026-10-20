package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.SensorTelemetry;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import jakarta.persistence.EntityManager;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * Чтение телеметрии датчиков из непрерывных агрегатов TimescaleDB
 * (sensor_telemetry_hourly / sensor_telemetry_daily, см. V2__retention.sql).
 * Вынесено отдельным бином: в тестах (H2) агрегатов нет, бин подменяется моком.
 */
@Singleton
public class TelemetryAggregateService {

    /** Длиннее — почасовой агрегат. */
    public static final Duration HOURLY_THRESHOLD = Duration.ofDays(7);
    /** Длиннее — суточный агрегат. */
    public static final Duration DAILY_THRESHOLD = Duration.ofDays(90);
    /** Сырые данные удаляет retention-политика через 1 год. */
    public static final Duration RAW_RETENTION = Duration.ofDays(365);
    /** Почасовой агрегат хранится 3 года. */
    public static final Duration HOURLY_RETENTION = Duration.ofDays(3 * 365);
    /** 90 дней по часу — 2160 точек, с запасом. */
    public static final int MAX_POINTS = 5000;

    public enum Resolution {
        RAW(null, null),
        HOUR("hour", "sensor_telemetry_hourly"),
        DAY("day", "sensor_telemetry_daily");

        public final String label;
        final String view;

        Resolution(String label, String view) {
            this.label = label;
            this.view = view;
        }
    }

    private final EntityManager em;

    public TelemetryAggregateService(EntityManager em) {
        this.em = em;
    }

    /**
     * Разрешение по запрошенному окну. Без since — «последние N записей», всегда сырые.
     * Окно, начинающееся раньше срока хранения сырых данных (или почасового агрегата),
     * берётся из более грубого агрегата независимо от длины.
     */
    public static Resolution choose(@Nullable Instant since, @Nullable Instant to, Instant now) {
        if (since == null) {
            return Resolution.RAW;
        }
        Instant end = to != null ? to : now;
        Duration span = Duration.between(since, end);
        if (span.compareTo(DAILY_THRESHOLD) > 0 || since.isBefore(now.minus(HOURLY_RETENTION))) {
            return Resolution.DAY;
        }
        if (span.compareTo(HOURLY_THRESHOLD) > 0 || since.isBefore(now.minus(RAW_RETENTION))) {
            return Resolution.HOUR;
        }
        return Resolution.RAW;
    }

    /**
     * Точки агрегата в формате SensorTelemetry (новые — первыми, как у сырых строк):
     * time = начало бакета, поля измерений = *_avg. Поля без агрегата (batload, power,
     * vacc, numMessage, ...) остаются null.
     */
    public List<SensorTelemetry> load(Resolution resolution,
                                      @Nullable Long deviceId,
                                      @Nullable Long sensorChipId,
                                      Instant since,
                                      @Nullable Instant to,
                                      int limit) {
        if (resolution.view == null) {
            throw new IllegalArgumentException("Raw resolution is not an aggregate");
        }
        var q = em.createNativeQuery(
                "SELECT bucket, device_id, sensor_chip_id, samples, moisture_adc_avg, temperature_avg, " +
                "humidity_avg, light_avg, battery_avg, vbat_avg, rssi_avg FROM " + resolution.view +
                " WHERE bucket >= :since " +
                (to != null ? "AND bucket <= :to " : "") +
                (deviceId != null ? "AND device_id = :deviceId " : "") +
                (sensorChipId != null ? "AND sensor_chip_id = :chipId " : "") +
                "ORDER BY bucket DESC LIMIT :limit")
                // Бакет, начавшийся до since, тоже частично попадает в окно.
                .setParameter("since", since.minus(resolution == Resolution.DAY ? Duration.ofDays(1) : Duration.ofHours(1)))
                .setParameter("limit", Math.min(Math.max(limit, 1), MAX_POINTS));
        if (to != null) {
            q.setParameter("to", to);
        }
        if (deviceId != null) {
            q.setParameter("deviceId", deviceId);
        }
        if (sensorChipId != null) {
            q.setParameter("chipId", sensorChipId);
        }
        @SuppressWarnings("unchecked")
        List<Object[]> rows = q.getResultList();
        return rows.stream().map(r -> toTelemetry(r, resolution)).toList();
    }

    private static SensorTelemetry toTelemetry(Object[] r, Resolution resolution) {
        SensorTelemetry t = new SensorTelemetry();
        t.setTime(toInstant(r[0]));
        t.setDeviceId(r[1] != null ? ((Number) r[1]).longValue() : null);
        t.setSensorChipId(r[2] != null ? ((Number) r[2]).longValue() : null);
        t.setSamples(r[3] != null ? ((Number) r[3]).longValue() : null);
        t.setMoistureAdc(roundInt(r[4]));
        t.setTemperature(toFloat(r[5]));
        t.setHumidity(toFloat(r[6]));
        t.setLight(roundInt(r[7]));
        t.setBattery(toFloat(r[8]));
        t.setVbat(toFloat(r[9]));
        t.setRssi(roundInt(r[10]));
        t.setResolution(resolution.label);
        return t;
    }

    private static Instant toInstant(Object o) {
        if (o instanceof Instant i) {
            return i;
        }
        if (o instanceof OffsetDateTime odt) {
            return odt.toInstant();
        }
        return ((Timestamp) o).toInstant();
    }

    private static Float toFloat(Object o) {
        return o != null ? ((Number) o).floatValue() : null;
    }

    private static Integer roundInt(Object o) {
        return o != null ? Math.round(((Number) o).floatValue()) : null;
    }
}
