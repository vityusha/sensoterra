package ru.sensoterra.server.controller;

import io.micronaut.http.HttpStatus;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.transaction.Transactional;
import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.AlarmService;
import ru.sensoterra.server.service.DeviceAccessService;

import java.time.Instant;
import java.util.List;

@Secured(SecurityRule.IS_AUTHENTICATED)
@Controller("/api/sensors/{chipId}/threshold/{fieldKey}")
public class SensorThresholdController {
    private final SensorRepository sensors;
    private final DeviceAccessService access;
    private final AlarmService alarms;

    public SensorThresholdController(SensorRepository sensors, DeviceAccessService access, AlarmService alarms) {
        this.sensors = sensors;
        this.access = access;
        this.alarms = alarms;
    }

    @Serdeable
    public record Config(Double limit1, Double limit2, Short typeAlarm, Double currentValue) {}

    @Get
    @Transactional
    public Config get(@PathVariable Long chipId, @PathVariable String fieldKey, @Nullable @QueryValue Long deviceId) {
        requireField(fieldKey);
        Sensor sensor = find(chipId, deviceId);
        requireSensorType(sensor, fieldKey);
        if (!access.canView(sensor.getDeviceId()) && !access.canView(chipId)) {
            throw new HttpStatusException(HttpStatus.FORBIDDEN, "No access to this sensor");
        }
        return config(sensor, fieldKey);
    }

    @Put
    @Transactional
    public Config set(@PathVariable Long chipId, @PathVariable String fieldKey,
                      @Nullable @QueryValue Long deviceId, @Body Config config) {
        requireField(fieldKey);
        if (deviceId != null) access.requireControl(deviceId);
        else access.requireSensorControlByChip(chipId);
        Sensor sensor = find(chipId, deviceId);
        requireSensorType(sensor, fieldKey);
        short type = config.typeAlarm() == null ? 0 : config.typeAlarm();
        if (type < 0 || type > 4) throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown threshold alarm type");
        if (type != 0 && config.limit1() == null) throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Limit 1 is required");
        if ((type == 3 || type == 4) && config.limit2() == null) throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Limit 2 is required");
        if (fieldKey.equals("v0") && ((config.limit1() != null && (config.limit1() < 0 || config.limit1() > 100)) || (config.limit2() != null && (config.limit2() < 0 || config.limit2() > 100)))) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "V0 limits must be between 0 and 100 percent");
        }
        if (config.limit1() != null && config.limit2() != null && config.limit1() > config.limit2()) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Limit 1 must not exceed Limit 2");
        }
        setLimits(sensor, fieldKey, config.limit1(), config.limit2(), type);
        sensors.update(sensor);
        alarms.clear(chipId, alarmType(fieldKey), Instant.now());
        alarms.reevaluateThreshold(sensor, fieldKey, sensor.getDeviceId(), Instant.now());
        return config(sensor, fieldKey);
    }

    private Sensor find(Long chipId, Long deviceId) {
        if (deviceId != null) {
            return sensors.findByDeviceIdAndSensorChipId(deviceId, chipId)
                    .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "sensor not found on device"));
        }
        return sensors.findBySensorChipIdOrderByUpdatedAtDesc(chipId).stream().findFirst()
                .orElseThrow(() -> new HttpStatusException(HttpStatus.NOT_FOUND, "sensor not found"));
    }

    private static void requireField(String fieldKey) {
        if (!List.of("v0", "v1", "v2", "v3").contains(fieldKey)) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Thresholds are supported for V0, V1, V2 and V3");
        }
    }

    private static void requireSensorType(Sensor sensor, String fieldKey) {
        if ("v0".equals(fieldKey) && sensor.getSensorType() != null && sensor.getSensorType() != 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "V0 thresholds are supported only for type 0 sensors");
        }
    }

    private Config config(Sensor sensor, String fieldKey) {
        return new Config(limit1(sensor, fieldKey), limit2(sensor, fieldKey), typeAlarm(sensor, fieldKey), value(sensor, fieldKey));
    }

    private Double limit1(Sensor s, String f) { return switch (f) { case "v0" -> s.getV0Limit1(); case "v1" -> s.getV1Limit1(); case "v2" -> s.getV2Limit1(); default -> s.getV3Limit1(); }; }
    private Double limit2(Sensor s, String f) { return switch (f) { case "v0" -> s.getV0Limit2(); case "v1" -> s.getV1Limit2(); case "v2" -> s.getV2Limit2(); default -> s.getV3Limit2(); }; }
    private Short typeAlarm(Sensor s, String f) { return switch (f) { case "v0" -> s.getV0TypeAlarm(); case "v1" -> s.getV1TypeAlarm(); case "v2" -> s.getV2TypeAlarm(); default -> s.getV3TypeAlarm(); }; }
    private Double value(Sensor s, String f) { return switch (f) { case "v0" -> alarms.moisturePercent(s, s.getMoistureAdc()); case "v1" -> s.getTemperature() == null ? null : s.getTemperature().doubleValue(); case "v2" -> s.getHumidity() == null ? null : s.getHumidity().doubleValue(); default -> s.getLight() == null ? null : s.getLight().doubleValue(); }; }
    private static String alarmType(String f) { return "THRESHOLD_" + f.toUpperCase(); }

    private static void setLimits(Sensor s, String f, Double l1, Double l2, Short type) {
        switch (f) {
            case "v0" -> { s.setV0Limit1(l1); s.setV0Limit2(l2); s.setV0TypeAlarm(type); }
            case "v1" -> { s.setV1Limit1(l1); s.setV1Limit2(l2); s.setV1TypeAlarm(type); }
            case "v2" -> { s.setV2Limit1(l1); s.setV2Limit2(l2); s.setV2TypeAlarm(type); }
            case "v3" -> { s.setV3Limit1(l1); s.setV3Limit2(l2); s.setV3TypeAlarm(type); }
            default -> throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Unknown field");
        }
    }
}
