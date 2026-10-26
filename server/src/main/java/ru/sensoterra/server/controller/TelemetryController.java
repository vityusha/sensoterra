package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.Device;
import ru.sensoterra.server.entity.RelayEvent;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorTelemetry;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.TelemetryAggregateService;
import ru.sensoterra.server.service.TelemetryAggregateService.Resolution;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;

import java.time.Instant;
import java.util.List;

@Controller("/api/telemetry")
@Secured(SecurityRule.IS_AUTHENTICATED)
public class TelemetryController {

    private final EntityManager em;
    private final DeviceAccessService access;
    private final SensorRepository sensorRepository;
    private final TelemetryAggregateService aggregates;

    public TelemetryController(EntityManager em,
                               DeviceAccessService access,
                               SensorRepository sensorRepository,
                               TelemetryAggregateService aggregates) {
        this.em = em;
        this.access = access;
        this.sensorRepository = sensorRepository;
        this.aggregates = aggregates;
    }

    @Get("/sensor")
    @Transactional
    public List<SensorTelemetry> sensor(
            @Nullable @QueryValue Long deviceId,
            @Nullable @QueryValue Long sensorChipId,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to,
            @Nullable @QueryValue Integer limit) {
        if (deviceId == null && sensorChipId == null) {
            return List.of();
        }
        deviceId = requireSensorTelemetryAccess(deviceId, sensorChipId);
        // Длинные окна (> 7 дней — по часу, > 90 дней — по суткам) и окна старше срока
        // хранения сырых данных отдаём из непрерывных агрегатов: те же поля, значения — средние.
        Resolution resolution = TelemetryAggregateService.choose(since, to, Instant.now());
        if (resolution != Resolution.RAW) {
            List<SensorTelemetry> points = aggregates.load(resolution, deviceId, sensorChipId, since, to,
                    limit != null && limit > 0 ? limit : TelemetryAggregateService.MAX_POINTS);
            fillSensorMeta(points, deviceId, sensorChipId);
            return points;
        }
        StringBuilder jpql = new StringBuilder("SELECT t FROM SensorTelemetry t WHERE 1=1");
        if (deviceId != null) {
            jpql.append(" AND t.deviceId = :deviceId");
        }
        if (sensorChipId != null) {
            jpql.append(" AND t.sensorChipId = :chipId");
        }
        if (since != null) {
            jpql.append(" AND t.time >= :since");
        }
        if (to != null) {
            jpql.append(" AND t.time <= :to");
        }
        jpql.append(" ORDER BY t.time DESC");
        var q = em.createQuery(jpql.toString(), SensorTelemetry.class);
        if (deviceId != null) {
            q.setParameter("deviceId", deviceId);
        }
        if (sensorChipId != null) {
            q.setParameter("chipId", sensorChipId);
        }
        if (since != null) {
            q.setParameter("since", since);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        // limit=1 — «последние данные» вне выбранного периода: одна строка вместо 500.
        q.setMaxResults(limit != null && limit > 0 ? Math.min(limit, 500) : 500);
        return q.getResultList();
    }

    /**
     * Access check for sensor telemetry; returns the deviceId the query must be limited to
     * (null = no limit, admins only).
     * <p>
     * With only sensorChipId the device comes from the current sensor record, and the query is
     * then limited to that device: rows of the same chip from another block (a resold sensor)
     * are someone else's history. Without a sensor record (sensor unregistered, telemetry kept)
     * there is nothing to check against, so only admins get the data.
     */
    private Long requireSensorTelemetryAccess(@Nullable Long deviceId, Long sensorChipId) {
        if (deviceId != null) {
            access.requireView(deviceId);
            return deviceId;
        }
        Sensor sensor = sensorRepository.findBySensorChipId(sensorChipId).orElse(null);
        if (sensor == null) {
            if (access.isAdmin()) {
                return null;
            }
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Sensor not found");
        }
        Long chip = sensor.getDeviceId() != null ? sensor.getDeviceId() : sensor.getSensorChipId();
        access.requireView(chip);
        return access.isAdmin() ? null : chip;
    }

    /**
     * В агрегатах нет sensor_name / sensor_type, а клиент по типу выбирает подписи и графики —
     * берём их из карточки датчика.
     */
    private void fillSensorMeta(List<SensorTelemetry> points, @Nullable Long deviceId, @Nullable Long sensorChipId) {
        if (points.isEmpty() || sensorChipId == null) {
            return;
        }
        List<Sensor> sensors = sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(sensorChipId);
        Sensor sensor = sensors.stream()
                .filter(s -> deviceId == null || deviceId.equals(s.getDeviceId()))
                .findFirst()
                .orElse(sensors.isEmpty() ? null : sensors.get(0));
        if (sensor == null) {
            return;
        }
        for (SensorTelemetry p : points) {
            p.setSensorName(sensor.getName());
            p.setSensorType(sensor.getSensorType());
        }
    }

    @Get("/sensor/start")
    @Transactional
    public SensorStartRow sensorStart(
            @Nullable @QueryValue Long deviceId,
            @Nullable @QueryValue Long sensorChipId) {
        if (sensorChipId == null) {
            if (deviceId != null) {
                access.requireView(deviceId);
            }
            return new SensorStartRow(null);
        }
        deviceId = requireSensorTelemetryAccess(deviceId, sensorChipId);
        final int pageSize = 5000;
        Instant before = null;
        Integer newerNum = null;
        Instant newerTime = null;
        while (true) {
            StringBuilder jpql = new StringBuilder(
                    "SELECT t.time, t.numMessage FROM SensorTelemetry t WHERE t.sensorChipId = :chipId");
            if (deviceId != null) {
                jpql.append(" AND t.deviceId = :deviceId");
            }
            if (before != null) {
                jpql.append(" AND t.time < :before");
            }
            jpql.append(" ORDER BY t.time DESC");
            var q = em.createQuery(jpql.toString(), Object[].class);
            q.setParameter("chipId", sensorChipId);
            if (deviceId != null) {
                q.setParameter("deviceId", deviceId);
            }
            if (before != null) {
                q.setParameter("before", before);
            }
            q.setMaxResults(pageSize);
            List<Object[]> rows = q.getResultList();
            if (rows.isEmpty()) {
                break;
            }
            for (Object[] row : rows) {
                Instant time = (Instant) row[0];
                Integer num = (Integer) row[1];
                if (num == null) {
                    continue;
                }
                if (newerNum != null && num - newerNum >= 2) {
                    return new SensorStartRow(newerTime);
                }
                newerNum = num;
                newerTime = time;
            }
            if (rows.size() < pageSize) {
                break;
            }
            before = (Instant) rows.get(rows.size() - 1)[0];
        }
        return new SensorStartRow(null);
    }

    @Get("/relay")
    @Transactional
    public List<RelayEvent> relay(
            @QueryValue Long deviceId,
            @Nullable @QueryValue Short relayNumber,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to) {
        access.requireView(deviceId);
        StringBuilder jpql = new StringBuilder("SELECT r FROM RelayEvent r WHERE r.deviceId = :deviceId");
        if (relayNumber != null) {
            jpql.append(" AND r.relayNumber = :relayNumber");
        }
        if (since != null) {
            jpql.append(" AND r.time >= :since");
        }
        if (to != null) {
            jpql.append(" AND r.time <= :to");
        }
        jpql.append(" ORDER BY r.time DESC");
        var q = em.createQuery(jpql.toString(), RelayEvent.class);
        q.setParameter("deviceId", deviceId);
        if (relayNumber != null) {
            q.setParameter("relayNumber", relayNumber);
        }
        if (since != null) {
            q.setParameter("since", since);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        q.setMaxResults(500);
        return q.getResultList();
    }

    @Get("/block")
    @Transactional
    public List<BlockTelemetryRow> block(
            @QueryValue Long deviceId,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to) {
        access.requireView(deviceId);
        var q = em.createNativeQuery(
                "SELECT time, rssi_r, vbat, vacc FROM block_telemetry " +
                "WHERE device_id = :deviceId " +
                (since != null ? "AND time >= :since " : "") +
                (to != null ? "AND time <= :to " : "") +
                "ORDER BY time DESC LIMIT 1000")
                .setParameter("deviceId", deviceId);
        if (since != null) {
            q.setParameter("since", since);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        @SuppressWarnings("unchecked")
        var rows = q.getResultList();
        return rows.stream()
                .map(row -> {
                    Object[] r = (Object[]) row;
                    Instant time = r[0] instanceof Instant i ? i : ((java.sql.Timestamp) r[0]).toInstant();
                    return new BlockTelemetryRow(
                            time,
                            r[1] != null ? ((Number) r[1]).intValue() : null,
                            r[2] != null ? ((Number) r[2]).floatValue() : null,
                            r[3] != null ? ((Number) r[3]).floatValue() : null);
                })
                .toList();
    }

    /**
     * Журнал статусов. Запрос только с to (без since) — «статус на момент to»: одна
     * последняя запись до to. device_status_log хранится 90 дней, поэтому если записи
     * нет, берём device.last_status, когда он выставлен не позже to (значит, статус с тех
     * пор не менялся).
     */
    @Get("/status")
    @Transactional
    public List<DeviceStatusRow> status(
            @QueryValue Long deviceId,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to) {
        access.requireView(deviceId);
        boolean latestBefore = since == null && to != null;
        var q = em.createNativeQuery(
                "SELECT time, status FROM device_status_log " +
                "WHERE device_id = :deviceId " +
                (since != null ? "AND time >= :since " : "") +
                (to != null ? "AND time <= :to " : "") +
                "ORDER BY time DESC LIMIT " + (latestBefore ? 1 : 1000))
                .setParameter("deviceId", deviceId);
        if (since != null) {
            q.setParameter("since", since);
        }
        if (to != null) {
            q.setParameter("to", to);
        }
        @SuppressWarnings("unchecked")
        var rows = q.getResultList();
        List<DeviceStatusRow> result = rows.stream()
                .map(row -> {
                    Object[] r = (Object[]) row;
                    Instant time = r[0] instanceof Instant i ? i
                            : r[0] instanceof java.time.OffsetDateTime odt ? odt.toInstant()
                            : ((java.sql.Timestamp) r[0]).toInstant();
                    return new DeviceStatusRow(
                            time,
                            (String) r[1]);
                })
                .toList();
        if (latestBefore && result.isEmpty()) {
            Device device = em.find(Device.class, deviceId);
            if (device != null && device.getLastStatus() != null && device.getLastStatusAt() != null
                    && !device.getLastStatusAt().isAfter(to)) {
                return List.of(new DeviceStatusRow(device.getLastStatusAt(), device.getLastStatus()));
            }
        }
        return result;
    }

    @Serdeable
    public record SensorStartRow(@Nullable Instant time) {
    }

    @Serdeable
    public record BlockTelemetryRow(Instant time, Integer rssiR, Float vbat, Float vacc) {
    }

    @Serdeable
    public record DeviceStatusRow(Instant time, String status) {
    }
}
