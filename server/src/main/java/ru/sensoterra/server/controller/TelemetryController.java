package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.RelayEvent;
import ru.sensoterra.server.entity.SensorTelemetry;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import io.micronaut.core.annotation.Nullable;
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

    public TelemetryController(EntityManager em,
                               DeviceAccessService access,
                               SensorRepository sensorRepository) {
        this.em = em;
        this.access = access;
        this.sensorRepository = sensorRepository;
    }

    @Get("/sensor")
    @Transactional
    public List<SensorTelemetry> sensor(
            @Nullable @QueryValue Long deviceId,
            @Nullable @QueryValue Long sensorChipId,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to,
            @Nullable @QueryValue Integer limit) {
        if (deviceId != null) {
            access.requireView(deviceId);
        } else if (sensorChipId != null) {
            sensorRepository.findBySensorChipId(sensorChipId).ifPresent(s -> {
                Long chip = s.getDeviceId() != null ? s.getDeviceId() : s.getSensorChipId();
                access.requireView(chip);
            });
        } else {
            return List.of();
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

    @Get("/sensor/start")
    @Transactional
    @Nullable
    public SensorStartRow sensorStart(
            @Nullable @QueryValue Long deviceId,
            @Nullable @QueryValue Long sensorChipId) {
        if (deviceId != null) {
            access.requireView(deviceId);
        } else if (sensorChipId != null) {
            sensorRepository.findBySensorChipId(sensorChipId).ifPresent(s -> {
                Long chip = s.getDeviceId() != null ? s.getDeviceId() : s.getSensorChipId();
                access.requireView(chip);
            });
        } else {
            return null;
        }
        if (sensorChipId == null) {
            return null;
        }
        StringBuilder jpql = new StringBuilder(
                "SELECT t.time, t.numMessage, t.battery FROM SensorTelemetry t WHERE t.sensorChipId = :chipId");
        if (deviceId != null) {
            jpql.append(" AND t.deviceId = :deviceId");
        }
        // Замена батареи в данных телеметрии выглядит так:
        //   1) счётчик Num_message/Number ПАДАЕТ (сбрасывается при перезапуске);
        //   2) у АВТОНОМНЫХ датчиков счётчик длительное время НЕ РАСТЁТ — у литий-ионной
        //      батареи в разряде число перестаёт увеличиваться, а после установки свежей
        //      батареи возобновляет рост в последующих сообщениях;
        //   3) резкий скачок напряжения батареи до нормы (~3 В).
        // Признак (2) применяется только к автономным датчикам (deviceId == sensorChipId):
        // у датчиков на блоке залипшая серия бывает, когда блок забирают (датчик шлёт,
        // приёма нет) — это не замена батареи и отслеживать его не надо.
        // «Работает с» = начало ПОСЛЕДНЕГО цикла роста (сброс или возобновление роста).
        // Разрывы во времени НЕ используем: сервис бывает выключен ночью/на выходных,
        // а батарею можно заменить за минуту — это даёт ложные срабатывания.
        jpql.append(" ORDER BY t.time ASC");
        var q = em.createQuery(jpql.toString(), Object[].class);
        q.setParameter("chipId", sensorChipId);
        if (deviceId != null) {
            q.setParameter("deviceId", deviceId);
        }
        q.setMaxResults(5000);
        List<Object[]> rows = q.getResultList();
        if (rows.isEmpty()) {
            return null;
        }
        Instant start = null;
        Instant voltageJump = null;
        Integer prevNum = null;
        Float lastBattery = null;
        int same = 0;
        boolean plateau = false;
        boolean autonomous = deviceId != null && deviceId.equals(sensorChipId);
        for (Object[] row : rows) {
            Instant time = (Instant) row[0];
            Integer curNum = (Integer) row[1];
            Float curBattery = (Float) row[2];
            if (prevNum != null && curNum != null) {
                if (curNum.equals(prevNum)) {
                    same++;
                    if (same >= 4) {
                        plateau = true;
                    }
                } else if (curNum > prevNum) {
                    if (plateau && autonomous) {
                        start = time;   // рост возобновился после залипшей серии — новый цикл
                    }
                    plateau = false;
                    same = 0;
                } else {
                    start = time;       // счётчик упал — сброс после замены/перезапуска
                    plateau = false;
                    same = 0;
                }
            }
            if (lastBattery != null && curBattery != null
                    && curBattery >= 2.9f && lastBattery <= 2.6f) {
                voltageJump = time;
            }
            prevNum = curNum;
            lastBattery = curBattery;
        }
        // Скачок напряжения — главный признак, у него приоритет; счётчик — дополнительный.
        if (voltageJump != null) {
            return new SensorStartRow(voltageJump);
        }
        if (start != null) {
            return new SensorStartRow(start);
        }
        return new SensorStartRow((Instant) rows.get(0)[0]);
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

    @Get("/status")
    @Transactional
    public List<DeviceStatusRow> status(
            @QueryValue Long deviceId,
            @Nullable @QueryValue Instant since,
            @Nullable @QueryValue Instant to) {
        access.requireView(deviceId);
        var q = em.createNativeQuery(
                "SELECT time, status FROM device_status_log " +
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
                    return new DeviceStatusRow(
                            time,
                            (String) r[1]);
                })
                .toList();
    }

    @Serdeable
    public record SensorStartRow(Instant time) {
    }

    @Serdeable
    public record BlockTelemetryRow(Instant time, Integer rssiR, Float vbat, Float vacc) {
    }

    @Serdeable
    public record DeviceStatusRow(Instant time, String status) {
    }
}
