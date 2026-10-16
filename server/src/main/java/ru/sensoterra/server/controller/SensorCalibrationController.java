package ru.sensoterra.server.controller;

import ru.sensoterra.server.entity.Sensor;
import ru.sensoterra.server.entity.SensorCalibration;
import ru.sensoterra.server.repository.SensorRepository;
import ru.sensoterra.server.service.DeviceAccessService;
import ru.sensoterra.server.service.SensorCalibrationService;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;

/**
 * Калибровка влажности датчика типа 0 (опорные точки АЦП: воздух / сухая почва / вода).
 * Чтение — всем, кто видит датчик; полная правка — только админом; value_2 (0%) —
 * пользователем с правом управления (самостоятельная калибровка со страницы датчика).
 */
@Controller("/api/sensors/{chipId}/calibration")
@Singleton
@Secured(SecurityRule.IS_AUTHENTICATED)
public class SensorCalibrationController {

    private final SensorRepository sensorRepository;
    private final SensorCalibrationService calibrationService;
    private final DeviceAccessService access;

    public SensorCalibrationController(SensorRepository sensorRepository,
                                       SensorCalibrationService calibrationService,
                                       DeviceAccessService access) {
        this.sensorRepository = sensorRepository;
        this.calibrationService = calibrationService;
        this.access = access;
    }

    @Get
    public CalibrationResponse get(Long chipId) {
        Sensor sensor = activeSensor(chipId);
        if (sensor == null) {
            return null;
        }
        access.requireView(sensor.getDeviceId() != null ? sensor.getDeviceId() : chipId);
        SensorCalibration cal = calibrationService.bySensorId(sensor.getId());
        if (cal != null) {
            return new CalibrationResponse(cal.getValue1(), cal.getValue2(), cal.getValue3());
        }
        // У датчика ещё нет строки (например, тип пришёл позже) — отдаём дефолты.
        SensorCalibrationService.Values d = calibrationService.defaultsFor(sensor.getSensorType());
        return new CalibrationResponse(d.value1(), d.value2(), d.value3());
    }

    @Put
    public CalibrationResponse update(Long chipId, @Body CalibrationRequest request) {
        access.requireAdmin();
        Sensor sensor = activeSensor(chipId);
        if (sensor == null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Датчик не найден");
        }
        Short type = sensor.getSensorType();
        if (type != null && type.shortValue() != 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Калибровка влажности поддерживается только для датчиков типа 0");
        }
        if (request == null || request.value1() == null || request.value2() == null || request.value3() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Все три опорных значения должны быть заполнены");
        }
        SensorCalibration cal = calibrationService.save(
                sensor.getId(), request.value1(), request.value2(), request.value3());
        return new CalibrationResponse(cal.getValue1(), cal.getValue2(), cal.getValue3());
    }

    /**
     * Самостоятельная калибровка пользователем: только value_2 (0% — сухая почва).
     * Измерение принимается, если лежит строго между водой и воздухом, иначе 400
     * «Некорректное измерение». Права — управление устройством (не viewer).
     */
    @Put("/value2")
    public CalibrationResponse updateValue2(Long chipId, @Body Value2Request request) {
        Sensor sensor = activeSensor(chipId);
        if (sensor == null) {
            throw new HttpStatusException(HttpStatus.NOT_FOUND, "Датчик не найден");
        }
        access.requireControl(sensor.getDeviceId() != null ? sensor.getDeviceId() : chipId);
        Short type = sensor.getSensorType();
        if (type != null && type.shortValue() != 0) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Калибровка влажности поддерживается только для датчиков типа 0");
        }
        if (request == null || request.value2() == null) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Значение измерения не передано");
        }
        SensorCalibration cal = calibrationService.saveValue2(sensor.getId(), type, request.value2());
        return new CalibrationResponse(cal.getValue1(), cal.getValue2(), cal.getValue3());
    }

    /** Актуальная строка датчика по чипу: какой бы слот/устройство ни обновлялся последним. */
    private Sensor activeSensor(Long chipId) {
        return sensorRepository.findBySensorChipIdOrderByUpdatedAtDesc(chipId)
                .stream().findFirst().orElse(null);
    }

    @Serdeable
    public record CalibrationRequest(Integer value1, Integer value2, Integer value3) {
    }

    @Serdeable
    public record Value2Request(Integer value2) {
    }

    @Serdeable
    public record CalibrationResponse(Integer value1, Integer value2, Integer value3) {
    }
}