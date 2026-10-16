package ru.sensoterra.server.service;

import ru.sensoterra.server.entity.SensorFieldDefault;
import ru.sensoterra.server.repository.SensorFieldDefaultRepository;
import jakarta.inject.Singleton;
import jakarta.transaction.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Значения по умолчанию для подписей каналов и единиц: то, что пользователь видит,
 * пока не завёл свои. Хранятся в sensor_field_default и правятся админом из
 * интерфейса, поэтому клиент их не знает — он берёт готовые значения из
 * {@code GET /api/prefs} (секция {@code defaults}).
 *
 * Ключевой момент, ради которого дефолты вынесены в отдельную таблицу, а не оставлены
 * в user_field_pref: «подпись взята из дефолта» и «подпись выбрана руками» в
 * персональных строках неразличимы. Правя дефолт, админ меняет подписи у будущих
 * пользователей сразу, а у нынешних — только у тех, кто не менял свои, и то отдельной
 * кнопкой.
 *
 * Типов в системе пока один — 0. Остальные появятся, когда прошивка блоков начнёт
 * присылать поле Type в EVENT_S/VALUES; для них дефолтов нет, и пользователь видит
 * сырые ключи каналов (v0, v1, ...) до тех пор, пока не подпишет их сам.
 */
@Singleton
public class SensorFieldDefaults {

    private final SensorFieldDefaultRepository repository;

    public SensorFieldDefaults(SensorFieldDefaultRepository repository) {
        this.repository = repository;
    }

    /** Все типы, для которых дефолты заданы, по возрастанию номера типа. */
    public List<Short> types() {
        return repository.findAll().stream()
                .map(SensorFieldDefault::getSensorType)
                .distinct()
                .sorted()
                .toList();
    }

    public List<SensorFieldDefault> forType(short sensorType) {
        return repository.findBySensorTypeOrderByField(sensorType);
    }

    /**
     * Правка дефолта админом. Строки создаются и правятся в одной транзакции: у
     * сущности assigned id, поэтому save() отсоединённой сущности сделал бы INSERT и
     * упал бы на первичном ключе вместо UPDATE.
     */
    @Transactional
    public SensorFieldDefault save(short sensorType, String field, String label, String unit) {
        SensorFieldDefault row = repository.findById(new SensorFieldDefault.Key(sensorType, field))
                .orElseGet(() -> {
                    SensorFieldDefault fresh = new SensorFieldDefault();
                    fresh.setSensorType(sensorType);
                    fresh.setField(field);
                    return fresh;
                });
        row.setLabel(label);
        row.setUnit(unit);
        return repository.save(row);
    }

    /** Дефолты в той же форме, в какой их ждёт клиент: Map<тип, Map<канал, {label, unit}>>. */
    public Map<String, Map<String, FieldDefault>> asMap() {
        Map<String, Map<String, FieldDefault>> out = new LinkedHashMap<>();
        for (SensorFieldDefault row : repository.findAll()) {
            out.computeIfAbsent(String.valueOf(row.getSensorType()), k -> new LinkedHashMap<>())
                    .put(row.getField(), new FieldDefault(row.getLabel(), row.getUnit()));
        }
        return out;
    }

    public record FieldDefault(String label, String unit) {
    }
}