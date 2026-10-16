package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.SensorCalibration;
import ru.sensoterra.server.entity.SensorCalibrationDefault;
import ru.sensoterra.server.repository.SensorCalibrationDefaultRepository;
import ru.sensoterra.server.repository.SensorCalibrationRepository;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.exceptions.HttpStatusException;
import io.micronaut.serde.annotation.Serdeable;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Калибровка влажности датчиков типа 0 (канал v0 / moisture_adc).
 *
 * Опорные точки по умолчанию (воздух / сухая почва 0% / вода 100%) лежат в
 * sensor_calibration_default по типу датчика и правятся админом на странице
 * настроек; тут же — фолбэк-константы, пока строки нет. При появлении датчика
 * типа 0 дефолты копируются в его собственную строку (sensor_calibration),
 * которую админ может переопределить на странице датчика.
 */
@Singleton
public class SensorCalibrationService {

    private static final Logger log = LoggerFactory.getLogger(SensorCalibrationService.class);

    public static final int FALLBACK_AIR = 1650;
    public static final int FALLBACK_DRY = 1550;
    public static final int FALLBACK_WATER = 400;

    private final SensorCalibrationRepository repository;
    private final SensorCalibrationDefaultRepository defaultRepository;

    public SensorCalibrationService(SensorCalibrationRepository repository,
                                    SensorCalibrationDefaultRepository defaultRepository) {
        this.repository = repository;
        this.defaultRepository = defaultRepository;
    }

    /** Опорные точки по умолчанию для типа; NULL/нет строки — константы. */
    public Values defaultsFor(Short sensorType) {
        short type = sensorType != null ? sensorType : 0;
        SensorCalibrationDefault row = defaultRepository.findBySensorType(type).orElse(null);
        if (row != null) {
            return new Values(row.getValue1(), row.getValue2(), row.getValue3());
        }
        return new Values(FALLBACK_AIR, FALLBACK_DRY, FALLBACK_WATER);
    }

    /** Все типы с их дефолтами (ключ — строковый тип, как на странице настроек). */
    public Map<String, Values> defaultsAll() {
        Map<String, Values> result = new LinkedHashMap<>();
        for (SensorCalibrationDefault row : defaultRepository.findAll()) {
            result.put(String.valueOf(row.getSensorType()),
                    new Values(row.getValue1(), row.getValue2(), row.getValue3()));
        }
        return result;
    }

    /** Правка дефолтов админом: upsert строки типа. */
    @Transactional
    public Map<String, Values> saveDefaults(Map<String, Values> patch) {
        for (Map.Entry<String, Values> e : patch.entrySet()) {
            String key = e.getKey();
            Values v = e.getValue();
            if (v == null || v.value1() == null || v.value2() == null || v.value3() == null) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                        "Все три опорных значения должны быть заполнены для type=" + key);
            }
            Short type;
            try {
                type = Short.parseShort(key);
            } catch (NumberFormatException ex) {
                throw new HttpStatusException(HttpStatus.BAD_REQUEST, "Неизвестный тип датчика: " + key);
            }
            SensorCalibrationDefault row = defaultRepository.findBySensorType(type).orElseGet(SensorCalibrationDefault::new);
            row.setSensorType(type);
            row.setValue1(v.value1());
            row.setValue2(v.value2());
            row.setValue3(v.value3());
            row.setUpdatedAt(Instant.now());
            defaultRepository.save(row);
        }
        return defaultsAll();
    }

    @Serdeable
    public record Values(Integer value1, Integer value2, Integer value3) {
    }

    public SensorCalibration bySensorId(Long sensorId) {
        if (sensorId == null) {
            return null;
        }
        return repository.findBySensorId(sensorId).orElse(null);
    }

    /**
     * Копирование дефолтов в строку датчика при его появлении. Существующая строка
     * не трогается; гонку двух retained-обработок гасит unique(sensor_id) — дубль
     * ловится и игнорируется. Нативный ON CONFLICT не используется: H2 в тестах
     * его не понимает, а ошибка ломает внешнюю транзакцию обработки сообщения.
     */
    public void ensureDefaults(Long sensorId, Short sensorType) {
        if (sensorId == null || sensorType == null || sensorType.shortValue() != 0) {
            return;
        }
        if (repository.existsBySensorId(sensorId)) {
            return;
        }
        Values d = defaultsFor(sensorType);
        try {
            SensorCalibration row = new SensorCalibration();
            row.setSensorId(sensorId);
            row.setValue1(d.value1());
            row.setValue2(d.value2());
            row.setValue3(d.value3());
            row.setCreatedAt(Instant.now());
            row.setUpdatedAt(Instant.now());
            repository.save(row);
        } catch (Exception e) {
            log.warn("Sensor calibration defaults insert failed for sensor {}: {}", sensorId, e.getMessage());
        }
    }

    /** Правка калибровки админом (только для датчика типа 0). */
    @Transactional
    public SensorCalibration save(Long sensorId, Integer value1, Integer value2, Integer value3) {
        SensorCalibration cal = repository.findBySensorId(sensorId)
                .orElseGet(() -> {
                    SensorCalibration fresh = new SensorCalibration();
                    fresh.setSensorId(sensorId);
                    fresh.setCreatedAt(Instant.now());
                    return fresh;
                });
        cal.setValue1(value1);
        cal.setValue2(value2);
        cal.setValue3(value3);
        cal.setUpdatedAt(Instant.now());
        return repository.save(cal);
    }

    /**
     * Правка только value_2 (0% — сухая почва) пользователем: остальные опорные точки
     * не трогаются. Значение обязано лежать строго между водой (value_3) и воздухом
     * (value_1), иначе измерение бракуется.
     */
    @Transactional
    public SensorCalibration saveValue2(Long sensorId, Short sensorType, Integer value2) {
        SensorCalibration cal = repository.findBySensorId(sensorId).orElse(null);
        Integer value1 = cal != null ? cal.getValue1() : null;
        Integer value3 = cal != null ? cal.getValue3() : null;
        if (value1 == null || value3 == null) {
            Values d = defaultsFor(sensorType);
            value1 = d.value1();
            value3 = d.value3();
        }
        if (value2 == null || value2 <= value3 || value2 >= value1) {
            throw new HttpStatusException(HttpStatus.BAD_REQUEST,
                    "Некорректное измерение (" + (value2 == null ? "null" : value2) + ")");
        }
        if (cal == null) {
            cal = new SensorCalibration();
            cal.setSensorId(sensorId);
            cal.setCreatedAt(Instant.now());
        }
        cal.setValue1(value1);
        cal.setValue2(value2);
        cal.setValue3(value3);
        cal.setUpdatedAt(Instant.now());
        return repository.save(cal);
    }
}